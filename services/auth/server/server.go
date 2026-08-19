package server

import (
	"context"
	"crypto/rand"
	"errors"
	"fmt"
	"strings"
	"time"

	"github.com/google/uuid"
	"github.com/redis/go-redis/v9"
	"go.uber.org/zap"
	"golang.org/x/crypto/bcrypt"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"

	authpb "gochat/gen/auth"
	"gochat/pkg/jwtutil"
	"gochat/services/auth/repository"
)

// AuthServer implements the gRPC AuthService.
type AuthServer struct {
	authpb.UnimplementedAuthServiceServer

	repo  *repository.UserRepository
	jwt   *jwtutil.Manager
	redis *redis.Client
	log   *zap.Logger
}

// New creates an AuthServer.
func New(repo *repository.UserRepository, jwt *jwtutil.Manager, redis *redis.Client, log *zap.Logger) *AuthServer {
	return &AuthServer{repo: repo, jwt: jwt, redis: redis, log: log}
}

// ── RPCs ──────────────────────────────────────────────────────────────────────

func (s *AuthServer) Register(ctx context.Context, req *authpb.RegisterRequest) (*authpb.RegisterResponse, error) {
	if req.Phone == "" && req.Email == "" {
		return nil, status.Error(codes.InvalidArgument, "phone number or email is required")
	}

	// Auto-generate a secure random password when none is provided
	password := req.Password
	if password == "" {
		b := make([]byte, 32)
		if _, err := rand.Read(b); err != nil {
			return nil, status.Error(codes.Internal, "failed to generate secure password")
		}
		password = fmt.Sprintf("%x", b)
	} else if len(password) < 8 {
		return nil, status.Error(codes.InvalidArgument, "password must be at least 8 characters")
	}

	user, err := s.repo.CreateUser(ctx, req.Phone, req.Email, password, req.DisplayName, req.CountryCode)
	if errors.Is(err, repository.ErrPhoneAlreadyTaken) {
		return nil, status.Error(codes.AlreadyExists, "phone number is already registered")
	}
	if errors.Is(err, repository.ErrEmailAlreadyTaken) {
		return nil, status.Error(codes.AlreadyExists, "email is already registered")
	}
	if err != nil {
		s.log.Error("register: create user", zap.Error(err))
		return nil, status.Error(codes.Internal, "failed to create user")
	}

	identifier := user.Email
	if identifier == "" {
		identifier = user.Phone
	}

	access, refresh, err := s.jwt.GenerateTokenPair(user.ID.String(), identifier)
	if err != nil {
		return nil, status.Error(codes.Internal, "failed to generate tokens")
	}

	if err := s.storeRefreshToken(ctx, user.ID, refresh); err != nil {
		s.log.Warn("register: failed to store refresh token", zap.Error(err))
	}

	s.log.Info("user registered", zap.String("user_id", user.ID.String()), zap.String("phone", user.Phone), zap.String("display_name", user.DisplayName))

	return &authpb.RegisterResponse{
		User:         domainToProto(user),
		AccessToken:  access,
		RefreshToken: refresh,
	}, nil
}

func (s *AuthServer) Login(ctx context.Context, req *authpb.LoginRequest) (*authpb.LoginResponse, error) {
	if req.Email == "" {
		return nil, status.Error(codes.InvalidArgument, "phone, email, or PIN is required")
	}

	user, err := s.repo.GetUserByIdentifier(ctx, req.Email)
	if errors.Is(err, repository.ErrUserNotFound) {
		// WhatsApp / Telegram style: auto-create account on phone/PIN login if user does not exist yet
		var phoneArg, emailArg string
		if strings.Contains(req.Email, "@") {
			emailArg = req.Email
		} else {
			phoneArg = req.Email
		}

		createdUser, createErr := s.repo.CreateUser(ctx, phoneArg, emailArg, "", "", "")
		if createErr != nil {
			// If already exists due to formatting, attempt secondary lookup
			u, fetchErr := s.repo.GetUserByIdentifier(ctx, req.Email)
			if fetchErr == nil {
				user = u
			} else {
				return nil, status.Error(codes.Unauthenticated, "invalid credentials")
			}
		} else {
			user = createdUser
		}
	} else if err != nil {
		return nil, status.Error(codes.Internal, "login failed")
	}

	// Only check password if explicitly provided and hash exists
	if req.Password != "" && user.PasswordHash != "" {
		if !s.repo.CheckPassword(user.PasswordHash, req.Password) {
			return nil, status.Error(codes.Unauthenticated, "invalid credentials")
		}
	}

	identifier := user.Email
	if identifier == "" {
		identifier = user.Phone
	}

	access, refresh, err := s.jwt.GenerateTokenPair(user.ID.String(), identifier)
	if err != nil {
		return nil, status.Error(codes.Internal, "failed to generate tokens")
	}

	if err := s.storeRefreshToken(ctx, user.ID, refresh); err != nil {
		s.log.Warn("login: failed to store refresh token", zap.Error(err))
	}

	// Mark user as online
	if err := s.repo.SetPresence(ctx, user.ID, true); err != nil {
		s.log.Warn("login: failed to set presence", zap.Error(err))
	}

	s.log.Info("user logged in", zap.String("user_id", user.ID.String()))

	return &authpb.LoginResponse{
		User:         domainToProto(user),
		AccessToken:  access,
		RefreshToken: refresh,
	}, nil
}

