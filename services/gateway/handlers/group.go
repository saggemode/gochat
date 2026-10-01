package handlers

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"time"

	"github.com/gin-gonic/gin"
	"go.uber.org/zap"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"

	authpb "gochat/gen/auth"
	chatpb "gochat/gen/chat"
	grouppb "gochat/gen/group"
	"gochat/pkg/fcm"
	"gochat/services/gateway/ws"
)

type GroupHandler struct {
	client     grouppb.GroupServiceClient
	authClient authpb.AuthServiceClient
	chatClient chatpb.ChatServiceClient
	hub        *ws.Hub
	log        *zap.Logger
}

func NewGroupHandler(client grouppb.GroupServiceClient, authClient authpb.AuthServiceClient, hub *ws.Hub, log *zap.Logger) *GroupHandler {
	return &GroupHandler{client: client, authClient: authClient, hub: hub, log: log}
}

func (h *GroupHandler) WithChatClient(chatClient chatpb.ChatServiceClient) *GroupHandler {
	h.chatClient = chatClient
	return h
}

func (h *GroupHandler) resolveUserName(ctx context.Context, userID string) string {
	if h.authClient == nil || userID == "" {
		return "Someone"
	}
	uResp, err := h.authClient.GetUser(ctx, &authpb.GetUserRequest{UserId: userID})
	if err == nil && uResp != nil && uResp.User != nil {
		if uResp.User.DisplayName != "" {
			return uResp.User.DisplayName
		}
		if uResp.User.Phone != "" {
			return uResp.User.Phone
		}
		if uResp.User.Email != "" {
			return uResp.User.Email
		}
	}
	return "Someone"
}

func (h *GroupHandler) notifyGroupEvent(actorID, convID, systemText, eventType string, extraData map[string]string) {
	if h.chatClient == nil {
		return
	}
	go func() {
		ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()

		convResp, err := h.chatClient.GetConversation(ctx, &chatpb.GetConversationRequest{
			ConversationId: convID,
			UserId:         actorID,
		})
		if err != nil || convResp == nil || convResp.Conversation == nil {
			return
		}

		conv := convResp.Conversation
		groupName := conv.Name
		if groupName == "" {
			groupName = "Group"
		}

		// 1. Post system message to the chat
		sysResp, sysErr := h.chatClient.SendMessage(ctx, &chatpb.SendMessageRequest{
			ConversationId: convID,
			SenderId:       actorID,
			Content:        "SYSTEM: " + systemText,
			Type:           chatpb.MessageType_TEXT,
		})

		// 2. Broadcast WebSocket event to all connected group members
		if h.hub != nil {
			wsPayloadMap := map[string]interface{}{
				"type":            eventType,
				"event_type":      eventType,
				"conversation_id": convID,
				"group_name":      groupName,
				"group_avatar":    conv.AvatarUrl,
				"actor_id":        actorID,
				"content":         systemText,
				"created_at":      time.Now().Unix(),
			}
			for k, v := range extraData {
				wsPayloadMap[k] = v
			}
			if sysErr == nil && sysResp != nil && sysResp.Message != nil {
				wsPayloadMap["msg_id"] = sysResp.Message.Id
				wsPayloadMap["message"] = map[string]interface{}{
					"id":              sysResp.Message.Id,
					"conversation_id": convID,
					"sender_id":       actorID,
					"content":         "SYSTEM: " + systemText,
					"type":            "system",
					"created_at":      sysResp.Message.CreatedAt,
				}
			}
			wsBytes, _ := json.Marshal(wsPayloadMap)
			for _, mID := range conv.MemberIds {
				if mID != "" {
					h.hub.SendToUser(mID, wsBytes)
				}
			}
		}

		// 3. Dispatch high-priority FCM Push Notification to all group members (except actor)
		pushTitle := groupName
		pushBody := systemText
		fcmData := map[string]string{
			"type":            eventType,
			"event_type":      eventType,
			"conversation_id": convID,
			"group_name":      groupName,
			"group_avatar":    conv.AvatarUrl,
			"actor_id":        actorID,
			"is_group":        "true",
			"title":           pushTitle,
			"body":            pushBody,
		}
		for k, v := range extraData {
			fcmData[k] = v
		}

		for _, mID := range conv.MemberIds {
			if mID != "" && mID != actorID {
				targetID := mID
				go func() {
					pCtx, pCancel := context.WithTimeout(context.Background(), 8*time.Second)
					defer pCancel()
					_ = fcm.SendToUser(pCtx, targetID, pushTitle, pushBody, fcmData)
				}()
			}
		}
	}()
}

