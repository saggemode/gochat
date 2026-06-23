package server

import (
	"context"
	"fmt"
	"strings"

	"github.com/google/uuid"
	"go.uber.org/zap"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"

	authzpb "gochat/gen/authz"
	"gochat/services/authz/engine"
	"gochat/services/authz/repository"
)

type AuthzServer struct {
	authzpb.UnimplementedAuthzServiceServer
	repo *repository.AuthzRepository
	log  *zap.Logger
}

func New(repo *repository.AuthzRepository, log *zap.Logger) *AuthzServer {
	return &AuthzServer{repo: repo, log: log}
}

func (s *AuthzServer) Authorize(ctx context.Context, req *authzpb.AuthorizeRequest) (*authzpb.AuthorizeResponse, error) {
	uid, err := uuid.Parse(req.UserId)
	if err != nil {
		return nil, status.Error(codes.InvalidArgument, "invalid user ID")
	}

	// 1. Get User's system roles
	roles, err := s.repo.GetUserRoles(ctx, uid)
	if err != nil {
		s.log.Error("failed to get user roles", zap.Error(err))
		return nil, status.Error(codes.Internal, "failed to check authorization")
	}
	if len(roles) == 0 {
		roles = []string{"user"} // Default system role
	}

	isSuperAdmin := false
	for _, r := range roles {
		if r == "super_admin" {
			isSuperAdmin = true
			break
		}
	}

	// 2. Fetch permissions of roles for RBAC check
	userPerms, err := s.repo.GetUserPermissions(ctx, uid)
	if err != nil {
		s.log.Error("failed to get user permissions", zap.Error(err))
		return nil, status.Error(codes.Internal, "failed to check authorization")
	}

	// Default user permissions fallback
	if len(userPerms) == 0 {
		userPerms = []string{
			"message:send", "message:edit_own", "message:delete_own",
			"message:pin", "conversation:create", "media:upload", "media:delete_own",
		}
	}

	attrs := make(map[string]string)
	for k, v := range req.Attributes {
		attrs[k] = v
	}
	attrs["subject.id"] = req.UserId
	attrs["subject.roles"] = strings.Join(roles, ",")

	// Check RBAC permission
	hasRBAC := isSuperAdmin
	if !hasRBAC {
		for _, perm := range userPerms {
			if perm == req.Action || perm == "*" {
				hasRBAC = true
				break
			}
		}
	}

	if !hasRBAC {
		return &authzpb.AuthorizeResponse{
			Allowed: false,
			Reason:  fmt.Sprintf("user does not have permission %s in RBAC roles", req.Action),
		}, nil
	}

	// 3. Evaluate ABAC policies
	policies, err := s.repo.ListPolicies(ctx)
	if err != nil {
		s.log.Error("failed to list ABAC policies", zap.Error(err))
		return nil, status.Error(codes.Internal, "failed to check authorization")
	}

	// Filter policies for this action
	var actionPolicies []*repository.Policy
	for _, p := range policies {
		if p.Action == req.Action || p.Action == "*" {
			actionPolicies = append(actionPolicies, p)
		}
	}

	// Evaluate policies: DENY overrides ALLOW
	allowMatched := false
	denyMatched := false
	var denyReason string

	for _, p := range actionPolicies {
		matched, err := engine.Evaluate(p.Condition, attrs)
		if err != nil {
			s.log.Warn("failed to evaluate policy condition", zap.String("policy_id", p.ID.String()), zap.Error(err))
			continue
		}

		if matched {
			if p.Effect == "deny" {
				denyMatched = true
				denyReason = fmt.Sprintf("denied by policy: %s", p.Description)
				break
			} else if p.Effect == "allow" {
				allowMatched = true
			}
		}
	}

	if denyMatched {
		return &authzpb.AuthorizeResponse{
			Allowed: false,
			Reason:  denyReason,
		}, nil
	}

	hasAllowPolicies := false
	for _, p := range actionPolicies {
		if p.Effect == "allow" {
			hasAllowPolicies = true
			break
		}
	}

	if hasAllowPolicies && !allowMatched {
		return &authzpb.AuthorizeResponse{
			Allowed: false,
			Reason:  "ABAC policies defined for this action did not authorize the request",
		}, nil
	}

	return &authzpb.AuthorizeResponse{
		Allowed: true,
		Reason:  "authorized",
	}, nil
}