func (s *AuthServer) RefreshToken(ctx context.Context, req *authpb.RefreshTokenRequest) (*authpb.RefreshTokenResponse, error) {
	claims, err := s.jwt.ValidateRefresh(req.RefreshToken)
	if errors.Is(err, jwtutil.ErrTokenExpired) {
		return nil, status.Error(codes.Unauthenticated, "refresh token expired")
	}
	if err != nil {
		return nil, status.Error(codes.Unauthenticated, "invalid refresh token")
	}

	// Check the token hasn't been revoked
	rt, err := s.repo.GetRefreshToken(ctx, claims.ID)
	if errors.Is(err, repository.ErrTokenNotFound) {
		return nil, status.Error(codes.Unauthenticated, "refresh token not found")
	}
	if err != nil {
		return nil, status.Error(codes.Internal, "token lookup failed")
	}
	if rt.Revoked {
		return nil, status.Error(codes.Unauthenticated, "refresh token has been revoked")
	}

	// Rotate: revoke old, issue new pair
	_ = s.repo.RevokeRefreshToken(ctx, claims.ID)

	access, refresh, err := s.jwt.GenerateTokenPair(claims.UserID, claims.Email)
	if err != nil {
		return nil, status.Error(codes.Internal, "failed to generate tokens")
	}

	uid, _ := uuid.Parse(claims.UserID)
	if err := s.storeRefreshToken(ctx, uid, refresh); err != nil {
		s.log.Warn("refresh: failed to store new refresh token", zap.Error(err))
	}

	return &authpb.RefreshTokenResponse{
		AccessToken:  access,
		RefreshToken: refresh,
	}, nil
}

func (s *AuthServer) ValidateToken(ctx context.Context, req *authpb.ValidateTokenRequest) (*authpb.ValidateTokenResponse, error) {
	claims, err := s.jwt.ValidateAccess(req.Token)
	if err != nil {
		return &authpb.ValidateTokenResponse{Valid: false}, nil
	}
	return &authpb.ValidateTokenResponse{
		Valid:  true,
		UserId: claims.UserID,
		Email:  claims.Email,
	}, nil
}

func (s *AuthServer) GetUser(ctx context.Context, req *authpb.GetUserRequest) (*authpb.GetUserResponse, error) {
	uid, err := uuid.Parse(req.UserId)
	if err == nil {
		user, err := s.repo.GetUserByID(ctx, uid)
		if errors.Is(err, repository.ErrUserNotFound) {
			return nil, status.Error(codes.NotFound, "user not found")
		}
		if err != nil {
			return nil, status.Error(codes.Internal, "failed to fetch user")
		}
		return &authpb.GetUserResponse{User: domainToProto(user)}, nil
	}

	user, err := s.repo.GetUserByIdentifier(ctx, req.UserId)
	if errors.Is(err, repository.ErrUserNotFound) {
		return nil, status.Error(codes.NotFound, "user not found")
	}
	if err != nil {
		return nil, status.Error(codes.Internal, "failed to fetch user")
	}

	return &authpb.GetUserResponse{User: domainToProto(user)}, nil
}

func (s *AuthServer) GetUsers(ctx context.Context, req *authpb.GetUsersRequest) (*authpb.GetUsersResponse, error) {
	users, err := s.repo.GetUsersByIdentifiers(ctx, req.UserIds)
	if err != nil {
		return nil, status.Error(codes.Internal, "failed to fetch users")
	}

	protoUsers := make([]*authpb.User, len(users))
	for i, u := range users {
		protoUsers[i] = domainToProto(u)
	}

	return &authpb.GetUsersResponse{Users: protoUsers}, nil
}

