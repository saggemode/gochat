package server

import (
	"context"

	"github.com/jackc/pgx/v5/pgxpool"

	pb "gochat/gen/social"
	"gochat/services/social/repository"

	"go.uber.org/zap"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"
)

type SocialServer struct {
	pb.UnimplementedSocialServiceServer
	repo *repository.SocialRepository
	log  *zap.Logger
}

func NewSocialServer(db *pgxpool.Pool, log *zap.Logger) *SocialServer {
	return &SocialServer{repo: repository.NewSocialRepository(db), log: log}
}

func (s *SocialServer) FollowUser(ctx context.Context, req *pb.FollowUserRequest) (*pb.FollowUserResponse, error) {
	if err := s.repo.Follow(ctx, req.UserId, req.TargetId); err != nil {
		return nil, status.Errorf(codes.Internal, "follow: %v", err)
	}
	return &pb.FollowUserResponse{Success: true}, nil
}

func (s *SocialServer) UnfollowUser(ctx context.Context, req *pb.UnfollowUserRequest) (*pb.UnfollowUserResponse, error) {
	if err := s.repo.Unfollow(ctx, req.UserId, req.TargetId); err != nil {
		return nil, status.Errorf(codes.Internal, "unfollow: %v", err)
	}
	return &pb.UnfollowUserResponse{Success: true}, nil
}

func (s *SocialServer) GetFollowers(ctx context.Context, req *pb.GetFollowersRequest) (*pb.GetFollowersResponse, error) {
	limit := int(req.Limit)
	if limit <= 0 { limit = 20 }
	ids, total, err := s.repo.GetFollowers(ctx, req.UserId, limit, int(req.Offset))
	if err != nil {
		return nil, status.Errorf(codes.Internal, "get followers: %v", err)
	}
	return &pb.GetFollowersResponse{FollowerIds: ids, Total: int32(total)}, nil
}

func (s *SocialServer) GetFollowing(ctx context.Context, req *pb.GetFollowingRequest) (*pb.GetFollowingResponse, error) {
	limit := int(req.Limit)
	if limit <= 0 { limit = 20 }
	ids, total, err := s.repo.GetFollowing(ctx, req.UserId, limit, int(req.Offset))
	if err != nil {
		return nil, status.Errorf(codes.Internal, "get following: %v", err)
	}
	return &pb.GetFollowingResponse{FollowingIds: ids, Total: int32(total)}, nil
}

func (s *SocialServer) CreateMoment(ctx context.Context, req *pb.CreateMomentRequest) (*pb.CreateMomentResponse, error) {
	m, err := s.repo.CreateMoment(ctx, req.UserId, req.Content, req.MediaUrl, req.MediaType, req.Visibility)
	if err != nil {
		return nil, status.Errorf(codes.Internal, "create moment: %v", err)
	}
	return &pb.CreateMomentResponse{Moment: &pb.Moment{
		Id: m.ID, UserId: m.UserID, Content: m.Content, MediaUrl: m.MediaURL,
		MediaType: m.MediaType, Visibility: m.Visibility, CreatedAt: m.CreatedAt.Unix(),
	}}, nil
}

func (s *SocialServer) LikeMoment(ctx context.Context, req *pb.LikeMomentRequest) (*pb.LikeMomentResponse, error) {
	if err := s.repo.LikeMoment(ctx, req.UserId, req.MomentId); err != nil {
		return nil, status.Errorf(codes.Internal, "like moment: %v", err)
	}
	return &pb.LikeMomentResponse{Success: true}, nil
}

func (s *SocialServer) CommentMoment(ctx context.Context, req *pb.CommentMomentRequest) (*pb.CommentMomentResponse, error) {
	return &pb.CommentMomentResponse{Comment: &pb.MomentComment{
		UserId: req.UserId, Content: req.Content,
	}}, nil
}

func (s *SocialServer) GetMomentsFeed(ctx context.Context, req *pb.GetMomentsFeedRequest) (*pb.GetMomentsFeedResponse, error) {
	limit := int(req.Limit)
	if limit <= 0 { limit = 20 }
	moments, total, err := s.repo.GetMomentsFeed(ctx, req.UserId, limit, int(req.Offset))
	if err != nil {
		return nil, status.Errorf(codes.Internal, "get feed: %v", err)
	}
	var pbMoments []*pb.Moment
	for _, m := range moments {
		pbMoments = append(pbMoments, &pb.Moment{
			Id: m.ID, UserId: m.UserID, Content: m.Content, MediaUrl: m.MediaURL,
			MediaType: m.MediaType, Visibility: m.Visibility,
			LikeCount: int32(m.LikeCount), CommentCount: int32(m.CommentCount),
			CreatedAt: m.CreatedAt.Unix(),
		})
	}
	return &pb.GetMomentsFeedResponse{Moments: pbMoments, Total: int32(total)}, nil
}

