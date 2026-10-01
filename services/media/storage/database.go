package storage

import (
	"context"
	"fmt"
	"io"
	"strings"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"
	"go.uber.org/zap"
)

// DatabaseStorage stores media files in PostgreSQL (e.g. Neon) as a durable fallback.
type DatabaseStorage struct {
	db  *pgxpool.Pool
	log *zap.Logger
}

// DatabaseUploadResult holds the result of a database-backed media upload.
type DatabaseUploadResult struct {
	FileID   string
	URL      string
	Size     int64
	MimeType string
	FileName string
}

// StoredMediaFile models a file retrieved from database storage.
type StoredMediaFile struct {
	FileID   string
	FileName string
	MimeType string
	Size     int64
	Data     []byte
}

// NewDatabaseStorage constructs a new DatabaseStorage provider.
func NewDatabaseStorage(db *pgxpool.Pool, log *zap.Logger) *DatabaseStorage {
	return &DatabaseStorage{
		db:  db,
		log: log,
	}
}

// IsConfigured returns true if the database pool is initialized and active.
func (d *DatabaseStorage) IsConfigured() bool {
	return d != nil && d.db != nil
}

// Ping checks if the database is reachable.
func (d *DatabaseStorage) Ping(ctx context.Context) error {
	if !d.IsConfigured() {
		return fmt.Errorf("database storage not configured")
	}
	return d.db.Ping(ctx)
}

// Upload reads the stream into memory and saves it to media.media_files.
func (d *DatabaseStorage) Upload(ctx context.Context, reader io.Reader, fileName, mimeType, uploaderID string, size int64) (*DatabaseUploadResult, error) {
	if !d.IsConfigured() {
		return nil, fmt.Errorf("database storage not configured")
	}

	data, err := io.ReadAll(reader)
	if err != nil {
		return nil, fmt.Errorf("reading file stream: %w", err)
	}

	actualSize := int64(len(data))
	if actualSize == 0 {
		return nil, fmt.Errorf("uploaded file is empty")
	}

	fileID := uuid.New().String()
	cleanName := sanitizeFileName(fileName)
	if cleanName == "" {
		cleanName = "file"
	}

	var parsedUploader *uuid.UUID
	if strings.TrimSpace(uploaderID) != "" {
		if u, err := uuid.Parse(strings.TrimSpace(uploaderID)); err == nil {
			parsedUploader = &u
		}
	}

	mediaType := "file"
	if strings.HasPrefix(mimeType, "image/") {
		mediaType = "image"
	} else if strings.HasPrefix(mimeType, "video/") {
		mediaType = "video"
	} else if strings.HasPrefix(mimeType, "audio/") {
		mediaType = "audio"
	}

	url := fmt.Sprintf("/api/v1/media/download/%s", fileID)

	query := `
		INSERT INTO media.media_files (
			id, object_key, url, mime_type, size_bytes, media_type, uploader_id, data, file_name, uploaded_at
		) VALUES (
			$1, $2, $3, $4, $5, $6, $7, $8, $9, NOW()
		)
	`

	_, err = d.db.Exec(ctx, query,
		fileID,
		fileID,
		url,
		mimeType,
		actualSize,
		mediaType,
		parsedUploader,
		data,
		cleanName,
	)
	if err != nil {
		return nil, fmt.Errorf("inserting media file into database: %w", err)
	}

	d.log.Info("file successfully stored in PostgreSQL media storage",
		zap.String("file_id", fileID),
		zap.Int64("size", actualSize),
		zap.String("mime", mimeType),
		zap.String("name", cleanName),
	)

	return &DatabaseUploadResult{
		FileID:   fileID,
		URL:      url,
		Size:     actualSize,
		MimeType: mimeType,
		FileName: cleanName,
	}, nil
}

// GetFile retrieves a media file from media.media_files by its ID or object key.
func (d *DatabaseStorage) GetFile(ctx context.Context, fileID string) (*StoredMediaFile, error) {
	if !d.IsConfigured() {
		return nil, fmt.Errorf("database storage not configured")
	}

	query := `
		SELECT id, COALESCE(file_name, 'file'), mime_type, size_bytes, data
		FROM media.media_files
		WHERE id::text = $1 OR object_key = $1
		LIMIT 1
	`

	var idStr, fileName, mimeType string
	var sizeBytes int64
	var data []byte

	err := d.db.QueryRow(ctx, query, fileID).Scan(&idStr, &fileName, &mimeType, &sizeBytes, &data)
	if err != nil {
		return nil, fmt.Errorf("fetching media file %q from db: %w", fileID, err)
	}

	if len(data) == 0 {
		return nil, fmt.Errorf("media file %q has no blob data in db", fileID)
	}

	return &StoredMediaFile{
		FileID:   idStr,
		FileName: fileName,
		MimeType: mimeType,
		Size:     int64(len(data)),
		Data:     data,
	}, nil
}

// Delete removes a file record from media.media_files.
func (d *DatabaseStorage) Delete(ctx context.Context, fileID string) error {
	if !d.IsConfigured() {
		return fmt.Errorf("database storage not configured")
	}

	query := `DELETE FROM media.media_files WHERE id::text = $1 OR object_key = $1`
	_, err := d.db.Exec(ctx, query, fileID)
	return err
}