func (s *AuthServer) UpdateUser(ctx context.Context, req *authpb.UpdateUserRequest) (*authpb.UpdateUserResponse, error) {
	uid, err := uuid.Parse(req.UserId)
	if err != nil {
		return nil, status.Error(codes.InvalidArgument, "invalid user ID")
	}

	user, err := s.repo.UpdateUser(ctx, uid, req.DisplayName, req.AvatarUrl, req.StatusText, req.Email, req.Phone)
	if errors.Is(err, repository.ErrUserNotFound) {
		return nil, status.Error(codes.NotFound, "user not found")
	}
	if errors.Is(err, repository.ErrEmailAlreadyTaken) {
		return nil, status.Error(codes.AlreadyExists, "email is already registered")
	}
	if err != nil {
		return nil, status.Error(codes.Internal, "failed to update user")
	}

	return &authpb.UpdateUserResponse{User: domainToProto(user)}, nil
}

func (s *AuthServer) DeleteUser(ctx context.Context, req *authpb.DeleteUserRequest) (*authpb.DeleteUserResponse, error) {
	uid, err := uuid.Parse(req.UserId)
	if err != nil {
		return nil, status.Error(codes.InvalidArgument, "invalid user ID")
	}

	err = s.repo.DeleteUser(ctx, uid)
	if errors.Is(err, repository.ErrUserNotFound) {
		return nil, status.Error(codes.NotFound, "user not found")
	}
	if err != nil {
		return nil, status.Error(codes.Internal, "failed to delete user")
	}

	return &authpb.DeleteUserResponse{Success: true}, nil
}

func (s *AuthServer) Logout(ctx context.Context, req *authpb.LogoutRequest) (*authpb.LogoutResponse, error) {
	claims, err := s.jwt.ValidateRefresh(req.RefreshToken)
	if err != nil {
		// Token is already invalid; treat as successful logout
		return &authpb.LogoutResponse{Success: true}, nil
	}

	_ = s.repo.RevokeRefreshToken(ctx, claims.ID)

	uid, _ := uuid.Parse(claims.UserID)
	if err := s.repo.SetPresence(ctx, uid, false); err != nil {
		s.log.Warn("logout: failed to clear presence", zap.Error(err))
	}

	return &authpb.LogoutResponse{Success: true}, nil
}

func (s *AuthServer) SetPresence(ctx context.Context, req *authpb.SetPresenceRequest) (*authpb.SetPresenceResponse, error) {
	uid, err := uuid.Parse(req.UserId)
	if err != nil {
		return nil, status.Error(codes.InvalidArgument, "invalid user ID")
	}

	if err := s.repo.SetPresence(ctx, uid, req.IsOnline); err != nil {
		return nil, status.Error(codes.Internal, "failed to set presence")
	}

	return &authpb.SetPresenceResponse{Success: true}, nil
}

// ── helpers ───────────────────────────────────────────────────────────────────

func (s *AuthServer) storeRefreshToken(ctx context.Context, userID uuid.UUID, tokenStr string) error {
	claims, err := s.jwt.ValidateRefresh(tokenStr)
	if err != nil {
		return fmt.Errorf("validating refresh token for storage: %w", err)
	}
	return s.repo.StoreRefreshToken(ctx, userID, claims.ID, claims.ExpiresAt.Time)
}

func domainToProto(u *repository.User) *authpb.User {
	return &authpb.User{
		Id:            u.ID.String(),
		Email:         u.Email,
		DisplayName:   u.DisplayName,
		AvatarUrl:     u.AvatarURL,
		StatusText:    u.StatusText,
		IsOnline:      u.IsOnline,
		LastSeen:      u.LastSeen.Unix(),
		CreatedAt:     u.CreatedAt.Unix(),
		Phone:         u.Phone,
		PhoneVerified: u.PhoneVerified,
		Pin:           u.PIN,
		CountryCode:   u.CountryCode,
	}
}

func (s *AuthServer) SetTwoStepPIN(ctx context.Context, req *authpb.SetTwoStepPINRequest) (*authpb.SetTwoStepPINResponse, error) {
	uid, err := uuid.Parse(req.UserId)
	if err != nil {
		return nil, status.Error(codes.InvalidArgument, "invalid user ID")
	}

	if req.Pin == "" {
		err = s.repo.UpdateTwoStepPIN(ctx, uid, "")
	} else {
		if len(req.Pin) != 6 {
			return nil, status.Error(codes.InvalidArgument, "PIN must be exactly 6 digits")
		}
		hash, err := bcrypt.GenerateFromPassword([]byte(req.Pin), bcrypt.DefaultCost)
		if err != nil {
			return nil, status.Errorf(codes.Internal, "hash PIN: %v", err)
		}
		err = s.repo.UpdateTwoStepPIN(ctx, uid, string(hash))
	}

	if err != nil {
		return nil, status.Errorf(codes.Internal, "failed to update Two-Step PIN: %v", err)
	}

	return &authpb.SetTwoStepPINResponse{Success: true}, nil
}

