package handlers

import (
	"net/http"

	pb "gochat/gen/payment"

	"github.com/gin-gonic/gin"
	"go.uber.org/zap"
)

type PaymentHandler struct {
	client pb.PaymentServiceClient
	log    *zap.Logger
}

func NewPaymentHandler(client pb.PaymentServiceClient, log *zap.Logger) *PaymentHandler {
	return &PaymentHandler{client: client, log: log}
}

func (h *PaymentHandler) CreateWallet(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}
	var req struct{ Currency string `json:"currency"` }
	c.ShouldBindJSON(&req)
	resp, err := h.client.CreateWallet(c.Request.Context(), &pb.CreateWalletRequest{UserId: userID, Currency: req.Currency})
	if err != nil { c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()}); return }
	c.JSON(http.StatusCreated, resp)
}

func (h *PaymentHandler) GetWallet(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}
	resp, err := h.client.GetWallet(c.Request.Context(), &pb.GetWalletRequest{UserId: userID})
	if err != nil { c.JSON(http.StatusNotFound, gin.H{"error": err.Error()}); return }
	c.JSON(http.StatusOK, resp)
}

func (h *PaymentHandler) SendPayment(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}
	var req struct {
		ReceiverID  string  `json:"receiver_id" binding:"required"`
		Amount      float64 `json:"amount" binding:"required"`
		Currency    string  `json:"currency"`
		Description string  `json:"description"`
	}
	if err := c.ShouldBindJSON(&req); err != nil { c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()}); return }
	resp, err := h.client.SendPayment(c.Request.Context(), &pb.SendPaymentRequest{
		SenderId: userID, ReceiverId: req.ReceiverID, Amount: req.Amount,
		Currency: req.Currency, Description: req.Description,
	})
	if err != nil { c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()}); return }
	c.JSON(http.StatusOK, resp)
}

func (h *PaymentHandler) RequestPayment(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}
	var req struct {
		PayerID     string  `json:"payer_id" binding:"required"`
		Amount      float64 `json:"amount" binding:"required"`
		Currency    string  `json:"currency"`
		Description string  `json:"description"`
	}
	if err := c.ShouldBindJSON(&req); err != nil { c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()}); return }
	resp, err := h.client.RequestPayment(c.Request.Context(), &pb.RequestPaymentRequest{
		RequesterId: userID, PayerId: req.PayerID, Amount: req.Amount,
		Currency: req.Currency, Description: req.Description,
	})
	if err != nil { c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()}); return }
	c.JSON(http.StatusOK, resp)
}

func (h *PaymentHandler) CreateExpenseGroup(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}
	var req struct {
		ConversationID string `json:"conversation_id" binding:"required"`
		Name           string `json:"name" binding:"required"`
		Currency       string `json:"currency"`
	}
	if err := c.ShouldBindJSON(&req); err != nil { c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()}); return }
	resp, err := h.client.CreateExpenseGroup(c.Request.Context(), &pb.CreateExpenseGroupRequest{
		ConversationId: req.ConversationID, CreatedBy: userID, Name: req.Name, Currency: req.Currency,
	})
	if err != nil { c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()}); return }
	c.JSON(http.StatusCreated, resp)
}

func (h *PaymentHandler) AddExpense(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}
	groupID := c.Param("id")
	var req struct {
		Description string   `json:"description" binding:"required"`
		Amount      float64  `json:"amount" binding:"required"`
		SplitAmong  []string `json:"split_among"`
	}
	if err := c.ShouldBindJSON(&req); err != nil { c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()}); return }
	resp, err := h.client.AddExpense(c.Request.Context(), &pb.AddExpenseRequest{
		ExpenseGroupId: groupID, PaidBy: userID, Description: req.Description,
		Amount: req.Amount, SplitAmong: req.SplitAmong,
	})
	if err != nil { c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()}); return }
	c.JSON(http.StatusCreated, resp)
}

func (h *PaymentHandler) SettleExpense(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}
	groupID := c.Param("id")
	resp, err := h.client.SettleExpense(c.Request.Context(), &pb.SettleExpenseRequest{
		ExpenseGroupId: groupID, UserId: userID,
	})
	if err != nil { c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()}); return }
	c.JSON(http.StatusOK, resp)
}

func (h *PaymentHandler) GetTransactionHistory(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}
	resp, err := h.client.GetTransactionHistory(c.Request.Context(), &pb.GetTransactionHistoryRequest{
		UserId: userID, Limit: 50,
	})
	if err != nil { c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()}); return }
	c.JSON(http.StatusOK, resp)
}
