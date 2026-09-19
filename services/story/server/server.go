package server

import (
	"context"
	"encoding/json"
	"fmt"
	"time"

	"github.com/google/uuid"
	"github.com/redis/go-redis/v9"
	"go.uber.org/zap"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"

	storypb "gochat/gen/story"
	"gochat/services/story/repository"
)

type StoryServer struct {
	storypb.UnimplementedStoryServiceServer
	repo  *repository.StoryRepository
	redis *redis.Client
	log   *zap.Logger
}

func New(repo *repository.StoryRepository, redis *redis.Client, log *zap.Logger) *StoryServer {
	return &StoryServer{
		repo:  repo,
		redis: redis,
		log:   log,
	}
}

func (s *StoryServer) PostStory(ctx context.Context, req *storypb.PostStoryRequest) (*storypb.PostStoryResponse, error) {
	userID, err := uuid.Parse(req.UserId)
	if err != nil {
		return nil, status.Error(codes.InvalidArgument, "invalid user_id")
	}

	story := &repository.Story{
		UserID:          userID,
		MediaURL:        req.MediaUrl,
		MediaType:       req.MediaType,
		Content:         req.Content,
		BackgroundColor: req.BackgroundColor,
		FontStyle:       req.FontStyle,
		AllowReshare:    req.AllowReshare,
	}

	err = s.repo.Create(ctx, story)
	if err != nil {
		return nil, status.Errorf(codes.Internal, "create story: %v", err)
	}

	pbStory := &storypb.Story{
		Id:              story.ID.String(),
		UserId:          story.UserID.String(),
		MediaUrl:        story.MediaURL,
		MediaType:       story.MediaType,
		Content:         story.Content,
		BackgroundColor: story.BackgroundColor,
		FontStyle:       story.FontStyle,
		ExpiresAt:       story.ExpiresAt.Unix(),
		CreatedAt:       story.CreatedAt.Unix(),
		AllowReshare:    story.AllowReshare,
		IsReshare:       story.IsReshare,
		ViewerCount:     0,
	}

	// Fetch hydrated profile details (display name and avatar)
	created, err := s.repo.GetByID(ctx, story.ID)
	if err == nil {
		pbStory.UserDisplayName = created.UserDisplayName
		pbStory.UserAvatarUrl = created.UserAvatarURL
		pbStory.AllowReshare = created.AllowReshare
		pbStory.IsReshare = created.IsReshare
		if created.ResharedFromStoryID != nil {
			pbStory.ResharedFromStoryId = created.ResharedFromStoryID.String()
		}
		if created.OriginalAuthorID != nil {
			pbStory.OriginalAuthorId = created.OriginalAuthorID.String()
		}
		pbStory.OriginalAuthorName = created.OriginalAuthorName
		pbStory.ViewerCount = created.ViewerCount
	}

	// Publish real-time event to Redis under "chat:stories" so chat.StreamMessages relays it
	spPayload := map[string]string{
		"story_id":             pbStory.Id,
		"user_id":              pbStory.UserId,
		"user_name":            pbStory.UserDisplayName,
		"user_avatar":          pbStory.UserAvatarUrl,
		"media_url":            pbStory.MediaUrl,
		"type":                 pbStory.MediaType,
		"content":              pbStory.Content,
		"allow_reshare":        fmt.Sprintf("%t", pbStory.AllowReshare),
		"is_reshare":           fmt.Sprintf("%t", pbStory.IsReshare),
		"original_author_name": pbStory.OriginalAuthorName,
	}
	spJSON, _ := json.Marshal(spPayload)

	eventPayload := map[string]string{
		"event":         "story_created",
		"actor_id":      pbStory.UserId,
		"story_payload": string(spJSON),
	}
	eventJSON, _ := json.Marshal(eventPayload)

	if s.redis != nil {
		if err := s.redis.Publish(ctx, "chat:stories", string(eventJSON)).Err(); err != nil {
			s.log.Warn("failed to publish story_created event", zap.Error(err))
		}
	}

	return &storypb.PostStoryResponse{
		Story: pbStory,
	}, nil
}

