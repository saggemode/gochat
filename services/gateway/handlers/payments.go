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
	"strconv"
	"strings"
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
	// Phone-signup users have a synthetic, non-routable address (…@phone.local)
	// which Paystack rejects. Give it a syntactically valid, routable domain.
	if at := strings.LastIndex(email, "@"); at >= 0 {
		domain := email[at+1:]
		if strings.HasSuffix(domain, ".local") || !strings.Contains(domain, ".") {
			email = email[:at] + "@gochat.app"
		}
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
	paystackBody := map[string]any{
		"email":        email,
		"amount":       amountSubunit,
		"currency":     currency,
		"reference":    reference,
		"callback_url": callbackURL,
	}
	// Split settlement: when the seller has a payout subaccount, Paystack settles
	// their share directly and keeps the platform's percentage_charge as commission.
	if attach.SubaccountCode != "" {
		paystackBody["subaccount"] = attach.SubaccountCode
	}
	payload, _ := json.Marshal(paystackBody)
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
		h.log.Warn("paystack initialize rejected", zap.Int("status", resp.StatusCode), zap.String("body", string(body)))
		msg := strings.TrimSpace(ps.Message)
		if msg == "" {
			msg = "could not start payment"
		}
		c.JSON(http.StatusBadGateway, gin.H{"error": msg})
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

// paystackGet proxies a signed GET to Paystack and returns the raw body + status.
func (h *BusinessHandler) paystackGet(c *gin.Context, path string) ([]byte, int, error) {
	req, _ := http.NewRequestWithContext(c.Request.Context(), http.MethodGet, paystackBaseURL+path, nil)
	req.Header.Set("Authorization", "Bearer "+paystackSecret())
	resp, err := paystackHTTP.Do(req)
	if err != nil {
		return nil, 0, err
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(resp.Body)
	return body, resp.StatusCode, nil
}

// ListPaystackBanks returns the banks a seller can choose for their payout account.
func (h *BusinessHandler) ListPaystackBanks(c *gin.Context) {
	if getUserID(c) == "" {
		return
	}
	if paystackSecret() == "" {
		c.JSON(http.StatusServiceUnavailable, gin.H{"error": "payments are not configured"})
		return
	}
	currency := c.DefaultQuery("currency", "NGN")
	body, _, err := h.paystackGet(c, "/bank?currency="+currency)
	if err != nil {
		c.JSON(http.StatusBadGateway, gin.H{"error": "payment provider unreachable"})
		return
	}
	c.Data(http.StatusOK, "application/json", body)
}

// ResolvePaystackAccount verifies a bank account number and returns its owner name.
func (h *BusinessHandler) ResolvePaystackAccount(c *gin.Context) {
	if getUserID(c) == "" {
		return
	}
	if paystackSecret() == "" {
		c.JSON(http.StatusServiceUnavailable, gin.H{"error": "payments are not configured"})
		return
	}
	acct := c.Query("account_number")
	bank := c.Query("bank_code")
	if acct == "" || bank == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "account_number and bank_code are required"})
		return
	}
	body, _, err := h.paystackGet(c, "/bank/resolve?account_number="+acct+"&bank_code="+bank)
	if err != nil {
		c.JSON(http.StatusBadGateway, gin.H{"error": "payment provider unreachable"})
		return
	}

	var ps struct {
		Status bool `json:"status"`
		Data   struct {
			AccountNumber string `json:"account_number"`
			AccountName   string `json:"account_name"`
		} `json:"data"`
	}
	if err := json.Unmarshal(body, &ps); err != nil || !ps.Status || ps.Data.AccountName == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "could not verify account"})
		return
	}
	c.JSON(http.StatusOK, gin.H{"account_number": ps.Data.AccountNumber, "account_name": ps.Data.AccountName})
}

