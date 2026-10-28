package handlers

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"os/exec"
	"strings"
	"time"

	"github.com/gin-gonic/gin"
	"go.uber.org/zap"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"

	_ "image/gif"
	_ "image/jpeg"
	_ "image/png"
	"strconv"

	"gochat/services/media/optimizer"
	mediapb "gochat/gen/media"
)



// MediaHandler wraps the Media Service gRPC client and Telegram CDN proxy.
type MediaHandler struct {
	client   mediapb.MediaServiceClient
	botToken string
	log      *zap.Logger
}

// NewMediaHandler constructs the MediaHandler.
func NewMediaHandler(client mediapb.MediaServiceClient, botToken string, log *zap.Logger) *MediaHandler {
	return &MediaHandler{
		client:   client,
		botToken: strings.TrimSpace(botToken),
		log:      log,
	}
}

// Upload handles incoming multipart uploads and streams them to the Media gRPC service.
func (h *MediaHandler) Upload(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}

	file, header, err := c.Request.FormFile("file")
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "file field is required in multipart form"})
		return
	}
	defer file.Close()

	fileName := header.Filename
	mimeType := header.Header.Get("Content-Type")
	totalSize := header.Size

	// Custom headers or browser defaults mapping
	if mimeType == "" {
		mimeType = "application/octet-stream"
	}

	stream, err := h.client.UploadMedia(c.Request.Context())
	if err != nil {
		h.handleGrpcError(c, err, "failed to open upload stream")
		return
	}

	// 1. Send metadata header
	err = stream.Send(&mediapb.UploadMediaRequest{
		Data: &mediapb.UploadMediaRequest_Header{
			Header: &mediapb.UploadHeader{
				FileName:   fileName,
				MimeType:   mimeType,
				TotalSize:  totalSize,
				UploaderId: userID,
				MediaType:  mapMimeToMediaType(mimeType),
			},
		},
	})
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "failed to write upload header: " + err.Error()})
		return
	}

	// 2. Stream file chunks
	buf := make([]byte, 64*1024) // 64KB chunk buffer
	for {
		n, err := file.Read(buf)
		if err == io.EOF {
			break
		}
		if err != nil {
			c.JSON(http.StatusBadRequest, gin.H{"error": "failed reading upload file source: " + err.Error()})
			return
		}

		err = stream.Send(&mediapb.UploadMediaRequest{
			Data: &mediapb.UploadMediaRequest_Chunk{
				Chunk: buf[:n],
			},
		})
		if err != nil {
			c.JSON(http.StatusInternalServerError, gin.H{"error": "failed to stream file chunk: " + err.Error()})
			return
		}
	}

	resp, err := stream.CloseAndRecv()
	if err != nil {
		h.handleGrpcError(c, err, "upload rejected by media service")
		return
	}

	c.JSON(http.StatusCreated, resp.Media)
}

func mapMimeToMediaType(mime string) mediapb.MediaType {
	mime = strings.ToLower(mime)
	if strings.HasPrefix(mime, "image/") {
		return mediapb.MediaType_MEDIA_IMAGE
	}
	if strings.HasPrefix(mime, "video/") {
		return mediapb.MediaType_MEDIA_VIDEO
	}
	if strings.HasPrefix(mime, "audio/") {
		// If audio is tagged as voice specifically or we can handle default audio formats
		if strings.Contains(mime, "ogg") || strings.Contains(mime, "opus") {
			return mediapb.MediaType_MEDIA_VOICE
		}
		return mediapb.MediaType_MEDIA_AUDIO
	}
	return mediapb.MediaType_MEDIA_FILE
}

func (h *MediaHandler) handleGrpcError(c *gin.Context, err error, actionMsg string) {
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

// DownloadTelegram proxies media downloads to the client.
// It checks Telegram CDN first (if botToken is configured), then falls back to the Media Service gRPC (PostgreSQL / Local storage).
func (h *MediaHandler) DownloadTelegram(c *gin.Context) {
	fileID := c.Param("fileId")
	if fileID == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "file_id is required"})
		return
	}

	// 1. Try Telegram CDN if bot token is configured
	if h.botToken != "" {
		if served := h.tryDownloadTelegram(c, fileID); served {
			return
		}
	}

	// 2. Fallback to Media Service gRPC (PostgreSQL DB or Local Disk storage)
	resp, err := h.client.GetMediaFile(c.Request.Context(), &mediapb.GetMediaFileRequest{
		FileId: fileID,
	})
	if err == nil && resp != nil && len(resp.Data) > 0 {
		contentType := resp.MimeType
		if contentType == "" {
			contentType = "application/octet-stream"
		}
		c.Header("Cache-Control", "public, max-age=31536000, immutable")
		if resp.FileName != "" {
			c.Header("Content-Disposition", fmt.Sprintf("inline; filename=%q", resp.FileName))
		}
		c.Data(http.StatusOK, contentType, resp.Data)
		return
	}

	c.JSON(http.StatusNotFound, gin.H{"error": "media file not found"})
}