// ListGroupMembers returns enriched group member details including roles.
func (h *GroupHandler) ListGroupMembers(c *gin.Context) {
	convID := c.Param("id")
	userID := getUserID(c)
	if userID == "" {
		return
	}

	resp, err := h.client.ListGroupMembers(c.Request.Context(), &grouppb.ListGroupMembersRequest{
		ConversationId: convID,
		RequesterId:    userID,
	})
	if err != nil {
		h.handleGrpcError(c, err, "failed to list group members")
		return
	}

	type MemberInfo struct {
		ID          string `json:"id"`
		DisplayName string `json:"display_name"`
		AvatarURL   string `json:"avatar_url"`
		Role        string `json:"role"`
		IsOnline    bool   `json:"is_online"`
		LastSeen    string `json:"last_seen,omitempty"`
	}

	members := make([]MemberInfo, 0, len(resp.Members))
	for _, m := range resp.Members {
		info := MemberInfo{
			ID:   m.UserId,
			Role: m.Role,
		}

		if h.authClient != nil {
			uResp, err := h.authClient.GetUser(c.Request.Context(), &authpb.GetUserRequest{UserId: m.UserId})
			if err == nil && uResp != nil && uResp.User != nil {
				info.DisplayName = uResp.User.DisplayName
				if info.DisplayName == "" {
					info.DisplayName = uResp.User.Phone
				}
				if info.DisplayName == "" {
					info.DisplayName = uResp.User.Email
				}
				info.AvatarURL = uResp.User.AvatarUrl
				info.IsOnline = uResp.User.IsOnline
				if uResp.User.LastSeen > 0 {
					info.LastSeen = time.Unix(uResp.User.LastSeen, 0).Format(time.RFC3339)
				}
			}
		}

		if h.hub != nil && h.hub.IsUserOnline(m.UserId) {
			info.IsOnline = true
		}

		members = append(members, info)
	}

	c.JSON(http.StatusOK, gin.H{"members": members})
}

