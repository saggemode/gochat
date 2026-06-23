package server

import (
	"context"
	"crypto/rand"
	"encoding/hex"

	"github.com/google/uuid"
	"go.uber.org/zap"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"

	grouppb "gochat/gen/group"
	"gochat/services/group/repository"
)

type GroupServer struct {
	grouppb.UnimplementedGroupServiceServer
	repo *repository.GroupRepository
	log  *zap.Logger
}

func New(repo *repository.GroupRepository, log *zap.Logger) *GroupServer {
	return &GroupServer{
		repo: repo,
		log:  log,
	}
}

func (s *GroupServer) UpdateGroupMetadata(ctx context.Context, req *grouppb.UpdateGroupMetadataRequest) (*grouppb.UpdateGroupMetadataResponse, error) {
	convID, err := uuid.Parse(req.ConversationId)
	if err != nil {
		return nil, status.Error(codes.InvalidArgument, "invalid conversation_id")
	}
	reqID, err := uuid.Parse(req.RequesterId)
	if err != nil {
		return nil, status.Error(codes.InvalidArgument, "invalid requester_id")
	}

	role, err := s.repo.GetUserRole(ctx, convID, reqID)
	if err != nil {
		return nil, status.Errorf(codes.Internal, "check user role: %v", err)
	}
	if role != "owner" && role != "admin" {
		return nil, status.Error(codes.PermissionDenied, "only group admins or owners can update metadata")
	}

	meta := &repository.GroupMetadata{
		ConversationID:       convID,
		Description:          req.Description,
		AnnouncementsOnly:    req.AnnouncementsOnly,
		AdminsOnlyEditInfo:   req.AdminsOnlyEditInfo,
		JoinApprovalRequired: req.JoinApprovalRequired,
	}

	// Preserving existing invite code
	existing, err := s.repo.GetMetadata(ctx, convID)
	if err == nil && existing.InviteCode.Valid {
		meta.InviteCode = existing.InviteCode
	}

	err = s.repo.UpdateMetadata(ctx, meta)
	if err != nil {
		return nil, status.Errorf(codes.Internal, "update metadata: %v", err)
	}

	return &grouppb.UpdateGroupMetadataResponse{
		Metadata: &grouppb.GroupMetadata{
			ConversationId:       meta.ConversationID.String(),
			Description:          meta.Description,
			AnnouncementsOnly:    meta.AnnouncementsOnly,
			AdminsOnlyEditInfo:   meta.AdminsOnlyEditInfo,
			InviteCode:           meta.InviteCode.String,
			JoinApprovalRequired: meta.JoinApprovalRequired,
		},
	}, nil
}

func (s *GroupServer) GetGroupMetadata(ctx context.Context, req *grouppb.GetGroupMetadataRequest) (*grouppb.GetGroupMetadataResponse, error) {
	convID, err := uuid.Parse(req.ConversationId)
	if err != nil {
		return nil, status.Error(codes.InvalidArgument, "invalid conversation_id")
	}
	reqID, err := uuid.Parse(req.RequesterId)
	if err != nil {
		return nil, status.Error(codes.InvalidArgument, "invalid requester_id")
	}

	role, err := s.repo.GetUserRole(ctx, convID, reqID)
	if err != nil {
		return nil, status.Errorf(codes.Internal, "check user role: %v", err)
	}
	if role == "" {
		return nil, status.Error(codes.PermissionDenied, "user is not a member of this group")
	}

	meta, err := s.repo.GetMetadata(ctx, convID)
	if err != nil {
		return nil, status.Errorf(codes.Internal, "get metadata: %v", err)
	}

	return &grouppb.GetGroupMetadataResponse{
		Metadata: &grouppb.GroupMetadata{
			ConversationId:       meta.ConversationID.String(),
			Description:          meta.Description,
			AnnouncementsOnly:    meta.AnnouncementsOnly,
			AdminsOnlyEditInfo:   meta.AdminsOnlyEditInfo,
			InviteCode:           meta.InviteCode.String,
			JoinApprovalRequired: meta.JoinApprovalRequired,
		},
	}, nil
}

