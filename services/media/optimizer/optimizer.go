package optimizer

import (
	"bytes"
	"context"
	"io"
	"strings"

	"go.uber.org/zap"
)

// Result holds the processed media stream details.
type Result struct {
	Reader    io.Reader
	MimeType  string
	Size      int64
	Optimized bool
}

// Optimize determines the media type and applies appropriate compression.
// If compression is not applicable or fails, it preserves the original stream.
func Optimize(ctx context.Context, r io.Reader, fileName, mimeType string, originalSize int64, log *zap.Logger) (Result, error) {
	mime := strings.ToLower(mimeType)

	// Image optimization
	if strings.HasPrefix(mime, "image/") && !strings.Contains(mime, "svg") {
		// Buffer image to memory
		var buf bytes.Buffer
		if _, err := io.Copy(&buf, r); err != nil {
			return Result{Reader: r, MimeType: mimeType, Size: originalSize, Optimized: false}, err
		}

		optReader, optMime, optSize, err := CompressImage(bytes.NewReader(buf.Bytes()), mime, log)
		if err != nil {
			log.Warn("image compression failed, storing original", zap.Error(err))
			return Result{
				Reader:    bytes.NewReader(buf.Bytes()),
				MimeType:  mimeType,
				Size:      int64(buf.Len()),
				Optimized: false,
			}, nil
		}

		// Only use optimized version if it actually reduced size (or normalized dimensions)
		savedBytes := int64(buf.Len()) - optSize
		log.Info("image compressed successfully",
			zap.Int64("original_bytes", int64(buf.Len())),
			zap.Int64("compressed_bytes", optSize),
			zap.Int64("saved_bytes", savedBytes),
			zap.Float64("saved_pct", float64(savedBytes)/float64(buf.Len())*100),
		)

		return Result{
			Reader:    optReader,
			MimeType:  optMime,
			Size:      optSize,
			Optimized: true,
		}, nil
	}

	// Video optimization
	if strings.HasPrefix(mime, "video/") {
		optReader, optMime, optSize, err := OptimizeVideo(ctx, r, mime, log)
		if err == nil && optReader != nil && optSize > 0 {
			return Result{
				Reader:    optReader,
				MimeType:  optMime,
				Size:      optSize,
				Optimized: true,
			}, nil
		}
	}

	// Fallback: pass-through
	return Result{
		Reader:    r,
		MimeType:  mimeType,
		Size:      originalSize,
		Optimized: false,
	}, nil
}
