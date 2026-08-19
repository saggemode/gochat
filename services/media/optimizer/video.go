package optimizer

import (
	"bytes"
	"context"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"time"

	"github.com/google/uuid"
	"go.uber.org/zap"
)

var hasFFmpeg = checkFFmpegInstalled()

func checkFFmpegInstalled() bool {
	_, err := exec.LookPath("ffmpeg")
	return err == nil
}

// OptimizeVideo runs faststart and H.264 compression via ffmpeg if available.
func OptimizeVideo(ctx context.Context, r io.Reader, mimeType string, log *zap.Logger) (io.Reader, string, int64, error) {
	if !hasFFmpeg {
		log.Debug("ffmpeg not installed on host, skipping video compression")
		return nil, mimeType, 0, nil
	}

	tempDir := os.TempDir()
	inPath := filepath.Join(tempDir, fmt.Sprintf("in_%s.tmp", uuid.New().String()))
	outPath := filepath.Join(tempDir, fmt.Sprintf("out_%s.mp4", uuid.New().String()))

	defer os.Remove(inPath)
	defer os.Remove(outPath)

	inFile, err := os.Create(inPath)
	if err != nil {
		return nil, mimeType, 0, fmt.Errorf("create temp video input: %w", err)
	}

	if _, err := io.Copy(inFile, r); err != nil {
		inFile.Close()
		return nil, mimeType, 0, fmt.Errorf("write temp video input: %w", err)
	}
	inFile.Close()

	// Run ffmpeg compression: H.264 CRF 26, AAC 128k, faststart for instant streaming
	cmdCtx, cancel := context.WithTimeout(ctx, 3*time.Minute)
	defer cancel()

	cmd := exec.CommandContext(cmdCtx, "ffmpeg",
		"-y",
		"-i", inPath,
		"-vcodec", "libx264",
		"-crf", "26",
		"-preset", "fast",
		"-movflags", "+faststart",
		"-acodec", "aac",
		"-b:a", "128k",
		"-max_muxing_queue_size", "1024",
		outPath,
	)

	var stderr bytes.Buffer
	cmd.Stderr = &stderr

	if err := cmd.Run(); err != nil {
		log.Warn("ffmpeg optimization failed, using original stream",
			zap.Error(err),
			zap.String("stderr", stderr.String()),
		)
		// Re-read input file as fallback
		originalBytes, readErr := os.ReadFile(inPath)
		if readErr != nil {
			return nil, mimeType, 0, fmt.Errorf("read fallback video: %w", readErr)
		}
		return bytes.NewReader(originalBytes), mimeType, int64(len(originalBytes)), nil
	}

	outBytes, err := os.ReadFile(outPath)
	if err != nil {
		return nil, mimeType, 0, fmt.Errorf("read optimized video output: %w", err)
	}

	log.Info("video optimized with faststart",
		zap.Int("optimized_size", len(outBytes)),
	)

	return bytes.NewReader(outBytes), "video/mp4", int64(len(outBytes)), nil
}