func (s *GroupServer) GenerateInviteLink(ctx context.Context, req *grouppb.GenerateInviteLinkRequest) (*grouppb.GenerateInviteLinkResponse, error) {
	convID, err := uuid.Parse(req.ConversationId)
	if err != nil {
		return nil, status.Error(codes.InvalidArgument, "invalid conversation_id")
	}
	reqID, err := uuid.Parse(req.RequesterId)
	if err != nil {
		return nil, status.Error(codes.InvalidArgument, "invalid requester_id")
	}

	role, err := s.repo.GetUserRole(ctx, convID, reqID)
	if err != nil {
		return nil, status.Errorf(codes.Internal, "check user role: %v", err)
	}
	if role != "owner" && role != "admin" {
		return nil, status.Error(codes.PermissionDenied, "only group admins or owners can generate invite links")
	}

	bytes := make([]byte, 16)
	if _, err := rand.Read(bytes); err != nil {
		return nil, status.Errorf(codes.Internal, "generate secure code: %v", err)
	}
	code := hex.EncodeToString(bytes)

	err = s.repo.GenerateInviteCode(ctx, convID, code)
	if err != nil {
		return nil, status.Errorf(codes.Internal, "save invite code: %v", err)
	}

	return &grouppb.GenerateInviteLinkResponse{
		InviteCode: code,
	}, nil
}

func (s *GroupServer) JoinByInviteCode(ctx context.Context, req *grouppb.JoinByInviteCodeRequest) (*grouppb.JoinByInviteCodeResponse, error) {
	userID, err := uuid.Parse(req.UserId)
	if err != nil {
		return nil, status.Error(codes.InvalidArgument, "invalid user_id")
	}

	convID, approvalRequired, err := s.repo.GetGroupIDByInviteCode(ctx, req.InviteCode)
	if err != nil {
		return nil, status.Errorf(codes.NotFound, "invite code invalid or expired: %v", err)
	}

	// Verify if already a member
	existingRole, err := s.repo.GetUserRole(ctx, convID, userID)
	if err != nil {
		return nil, status.Errorf(codes.Internal, "check existing membership: %v", err)
	}
	if existingRole != "" {
		return &grouppb.JoinByInviteCodeResponse{
			ConversationId: convID.String(),
			Status:         "already_member",
		}, nil
	}

	if approvalRequired {
		err = s.repo.CreateJoinRequest(ctx, convID, userID)
		if err != nil {
			return nil, status.Errorf(codes.Internal, "create join request: %v", err)
		}
		return &grouppb.JoinByInviteCodeResponse{
			ConversationId: convID.String(),
			Status:         "pending_approval",
		}, nil
	}

	err = s.repo.JoinGroupDirect(ctx, convID, userID)
	if err != nil {
		return nil, status.Errorf(codes.Internal, "join group directly: %v", err)
	}

	return &grouppb.JoinByInviteCodeResponse{
		ConversationId: convID.String(),
		Status:         "joined",
	}, nil
}

