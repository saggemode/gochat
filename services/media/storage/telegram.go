package storage

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"image"
	"image/jpeg"
	_ "image/png"
	"io"
	"mime/multipart"
	"net/http"
	"net/url"
	"strings"
	"time"

	"go.uber.org/zap"
)

// TelegramStorage manages media upload and download via Telegram CDN / Bot API.
type TelegramStorage struct {
	apiID     string
	apiHash   string
	botToken  string
	channelID string
	client    *http.Client
	log       *zap.Logger
}

// TelegramUploadResult holds the returned identifiers from a Telegram upload.
type TelegramUploadResult struct {
	FileID          string `json:"file_id"`
	FileUniqueID    string `json:"file_unique_id"`
	ThumbnailFileID string `json:"thumbnail_file_id,omitempty"`
	ThumbnailURL    string `json:"thumbnail_url,omitempty"`
	URL             string `json:"url"`
	Size            int64  `json:"size"`
	MimeType        string `json:"mime_type"`
	FileName        string `json:"file_name"`
}

// TelegramFileResponse models the Telegram getFile response.
type telegramGetFileResponse struct {
	OK     bool `json:"ok"`
	Result struct {
		FileID       string `json:"file_id"`
		FileUniqueID string `json:"file_unique_id"`
		FileSize     int64  `json:"file_size"`
		FilePath     string `json:"file_path"`
	} `json:"result"`
	Description string `json:"description"`
}

// telegramSendDocResponse models the sendDocument / sendPhoto response.
type telegramSendDocResponse struct {
	OK     bool `json:"ok"`
	Result struct {
		MessageID int `json:"message_id"`
		Document  *struct {
			FileID       string `json:"file_id"`
			FileUniqueID string `json:"file_unique_id"`
			FileName     string `json:"file_name"`
			MimeType     string `json:"mime_type"`
			FileSize     int64  `json:"file_size"`
			Thumbnail    *struct {
				FileID   string `json:"file_id"`
				FileSize int64  `json:"file_size"`
				Width    int    `json:"width"`
				Height   int    `json:"height"`
			} `json:"thumbnail"`
		} `json:"document"`
		Photo []struct {
			FileID       string `json:"file_id"`
			FileUniqueID string `json:"file_unique_id"`
			Width        int    `json:"width"`
			Height       int    `json:"height"`
			FileSize     int64  `json:"file_size"`
		} `json:"photo"`
		Video *struct {
			FileID       string `json:"file_id"`
			FileUniqueID string `json:"file_unique_id"`
			FileSize     int64  `json:"file_size"`
			Thumbnail    *struct {
				FileID string `json:"file_id"`
			} `json:"thumbnail"`
		} `json:"video"`
		Audio *struct {
			FileID       string `json:"file_id"`
			FileUniqueID string `json:"file_unique_id"`
			FileSize     int64  `json:"file_size"`
		} `json:"audio"`
		Voice *struct {
			FileID       string `json:"file_id"`
			FileUniqueID string `json:"file_unique_id"`
			FileSize     int64  `json:"file_size"`
		} `json:"voice"`
	} `json:"result"`
	Description string `json:"description"`
}

// NewTelegramStorage creates a new TelegramStorage instance.
func NewTelegramStorage(apiID, apiHash, botToken, channelID string, log *zap.Logger) *TelegramStorage {
	return &TelegramStorage{
		apiID:     apiID,
		apiHash:   apiHash,
		botToken:  strings.TrimSpace(botToken),
		channelID: strings.TrimSpace(channelID),
		client: &http.Client{
			Timeout: 120 * time.Second,
		},
		log: log,
	}
}

// IsConfigured checks if the Telegram bot credentials and channel are provided.
func (t *TelegramStorage) IsConfigured() bool {
	return t.botToken != "" && t.channelID != ""
}