func (s *AuthServer) VerifyTwoStepPIN(ctx context.Context, req *authpb.VerifyTwoStepPINRequest) (*authpb.VerifyTwoStepPINResponse, error) {
	uid, err := uuid.Parse(req.UserId)
	if err != nil {
		return nil, status.Error(codes.InvalidArgument, "invalid user ID")
	}

	hash, err := s.repo.GetTwoStepPINHash(ctx, uid)
	if err != nil {
		return nil, status.Errorf(codes.Internal, "fetch PIN hash: %v", err)
	}

	if hash == "" {
		return &authpb.VerifyTwoStepPINResponse{Valid: true}, nil
	}

	err = bcrypt.CompareHashAndPassword([]byte(hash), []byte(req.Pin))
	if err != nil {
		return &authpb.VerifyTwoStepPINResponse{Valid: false}, nil
	}

	return &authpb.VerifyTwoStepPINResponse{Valid: true}, nil
}

func (s *AuthServer) UploadE2EEKeys(ctx context.Context, req *authpb.UploadE2EEKeysRequest) (*authpb.UploadE2EEKeysResponse, error) {
	uid, err := uuid.Parse(req.UserId)
	if err != nil {
		return nil, status.Error(codes.InvalidArgument, "invalid user ID")
	}

	keys := make([]*repository.EphemeralPreKey, len(req.OneTimeKeys))
	for i, k := range req.OneTimeKeys {
		keys[i] = &repository.EphemeralPreKey{
			KeyID:     k.KeyId,
			PublicKey: k.PublicKey,
		}
	}

	err = s.repo.SavePreKeyBundle(ctx, uid, req.PrekeyIdentity, req.PrekeySigned, req.PrekeySignature, keys)
	if err != nil {
		return nil, status.Errorf(codes.Internal, "failed to save prekey bundle: %v", err)
	}

	return &authpb.UploadE2EEKeysResponse{Success: true}, nil
}

func (s *AuthServer) GetE2EEKeys(ctx context.Context, req *authpb.GetE2EEKeysRequest) (*authpb.GetE2EEKeysResponse, error) {
	targetUID, err := uuid.Parse(req.TargetUserId)
	if err != nil {
		return nil, status.Error(codes.InvalidArgument, "invalid target user ID")
	}

	identity, signedKey, signature, oneTimeKey, err := s.repo.ConsumePreKeyBundle(ctx, targetUID)
	if err != nil {
		return nil, status.Errorf(codes.Internal, "failed to consume prekey bundle: %v", err)
	}

	var pbKey *authpb.EphemeralPreKey
	if oneTimeKey != nil {
		pbKey = &authpb.EphemeralPreKey{
			KeyId:     oneTimeKey.KeyID,
			PublicKey: oneTimeKey.PublicKey,
		}
	}

	return &authpb.GetE2EEKeysResponse{
		UserId:          req.TargetUserId,
		PrekeyIdentity:  identity,
		PrekeySigned:    signedKey,
		PrekeySignature: signature,
		OneTimeKey:      pbKey,
	}, nil
}

func (s *AuthServer) BlockUser(ctx context.Context, req *authpb.BlockUserRequest) (*authpb.BlockUserResponse, error) {
	uid, err := uuid.Parse(req.UserId)
	if err != nil {
		return nil, status.Error(codes.InvalidArgument, "invalid user ID")
	}
	targetUID, err := uuid.Parse(req.TargetUserId)
	if err != nil {
		return nil, status.Error(codes.InvalidArgument, "invalid target user ID")
	}

	err = s.repo.BlockUser(ctx, uid, targetUID)
	if err != nil {
		return nil, status.Errorf(codes.Internal, "failed to block user: %v", err)
	}

	return &authpb.BlockUserResponse{Success: true}, nil
}