func (s *GroupServer) GetPendingApprovals(ctx context.Context, req *grouppb.GetPendingApprovalsRequest) (*grouppb.GetPendingApprovalsResponse, error) {
	convID, err := uuid.Parse(req.ConversationId)
	if err != nil {
		return nil, status.Error(codes.InvalidArgument, "invalid conversation_id")
	}
	reqID, err := uuid.Parse(req.RequesterId)
	if err != nil {
		return nil, status.Error(codes.InvalidArgument, "invalid requester_id")
	}

	role, err := s.repo.GetUserRole(ctx, convID, reqID)
	if err != nil {
		return nil, status.Errorf(codes.Internal, "check user role: %v", err)
	}
	if role != "owner" && role != "admin" {
		return nil, status.Error(codes.PermissionDenied, "only group admins or owners can get pending approvals")
	}

	approvals, err := s.repo.GetPendingApprovals(ctx, convID)
	if err != nil {
		return nil, status.Errorf(codes.Internal, "fetch approvals: %v", err)
	}

	pbApprovals := make([]*grouppb.GroupJoinApproval, len(approvals))
	for i, a := range approvals {
		var resolvedBy string
		if a.ResolvedBy.Valid {
			resolvedBy = a.ResolvedBy.UUID.String()
		}
		var resolvedAt int64
		if a.ResolvedAt != nil {
			resolvedAt = a.ResolvedAt.Unix()
		}
		pbApprovals[i] = &grouppb.GroupJoinApproval{
			Id:              a.ID.String(),
			ConversationId:  a.ConversationID.String(),
			UserId:          a.UserID.String(),
			Status:          a.Status,
			RequestedAt:     a.RequestedAt.Unix(),
			ResolvedBy:      resolvedBy,
			ResolvedAt:      resolvedAt,
			UserDisplayName: a.UserDisplayName,
			UserAvatarUrl:   a.UserAvatarURL,
		}
	}

	return &grouppb.GetPendingApprovalsResponse{
		Approvals: pbApprovals,
	}, nil
}

func (s *GroupServer) ResolvePendingApproval(ctx context.Context, req *grouppb.ResolvePendingApprovalRequest) (*grouppb.ResolvePendingApprovalResponse, error) {
	approvalID, err := uuid.Parse(req.ApprovalId)
	if err != nil {
		return nil, status.Error(codes.InvalidArgument, "invalid approval_id")
	}
	resolverID, err := uuid.Parse(req.ResolverId)
	if err != nil {
		return nil, status.Error(codes.InvalidArgument, "invalid resolver_id")
	}

	convID, userID, err := s.repo.ResolveApproval(ctx, approvalID, resolverID, req.Approve)
	if err != nil {
		return nil, status.Errorf(codes.Internal, "resolve approval: %v", err)
	}

	return &grouppb.ResolvePendingApprovalResponse{
		Success:        true,
		ConversationId: convID.String(),
		UserId:         userID.String(),
	}, nil
}

func (s *GroupServer) PromoteMember(ctx context.Context, req *grouppb.PromoteMemberRequest) (*grouppb.PromoteMemberResponse, error) {
	convID, err := uuid.Parse(req.ConversationId)
	if err != nil {
		return nil, status.Error(codes.InvalidArgument, "invalid conversation_id")
	}
	reqID, err := uuid.Parse(req.RequesterId)
	if err != nil {
		return nil, status.Error(codes.InvalidArgument, "invalid requester_id")
	}
	targetID, err := uuid.Parse(req.TargetUserId)
	if err != nil {
		return nil, status.Error(codes.InvalidArgument, "invalid target_user_id")
	}

	role, err := s.repo.GetUserRole(ctx, convID, reqID)
	if err != nil {
		return nil, status.Errorf(codes.Internal, "check user role: %v", err)
	}
	if role != "owner" && role != "admin" {
		return nil, status.Error(codes.PermissionDenied, "only group admins or owners can promote members")
	}

	if req.Role != "admin" && req.Role != "moderator" && req.Role != "member" {
		return nil, status.Error(codes.InvalidArgument, "invalid role specified")
	}

	err = s.repo.SetUserRole(ctx, convID, targetID, req.Role)
	if err != nil {
		return nil, status.Errorf(codes.Internal, "set user role: %v", err)
	}

	return &grouppb.PromoteMemberResponse{
		Success: true,
	}, nil
}