func (h *GroupHandler) UpdateGroupMetadata(c *gin.Context) {
	convID := c.Param("id")
	userID := getUserID(c)
	if userID == "" {
		return
	}

	var req struct {
		Name                 string `json:"name"`
		AvatarUrl            string `json:"avatar_url"`
		Description          string `json:"description"`
		AnnouncementsOnly    bool   `json:"announcements_only"`
		AdminsOnlyEditInfo   bool   `json:"admins_only_edit_info"`
		JoinApprovalRequired bool   `json:"join_approval_required"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid json payload"})
		return
	}

	resp, err := h.client.UpdateGroupMetadata(c.Request.Context(), &grouppb.UpdateGroupMetadataRequest{
		ConversationId:       convID,
		RequesterId:          userID,
		Name:                 req.Name,
		AvatarUrl:            req.AvatarUrl,
		Description:          req.Description,
		AnnouncementsOnly:    req.AnnouncementsOnly,
		AdminsOnlyEditInfo:   req.AdminsOnlyEditInfo,
		JoinApprovalRequired: req.JoinApprovalRequired,
	})
	if err != nil {
		h.handleGrpcError(c, err, "failed to update group metadata")
		return
	}

	actorName := h.resolveUserName(c.Request.Context(), userID)
	if req.Name != "" {
		h.notifyGroupEvent(userID, convID, fmt.Sprintf("%s changed the group subject to \"%s\"", actorName, req.Name), "group_updated", map[string]string{
			"group_name": req.Name,
		})
	} else if req.AvatarUrl != "" {
		h.notifyGroupEvent(userID, convID, fmt.Sprintf("%s changed this group's icon", actorName), "group_updated", map[string]string{
			"group_avatar": req.AvatarUrl,
		})
	} else if req.Description != "" {
		h.notifyGroupEvent(userID, convID, fmt.Sprintf("%s changed the group description", actorName), "group_updated", nil)
	}

	c.JSON(http.StatusOK, resp.Metadata)
}

func (h *GroupHandler) GetGroupMetadata(c *gin.Context) {
	convID := c.Param("id")
	userID := getUserID(c)
	if userID == "" {
		return
	}

	resp, err := h.client.GetGroupMetadata(c.Request.Context(), &grouppb.GetGroupMetadataRequest{
		ConversationId: convID,
		RequesterId:    userID,
	})
	if err != nil {
		h.handleGrpcError(c, err, "failed to fetch group metadata")
		return
	}

	c.JSON(http.StatusOK, resp.Metadata)
}

func (h *GroupHandler) GenerateInviteLink(c *gin.Context) {
	convID := c.Param("id")
	userID := getUserID(c)
	if userID == "" {
		return
	}

	resp, err := h.client.GenerateInviteLink(c.Request.Context(), &grouppb.GenerateInviteLinkRequest{
		ConversationId: convID,
		RequesterId:    userID,
	})
	if err != nil {
		h.handleGrpcError(c, err, "failed to generate group invite code")
		return
	}

	c.JSON(http.StatusOK, gin.H{"invite_code": resp.InviteCode})
}

func (h *GroupHandler) JoinByInviteCode(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}

	var req struct {
		InviteCode string `json:"invite_code"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invite_code is required"})
		return
	}

	resp, err := h.client.JoinByInviteCode(c.Request.Context(), &grouppb.JoinByInviteCodeRequest{
		InviteCode: req.InviteCode,
		UserId:     userID,
	})
	if err != nil {
		h.handleGrpcError(c, err, "failed to join group via invite link")
		return
	}

	userName := h.resolveUserName(c.Request.Context(), userID)
	h.notifyGroupEvent(userID, resp.ConversationId, fmt.Sprintf("%s joined using this group's invite link", userName), "member_joined", nil)

	c.JSON(http.StatusOK, gin.H{
		"conversation_id": resp.ConversationId,
		"status":          resp.Status,
	})
}

func (h *GroupHandler) GetPendingApprovals(c *gin.Context) {
	convID := c.Param("id")
	userID := getUserID(c)
	if userID == "" {
		return
	}

	resp, err := h.client.GetPendingApprovals(c.Request.Context(), &grouppb.GetPendingApprovalsRequest{
		ConversationId: convID,
		RequesterId:    userID,
	})
	if err != nil {
		h.handleGrpcError(c, err, "failed to fetch pending join approvals")
		return
	}

	c.JSON(http.StatusOK, resp.Approvals)
}

func (h *GroupHandler) ResolvePendingApproval(c *gin.Context) {
	approvalID := c.Param("req_id")
	userID := getUserID(c)
	if userID == "" {
		return
	}

	var req struct {
		Approve bool `json:"approve"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "approve boolean is required"})
		return
	}

	resp, err := h.client.ResolvePendingApproval(c.Request.Context(), &grouppb.ResolvePendingApprovalRequest{
		ApprovalId: approvalID,
		ResolverId: userID,
		Approve:    req.Approve,
	})
	if err != nil {
		h.handleGrpcError(c, err, "failed to resolve join approval")
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"success":         resp.Success,
		"conversation_id": resp.ConversationId,
		"user_id":         resp.UserId,
	})
}

func (h *GroupHandler) PromoteMember(c *gin.Context) {
	convID := c.Param("id")
	userID := getUserID(c)
	if userID == "" {
		return
	}
	targetID := c.Param("user_id")

	var req struct {
		Role string `json:"role"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "role field is required"})
		return
	}

	resp, err := h.client.PromoteMember(c.Request.Context(), &grouppb.PromoteMemberRequest{
		ConversationId: convID,
		RequesterId:    userID,
		TargetUserId:   targetID,
		Role:           req.Role,
	})
	if err != nil {
		h.handleGrpcError(c, err, "failed to promote group member")
		return
	}

	actorName := h.resolveUserName(c.Request.Context(), userID)
	targetName := h.resolveUserName(c.Request.Context(), targetID)
	h.notifyGroupEvent(userID, convID, fmt.Sprintf("%s made %s a group admin", actorName, targetName), "member_promoted", map[string]string{
		"target_user_id": targetID,
		"role":           req.Role,
	})

	c.JSON(http.StatusOK, gin.H{"success": resp.Success})
}