func (s *AuthzServer) AssignRole(ctx context.Context, req *authzpb.AssignRoleRequest) (*authzpb.AssignRoleResponse, error) {
	uid, err := uuid.Parse(req.UserId)
	if err != nil {
		return nil, status.Error(codes.InvalidArgument, "invalid user ID")
	}

	if err := s.repo.AssignRole(ctx, uid, req.Role); err != nil {
		s.log.Error("failed to assign role", zap.Error(err))
		return nil, status.Error(codes.Internal, "failed to assign role")
	}

	s.log.Info("assigned role", zap.String("user_id", req.UserId), zap.String("role", req.Role))
	return &authzpb.AssignRoleResponse{Success: true}, nil
}

func (s *AuthzServer) RevokeRole(ctx context.Context, req *authzpb.RevokeRoleRequest) (*authzpb.RevokeRoleResponse, error) {
	uid, err := uuid.Parse(req.UserId)
	if err != nil {
		return nil, status.Error(codes.InvalidArgument, "invalid user ID")
	}

	if err := s.repo.RevokeRole(ctx, uid, req.Role); err != nil {
		s.log.Error("failed to revoke role", zap.Error(err))
		return nil, status.Error(codes.Internal, "failed to revoke role")
	}

	s.log.Info("revoked role", zap.String("user_id", req.UserId), zap.String("role", req.Role))
	return &authzpb.RevokeRoleResponse{Success: true}, nil
}

func (s *AuthzServer) GetUserRoles(ctx context.Context, req *authzpb.GetUserRolesRequest) (*authzpb.GetUserRolesResponse, error) {
	uid, err := uuid.Parse(req.UserId)
	if err != nil {
		return nil, status.Error(codes.InvalidArgument, "invalid user ID")
	}

	roles, err := s.repo.GetUserRoles(ctx, uid)
	if err != nil {
		s.log.Error("failed to get user roles", zap.Error(err))
		return nil, status.Error(codes.Internal, "failed to get user roles")
	}

	return &authzpb.GetUserRolesResponse{Roles: roles}, nil
}

func (s *AuthzServer) CreatePolicy(ctx context.Context, req *authzpb.CreatePolicyRequest) (*authzpb.CreatePolicyResponse, error) {
	if req.Action == "" || req.Effect == "" || req.Condition == "" {
		return nil, status.Error(codes.InvalidArgument, "action, effect and condition are required")
	}
	if req.Effect != "allow" && req.Effect != "deny" {
		return nil, status.Error(codes.InvalidArgument, "effect must be 'allow' or 'deny'")
	}

	p, err := s.repo.CreatePolicy(ctx, req.Action, req.Effect, req.Condition, req.Description)
	if err != nil {
		s.log.Error("failed to create policy", zap.Error(err))
		return nil, status.Error(codes.Internal, "failed to create policy")
	}

	return &authzpb.CreatePolicyResponse{
		Policy: &authzpb.Policy{
			Id:          p.ID.String(),
			Action:      p.Action,
			Effect:      p.Effect,
			Condition:   p.Condition,
			Description: p.Description,
		},
	}, nil
}

func (s *AuthzServer) ListPolicies(ctx context.Context, req *authzpb.ListPoliciesRequest) (*authzpb.ListPoliciesResponse, error) {
	policies, err := s.repo.ListPolicies(ctx)
	if err != nil {
		s.log.Error("failed to list policies", zap.Error(err))
		return nil, status.Error(codes.Internal, "failed to list policies")
	}

	protoPolicies := make([]*authzpb.Policy, len(policies))
	for i, p := range policies {
		protoPolicies[i] = &authzpb.Policy{
			Id:          p.ID.String(),
			Action:      p.Action,
			Effect:      p.Effect,
			Condition:   p.Condition,
			Description: p.Description,
		}
	}

	return &authzpb.ListPoliciesResponse{Policies: protoPolicies}, nil
}