func (s *GroupServer) DemoteMember(ctx context.Context, req *grouppb.DemoteMemberRequest) (*grouppb.DemoteMemberResponse, error) {
	convID, err := uuid.Parse(req.ConversationId)
	if err != nil {
		return nil, status.Error(codes.InvalidArgument, "invalid conversation_id")
	}
	reqID, err := uuid.Parse(req.RequesterId)
	if err != nil {
		return nil, status.Error(codes.InvalidArgument, "invalid requester_id")
	}
	targetID, err := uuid.Parse(req.TargetUserId)
	if err != nil {
		return nil, status.Error(codes.InvalidArgument, "invalid target_user_id")
	}

	role, err := s.repo.GetUserRole(ctx, convID, reqID)
	if err != nil {
		return nil, status.Errorf(codes.Internal, "check user role: %v", err)
	}
	if role != "owner" && role != "admin" {
		return nil, status.Error(codes.PermissionDenied, "only group admins or owners can demote members")
	}

	// Reset to default "member" role
	err = s.repo.SetUserRole(ctx, convID, targetID, "member")
	if err != nil {
		return nil, status.Errorf(codes.Internal, "demote user: %v", err)
	}

	return &grouppb.DemoteMemberResponse{
		Success: true,
	}, nil
}

func (s *GroupServer) CreateCommunity(ctx context.Context, req *grouppb.CreateCommunityRequest) (*grouppb.CreateCommunityResponse, error) {
	creatorID, err := uuid.Parse(req.CreatorId)
	if err != nil {
		return nil, status.Error(codes.InvalidArgument, "invalid creator_id")
	}
	if req.Name == "" {
		return nil, status.Error(codes.InvalidArgument, "name cannot be empty")
	}

	c, err := s.repo.CreateCommunity(ctx, req.Name, req.Description, creatorID)
	if err != nil {
		return nil, status.Errorf(codes.Internal, "create community: %v", err)
	}

	return &grouppb.CreateCommunityResponse{
		Community: &grouppb.Community{
			Id:                   c.ID.String(),
			Name:                 c.Name,
			Description:          c.Description,
			CreatedBy:            c.CreatedBy.String(),
			CreatedAt:            c.CreatedAt.Unix(),
			GroupConversationIds: []string{},
		},
	}, nil
}

func (s *GroupServer) AddGroupToCommunity(ctx context.Context, req *grouppb.AddGroupToCommunityRequest) (*grouppb.AddGroupToCommunityResponse, error) {
	communityID, err := uuid.Parse(req.CommunityId)
	if err != nil {
		return nil, status.Error(codes.InvalidArgument, "invalid community_id")
	}
	convID, err := uuid.Parse(req.ConversationId)
	if err != nil {
		return nil, status.Error(codes.InvalidArgument, "invalid conversation_id")
	}
	requesterID, err := uuid.Parse(req.RequesterId)
	if err != nil {
		return nil, status.Error(codes.InvalidArgument, "invalid requester_id")
	}

	err = s.repo.AddGroupToCommunity(ctx, communityID, convID, requesterID)
	if err != nil {
		return nil, status.Errorf(codes.Internal, "add group to community: %v", err)
	}

	return &grouppb.AddGroupToCommunityResponse{Success: true}, nil
}

func (s *GroupServer) RemoveGroupFromCommunity(ctx context.Context, req *grouppb.RemoveGroupFromCommunityRequest) (*grouppb.RemoveGroupFromCommunityResponse, error) {
	communityID, err := uuid.Parse(req.CommunityId)
	if err != nil {
		return nil, status.Error(codes.InvalidArgument, "invalid community_id")
	}
	convID, err := uuid.Parse(req.ConversationId)
	if err != nil {
		return nil, status.Error(codes.InvalidArgument, "invalid conversation_id")
	}
	requesterID, err := uuid.Parse(req.RequesterId)
	if err != nil {
		return nil, status.Error(codes.InvalidArgument, "invalid requester_id")
	}

	err = s.repo.RemoveGroupFromCommunity(ctx, communityID, convID, requesterID)
	if err != nil {
		return nil, status.Errorf(codes.Internal, "remove group from community: %v", err)
	}

	return &grouppb.RemoveGroupFromCommunityResponse{Success: true}, nil
}