func (h *GroupHandler) DemoteMember(c *gin.Context) {
	convID := c.Param("id")
	userID := getUserID(c)
	if userID == "" {
		return
	}
	targetID := c.Param("user_id")

	resp, err := h.client.DemoteMember(c.Request.Context(), &grouppb.DemoteMemberRequest{
		ConversationId: convID,
		RequesterId:    userID,
		TargetUserId:   targetID,
	})
	if err != nil {
		h.handleGrpcError(c, err, "failed to demote group member")
		return
	}

	actorName := h.resolveUserName(c.Request.Context(), userID)
	targetName := h.resolveUserName(c.Request.Context(), targetID)
	h.notifyGroupEvent(userID, convID, fmt.Sprintf("%s dismissed %s as group admin", actorName, targetName), "member_demoted", map[string]string{
		"target_user_id": targetID,
	})

	c.JSON(http.StatusOK, gin.H{"success": resp.Success})
}

func (h *GroupHandler) CreateCommunity(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}
	var req struct {
		Name        string `json:"name"`
		Description string `json:"description"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "name is required"})
		return
	}

	resp, err := h.client.CreateCommunity(c.Request.Context(), &grouppb.CreateCommunityRequest{
		Name:        req.Name,
		Description: req.Description,
		CreatorId:   userID,
	})
	if err != nil {
		h.handleGrpcError(c, err, "failed to create community")
		return
	}

	c.JSON(http.StatusCreated, resp.Community)
}

func (h *GroupHandler) AddGroupToCommunity(c *gin.Context) {
	communityID := c.Param("id")
	userID := getUserID(c)
	if userID == "" {
		return
	}
	var req struct {
		ConversationID string `json:"conversation_id"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "conversation_id is required"})
		return
	}

	resp, err := h.client.AddGroupToCommunity(c.Request.Context(), &grouppb.AddGroupToCommunityRequest{
		CommunityId:    communityID,
		ConversationId: req.ConversationID,
		RequesterId:    userID,
	})
	if err != nil {
		h.handleGrpcError(c, err, "failed to add group to community")
		return
	}

	c.JSON(http.StatusOK, gin.H{"success": resp.Success})
}

func (h *GroupHandler) RemoveGroupFromCommunity(c *gin.Context) {
	communityID := c.Param("id")
	convID := c.Param("group_id")
	userID := getUserID(c)
	if userID == "" {
		return
	}

	resp, err := h.client.RemoveGroupFromCommunity(c.Request.Context(), &grouppb.RemoveGroupFromCommunityRequest{
		CommunityId:    communityID,
		ConversationId: convID,
		RequesterId:    userID,
	})
	if err != nil {
		h.handleGrpcError(c, err, "failed to remove group from community")
		return
	}

	c.JSON(http.StatusOK, gin.H{"success": resp.Success})
}

func (h *GroupHandler) GetCommunity(c *gin.Context) {
	communityID := c.Param("id")

	resp, err := h.client.GetCommunity(c.Request.Context(), &grouppb.GetCommunityRequest{
		CommunityId: communityID,
	})
	if err != nil {
		h.handleGrpcError(c, err, "failed to fetch community")
		return
	}

	c.JSON(http.StatusOK, resp.Community)
}

func (h *GroupHandler) ListCommunities(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}

	resp, err := h.client.ListCommunities(c.Request.Context(), &grouppb.ListCommunitiesRequest{
		UserId: userID,
	})
	if err != nil {
		h.handleGrpcError(c, err, "failed to list communities")
		return
	}

	c.JSON(http.StatusOK, resp.Communities)
}