// Upload streams file content to the designated Telegram channel and returns the file_id and thumbnail.
func (t *TelegramStorage) Upload(ctx context.Context, reader io.Reader, fileName, mimeType string, size int64) (*TelegramUploadResult, error) {
	if !t.IsConfigured() {
		return nil, fmt.Errorf("telegram storage not configured: missing TELEGRAM_BOT_TOKEN or TELEGRAM_CHANNEL_ID")
	}

	// Buffer file data in memory / temp buffer for multipart stream and thumbnail generation
	data, err := io.ReadAll(reader)
	if err != nil {
		return nil, fmt.Errorf("reading upload stream: %w", err)
	}

	actualSize := int64(len(data))
	if actualSize > 50*1024*1024 {
		return nil, fmt.Errorf("file size (%d bytes) exceeds Telegram 50MB limit", actualSize)
	}

	// Generate a compact thumbnail data URI if it's an image
	thumbnailDataURI := ""
	if strings.HasPrefix(strings.ToLower(mimeType), "image/") {
		if thumbBytes, err := t.generateThumbnail(data, 320, 320); err == nil && len(thumbBytes) > 0 {
			thumbnailDataURI = "data:image/jpeg;base64," + base64.StdEncoding.EncodeToString(thumbBytes)
		}
	}

	// Prepare multipart form for sendDocument
	bodyBuf := &bytes.Buffer{}
	writer := multipart.NewWriter(bodyBuf)

	// Chat ID
	if err := writer.WriteField("chat_id", t.channelID); err != nil {
		return nil, fmt.Errorf("writing chat_id: %w", err)
	}

	// Caption
	cleanName := sanitizeFileName(fileName)
	if cleanName == "" {
		cleanName = "file"
	}
	_ = writer.WriteField("caption", fmt.Sprintf("GoChat CDN: %s (%s)", cleanName, mimeType))

	// File Part
	part, err := writer.CreateFormFile("document", cleanName)
	if err != nil {
		return nil, fmt.Errorf("creating document form file: %w", err)
	}
	if _, err := part.Write(data); err != nil {
		return nil, fmt.Errorf("writing file to multipart: %w", err)
	}

	if err := writer.Close(); err != nil {
		return nil, fmt.Errorf("closing multipart writer: %w", err)
	}

	apiURL := fmt.Sprintf("https://api.telegram.org/bot%s/sendDocument", t.botToken)
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, apiURL, bodyBuf)
	if err != nil {
		return nil, fmt.Errorf("creating telegram request: %w", err)
	}
	req.Header.Set("Content-Type", writer.FormDataContentType())

	resp, err := t.client.Do(req)
	if err != nil {
		return nil, fmt.Errorf("sending document to telegram: %w", err)
	}
	defer resp.Body.Close()

	respBytes, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, fmt.Errorf("reading telegram response: %w", err)
	}

	var tgResp telegramSendDocResponse
	if err := json.Unmarshal(respBytes, &tgResp); err != nil {
		return nil, fmt.Errorf("parsing telegram response: %w, raw: %s", err, string(respBytes))
	}

	if !tgResp.OK {
		return nil, fmt.Errorf("telegram upload failed: %s", tgResp.Description)
	}

	var fileID, fileUniqueID, thumbFileID string
	var returnSize int64 = actualSize

	if tgResp.Result.Document != nil {
		fileID = tgResp.Result.Document.FileID
		fileUniqueID = tgResp.Result.Document.FileUniqueID
		returnSize = tgResp.Result.Document.FileSize
		if tgResp.Result.Document.Thumbnail != nil {
			thumbFileID = tgResp.Result.Document.Thumbnail.FileID
		}
	} else if len(tgResp.Result.Photo) > 0 {
		// Pick largest photo
		largest := tgResp.Result.Photo[len(tgResp.Result.Photo)-1]
		fileID = largest.FileID
		fileUniqueID = largest.FileUniqueID
		returnSize = largest.FileSize
	} else if tgResp.Result.Video != nil {
		fileID = tgResp.Result.Video.FileID
		fileUniqueID = tgResp.Result.Video.FileUniqueID
		returnSize = tgResp.Result.Video.FileSize
		if tgResp.Result.Video.Thumbnail != nil {
			thumbFileID = tgResp.Result.Video.Thumbnail.FileID
		}
	} else if tgResp.Result.Audio != nil {
		fileID = tgResp.Result.Audio.FileID
		fileUniqueID = tgResp.Result.Audio.FileUniqueID
		returnSize = tgResp.Result.Audio.FileSize
	} else if tgResp.Result.Voice != nil {
		fileID = tgResp.Result.Voice.FileID
		fileUniqueID = tgResp.Result.Voice.FileUniqueID
		returnSize = tgResp.Result.Voice.FileSize
	}

	if fileID == "" {
		return nil, fmt.Errorf("telegram response did not return a valid file_id")
	}

	// If server-side thumbnail wasn't generated and telegram returned a thumb file_id, we can fetch its download link
	if thumbnailDataURI == "" && thumbFileID != "" {
		if thumbURL, err := t.GetDirectDownloadURL(ctx, thumbFileID); err == nil {
			thumbnailDataURI = thumbURL
		}
	}

	t.log.Info("file successfully uploaded to Telegram CDN",
		zap.String("file_id", fileID),
		zap.String("unique_id", fileUniqueID),
		zap.Int64("size", returnSize),
		zap.String("mime", mimeType),
	)

	return &TelegramUploadResult{
		FileID:          fileID,
		FileUniqueID:    fileUniqueID,
		ThumbnailFileID: thumbFileID,
		ThumbnailURL:    thumbnailDataURI,
		URL:             fmt.Sprintf("/api/v1/media/download/%s", fileID),
		Size:            returnSize,
		MimeType:        mimeType,
		FileName:        cleanName,
	}, nil
}

