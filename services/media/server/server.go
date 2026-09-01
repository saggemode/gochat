package server

import (
	"bytes"
	"context"
	"io"
	"time"

	"go.uber.org/zap"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"

	mediapb "gochat/gen/media"
	"gochat/services/media/optimizer"
	"gochat/services/media/storage"
)

// MediaServer implements the gRPC MediaService.
type MediaServer struct {
	mediapb.UnimplementedMediaServiceServer

	store   *storage.MinIOStorage
	tgStore *storage.TelegramStorage
	log     *zap.Logger
}

// New creates a MediaServer.
func New(store *storage.MinIOStorage, tgStore *storage.TelegramStorage, log *zap.Logger) *MediaServer {
	return &MediaServer{store: store, tgStore: tgStore, log: log}
}

// UploadMedia handles client-streaming upload with automated optimization.
// The first message must contain a header; subsequent messages contain file chunks.
func (s *MediaServer) UploadMedia(stream mediapb.MediaService_UploadMediaServer) error {
	ctx := stream.Context()

	// ── First message: header ─────────────────────────────────────────────────
	first, err := stream.Recv()
	if err != nil {
		return status.Error(codes.Internal, "failed to receive header")
	}

	header, ok := first.Data.(*mediapb.UploadMediaRequest_Header)
	if !ok || header.Header == nil {
		return status.Error(codes.InvalidArgument, "first message must be a header")
	}

	h := header.Header
	if h.FileName == "" || h.MimeType == "" {
		return status.Error(codes.InvalidArgument, "file_name and mime_type are required")
	}
	if h.TotalSize > 100*1024*1024 { // 100 MB cap
		return status.Error(codes.InvalidArgument, "file size exceeds 100 MB limit")
	}

	s.log.Info("upload started",
		zap.String("file", h.FileName),
		zap.String("mime", h.MimeType),
		zap.Int64("size", h.TotalSize),
		zap.String("uploader", h.UploaderId),
	)

	// ── Remaining messages: file chunks ───────────────────────────────────────
	// Buffer chunks into a pipe for streaming through optimizer and storage
	pr, pw := io.Pipe()

	errCh := make(chan error, 1)
	go func() {
		defer pw.Close()
		for {
			msg, err := stream.Recv()
			if err == io.EOF {
				errCh <- nil
				return
			}
			if err != nil {
				pw.CloseWithError(err)
				errCh <- err
				return
			}

			chunk, ok := msg.Data.(*mediapb.UploadMediaRequest_Chunk)
			if !ok {
				continue
			}
			if _, err := pw.Write(chunk.Chunk); err != nil {
				errCh <- err
				return
			}
		}
	}()

	// ── Server-Side Optimization & Compression ────────────────────────────────
	optResult, optErr := optimizer.Optimize(ctx, pr, h.FileName, h.MimeType, h.TotalSize, s.log)
	if optErr != nil {
		s.log.Warn("optimizer error, continuing with stream", zap.Error(optErr))
	}

	var objectKey, publicURL string
	var sizeBytes int64

	// 1. Try Telegram Storage first if configured
	if s.tgStore != nil && s.tgStore.IsConfigured() {
		tgResult, tgErr := s.tgStore.Upload(ctx, optResult.Reader, h.FileName, optResult.MimeType, optResult.Size)
		if tgErr == nil {
			objectKey = tgResult.FileID
			publicURL = tgResult.URL
			sizeBytes = tgResult.Size
			s.log.Info("media uploaded via Telegram CDN",
				zap.String("file_id", tgResult.FileID),
				zap.String("url", tgResult.URL),
			)
		} else {
			s.log.Warn("telegram storage upload failed, attempting fallback to MinIO", zap.Error(tgErr))
		}
	}

	// 2. Fallback to MinIO if Telegram was not configured or failed
	if objectKey == "" && s.store != nil {
		minioResult, minioErr := s.store.Upload(ctx, optResult.Reader, h.FileName, optResult.MimeType, optResult.Size)
		if minioErr != nil {
			return status.Errorf(codes.Internal, "storage upload failed: %v", minioErr)
		}
		objectKey = minioResult.ObjectKey
		publicURL = minioResult.URL
		sizeBytes = minioResult.Size
	} else if objectKey == "" {
		return status.Errorf(codes.Unavailable, "no media storage provider available")
	}

	// Wait for chunk receiving goroutine to finish
	if err := <-errCh; err != nil {
		return status.Errorf(codes.Internal, "receiving chunks: %v", err)
	}

	return stream.SendAndClose(&mediapb.UploadMediaResponse{
		Media: &mediapb.MediaMeta{
			ObjectKey:  objectKey,
			Url:        publicURL,
			MimeType:   optResult.MimeType,
			SizeBytes:  sizeBytes,
			MediaType:  h.MediaType,
			UploadedAt: time.Now().Unix(),
		},
	})
}

// GetMediaURL generates a pre-signed or permanent URL for a media object.
func (s *MediaServer) GetMediaURL(ctx context.Context, req *mediapb.GetMediaURLRequest) (*mediapb.GetMediaURLResponse, error) {
	if req.ObjectKey == "" {
		return nil, status.Error(codes.InvalidArgument, "object_key is required")
	}

	var expires time.Duration
	if req.ExpiresSeconds > 0 {
		expires = time.Duration(req.ExpiresSeconds) * time.Second
	}

	url, expiresAt, err := s.store.GetPresignedURL(ctx, req.ObjectKey, expires)
	if err != nil {
		return nil, status.Errorf(codes.Internal, "failed to generate URL: %v", err)
	}

	resp := &mediapb.GetMediaURLResponse{Url: url}
	if !expiresAt.IsZero() {
		resp.ExpiresAt = expiresAt.Unix()
	}
	return resp, nil
}

// DeleteMedia removes an object from MinIO.
func (s *MediaServer) DeleteMedia(ctx context.Context, req *mediapb.DeleteMediaRequest) (*mediapb.DeleteMediaResponse, error) {
	if req.ObjectKey == "" {
		return nil, status.Error(codes.InvalidArgument, "object_key is required")
	}

	if err := s.store.Delete(ctx, req.ObjectKey); err != nil {
		return nil, status.Errorf(codes.Internal, "failed to delete media: %v", err)
	}

	return &mediapb.DeleteMediaResponse{Success: true}, nil
}

// ── helper: in-memory buffer (for small files in tests) ──────────────────────
func readAll(stream mediapb.MediaService_UploadMediaServer) ([]byte, error) {
	var buf bytes.Buffer
	for {
		msg, err := stream.Recv()
		if err == io.EOF {
			return buf.Bytes(), nil
		}
		if err != nil {
			return nil, err
		}
		if chunk, ok := msg.Data.(*mediapb.UploadMediaRequest_Chunk); ok {
			buf.Write(chunk.Chunk)
		}
	}
}
