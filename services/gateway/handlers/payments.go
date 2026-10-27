package handlers

import (
	"bytes"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha512"
	"encoding/hex"
	"encoding/json"
	"io"
	"math"
	"net/http"
	"os"
	"time"

	pb "gochat/gen/business"

	"github.com/gin-gonic/gin"
	"go.uber.org/zap"
)

// Paystack payment integration. The secret key lives only in the gateway env
// (PAYSTACK_SECRET_KEY); nothing sensitive reaches the mobile client. The flow:
//   1. client POSTs order ids to /payments/paystack/init
//   2. gateway asks the business service for the trusted total + attaches a ref
//   3. gateway initializes the Paystack transaction and returns authorization_url
//   4. client opens that URL (Paystack checkout)
//   5. Paystack calls our webhook (signature-verified) → orders flip to paid
//   6. client can also poll /verify/:reference as a fallback to the webhook

const paystackBaseURL = "https://api.paystack.co"

var paystackHTTP = &http.Client{Timeout: 20 * time.Second}

func paystackSecret() string { return os.Getenv("PAYSTACK_SECRET_KEY") }

func newPaymentReference() string {
	b := make([]byte, 16)
	if _, err := rand.Read(b); err != nil {
		return "gochat_" + hex.EncodeToString([]byte(time.Now().String()))
	}
	return "gochat_" + hex.EncodeToString(b)
}

// InitPaystackPayment starts a Paystack checkout for the buyer's orders.
func (h *BusinessHandler) InitPaystackPayment(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}
	if paystackSecret() == "" {
		c.JSON(http.StatusServiceUnavailable, gin.H{"error": "payments are not configured"})
		return
	}

	var req struct {
		OrderIDs []string `json:"order_ids" binding:"required"`
		Email    string   `json:"email"`
	}
	if err := c.ShouldBindJSON(&req); err != nil || len(req.OrderIDs) == 0 {
		c.JSON(http.StatusBadRequest, gin.H{"error": "order_ids are required"})
		return
	}

	// Paystack requires a customer email; prefer the authenticated user's.
	email := req.Email
	if email == "" {
		email = c.GetString("email")
	}
	if email == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "email is required for payment"})
		return
	}

	reference := newPaymentReference()

	// Server-trusted amount: the business service sums the orders' grand totals
	// and records the reference against them (status → pending).
	attach, err := h.client.AttachPaymentReference(c.Request.Context(), &pb.AttachPaymentReferenceRequest{
		Reference: reference,
		BuyerId:   userID,
		OrderIds:  req.OrderIDs,
	}, jsonOpt)
	if err != nil {
		h.log.Warn("attach payment reference failed", zap.Error(err))
		c.JSON(http.StatusBadRequest, gin.H{"error": "could not prepare payment for these orders"})
		return
	}

	currency := attach.Currency
	if currency == "" {
		currency = "NGN"
	}
	// Paystack expects the amount in the currency's subunit (kobo for NGN).
	amountSubunit := int64(math.Round(attach.Amount * 100))
	if amountSubunit <= 0 {
		c.JSON(http.StatusBadRequest, gin.H{"error": "order total must be greater than zero"})
		return
	}

	// The app's WebView watches for this callback URL to know the checkout
	// finished; actual paid/failed status is confirmed server-side via verify.
	callbackURL := os.Getenv("PAYSTACK_CALLBACK_URL")
	if callbackURL == "" {
		callbackURL = "https://gochat.app/paystack/callback"
	}
	payload, _ := json.Marshal(map[string]any{
		"email":        email,
		"amount":       amountSubunit,
		"currency":     currency,
		"reference":    reference,
		"callback_url": callbackURL,
	})
	httpReq, _ := http.NewRequestWithContext(c.Request.Context(), http.MethodPost,
		paystackBaseURL+"/transaction/initialize", bytes.NewReader(payload))
	httpReq.Header.Set("Authorization", "Bearer "+paystackSecret())
	httpReq.Header.Set("Content-Type", "application/json")

	resp, err := paystackHTTP.Do(httpReq)
	if err != nil {
		h.log.Error("paystack initialize request failed", zap.Error(err))
		c.JSON(http.StatusBadGateway, gin.H{"error": "payment provider unreachable"})
		return
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(resp.Body)

	var ps struct {
		Status  bool   `json:"status"`
		Message string `json:"message"`
		Data    struct {
			AuthorizationURL string `json:"authorization_url"`
			AccessCode       string `json:"access_code"`
			Reference        string `json:"reference"`
		} `json:"data"`
	}
	if err := json.Unmarshal(body, &ps); err != nil || !ps.Status || ps.Data.AuthorizationURL == "" {
		h.log.Warn("paystack initialize rejected", zap.String("body", string(body)))
		c.JSON(http.StatusBadGateway, gin.H{"error": "could not start payment"})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"authorization_url": ps.Data.AuthorizationURL,
		"access_code":       ps.Data.AccessCode,
		"reference":         ps.Data.Reference,
		"amount":            attach.Amount,
		"currency":          currency,
	})
}