// GetFilePath queries Telegram for the relative file path of a file_id.
func (t *TelegramStorage) GetFilePath(ctx context.Context, fileID string) (string, int64, error) {
	if !t.IsConfigured() {
		return "", 0, fmt.Errorf("telegram storage not configured")
	}

	apiURL := fmt.Sprintf("https://api.telegram.org/bot%s/getFile?file_id=%s", t.botToken, url.QueryEscape(fileID))
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, apiURL, nil)
	if err != nil {
		return "", 0, fmt.Errorf("creating getFile request: %w", err)
	}

	resp, err := t.client.Do(req)
	if err != nil {
		return "", 0, fmt.Errorf("executing getFile request: %w", err)
	}
	defer resp.Body.Close()

	var res telegramGetFileResponse
	if err := json.NewDecoder(resp.Body).Decode(&res); err != nil {
		return "", 0, fmt.Errorf("decoding getFile response: %w", err)
	}

	if !res.OK {
		return "", 0, fmt.Errorf("telegram getFile error: %s", res.Description)
	}

	return res.Result.FilePath, res.Result.FileSize, nil
}

// GetDirectDownloadURL returns the full HTTPS URL to download a file from Telegram servers.
func (t *TelegramStorage) GetDirectDownloadURL(ctx context.Context, fileID string) (string, error) {
	filePath, _, err := t.GetFilePath(ctx, fileID)
	if err != nil {
		return "", err
	}
	return fmt.Sprintf("https://api.telegram.org/file/bot%s/%s", t.botToken, filePath), nil
}

// Download streams the file bytes from Telegram CDN directly to an io.ReadCloser.
func (t *TelegramStorage) Download(ctx context.Context, fileID string) (io.ReadCloser, int64, string, error) {
	filePath, size, err := t.GetFilePath(ctx, fileID)
	if err != nil {
		return nil, 0, "", err
	}

	fileURL := fmt.Sprintf("https://api.telegram.org/file/bot%s/%s", t.botToken, filePath)
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, fileURL, nil)
	if err != nil {
		return nil, 0, "", fmt.Errorf("creating download request: %w", err)
	}

	resp, err := t.client.Do(req)
	if err != nil {
		return nil, 0, "", fmt.Errorf("downloading from telegram: %w", err)
	}

	if resp.StatusCode != http.StatusOK {
		resp.Body.Close()
		return nil, 0, "", fmt.Errorf("telegram download status %d", resp.StatusCode)
	}

	contentType := resp.Header.Get("Content-Type")
	if contentType == "" {
		contentType = "application/octet-stream"
	}

	return resp.Body, size, contentType, nil
}

// generateThumbnail creates a scaled down JPEG thumbnail from image bytes.
func (t *TelegramStorage) generateThumbnail(imgData []byte, maxWidth, maxHeight int) ([]byte, error) {
	img, _, err := image.Decode(bytes.NewReader(imgData))
	if err != nil {
		return nil, err
	}

	bounds := img.Bounds()
	w := bounds.Dx()
	h := bounds.Dy()
	if w <= 0 || h <= 0 {
		return nil, fmt.Errorf("invalid image dimensions")
	}

	// Calculate target dimensions
	scaleX := float64(maxWidth) / float64(w)
	scaleY := float64(maxHeight) / float64(h)
	scale := scaleX
	if scaleY < scale {
		scale = scaleY
	}
	if scale > 1.0 {
		scale = 1.0
	}

	targetW := int(float64(w) * scale)
	targetH := int(float64(h) * scale)
	if targetW < 1 {
		targetW = 1
	}
	if targetH < 1 {
		targetH = 1
	}

	// Simple nearest-neighbor/box downscale into RGBA
	thumb := image.NewRGBA(image.Rect(0, 0, targetW, targetH))
	for y := 0; y < targetH; y++ {
		srcY := bounds.Min.Y + int(float64(y)/scale)
		if srcY >= bounds.Max.Y {
			srcY = bounds.Max.Y - 1
		}
		for x := 0; x < targetW; x++ {
			srcX := bounds.Min.X + int(float64(x)/scale)
			if srcX >= bounds.Max.X {
				srcX = bounds.Max.X - 1
			}
			thumb.Set(x, y, img.At(srcX, srcY))
		}
	}

	outBuf := &bytes.Buffer{}
	if err := jpeg.Encode(outBuf, thumb, &jpeg.Options{Quality: 65}); err != nil {
		return nil, err
	}

	return outBuf.Bytes(), nil
}

// Ping checks if the Telegram Bot API is responsive.
func (t *TelegramStorage) Ping(ctx context.Context) error {
	if !t.IsConfigured() {
		return nil
	}
	apiURL := fmt.Sprintf("https://api.telegram.org/bot%s/getMe", t.botToken)
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, apiURL, nil)
	if err != nil {
		return err
	}
	resp, err := t.client.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("getMe returned HTTP %d", resp.StatusCode)
	}
	return nil
}
