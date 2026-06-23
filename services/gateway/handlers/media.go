package handlers

import (
	"io"
	"net/http"
	"strings"

	"github.com/gin-gonic/gin"
	"go.uber.org/zap"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"

	mediapb "gochat/gen/media"
)

// MediaHandler wraps the Media Service gRPC client.
type MediaHandler struct {
	client mediapb.MediaServiceClient
	log    *zap.Logger
}

// NewMediaHandler constructs the MediaHandler.
func NewMediaHandler(client mediapb.MediaServiceClient, log *zap.Logger) *MediaHandler {
	return &MediaHandler{client: client, log: log}
}

// Upload handles incoming multipart uploads and streams them to the Media gRPC service.
func (h *MediaHandler) Upload(c *gin.Context) {
	userID, _ := c.Get("user_id")

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
				UploaderId: userID.(string),
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