func (s *SocialServer) DeleteMoment(ctx context.Context, req *pb.DeleteMomentRequest) (*pb.DeleteMomentResponse, error) {
	return &pb.DeleteMomentResponse{Success: true}, nil
}

func (s *SocialServer) SetNearbyVisible(ctx context.Context, req *pb.SetNearbyVisibleRequest) (*pb.SetNearbyVisibleResponse, error) {
	if err := s.repo.SetNearbyVisible(ctx, req.UserId, req.Latitude, req.Longitude, req.IsVisible, int(req.RadiusKm)); err != nil {
		return nil, status.Errorf(codes.Internal, "set nearby: %v", err)
	}
	return &pb.SetNearbyVisibleResponse{Success: true}, nil
}

func (s *SocialServer) GetNearbyUsers(ctx context.Context, req *pb.GetNearbyUsersRequest) (*pb.GetNearbyUsersResponse, error) {
	radius := int(req.RadiusKm)
	if radius <= 0 { radius = 5 }
	limit := int(req.Limit)
	if limit <= 0 { limit = 20 }
	users, err := s.repo.GetNearbyUsers(ctx, req.UserId, radius, limit)
	if err != nil {
		return nil, status.Errorf(codes.Internal, "get nearby: %v", err)
	}
	var pbUsers []*pb.NearbyUser
	for _, u := range users {
		pbUsers = append(pbUsers, &pb.NearbyUser{UserId: u.UserID, DistanceKm: u.DistanceKM})
	}
	return &pb.GetNearbyUsersResponse{Users: pbUsers}, nil
}

func (s *SocialServer) ApplyForBadge(ctx context.Context, req *pb.ApplyForBadgeRequest) (*pb.ApplyForBadgeResponse, error) {
	return &pb.ApplyForBadgeResponse{Success: true}, nil
}

func (s *SocialServer) GetUserBadges(ctx context.Context, req *pb.GetUserBadgesRequest) (*pb.GetUserBadgesResponse, error) {
	return &pb.GetUserBadgesResponse{Badges: []*pb.Badge{}}, nil
}

func (s *SocialServer) CreateAudioRoom(ctx context.Context, req *pb.CreateAudioRoomRequest) (*pb.CreateAudioRoomResponse, error) {
	maxSpeakers := int(req.MaxSpeakers)
	if maxSpeakers <= 0 { maxSpeakers = 10 }
	rm, err := s.repo.CreateAudioRoom(ctx, req.UserId, req.Title, maxSpeakers)
	if err != nil {
		return nil, status.Errorf(codes.Internal, "create audio room: %v", err)
	}
	return &pb.CreateAudioRoomResponse{Room: &pb.AudioRoom{
		Id: rm.ID, Title: rm.Title, CreatedBy: rm.CreatedBy,
		IsActive: rm.IsActive, MaxSpeakers: int32(rm.MaxSpeakers),
		ParticipantCount: int32(rm.ParticipantCount), CreatedAt: rm.CreatedAt.Unix(),
	}}, nil
}

func (s *SocialServer) JoinAudioRoom(ctx context.Context, req *pb.JoinAudioRoomRequest) (*pb.JoinAudioRoomResponse, error) {
	if err := s.repo.JoinAudioRoom(ctx, req.UserId, req.RoomId, req.Role); err != nil {
		return nil, status.Errorf(codes.Internal, "join room: %v", err)
	}
	return &pb.JoinAudioRoomResponse{Success: true}, nil
}

func (s *SocialServer) LeaveAudioRoom(ctx context.Context, req *pb.LeaveAudioRoomRequest) (*pb.LeaveAudioRoomResponse, error) {
	if err := s.repo.LeaveAudioRoom(ctx, req.UserId, req.RoomId); err != nil {
		return nil, status.Errorf(codes.Internal, "leave room: %v", err)
	}
	return &pb.LeaveAudioRoomResponse{Success: true}, nil
}

func (s *SocialServer) ListAudioRooms(ctx context.Context, req *pb.ListAudioRoomsRequest) (*pb.ListAudioRoomsResponse, error) {
	limit := int(req.Limit)
	if limit <= 0 { limit = 20 }
	rooms, total, err := s.repo.ListAudioRooms(ctx, limit, int(req.Offset))
	if err != nil {
		return nil, status.Errorf(codes.Internal, "list rooms: %v", err)
	}
	var pbRooms []*pb.AudioRoom
	for _, rm := range rooms {
		pbRooms = append(pbRooms, &pb.AudioRoom{
			Id: rm.ID, Title: rm.Title, CreatedBy: rm.CreatedBy,
			IsActive: rm.IsActive, MaxSpeakers: int32(rm.MaxSpeakers),
			ParticipantCount: int32(rm.ParticipantCount), CreatedAt: rm.CreatedAt.Unix(),
		})
	}
	return &pb.ListAudioRoomsResponse{Rooms: pbRooms, Total: int32(total)}, nil
}