func (h *GroupHandler) CreateBroadcastList(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}
	var req struct {
		Name         string   `json:"name"`
		RecipientIDs []string `json:"recipient_ids"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "name and recipient_ids are required"})
		return
	}

	resp, err := h.client.CreateBroadcastList(c.Request.Context(), &grouppb.CreateBroadcastListRequest{
		OwnerId:      userID,
		Name:         req.Name,
		RecipientIds: req.RecipientIDs,
	})
	if err != nil {
		h.handleGrpcError(c, err, "failed to create broadcast list")
		return
	}

	c.JSON(http.StatusCreated, resp.BroadcastList)
}

func (h *GroupHandler) DeleteBroadcastList(c *gin.Context) {
	listID := c.Param("id")
	userID := getUserID(c)
	if userID == "" {
		return
	}

	resp, err := h.client.DeleteBroadcastList(c.Request.Context(), &grouppb.DeleteBroadcastListRequest{
		Id:      listID,
		OwnerId: userID,
	})
	if err != nil {
		h.handleGrpcError(c, err, "failed to delete broadcast list")
		return
	}

	c.JSON(http.StatusOK, gin.H{"success": resp.Success})
}

func (h *GroupHandler) AddBroadcastRecipient(c *gin.Context) {
	listID := c.Param("id")
	userID := getUserID(c)
	if userID == "" {
		return
	}
	var req struct {
		RecipientID string `json:"recipient_id"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "recipient_id is required"})
		return
	}

	resp, err := h.client.AddBroadcastRecipient(c.Request.Context(), &grouppb.AddBroadcastRecipientRequest{
		BroadcastListId: listID,
		RecipientId:     req.RecipientID,
		OwnerId:         userID,
	})
	if err != nil {
		h.handleGrpcError(c, err, "failed to add broadcast recipient")
		return
	}

	c.JSON(http.StatusOK, gin.H{"success": resp.Success})
}

func (h *GroupHandler) RemoveBroadcastRecipient(c *gin.Context) {
	listID := c.Param("id")
	recipientID := c.Param("recipient_id")
	userID := getUserID(c)
	if userID == "" {
		return
	}

	resp, err := h.client.RemoveBroadcastRecipient(c.Request.Context(), &grouppb.RemoveBroadcastRecipientRequest{
		BroadcastListId: listID,
		RecipientId:     recipientID,
		OwnerId:         userID,
	})
	if err != nil {
		h.handleGrpcError(c, err, "failed to remove broadcast recipient")
		return
	}

	c.JSON(http.StatusOK, gin.H{"success": resp.Success})
}

func (h *GroupHandler) GetBroadcastList(c *gin.Context) {
	listID := c.Param("id")
	userID := getUserID(c)
	if userID == "" {
		return
	}

	resp, err := h.client.GetBroadcastList(c.Request.Context(), &grouppb.GetBroadcastListRequest{
		BroadcastListId: listID,
		OwnerId:         userID,
	})
	if err != nil {
		h.handleGrpcError(c, err, "failed to fetch broadcast list")
		return
	}

	c.JSON(http.StatusOK, resp.BroadcastList)
}

func (h *GroupHandler) handleGrpcError(c *gin.Context, err error, actionMsg string) {
	st, ok := status.FromError(err)
	if !ok {
		h.log.Error(actionMsg, zap.Error(err))
		c.JSON(http.StatusInternalServerError, gin.H{"error": "Internal server error"})
		return
	}

	h.log.Warn(actionMsg+" with gRPC status", zap.String("code", st.Code().String()), zap.String("msg", st.Message()))

	switch st.Code() {
	case codes.InvalidArgument:
		c.JSON(http.StatusBadRequest, gin.H{"error": st.Message()})
	case codes.Unauthenticated:
		c.JSON(http.StatusUnauthorized, gin.H{"error": st.Message()})
	case codes.NotFound:
		c.JSON(http.StatusNotFound, gin.H{"error": st.Message()})
	case codes.PermissionDenied:
		c.JSON(http.StatusForbidden, gin.H{"error": st.Message()})
	default:
		c.JSON(http.StatusInternalServerError, gin.H{"error": st.Message()})
	}
}
