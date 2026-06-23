package storage

import (
	"context"
	"fmt"
	"io"
	"net/url"
	"time"

	"github.com/google/uuid"
	"github.com/minio/minio-go/v7"
	"github.com/minio/minio-go/v7/pkg/credentials"
	"go.uber.org/zap"
)

// MinIOStorage wraps the MinIO client with GoChat-specific operations.
type MinIOStorage struct {
	client *minio.Client
	bucket string
	log    *zap.Logger
}

// NewMinIOStorage creates and initialises MinIO storage.
// It creates the bucket if it doesn't exist.
func NewMinIOStorage(endpoint, accessKey, secretKey, bucket string, useSSL bool, log *zap.Logger) (*MinIOStorage, error) {
	client, err := minio.New(endpoint, &minio.Options{
		Creds:  credentials.NewStaticV4(accessKey, secretKey, ""),
		Secure: useSSL,
	})
	if err != nil {
		return nil, fmt.Errorf("creating minio client: %w", err)
	}

	ctx := context.Background()

	// Retry until MinIO is ready (handles slow Docker startup)
	for attempt := 1; attempt <= 10; attempt++ {
		exists, err := client.BucketExists(ctx, bucket)
		if err == nil {
			if !exists {
				if err := client.MakeBucket(ctx, bucket, minio.MakeBucketOptions{}); err != nil {
					return nil, fmt.Errorf("creating bucket %q: %w", bucket, err)
				}
				log.Info("created MinIO bucket", zap.String("bucket", bucket))

				// Set public read policy for the bucket
				policy := fmt.Sprintf(`{
					"Version":"2012-10-17",
					"Statement":[{
						"Effect":"Allow",
						"Principal":"*",
						"Action":["s3:GetObject"],
						"Resource":["arn:aws:s3:::%s/*"]
					}]
				}`, bucket)
				if err := client.SetBucketPolicy(ctx, bucket, policy); err != nil {
					log.Warn("failed to set bucket policy", zap.Error(err))
				}
			}
			log.Info("MinIO connected", zap.String("endpoint", endpoint), zap.String("bucket", bucket))
			return &MinIOStorage{client: client, bucket: bucket, log: log}, nil
		}

		log.Warn("MinIO not ready, retrying...", zap.Int("attempt", attempt), zap.Error(err))
		time.Sleep(time.Duration(attempt) * time.Second)
	}

	return nil, fmt.Errorf("failed to connect to MinIO after 10 attempts")
}

// UploadResult holds the result of a successful upload.
type UploadResult struct {
	ObjectKey string
	URL       string
	Size      int64
}

// Upload streams data into MinIO and returns the object key and public URL.
func (s *MinIOStorage) Upload(ctx context.Context, reader io.Reader, fileName, mimeType string, size int64) (*UploadResult, error) {
	// Generate a unique object key to prevent collisions
	objectKey := fmt.Sprintf("%s/%s", time.Now().Format("2006/01/02"), uuid.New().String()+"-"+sanitizeFileName(fileName))

	info, err := s.client.PutObject(ctx, s.bucket, objectKey, reader, size, minio.PutObjectOptions{
		ContentType: mimeType,
	})
	if err != nil {
		return nil, fmt.Errorf("uploading to minio: %w", err)
	}

	// Construct the public URL
	publicURL := s.publicURL(objectKey)

	s.log.Info("file uploaded",
		zap.String("key", objectKey),
		zap.Int64("size", info.Size),
		zap.String("mime", mimeType),
	)

	return &UploadResult{
		ObjectKey: objectKey,
		URL:       publicURL,
		Size:      info.Size,
	}, nil
}

// GetPresignedURL generates a time-limited download URL for a private object.
// If expires is 0, returns the permanent public URL.
func (s *MinIOStorage) GetPresignedURL(ctx context.Context, objectKey string, expires time.Duration) (string, time.Time, error) {
	if expires == 0 {
		return s.publicURL(objectKey), time.Time{}, nil
	}

	reqParams := make(url.Values)
	presigned, err := s.client.PresignedGetObject(ctx, s.bucket, objectKey, expires, reqParams)
	if err != nil {
		return "", time.Time{}, fmt.Errorf("generating presigned URL: %w", err)
	}

	expiresAt := time.Now().Add(expires)
	return presigned.String(), expiresAt, nil
}

// Delete removes an object from MinIO.
func (s *MinIOStorage) Delete(ctx context.Context, objectKey string) error {
	if err := s.client.RemoveObject(ctx, s.bucket, objectKey, minio.RemoveObjectOptions{}); err != nil {
		return fmt.Errorf("deleting object %q: %w", objectKey, err)
	}
	s.log.Info("file deleted", zap.String("key", objectKey))
	return nil
}

// ── helpers ───────────────────────────────────────────────────────────────────

func (s *MinIOStorage) publicURL(objectKey string) string {
	return fmt.Sprintf("http://%s/%s/%s", s.client.EndpointURL().Host, s.bucket, objectKey)
}

func sanitizeFileName(name string) string {
	// Replace spaces and special chars that could break URL paths
	result := make([]byte, 0, len(name))
	for _, c := range []byte(name) {
		if (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') ||
			(c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.' {
			result = append(result, c)
		} else {
			result = append(result, '_')
		}
	}
	return string(result)
}
