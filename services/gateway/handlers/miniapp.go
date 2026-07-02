package handlers

import (
	"net/http"

	pb "gochat/gen/miniapp"

	"github.com/gin-gonic/gin"
	"go.uber.org/zap"
)

type MiniAppHandler struct {
	client pb.MiniAppServiceClient
	log    *zap.Logger
}

func NewMiniAppHandler(client pb.MiniAppServiceClient, log *zap.Logger) *MiniAppHandler {
	return &MiniAppHandler{client: client, log: log}
}

func (h *MiniAppHandler) RegisterBot(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}
	var req struct {
		Username    string            `json:"username" binding:"required"`
		DisplayName string            `json:"display_name" binding:"required"`
		Description string            `json:"description"`
		WebhookURL  string            `json:"webhook_url"`
		Commands    []*pb.BotCommand  `json:"commands"`
	}
	if err := c.ShouldBindJSON(&req); err != nil { c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()}); return }
	resp, err := h.client.RegisterBot(c.Request.Context(), &pb.RegisterBotRequest{
		OwnerId: userID, Username: req.Username, DisplayName: req.DisplayName,
		Description: req.Description, WebhookUrl: req.WebhookURL, Commands: req.Commands,
	})
	if err != nil { c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()}); return }
	c.JSON(http.StatusCreated, resp)
}

func (h *MiniAppHandler) ListBots(c *gin.Context) {
	resp, err := h.client.ListBots(c.Request.Context(), &pb.ListBotsRequest{Limit: 20})
	if err != nil { c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()}); return }
	c.JSON(http.StatusOK, resp)
}

func (h *MiniAppHandler) SendBotMessage(c *gin.Context) {
	botID := c.Param("id")
	var req struct {
		ConversationID string `json:"conversation_id" binding:"required"`
		Content        string `json:"content" binding:"required"`
	}
	if err := c.ShouldBindJSON(&req); err != nil { c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()}); return }
	resp, err := h.client.SendBotMessage(c.Request.Context(), &pb.SendBotMessageRequest{
		BotId: botID, ConversationId: req.ConversationID, Content: req.Content,
	})
	if err != nil { c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()}); return }
	c.JSON(http.StatusOK, resp)
}

func (h *MiniAppHandler) RegisterMiniApp(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}
	var req struct {
		Name        string `json:"name" binding:"required"`
		Description string `json:"description"`
		IconURL     string `json:"icon_url"`
		ManifestURL string `json:"manifest_url" binding:"required"`
		Category    string `json:"category"`
	}
	if err := c.ShouldBindJSON(&req); err != nil { c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()}); return }
	resp, err := h.client.RegisterMiniApp(c.Request.Context(), &pb.RegisterMiniAppRequest{
		DeveloperId: userID, Name: req.Name, Description: req.Description,
		IconUrl: req.IconURL, ManifestUrl: req.ManifestURL, Category: req.Category,
	})
	if err != nil { c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()}); return }
	c.JSON(http.StatusCreated, resp)
}

func (h *MiniAppHandler) LaunchMiniApp(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}
	appID := c.Param("id")
	var req struct{ ConversationID string `json:"conversation_id"` }
	c.ShouldBindJSON(&req)
	resp, err := h.client.LaunchMiniApp(c.Request.Context(), &pb.LaunchMiniAppRequest{
		UserId: userID, MiniappId: appID, ConversationId: req.ConversationID,
	})
	if err != nil { c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()}); return }
	c.JSON(http.StatusOK, resp)
}

func (h *MiniAppHandler) ListMiniApps(c *gin.Context) {
	resp, err := h.client.ListMiniApps(c.Request.Context(), &pb.ListMiniAppsRequest{Limit: 20, Category: c.Query("category")})
	if err != nil { c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()}); return }
	c.JSON(http.StatusOK, resp)
}

func (h *MiniAppHandler) RegisterWebhook(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}
	var req struct {
		URL    string   `json:"url" binding:"required"`
		Events []string `json:"events" binding:"required"`
	}
	if err := c.ShouldBindJSON(&req); err != nil { c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()}); return }
	resp, err := h.client.RegisterWebhook(c.Request.Context(), &pb.RegisterWebhookRequest{
		UserId: userID, Url: req.URL, Events: req.Events,
	})
	if err != nil { c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()}); return }
	c.JSON(http.StatusCreated, resp)
}

func (h *MiniAppHandler) ListWebhooks(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}
	resp, err := h.client.ListWebhooks(c.Request.Context(), &pb.ListWebhooksRequest{UserId: userID})
	if err != nil { c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()}); return }
	c.JSON(http.StatusOK, resp)
}

func (h *MiniAppHandler) DeleteWebhook(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}
	whID := c.Param("id")
	resp, err := h.client.DeleteWebhook(c.Request.Context(), &pb.DeleteWebhookRequest{UserId: userID, WebhookId: whID})
	if err != nil { c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()}); return }
	c.JSON(http.StatusOK, resp)
}

func (h *MiniAppHandler) CreateAPIKey(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}
	var req struct {
		Name        string   `json:"name" binding:"required"`
		Permissions []string `json:"permissions"`
	}
	if err := c.ShouldBindJSON(&req); err != nil { c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()}); return }
	resp, err := h.client.CreateAPIKey(c.Request.Context(), &pb.CreateAPIKeyRequest{
		UserId: userID, Name: req.Name, Permissions: req.Permissions,
	})
	if err != nil { c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()}); return }
	c.JSON(http.StatusCreated, resp)
}

func (h *MiniAppHandler) ListAPIKeys(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}
	resp, err := h.client.ListAPIKeys(c.Request.Context(), &pb.ListAPIKeysRequest{UserId: userID})
	if err != nil { c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()}); return }
	c.JSON(http.StatusOK, resp)
}

func (h *MiniAppHandler) RevokeAPIKey(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}
	keyID := c.Param("id")
	resp, err := h.client.RevokeAPIKey(c.Request.Context(), &pb.RevokeAPIKeyRequest{UserId: userID, KeyId: keyID})
	if err != nil { c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()}); return }
	c.JSON(http.StatusOK, resp)
}
