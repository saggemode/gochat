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
	"gochat/services/media/storage"
)

// MediaServer implements the gRPC MediaService.
type MediaServer struct {
	mediapb.UnimplementedMediaServiceServer

	store *storage.MinIOStorage
	log   *zap.Logger
}

// New creates a MediaServer.
func New(store *storage.MinIOStorage, log *zap.Logger) *MediaServer {
	return &MediaServer{store: store, log: log}
}

// UploadMedia handles client-streaming upload.
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
	// Buffer chunks into a pipe for streaming to MinIO without full in-memory load
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

	// Upload to MinIO while chunks stream in
	result, err := s.store.Upload(ctx, pr, h.FileName, h.MimeType, h.TotalSize)
	if err != nil {
		return status.Errorf(codes.Internal, "upload failed: %v", err)
	}

	// Wait for goroutine to finish
	if err := <-errCh; err != nil {
		return status.Errorf(codes.Internal, "receiving chunks: %v", err)
	}

	return stream.SendAndClose(&mediapb.UploadMediaResponse{
		Media: &mediapb.MediaMeta{
			ObjectKey:  result.ObjectKey,
			Url:        result.URL,
			MimeType:   h.MimeType,
			SizeBytes:  result.Size,
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