func (s *GroupServer) GetCommunity(ctx context.Context, req *grouppb.GetCommunityRequest) (*grouppb.GetCommunityResponse, error) {
	communityID, err := uuid.Parse(req.CommunityId)
	if err != nil {
		return nil, status.Error(codes.InvalidArgument, "invalid community_id")
	}

	c, err := s.repo.GetCommunity(ctx, communityID)
	if err != nil {
		return nil, status.Errorf(codes.Internal, "get community: %v", err)
	}

	groupConversationIDs := make([]string, len(c.GroupConversationIDs))
	for i, id := range c.GroupConversationIDs {
		groupConversationIDs[i] = id.String()
	}

	return &grouppb.GetCommunityResponse{
		Community: &grouppb.Community{
			Id:                   c.ID.String(),
			Name:                 c.Name,
			Description:          c.Description,
			CreatedBy:            c.CreatedBy.String(),
			CreatedAt:            c.CreatedAt.Unix(),
			GroupConversationIds: groupConversationIDs,
		},
	}, nil
}

func (s *GroupServer) ListCommunities(ctx context.Context, req *grouppb.ListCommunitiesRequest) (*grouppb.ListCommunitiesResponse, error) {
	var userID uuid.UUID
	var err error
	if req.UserId != "" {
		userID, err = uuid.Parse(req.UserId)
		if err != nil {
			return nil, status.Error(codes.InvalidArgument, "invalid user_id")
		}
	}

	communities, err := s.repo.ListCommunities(ctx, userID)
	if err != nil {
		return nil, status.Errorf(codes.Internal, "list communities: %v", err)
	}

	pbCommunities := make([]*grouppb.Community, len(communities))
	for i, c := range communities {
		groupConversationIDs := make([]string, len(c.GroupConversationIDs))
		for j, id := range c.GroupConversationIDs {
			groupConversationIDs[j] = id.String()
		}
		pbCommunities[i] = &grouppb.Community{
			Id:                   c.ID.String(),
			Name:                 c.Name,
			Description:          c.Description,
			CreatedBy:            c.CreatedBy.String(),
			CreatedAt:            c.CreatedAt.Unix(),
			GroupConversationIds: groupConversationIDs,
		}
	}

	return &grouppb.ListCommunitiesResponse{Communities: pbCommunities}, nil
}

func (s *GroupServer) CreateBroadcastList(ctx context.Context, req *grouppb.CreateBroadcastListRequest) (*grouppb.CreateBroadcastListResponse, error) {
	ownerID, err := uuid.Parse(req.OwnerId)
	if err != nil {
		return nil, status.Error(codes.InvalidArgument, "invalid owner_id")
	}
	if req.Name == "" {
		return nil, status.Error(codes.InvalidArgument, "name cannot be empty")
	}

	recipientIDs := make([]uuid.UUID, len(req.RecipientIds))
	for i, idStr := range req.RecipientIds {
		id, err := uuid.Parse(idStr)
		if err != nil {
			return nil, status.Errorf(codes.InvalidArgument, "invalid recipient_id at index %d", i)
		}
		recipientIDs[i] = id
	}

	b, err := s.repo.CreateBroadcastList(ctx, req.Name, ownerID, recipientIDs)
	if err != nil {
		return nil, status.Errorf(codes.Internal, "create broadcast list: %v", err)
	}

	recipientIDsStr := make([]string, len(b.RecipientIDs))
	for i, id := range b.RecipientIDs {
		recipientIDsStr[i] = id.String()
	}

	return &grouppb.CreateBroadcastListResponse{
		BroadcastList: &grouppb.BroadcastList{
			Id:           b.ID.String(),
			OwnerId:      b.OwnerID.String(),
			Name:         b.Name,
			CreatedAt:    b.CreatedAt.Unix(),
			RecipientIds: recipientIDsStr,
		},
	}, nil
}