// CreateSellerSubaccount registers the seller's bank account with Paystack as a
// subaccount (so future split payments settle to it) and stores the code.
func (h *BusinessHandler) CreateSellerSubaccount(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}
	if paystackSecret() == "" {
		c.JSON(http.StatusServiceUnavailable, gin.H{"error": "payments are not configured"})
		return
	}

	var req struct {
		BankCode      string `json:"bank_code" binding:"required"`
		AccountNumber string `json:"account_number" binding:"required"`
		BusinessName  string `json:"business_name"`
		BankName      string `json:"bank_name"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "bank_code and account_number are required"})
		return
	}
	businessName := req.BusinessName
	if businessName == "" {
		shortID := userID
		if len(shortID) > 8 {
			shortID = shortID[:8]
		}
		businessName = "GoChat Seller " + shortID
	}

	commission := 5.0
	if v := os.Getenv("PLATFORM_COMMISSION_PERCENT"); v != "" {
		if f, err := strconv.ParseFloat(v, 64); err == nil && f >= 0 && f < 100 {
			commission = f
		}
	}

	payload, _ := json.Marshal(map[string]any{
		"business_name":    businessName,
		"settlement_bank":  req.BankCode,
		"account_number":   req.AccountNumber,
		"percentage_charge": commission, // platform commission kept from each split
	})
	httpReq, _ := http.NewRequestWithContext(c.Request.Context(), http.MethodPost,
		paystackBaseURL+"/subaccount", bytes.NewReader(payload))
	httpReq.Header.Set("Authorization", "Bearer "+paystackSecret())
	httpReq.Header.Set("Content-Type", "application/json")

	resp, err := paystackHTTP.Do(httpReq)
	if err != nil {
		c.JSON(http.StatusBadGateway, gin.H{"error": "payment provider unreachable"})
		return
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(resp.Body)

	var ps struct {
		Status bool   `json:"status"`
		Message string `json:"message"`
		Data   struct {
			SubaccountCode string `json:"subaccount_code"`
			AccountNumber  string `json:"account_number"`
			SettlementBank string `json:"settlement_bank"`
		} `json:"data"`
	}
	if err := json.Unmarshal(body, &ps); err != nil || !ps.Status || ps.Data.SubaccountCode == "" {
		h.log.Warn("paystack create subaccount rejected", zap.String("body", string(body)))
		c.JSON(http.StatusBadGateway, gin.H{"error": "could not register payout account"})
		return
	}

	// Resolve the account name for display (best-effort).
	accountName := ""
	if rb, _, err := h.paystackGet(c, "/bank/resolve?account_number="+req.AccountNumber+"&bank_code="+req.BankCode); err == nil {
		var rr struct {
			Data struct {
				AccountName string `json:"account_name"`
			} `json:"data"`
		}
		_ = json.Unmarshal(rb, &rr)
		accountName = rr.Data.AccountName
	}

	if _, err := h.client.SaveSellerSubaccount(c.Request.Context(), &pb.SaveSellerSubaccountRequest{
		UserId:         userID,
		SubaccountCode: ps.Data.SubaccountCode,
		BankCode:       req.BankCode,
		AccountNumber:  req.AccountNumber,
		AccountName:    accountName,
		BankName:       req.BankName,
	}, jsonOpt); err != nil {
		h.log.Error("persist seller subaccount failed", zap.Error(err))
		c.JSON(http.StatusInternalServerError, gin.H{"error": "could not save payout account"})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"subaccount_code": ps.Data.SubaccountCode,
		"account_number":  req.AccountNumber,
		"account_name":    accountName,
	})
}

// GetMyPayoutAccount returns the seller's saved payout account, if any.
func (h *BusinessHandler) GetMyPayoutAccount(c *gin.Context) {
	userID := getUserID(c)
	if userID == "" {
		return
	}
	resp, err := h.client.GetSellerSubaccount(c.Request.Context(), &pb.GetSellerSubaccountRequest{UserId: userID}, jsonOpt)
	if err != nil {
		c.JSON(http.StatusOK, gin.H{"exists": false})
		return
	}
	c.JSON(http.StatusOK, gin.H{
		"exists":         resp.Exists,
		"account_number": resp.AccountNumber,
		"account_name":   resp.AccountName,
		"bank_code":      resp.BankCode,
		"bank_name":      resp.BankName,
	})
}
