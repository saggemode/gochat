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

// MediaServer implements mediapb.MediaServiceServer.
type MediaServer struct {
	mediapb.UnimplementedMediaServiceServer

	store        *storage.MinIOStorage
	tgStore      *storage.TelegramStorage
	dbStore      *storage.DatabaseStorage
	localStorage *storage.LocalStorage
	log          *zap.Logger
}

// New creates a MediaServer with all configured storage providers.
func New(
	store *storage.MinIOStorage,
	tgStore *storage.TelegramStorage,
	dbStore *storage.DatabaseStorage,
	localStorage *storage.LocalStorage,
	log *zap.Logger,
) *MediaServer {
	return &MediaServer{
		store:        store,
		tgStore:      tgStore,
		dbStore:      dbStore,
		localStorage: localStorage,
		log:          log,
	}
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

	optBytes, readErr := io.ReadAll(optResult.Reader)
	if readErr != nil {
		return status.Errorf(codes.Internal, "reading optimized stream: %v", readErr)
	}
	optSize := int64(len(optBytes))

	var objectKey, publicURL string
	var sizeBytes int64

	// 0. Telegram's bot download API caps at ~20MB, so anything larger can be uploaded
	//    (sendDocument allows 50MB) but later 404s on getFile. The client trims videos to
	//    ~15MB so they serve back fine from Telegram; this is only a safety net for any
	//    oversized file that slips through — it goes to the DB store, which the download
	//    endpoint can serve directly.
	tooBigForTelegram := optSize > 19*1024*1024
	if objectKey == "" && tooBigForTelegram && s.dbStore != nil && s.dbStore.IsConfigured() {
		if dbResult, dbErr := s.dbStore.Upload(ctx, bytes.NewReader(optBytes), h.FileName, optResult.MimeType, h.UploaderId, optSize); dbErr == nil {
			objectKey = dbResult.FileID
			publicURL = dbResult.URL
			sizeBytes = dbResult.Size
			s.log.Info("oversized media stored in DB (Telegram can't serve it back)",
				zap.String("file_id", dbResult.FileID), zap.Int64("size", optSize))
		} else {
			s.log.Warn("DB storage upload failed for oversized file", zap.Error(dbErr))
		}
	}

	// 1. Try Telegram Storage if configured (images / small files)
	if objectKey == "" && s.tgStore != nil && s.tgStore.IsConfigured() {
		tgResult, tgErr := s.tgStore.Upload(ctx, bytes.NewReader(optBytes), h.FileName, optResult.MimeType, optSize)
		if tgErr == nil {
			objectKey = tgResult.FileID
			publicURL = tgResult.URL
			sizeBytes = tgResult.Size
			s.log.Info("media uploaded via Telegram CDN",
				zap.String("file_id", tgResult.FileID),
				zap.String("url", tgResult.URL),
			)
		} else {
			s.log.Warn("telegram storage upload failed, attempting fallback to MinIO/DB", zap.Error(tgErr))
		}
	}

	// 2. Fallback to MinIO if Telegram was not configured or failed
	if objectKey == "" && s.store != nil {
		minioResult, minioErr := s.store.Upload(ctx, bytes.NewReader(optBytes), h.FileName, optResult.MimeType, optSize)
		if minioErr == nil {
			objectKey = minioResult.ObjectKey
			publicURL = minioResult.URL
			sizeBytes = minioResult.Size
			s.log.Info("media uploaded via MinIO/S3",
				zap.String("key", minioResult.ObjectKey),
				zap.String("url", minioResult.URL),
			)
		} else {
			s.log.Warn("minio storage upload failed, attempting fallback to DB", zap.Error(minioErr))
		}
	}

	// 3. Fallback to Database Storage (PostgreSQL / Neon)
	if objectKey == "" && s.dbStore != nil && s.dbStore.IsConfigured() {
		dbResult, dbErr := s.dbStore.Upload(ctx, bytes.NewReader(optBytes), h.FileName, optResult.MimeType, h.UploaderId, optSize)
		if dbErr == nil {
			objectKey = dbResult.FileID
			publicURL = dbResult.URL
			sizeBytes = dbResult.Size
			s.log.Info("media uploaded via PostgreSQL Storage",
				zap.String("file_id", dbResult.FileID),
				zap.String("url", dbResult.URL),
			)
		} else {
			s.log.Warn("database storage upload failed, attempting fallback to Local Storage", zap.Error(dbErr))
		}
	}

	// 4. Fallback to Local Disk Storage
	if objectKey == "" && s.localStorage != nil {
		localResult, localErr := s.localStorage.Upload(ctx, bytes.NewReader(optBytes), h.FileName, optResult.MimeType, optSize)
		if localErr == nil {
			objectKey = localResult.FileID
			publicURL = localResult.URL
			sizeBytes = localResult.Size
			s.log.Info("media uploaded via Local Disk Storage",
				zap.String("file_id", localResult.FileID),
				zap.String("url", localResult.URL),
			)
		} else {
			return status.Errorf(codes.Internal, "all storage providers failed: %v", localErr)
		}
	}

	if objectKey == "" {
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

	if s.store != nil {
		var expires time.Duration
		if req.ExpiresSeconds > 0 {
			expires = time.Duration(req.ExpiresSeconds) * time.Second
		}

		url, expiresAt, err := s.store.GetPresignedURL(ctx, req.ObjectKey, expires)
		if err == nil {
			resp := &mediapb.GetMediaURLResponse{Url: url}
			if !expiresAt.IsZero() {
				resp.ExpiresAt = expiresAt.Unix()
			}
			return resp, nil
		}
	}

	// Fallback to local / DB download URL
	return &mediapb.GetMediaURLResponse{
		Url: "/api/v1/media/download/" + req.ObjectKey,
	}, nil
}

// GetMediaFile retrieves a stored media file (from DB or local disk or Telegram) and returns raw bytes.
func (s *MediaServer) GetMediaFile(ctx context.Context, req *mediapb.GetMediaFileRequest) (*mediapb.GetMediaFileResponse, error) {
	if req.FileId == "" {
		return nil, status.Error(codes.InvalidArgument, "file_id is required")
	}

	// 1. Try MinIO storage
	if s.store != nil {
		rc, size, mime, err := s.store.GetFile(ctx, req.FileId)
		if err == nil {
			defer rc.Close()
			data, readErr := io.ReadAll(rc)
			if readErr == nil {
				return &mediapb.GetMediaFileResponse{
					FileId:    req.FileId,
					FileName:  req.FileId,
					MimeType:  mime,
					SizeBytes: size,
					Data:      data,
				}, nil
			}
		}
	}

	// 2. Try DB storage
	if s.dbStore != nil && s.dbStore.IsConfigured() {
		file, err := s.dbStore.GetFile(ctx, req.FileId)
		if err == nil {
			return &mediapb.GetMediaFileResponse{
				FileId:    file.FileID,
				FileName:  file.FileName,
				MimeType:  file.MimeType,
				SizeBytes: file.Size,
				Data:      file.Data,
			}, nil
		}
	}

	// 2. Try Local storage
	if s.localStorage != nil {
		file, err := s.localStorage.GetFile(ctx, req.FileId)
		if err == nil {
			return &mediapb.GetMediaFileResponse{
				FileId:    file.FileID,
				FileName:  file.FileName,
				MimeType:  file.MimeType,
				SizeBytes: file.Size,
				Data:      file.Data,
			}, nil
		}
	}

	// 3. Try Telegram storage
	if s.tgStore != nil && s.tgStore.IsConfigured() {
		rc, size, mime, err := s.tgStore.Download(ctx, req.FileId)
		if err == nil {
			defer rc.Close()
			data, readErr := io.ReadAll(rc)
			if readErr == nil {
				return &mediapb.GetMediaFileResponse{
					FileId:    req.FileId,
					FileName:  req.FileId,
					MimeType:  mime,
					SizeBytes: size,
					Data:      data,
				}, nil
			}
		}
	}

	return nil, status.Errorf(codes.NotFound, "media file %q not found", req.FileId)
}

// DeleteMedia removes an object from MinIO, DB, and local disk.
func (s *MediaServer) DeleteMedia(ctx context.Context, req *mediapb.DeleteMediaRequest) (*mediapb.DeleteMediaResponse, error) {
	if req.ObjectKey == "" {
		return nil, status.Error(codes.InvalidArgument, "object_key is required")
	}

	if s.store != nil {
		_ = s.store.Delete(ctx, req.ObjectKey)
	}
	if s.dbStore != nil && s.dbStore.IsConfigured() {
		_ = s.dbStore.Delete(ctx, req.ObjectKey)
	}
	if s.localStorage != nil {
		_ = s.localStorage.Delete(ctx, req.ObjectKey)
	}

	return &mediapb.DeleteMediaResponse{Success: true}, nil
}