func (s *StoryServer) ReshareStory(ctx context.Context, req *storypb.ReshareStoryRequest) (*storypb.PostStoryResponse, error) {
	userID, err := uuid.Parse(req.UserId)
	if err != nil {
		return nil, status.Error(codes.InvalidArgument, "invalid user_id")
	}
	storyID, err := uuid.Parse(req.StoryId)
	if err != nil {
		return nil, status.Error(codes.InvalidArgument, "invalid story_id")
	}

	// 1. Fetch original story
	originalStory, err := s.repo.GetByID(ctx, storyID)
	if err != nil {
		return nil, status.Errorf(codes.NotFound, "story not found: %v", err)
	}

	// 2. Validate author allowed resharing
	if !originalStory.AllowReshare {
		return nil, status.Error(codes.PermissionDenied, "the creator has disabled resharing for this status")
	}

	// 3. Prevent reshares of already expired stories
	if !originalStory.ExpiresAt.IsZero() && originalStory.ExpiresAt.Before(time.Now()) {
		return nil, status.Error(codes.FailedPrecondition, "cannot reshare an expired status")
	}

	caption := req.Caption
	if caption == "" {
		caption = originalStory.Content
	}

	resharedStory := &repository.Story{
		UserID:              userID,
		MediaURL:            originalStory.MediaURL,
		MediaType:           originalStory.MediaType,
		Content:             caption,
		BackgroundColor:     originalStory.BackgroundColor,
		FontStyle:           originalStory.FontStyle,
		AllowReshare:        true,
		IsReshare:           true,
		ResharedFromStoryID: &originalStory.ID,
		OriginalAuthorID:    &originalStory.UserID,
		OriginalAuthorName:  originalStory.UserDisplayName,
	}

	err = s.repo.Create(ctx, resharedStory)
	if err != nil {
		return nil, status.Errorf(codes.Internal, "reshare story failed: %v", err)
	}

	pbStory := &storypb.Story{
		Id:                  resharedStory.ID.String(),
		UserId:              resharedStory.UserID.String(),
		MediaUrl:            resharedStory.MediaURL,
		MediaType:           resharedStory.MediaType,
		Content:             resharedStory.Content,
		BackgroundColor:     resharedStory.BackgroundColor,
		FontStyle:           resharedStory.FontStyle,
		ExpiresAt:           resharedStory.ExpiresAt.Unix(),
		CreatedAt:           resharedStory.CreatedAt.Unix(),
		AllowReshare:        resharedStory.AllowReshare,
		IsReshare:           true,
		ResharedFromStoryId: originalStory.ID.String(),
		OriginalAuthorId:    originalStory.UserID.String(),
		OriginalAuthorName:  originalStory.UserDisplayName,
		ViewerCount:         0,
	}

	// Hydrate creator details
	created, err := s.repo.GetByID(ctx, resharedStory.ID)
	if err == nil {
		pbStory.UserDisplayName = created.UserDisplayName
		pbStory.UserAvatarUrl = created.UserAvatarURL
	}

	// Publish real-time event to Redis
	spPayload := map[string]string{
		"story_id":             pbStory.Id,
		"user_id":              pbStory.UserId,
		"user_name":            pbStory.UserDisplayName,
		"user_avatar":          pbStory.UserAvatarUrl,
		"media_url":            pbStory.MediaUrl,
		"type":                 pbStory.MediaType,
		"content":              pbStory.Content,
		"allow_reshare":        "true",
		"is_reshare":           "true",
		"original_author_id":   pbStory.OriginalAuthorId,
		"original_author_name": pbStory.OriginalAuthorName,
	}
	spJSON, _ := json.Marshal(spPayload)

	eventPayload := map[string]string{
		"event":         "story_created",
		"actor_id":      pbStory.UserId,
		"story_payload": string(spJSON),
	}
	eventJSON, _ := json.Marshal(eventPayload)

	if s.redis != nil {
		if err := s.redis.Publish(ctx, "chat:stories", string(eventJSON)).Err(); err != nil {
			s.log.Warn("failed to publish reshare story_created event", zap.Error(err))
		}
	}

	return &storypb.PostStoryResponse{
		Story: pbStory,
	}, nil
}

func (s *StoryServer) DeleteStory(ctx context.Context, req *storypb.DeleteStoryRequest) (*storypb.DeleteStoryResponse, error) {
	storyID, err := uuid.Parse(req.StoryId)
	if err != nil {
		return nil, status.Error(codes.InvalidArgument, "invalid story_id")
	}
	userID, err := uuid.Parse(req.UserId)
	if err != nil {
		return nil, status.Error(codes.InvalidArgument, "invalid user_id")
	}

	err = s.repo.Delete(ctx, storyID, userID)
	if err != nil {
		return nil, status.Errorf(codes.Internal, "delete story: %v", err)
	}

	// Publish real-time event to Redis
	spPayload := map[string]string{
		"story_id": storyID.String(),
		"user_id":  userID.String(),
	}
	spJSON, _ := json.Marshal(spPayload)

	eventPayload := map[string]string{
		"event":         "story_deleted",
		"actor_id":      userID.String(),
		"story_payload": string(spJSON),
	}
	eventJSON, _ := json.Marshal(eventPayload)

	if s.redis != nil {
		if err := s.redis.Publish(ctx, "chat:stories", string(eventJSON)).Err(); err != nil {
			s.log.Warn("failed to publish story_deleted event", zap.Error(err))
		}
	}

	return &storypb.DeleteStoryResponse{
		Success: true,
	}, nil
}