func (s *GroupServer) DeleteBroadcastList(ctx context.Context, req *grouppb.DeleteBroadcastListRequest) (*grouppb.DeleteBroadcastListResponse, error) {
	listID, err := uuid.Parse(req.Id)
	if err != nil {
		return nil, status.Error(codes.InvalidArgument, "invalid broadcast_list_id")
	}
	ownerID, err := uuid.Parse(req.OwnerId)
	if err != nil {
		return nil, status.Error(codes.InvalidArgument, "invalid owner_id")
	}

	err = s.repo.DeleteBroadcastList(ctx, listID, ownerID)
	if err != nil {
		return nil, status.Errorf(codes.Internal, "delete broadcast list: %v", err)
	}

	return &grouppb.DeleteBroadcastListResponse{Success: true}, nil
}

func (s *GroupServer) AddBroadcastRecipient(ctx context.Context, req *grouppb.AddBroadcastRecipientRequest) (*grouppb.AddBroadcastRecipientResponse, error) {
	listID, err := uuid.Parse(req.BroadcastListId)
	if err != nil {
		return nil, status.Error(codes.InvalidArgument, "invalid broadcast_list_id")
	}
	recipientID, err := uuid.Parse(req.RecipientId)
	if err != nil {
		return nil, status.Error(codes.InvalidArgument, "invalid recipient_id")
	}
	ownerID, err := uuid.Parse(req.OwnerId)
	if err != nil {
		return nil, status.Error(codes.InvalidArgument, "invalid owner_id")
	}

	err = s.repo.AddBroadcastRecipient(ctx, listID, recipientID, ownerID)
	if err != nil {
		return nil, status.Errorf(codes.Internal, "add broadcast recipient: %v", err)
	}

	return &grouppb.AddBroadcastRecipientResponse{Success: true}, nil
}

func (s *GroupServer) RemoveBroadcastRecipient(ctx context.Context, req *grouppb.RemoveBroadcastRecipientRequest) (*grouppb.RemoveBroadcastRecipientResponse, error) {
	listID, err := uuid.Parse(req.BroadcastListId)
	if err != nil {
		return nil, status.Error(codes.InvalidArgument, "invalid broadcast_list_id")
	}
	recipientID, err := uuid.Parse(req.RecipientId)
	if err != nil {
		return nil, status.Error(codes.InvalidArgument, "invalid recipient_id")
	}
	ownerID, err := uuid.Parse(req.OwnerId)
	if err != nil {
		return nil, status.Error(codes.InvalidArgument, "invalid owner_id")
	}

	err = s.repo.RemoveBroadcastRecipient(ctx, listID, recipientID, ownerID)
	if err != nil {
		return nil, status.Errorf(codes.Internal, "remove broadcast recipient: %v", err)
	}

	return &grouppb.RemoveBroadcastRecipientResponse{Success: true}, nil
}

func (s *GroupServer) GetBroadcastList(ctx context.Context, req *grouppb.GetBroadcastListRequest) (*grouppb.GetBroadcastListResponse, error) {
	listID, err := uuid.Parse(req.BroadcastListId)
	if err != nil {
		return nil, status.Error(codes.InvalidArgument, "invalid broadcast_list_id")
	}
	ownerID, err := uuid.Parse(req.OwnerId)
	if err != nil {
		return nil, status.Error(codes.InvalidArgument, "invalid owner_id")
	}

	b, err := s.repo.GetBroadcastList(ctx, listID, ownerID)
	if err != nil {
		return nil, status.Errorf(codes.Internal, "get broadcast list: %v", err)
	}

	recipientIDsStr := make([]string, len(b.RecipientIDs))
	for i, id := range b.RecipientIDs {
		recipientIDsStr[i] = id.String()
	}

	return &grouppb.GetBroadcastListResponse{
		BroadcastList: &grouppb.BroadcastList{
			Id:           b.ID.String(),
			OwnerId:      b.OwnerID.String(),
			Name:         b.Name,
			CreatedAt:    b.CreatedAt.Unix(),
			RecipientIds: recipientIDsStr,
		},
	}, nil
}