func (s *AuthServer) UnblockUser(ctx context.Context, req *authpb.UnblockUserRequest) (*authpb.UnblockUserResponse, error) {
	uid, err := uuid.Parse(req.UserId)
	if err != nil {
		return nil, status.Error(codes.InvalidArgument, "invalid user ID")
	}
	targetUID, err := uuid.Parse(req.TargetUserId)
	if err != nil {
		return nil, status.Error(codes.InvalidArgument, "invalid target user ID")
	}

	err = s.repo.UnblockUser(ctx, uid, targetUID)
	if err != nil {
		return nil, status.Errorf(codes.Internal, "failed to unblock user: %v", err)
	}

	return &authpb.UnblockUserResponse{Success: true}, nil
}

func (s *AuthServer) GetBlockedUsers(ctx context.Context, req *authpb.GetBlockedUsersRequest) (*authpb.GetBlockedUsersResponse, error) {
	uid, err := uuid.Parse(req.UserId)
	if err != nil {
		return nil, status.Error(codes.InvalidArgument, "invalid user ID")
	}

	blockedIDs, err := s.repo.GetBlockedUsers(ctx, uid)
	if err != nil {
		return nil, status.Errorf(codes.Internal, "failed to fetch blocked users: %v", err)
	}

	blockedStrIDs := make([]string, len(blockedIDs))
	for i, id := range blockedIDs {
		blockedStrIDs[i] = id.String()
	}

	return &authpb.GetBlockedUsersResponse{
		BlockedUserIds: blockedStrIDs,
	}, nil
}

func (s *AuthServer) RegisterPhone(ctx context.Context, req *authpb.RegisterPhoneRequest) (*authpb.RegisterPhoneResponse, error) {
	uid, err := uuid.Parse(req.UserId)
	if err != nil {
		return nil, status.Error(codes.InvalidArgument, "invalid user ID")
	}
	if req.Phone == "" {
		return nil, status.Error(codes.InvalidArgument, "phone number is required")
	}

	err = s.repo.RegisterPhone(ctx, uid, req.Phone)
	if err != nil {
		return nil, status.Errorf(codes.Internal, "failed to register phone: %v", err)
	}

	// Generate and store mock OTP
	otpCode := "123456"
	redisKey := fmt.Sprintf("otp:phone:%s", req.Phone)
	err = s.redis.Set(ctx, redisKey, otpCode, 5*time.Minute).Err()
	if err != nil {
		return nil, status.Errorf(codes.Internal, "failed to store OTP: %v", err)
	}

	s.log.Info("MOCK OTP SENT", zap.String("phone", req.Phone), zap.String("otp_code", otpCode))

	return &authpb.RegisterPhoneResponse{Success: true}, nil
}

func (s *AuthServer) VerifyPhoneOTP(ctx context.Context, req *authpb.VerifyPhoneOTPRequest) (*authpb.VerifyPhoneOTPResponse, error) {
	uid, err := uuid.Parse(req.UserId)
	if err != nil {
		return nil, status.Error(codes.InvalidArgument, "invalid user ID")
	}
	if req.Phone == "" || req.Otp == "" {
		return nil, status.Error(codes.InvalidArgument, "phone and otp are required")
	}

	redisKey := fmt.Sprintf("otp:phone:%s", req.Phone)
	storedOTP, err := s.redis.Get(ctx, redisKey).Result()
	if err != nil {
		if errors.Is(err, redis.Nil) {
			return &authpb.VerifyPhoneOTPResponse{Valid: false}, nil
		}
		return nil, status.Errorf(codes.Internal, "failed to verify OTP: %v", err)
	}

	if storedOTP != req.Otp {
		return &authpb.VerifyPhoneOTPResponse{Valid: false}, nil
	}

	err = s.repo.VerifyPhone(ctx, uid, req.Phone)
	if err != nil {
		return nil, status.Errorf(codes.Internal, "failed to update verification status: %v", err)
	}

	_ = s.redis.Del(ctx, redisKey).Err()

	return &authpb.VerifyPhoneOTPResponse{Valid: true}, nil
}

func (s *AuthServer) SubscribePush(ctx context.Context, req *authpb.SubscribePushRequest) (*authpb.SubscribePushResponse, error) {
	uid, err := uuid.Parse(req.UserId)
	if err != nil {
		return nil, status.Error(codes.InvalidArgument, "invalid user ID")
	}
	if req.PushToken == "" || req.Platform == "" {
		return nil, status.Error(codes.InvalidArgument, "push_token and platform are required")
	}

	err = s.repo.SubscribePushToken(ctx, uid, req.PushToken, req.Platform)
	if err != nil {
		return nil, status.Errorf(codes.Internal, "failed to store push token: %v", err)
	}

	s.log.Info("push subscription registered", zap.String("user_id", req.UserId), zap.String("platform", req.Platform))

	return &authpb.SubscribePushResponse{Success: true}, nil
}