func (s *StoryServer) GetStories(ctx context.Context, req *storypb.GetStoriesRequest) (*storypb.GetStoriesResponse, error) {
	reqID, err := uuid.Parse(req.UserId)
	if err != nil {
		return nil, status.Error(codes.InvalidArgument, "invalid user_id")
	}

	feed, err := s.repo.GetStoriesFeed(ctx, reqID)
	if err != nil {
		return nil, status.Errorf(codes.Internal, "get stories feed: %v", err)
	}

	pbFeed := make([]*storypb.UserStories, len(feed))
	for i, uf := range feed {
		stories := make([]*storypb.Story, len(uf.Stories))
		for j, story := range uf.Stories {
			pbStoryItem := &storypb.Story{
				Id:                 story.ID.String(),
				UserId:             story.UserID.String(),
				UserDisplayName:    story.UserDisplayName,
				UserAvatarUrl:      story.UserAvatarURL,
				MediaUrl:           story.MediaURL,
				MediaType:          story.MediaType,
				Content:            story.Content,
				BackgroundColor:    story.BackgroundColor,
				FontStyle:          story.FontStyle,
				ExpiresAt:          story.ExpiresAt.Unix(),
				CreatedAt:          story.CreatedAt.Unix(),
				Viewed:             story.Viewed,
				AllowReshare:       story.AllowReshare,
				IsReshare:          story.IsReshare,
				OriginalAuthorName: story.OriginalAuthorName,
				ViewerCount:        story.ViewerCount,
			}
			if story.ResharedFromStoryID != nil {
				pbStoryItem.ResharedFromStoryId = story.ResharedFromStoryID.String()
			}
			if story.OriginalAuthorID != nil {
				pbStoryItem.OriginalAuthorId = story.OriginalAuthorID.String()
			}
			stories[j] = pbStoryItem
		}
		pbFeed[i] = &storypb.UserStories{
			UserId:          uf.UserID.String(),
			UserDisplayName: uf.UserDisplayName,
			UserAvatarUrl:   uf.UserAvatarURL,
			Stories:         stories,
		}
	}

	return &storypb.GetStoriesResponse{
		Feed: pbFeed,
	}, nil
}

func (s *StoryServer) ViewStory(ctx context.Context, req *storypb.ViewStoryRequest) (*storypb.ViewStoryResponse, error) {
	storyID, err := uuid.Parse(req.StoryId)
	if err != nil {
		return nil, status.Error(codes.InvalidArgument, "invalid story_id")
	}
	viewerID, err := uuid.Parse(req.ViewerId)
	if err != nil {
		return nil, status.Error(codes.InvalidArgument, "invalid viewer_id")
	}

	err = s.repo.ViewStory(ctx, storyID, viewerID)
	if err != nil {
		return nil, status.Errorf(codes.Internal, "record view: %v", err)
	}

	return &storypb.ViewStoryResponse{
		Success: true,
	}, nil
}

func (s *StoryServer) GetStoryViewerList(ctx context.Context, req *storypb.GetStoryViewerListRequest) (*storypb.GetStoryViewerListResponse, error) {
	storyID, err := uuid.Parse(req.StoryId)
	if err != nil {
		return nil, status.Error(codes.InvalidArgument, "invalid story_id")
	}
	userID, err := uuid.Parse(req.UserId)
	if err != nil {
		return nil, status.Error(codes.InvalidArgument, "invalid user_id")
	}

	story, err := s.repo.GetByID(ctx, storyID)
	if err != nil {
		return nil, status.Errorf(codes.NotFound, "story not found: %v", err)
	}

	if story.UserID != userID {
		return nil, status.Error(codes.PermissionDenied, "only the story creator can view the viewer list")
	}

	viewers, err := s.repo.GetViewerList(ctx, storyID)
	if err != nil {
		return nil, status.Errorf(codes.Internal, "get viewers: %v", err)
	}

	pbViewers := make([]*storypb.StoryViewer, len(viewers))
	for i, v := range viewers {
		pbViewers[i] = &storypb.StoryViewer{
			UserId:      v.UserID.String(),
			DisplayName: v.DisplayName,
			AvatarUrl:   v.AvatarURL,
			ViewedAt:    v.ViewedAt.Unix(),
		}
	}

	return &storypb.GetStoryViewerListResponse{
		Viewers: pbViewers,
	}, nil
}
