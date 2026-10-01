package storage

import (
	"context"
	"fmt"
	"io"
	"os"
	"path/filepath"

	"github.com/google/uuid"
	"go.uber.org/zap"
)

// LocalStorage stores media files on local disk as an emergency fallback.
type LocalStorage struct {
	baseDir string
	log     *zap.Logger
}

// LocalUploadResult holds the result of a local disk upload.
type LocalUploadResult struct {
	FileID   string
	URL      string
	Size     int64
	MimeType string
	FileName string
}

// NewLocalStorage creates a new LocalStorage provider with a configurable or default base directory.
func NewLocalStorage(log *zap.Logger) *LocalStorage {
	baseDir := os.Getenv("MEDIA_LOCAL_DIR")
	if baseDir == "" {
		baseDir = filepath.Join(".", "uploads")
	}

	_ = os.MkdirAll(baseDir, 0755)

	return &LocalStorage{
		baseDir: baseDir,
		log:     log,
	}
}

// Upload streams data into a local file and returns the file ID.
func (l *LocalStorage) Upload(ctx context.Context, reader io.Reader, fileName, mimeType string, size int64) (*LocalUploadResult, error) {
	fileID := uuid.New().String()
	cleanName := sanitizeFileName(fileName)
	if cleanName == "" {
		cleanName = "file"
	}

	filePath := filepath.Join(l.baseDir, fileID)
	f, err := os.Create(filePath)
	if err != nil {
		return nil, fmt.Errorf("creating local media file: %w", err)
	}
	defer f.Close()

	n, err := io.Copy(f, reader)
	if err != nil {
		_ = os.Remove(filePath)
		return nil, fmt.Errorf("writing local media file: %w", err)
	}

	url := fmt.Sprintf("/api/v1/media/download/%s", fileID)

	l.log.Info("file successfully stored in local disk media storage",
		zap.String("file_id", fileID),
		zap.Int64("size", n),
		zap.String("mime", mimeType),
	)

	return &LocalUploadResult{
		FileID:   fileID,
		URL:      url,
		Size:     n,
		MimeType: mimeType,
		FileName: cleanName,
	}, nil
}

// GetFile reads a local media file into memory.
func (l *LocalStorage) GetFile(ctx context.Context, fileID string) (*StoredMediaFile, error) {
	filePath := filepath.Join(l.baseDir, filepath.Clean(fileID))
	data, err := os.ReadFile(filePath)
	if err != nil {
		return nil, fmt.Errorf("reading local media file %q: %w", fileID, err)
	}

	return &StoredMediaFile{
		FileID:   fileID,
		FileName: fileID,
		MimeType: "application/octet-stream",
		Size:     int64(len(data)),
		Data:     data,
	}, nil
}

// Delete removes a file from local disk.
func (l *LocalStorage) Delete(ctx context.Context, fileID string) error {
	filePath := filepath.Join(l.baseDir, filepath.Clean(fileID))
	return os.Remove(filePath)
}