func (h *MediaHandler) tryDownloadTelegram(c *gin.Context, fileID string) bool {
	client := &http.Client{Timeout: 120 * time.Second}
	getFileURL := "https://api.telegram.org/bot" + h.botToken + "/getFile?file_id=" + fileID
	req, err := http.NewRequestWithContext(c.Request.Context(), http.MethodGet, getFileURL, nil)
	if err != nil {
		return false
	}

	resp, err := client.Do(req)
	if err != nil {
		return false
	}
	defer resp.Body.Close()

	var getFileResp struct {
		OK     bool `json:"ok"`
		Result struct {
			FilePath string `json:"file_path"`
			FileSize int64  `json:"file_size"`
		} `json:"result"`
	}

	if err := json.NewDecoder(resp.Body).Decode(&getFileResp); err != nil || !getFileResp.OK {
		return false
	}

	fileDownloadURL := "https://api.telegram.org/file/bot" + h.botToken + "/" + getFileResp.Result.FilePath
	dlReq, err := http.NewRequestWithContext(c.Request.Context(), http.MethodGet, fileDownloadURL, nil)
	if err != nil {
		return false
	}

	dlResp, err := client.Do(dlReq)
	if err != nil {
		return false
	}
	defer dlResp.Body.Close()

	if dlResp.StatusCode != http.StatusOK {
		return false
	}

	contentType := dlResp.Header.Get("Content-Type")
	if contentType == "" {
		contentType = "application/octet-stream"
	}

	c.Header("Cache-Control", "public, max-age=31536000, immutable")
	c.DataFromReader(
		http.StatusOK,
		getFileResp.Result.FileSize,
		contentType,
		dlResp.Body,
		nil,
	)
	return true
}

// DownloadResized handles on-demand resizing for media downloads.
func (h *MediaHandler) DownloadResized(c *gin.Context) {
	fileID := c.Param("fileId")
	widthStr := c.Query("w")

	width, _ := strconv.Atoi(widthStr)
	if width <= 0 {
		h.DownloadTelegram(c)
		return
	}

	var rawData []byte
	var contentType string

	// 1. Try Telegram if bot token configured
	if h.botToken != "" {
		client := &http.Client{Timeout: 120 * time.Second}
		getFileURL := "https://api.telegram.org/bot" + h.botToken + "/getFile?file_id=" + fileID
		req, _ := http.NewRequestWithContext(c.Request.Context(), http.MethodGet, getFileURL, nil)
		resp, err := client.Do(req)
		if err == nil {
			defer resp.Body.Close()
			var getFileResp struct {
				OK     bool `json:"ok"`
				Result struct {
					FilePath string `json:"file_path"`
				} `json:"result"`
			}
			if json.NewDecoder(resp.Body).Decode(&getFileResp) == nil && getFileResp.OK {
				fileDownloadURL := "https://api.telegram.org/file/bot" + h.botToken + "/" + getFileResp.Result.FilePath
				dlResp, dlErr := client.Get(fileDownloadURL)
				if dlErr == nil {
					defer dlResp.Body.Close()
					if dlResp.StatusCode == http.StatusOK {
						contentType = dlResp.Header.Get("Content-Type")
						rawData, _ = io.ReadAll(dlResp.Body)
					}
				}
			}
		}
	}

	// 2. Fallback to Media Service gRPC
	if len(rawData) == 0 {
		resp, err := h.client.GetMediaFile(c.Request.Context(), &mediapb.GetMediaFileRequest{
			FileId: fileID,
		})
		if err == nil && resp != nil && len(resp.Data) > 0 {
			rawData = resp.Data
			contentType = resp.MimeType
		}
	}

	if len(rawData) == 0 {
		c.JSON(http.StatusNotFound, gin.H{"error": "media file not found"})
		return
	}

	if contentType == "" {
		contentType = "application/octet-stream"
	}

	// Video → return a poster JPEG (first frame) so clients don't fetch the
	// whole video just to show a preview.
	if strings.HasPrefix(contentType, "video/") {
		if poster, err := extractVideoPoster(c.Request.Context(), rawData); err == nil && len(poster) > 0 {
			c.Header("Cache-Control", "public, max-age=31536000, immutable")
			c.Data(http.StatusOK, "image/jpeg", poster)
			return
		}
		// ffmpeg unavailable/failed: fall through and serve the original.
	}

	// Resize if it's an image
	if strings.HasPrefix(contentType, "image/") && !strings.Contains(contentType, "gif") {
		optReader, optMime, _, err := optimizer.CompressImageWithLimit(bytes.NewReader(rawData), contentType, width, h.log)
		if err == nil {
			c.Header("Cache-Control", "public, max-age=31536000, immutable")
			c.DataFromReader(http.StatusOK, -1, optMime, optReader, nil)
			return
		}
	}

	c.Header("Cache-Control", "public, max-age=31536000, immutable")
	c.Data(http.StatusOK, contentType, rawData)
}

// extractVideoPoster uses ffmpeg to pull the first frame of a video as a JPEG.
// Returns an error if ffmpeg isn't installed or extraction fails.
func extractVideoPoster(ctx context.Context, video []byte) ([]byte, error) {
	if _, err := exec.LookPath("ffmpeg"); err != nil {
		return nil, err
	}
	tmpIn, err := os.CreateTemp("", "poster-*.mp4")
	if err != nil {
		return nil, err
	}
	defer os.Remove(tmpIn.Name())
	if _, err := tmpIn.Write(video); err != nil {
		tmpIn.Close()
		return nil, err
	}
	tmpIn.Close()

	outPath := tmpIn.Name() + ".jpg"
	defer os.Remove(outPath)

	cctx, cancel := context.WithTimeout(ctx, 20*time.Second)
	defer cancel()
	// Scale the poster down to 480px wide (keeps aspect) — small and quick to send.
	cmd := exec.CommandContext(cctx, "ffmpeg", "-y",
		"-i", tmpIn.Name(),
		"-frames:v", "1",
		"-vf", "scale=480:-2",
		"-q:v", "4",
		outPath,
	)
	if err := cmd.Run(); err != nil {
		return nil, err
	}
	return os.ReadFile(outPath)
}