// PaystackWebhook receives Paystack events. It is public but authenticated by
// the x-paystack-signature HMAC over the raw body, so only Paystack can mark an
// order paid.
func (h *BusinessHandler) PaystackWebhook(c *gin.Context) {
	secret := paystackSecret()
	if secret == "" {
		c.Status(http.StatusServiceUnavailable)
		return
	}

	body, err := io.ReadAll(c.Request.Body)
	if err != nil {
		c.Status(http.StatusBadRequest)
		return
	}

	mac := hmac.New(sha512.New, []byte(secret))
	mac.Write(body)
	expected := hex.EncodeToString(mac.Sum(nil))
	got := c.GetHeader("x-paystack-signature")
	if !hmac.Equal([]byte(expected), []byte(got)) {
		h.log.Warn("paystack webhook signature mismatch")
		c.Status(http.StatusUnauthorized)
		return
	}

	var evt struct {
		Event string `json:"event"`
		Data  struct {
			Reference string `json:"reference"`
			Status    string `json:"status"`
		} `json:"data"`
	}
	if err := json.Unmarshal(body, &evt); err != nil {
		c.Status(http.StatusBadRequest)
		return
	}

	if evt.Event == "charge.success" && evt.Data.Reference != "" {
		if _, err := h.client.ConfirmPaymentByReference(c.Request.Context(), &pb.ConfirmPaymentByReferenceRequest{
			Reference: evt.Data.Reference,
		}, jsonOpt); err != nil {
			h.log.Error("confirm payment from webhook failed", zap.Error(err), zap.String("reference", evt.Data.Reference))
			// 500 so Paystack retries the webhook.
			c.Status(http.StatusInternalServerError)
			return
		}
	}

	// Always 200 for handled/ignored events so Paystack stops retrying.
	c.Status(http.StatusOK)
}

// VerifyPaystackPayment lets the client confirm a payment right after the
// checkout returns, as a fallback to the asynchronous webhook.
func (h *BusinessHandler) VerifyPaystackPayment(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}
	if paystackSecret() == "" {
		c.JSON(http.StatusServiceUnavailable, gin.H{"error": "payments are not configured"})
		return
	}
	reference := c.Param("reference")
	if reference == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "reference is required"})
		return
	}

	httpReq, _ := http.NewRequestWithContext(c.Request.Context(), http.MethodGet,
		paystackBaseURL+"/transaction/verify/"+reference, nil)
	httpReq.Header.Set("Authorization", "Bearer "+paystackSecret())

	resp, err := paystackHTTP.Do(httpReq)
	if err != nil {
		c.JSON(http.StatusBadGateway, gin.H{"error": "payment provider unreachable"})
		return
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(resp.Body)

	var ps struct {
		Status bool `json:"status"`
		Data   struct {
			Status string `json:"status"`
		} `json:"data"`
	}
	if err := json.Unmarshal(body, &ps); err != nil || !ps.Status {
		c.JSON(http.StatusBadGateway, gin.H{"error": "could not verify payment"})
		return
	}

	paid := ps.Data.Status == "success"
	if paid {
		if _, err := h.client.ConfirmPaymentByReference(c.Request.Context(), &pb.ConfirmPaymentByReferenceRequest{
			Reference: reference,
		}, jsonOpt); err != nil {
			h.log.Error("confirm payment from verify failed", zap.Error(err))
		}
	}

	c.JSON(http.StatusOK, gin.H{"paid": paid, "status": ps.Data.Status})
}
