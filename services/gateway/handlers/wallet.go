package handlers

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"io"
	"math"
	"math/big"
	"net/http"
	"os"
	"strings"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
	"go.uber.org/zap"
)

// WalletHandler owns the in-app wallet and red packets (hóngbāo).
//
// MONEY BOUNDARIES — read before changing anything here:
//   - Balances are held in business.wallets in MINOR units (kobo/cents).
//   - Red packets are PURE LEDGER operations: creating one debits the sender's
//     wallet, claiming one credits the claimer's wallet. No external money moves.
//   - Real money only ENTERS via Paystack top-up, and the amount credited is the
//     amount Paystack itself reports as paid (we never trust a client amount).
//     A top-up reference can credit a wallet at most once (unique ledger index).
//   - Money does NOT leave the system: withdrawal/payout is deliberately NOT
//     implemented here. Until a reviewed, tested payout path exists, a balance is
//     spendable only on more red packets. Do not add a cash-out without that review.
//
// It reuses the shared admin DB pool (adminDB in main.go); when that pool is nil
// (no PostgresDSN configured) every endpoint returns 503.
type WalletHandler struct {
	db  *pgxpool.Pool
	log *zap.Logger
}

func NewWalletHandler(db *pgxpool.Pool, log *zap.Logger) *WalletHandler {
	return &WalletHandler{db: db, log: log}
}

func (h *WalletHandler) ready(c *gin.Context) bool {
	if h.db == nil {
		c.JSON(http.StatusServiceUnavailable, gin.H{"error": "wallet is not configured"})
		return false
	}
	return true
}

// ensureWallet creates the wallet row if missing and returns the current balance.
func ensureWallet(ctx context.Context, q pgx.Tx, userID string) (int64, string, error) {
	var balance int64
	var currency string
	err := q.QueryRow(ctx, `
		INSERT INTO business.wallets (user_id) VALUES ($1)
		ON CONFLICT (user_id) DO UPDATE SET user_id = business.wallets.user_id
		RETURNING balance, currency`, userID).Scan(&balance, &currency)
	return balance, currency, err
}

// GetWallet returns the signed-in user's balance plus recent ledger entries.
func (h *WalletHandler) GetWallet(c *gin.Context) {
	if !h.ready(c) {
		return
	}
	userID := getUserID(c)
	if userID == "" {
		return
	}
	ctx := c.Request.Context()

	tx, err := h.db.Begin(ctx)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "wallet unavailable"})
		return
	}
	defer tx.Rollback(ctx)
	balance, currency, err := ensureWallet(ctx, tx, userID)
	if err != nil {
		h.log.Warn("ensure wallet failed", zap.Error(err))
		c.JSON(http.StatusInternalServerError, gin.H{"error": "wallet unavailable"})
		return
	}
	if err := tx.Commit(ctx); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "wallet unavailable"})
		return
	}

	// Recent ledger (read-only, outside the tx).
	entries := make([]gin.H, 0, 20)
	rows, err := h.db.Query(ctx, `
		SELECT amount, kind, reference, created_at
		FROM business.wallet_ledger WHERE user_id = $1
		ORDER BY created_at DESC LIMIT 20`, userID)
	if err == nil {
		defer rows.Close()
		for rows.Next() {
			var amount int64
			var kind, ref string
			var createdAt time.Time
			if rows.Scan(&amount, &kind, &ref, &createdAt) == nil {
				entries = append(entries, gin.H{
					"amount":     amount,
					"kind":       kind,
					"reference":  ref,
					"created_at": createdAt.Format(time.RFC3339),
				})
			}
		}
	}

	c.JSON(http.StatusOK, gin.H{"balance": balance, "currency": currency, "ledger": entries})
}

// TopUpInit starts a Paystack checkout whose proceeds credit the user's wallet.
// The reference is prefixed "wallet_" so verify/webhook knows to credit a wallet
// rather than settle a marketplace order.
func (h *WalletHandler) TopUpInit(c *gin.Context) {
	if !h.ready(c) {
		return
	}
	userID := getUserID(c)
	if userID == "" {
		return
	}
	var req struct {
		Amount   int64  `json:"amount"`   // minor units (kobo/cents)
		Email    string `json:"email"`
		Provider string `json:"provider"` // "paystack" (default) or "flutterwave"
	}
	if err := c.ShouldBindJSON(&req); err != nil || req.Amount <= 0 {
		c.JSON(http.StatusBadRequest, gin.H{"error": "a positive amount is required"})
		return
	}
	// Guardrail: cap a single top-up (minor units) to limit blast radius of a bug.
	if req.Amount > 2_000_000_00 {
		c.JSON(http.StatusBadRequest, gin.H{"error": "amount exceeds the per-top-up limit"})
		return
	}

	email := req.Email
	if email == "" {
		email = c.GetString("email")
	}
	if email == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "email is required for payment"})
		return
	}
	if at := strings.LastIndex(email, "@"); at >= 0 {
		domain := email[at+1:]
		if strings.HasSuffix(domain, ".local") || !strings.Contains(domain, ".") {
			email = email[:at] + "@gochat.app"
		}
	}

	currency := "NGN"

	// Same callback the marketplace checkout WebView watches for, so the client
	// knows the checkout finished and can verify the reference server-side.
	callbackURL := os.Getenv("PAYSTACK_CALLBACK_URL")
	if callbackURL == "" {
		callbackURL = "https://gochat.app/paystack/callback"
	}

	// Flutterwave is an alternative money-in provider with the same safety model
	// (credit only the provider-verified amount; idempotent via the ledger ref).
	if strings.EqualFold(req.Provider, "flutterwave") {
		h.initFlutterwaveTopUp(c, userID, req.Amount, email, currency, callbackURL)
		return
	}

	if paystackSecret() == "" {
		c.JSON(http.StatusServiceUnavailable, gin.H{"error": "payments are not configured"})
		return
	}
	reference := "wallet_" + randHex(16)
	paystackBody := map[string]any{
		"email":        email,
		"amount":       req.Amount,
		"currency":     currency,
		"reference":    reference,
		"callback_url": callbackURL,
		"metadata":     map[string]any{"purpose": "wallet_topup", "user_id": userID},
	}
	payload, _ := json.Marshal(paystackBody)
	httpReq, _ := http.NewRequestWithContext(c.Request.Context(), http.MethodPost,
		paystackBaseURL+"/transaction/initialize", bytes.NewReader(payload))
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
		Status bool `json:"status"`
		Data   struct {
			AuthorizationURL string `json:"authorization_url"`
			AccessCode       string `json:"access_code"`
			Reference        string `json:"reference"`
		} `json:"data"`
	}
	if err := json.Unmarshal(body, &ps); err != nil || !ps.Status || ps.Data.AuthorizationURL == "" {
		h.log.Warn("wallet top-up initialize rejected", zap.String("body", string(body)))
		c.JSON(http.StatusBadGateway, gin.H{"error": "could not start top-up"})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"authorization_url": ps.Data.AuthorizationURL,
		"reference":         ps.Data.Reference,
		"amount":            req.Amount,
		"currency":          currency,
	})
}

// TopUpVerify confirms a top-up with Paystack and credits the wallet with the
// amount Paystack reports as paid. Idempotent: the unique ledger index on the
// reference means a second verify of the same reference credits nothing.
func (h *WalletHandler) TopUpVerify(c *gin.Context) {
	if !h.ready(c) {
		return
	}
	userID := getUserID(c)
	if userID == "" {
		return
	}
	reference := c.Param("reference")
	// Flutterwave top-ups use a distinct reference prefix.
	if strings.HasPrefix(reference, "walletflw_") {
		h.verifyFlutterwaveTopUp(c, userID, reference)
		return
	}
	if !strings.HasPrefix(reference, "wallet_") {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid top-up reference"})
		return
	}
	if paystackSecret() == "" {
		c.JSON(http.StatusServiceUnavailable, gin.H{"error": "payments are not configured"})
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
			Status   string `json:"status"`
			Amount   int64  `json:"amount"`   // minor units, as actually charged
			Currency string `json:"currency"`
		} `json:"data"`
	}
	if err := json.Unmarshal(body, &ps); err != nil || !ps.Status {
		c.JSON(http.StatusBadGateway, gin.H{"error": "could not verify top-up"})
		return
	}
	if ps.Data.Status != "success" || ps.Data.Amount <= 0 {
		c.JSON(http.StatusOK, gin.H{"paid": false, "status": ps.Data.Status})
		return
	}

	balance, err := h.creditWallet(c.Request.Context(), userID, ps.Data.Amount, "topup", reference)
	if err != nil {
		h.log.Error("credit wallet after top-up failed", zap.Error(err), zap.String("reference", reference))
		c.JSON(http.StatusInternalServerError, gin.H{"error": "could not credit wallet"})
		return
	}

	c.JSON(http.StatusOK, gin.H{"paid": true, "balance": balance, "credited": ps.Data.Amount})
}

// creditWallet adds amount (minor units) to the user's wallet and records a
// ledger row. For a top-up the unique index on (reference) WHERE kind='topup'
// makes a repeated reference a no-op, so double-verify can't double-credit.
// Returns the balance after the operation (or the current balance if the credit
// was a deduped no-op).
func (h *WalletHandler) creditWallet(ctx context.Context, userID string, amount int64, kind, reference string) (int64, error) {
	tx, err := h.db.Begin(ctx)
	if err != nil {
		return 0, err
	}
	defer tx.Rollback(ctx)

	if _, _, err := ensureWallet(ctx, tx, userID); err != nil {
		return 0, err
	}

	// Record the ledger row first; for a top-up a duplicate reference is ignored.
	var inserted bool
	if kind == "topup" {
		var dummy string
		err = tx.QueryRow(ctx, `
			INSERT INTO business.wallet_ledger (user_id, amount, kind, reference)
			VALUES ($1, $2, 'topup', $3)
			ON CONFLICT DO NOTHING
			RETURNING 'x'`, userID, amount, reference).Scan(&dummy)
		if err == pgx.ErrNoRows {
			// Already credited for this reference — return current balance unchanged.
			var bal int64
			if e := tx.QueryRow(ctx, `SELECT balance FROM business.wallets WHERE user_id = $1`, userID).Scan(&bal); e != nil {
				return 0, e
			}
			_ = tx.Commit(ctx)
			return bal, nil
		}
		if err != nil {
			return 0, err
		}
		inserted = true
	} else {
		if _, err = tx.Exec(ctx, `
			INSERT INTO business.wallet_ledger (user_id, amount, kind, reference)
			VALUES ($1, $2, $3, $4)`, userID, amount, kind, reference); err != nil {
			return 0, err
		}
		inserted = true
	}

	var balance int64
	if inserted {
		if err = tx.QueryRow(ctx, `
			UPDATE business.wallets SET balance = balance + $2, updated_at = NOW()
			WHERE user_id = $1 RETURNING balance`, userID, amount).Scan(&balance); err != nil {
			return 0, err
		}
	}
	if err = tx.Commit(ctx); err != nil {
		return 0, err
	}
	return balance, nil
}

// CreateRedPacket debits the sender's wallet and opens a packet for a conversation.
func (h *WalletHandler) CreateRedPacket(c *gin.Context) {
	if !h.ready(c) {
		return
	}
	userID := getUserID(c)
	if userID == "" {
		return
	}

	var req struct {
		ConversationID string `json:"conversation_id" binding:"required"`
		TotalAmount    int64  `json:"total_amount"` // minor units
		Count          int    `json:"count"`
		Message        string `json:"message"`
	}
	if err := c.ShouldBindJSON(&req); err != nil || req.ConversationID == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "conversation_id is required"})
		return
	}
	if req.Count < 1 || req.Count > 200 {
		c.JSON(http.StatusBadRequest, gin.H{"error": "count must be between 1 and 200"})
		return
	}
	// Each recipient must be able to receive at least 1 minor unit.
	if req.TotalAmount < int64(req.Count) {
		c.JSON(http.StatusBadRequest, gin.H{"error": "amount is too small for that many packets"})
		return
	}
	if req.Message == "" {
		req.Message = "Best wishes!"
	}

	ctx := c.Request.Context()
	tx, err := h.db.Begin(ctx)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "wallet unavailable"})
		return
	}
	defer tx.Rollback(ctx)

	// Lock the wallet row and check funds.
	var balance int64
	if _, _, err := ensureWallet(ctx, tx, userID); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "wallet unavailable"})
		return
	}
	if err := tx.QueryRow(ctx, `SELECT balance FROM business.wallets WHERE user_id = $1 FOR UPDATE`, userID).Scan(&balance); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "wallet unavailable"})
		return
	}
	if balance < req.TotalAmount {
		c.JSON(http.StatusBadRequest, gin.H{"error": "insufficient wallet balance"})
		return
	}

	var packetID string
	if err := tx.QueryRow(ctx, `
		INSERT INTO business.red_packets
			(conversation_id, sender_id, total_amount, remaining_amount, total_count, remaining_count, message)
		VALUES ($1, $2, $3, $3, $4, $4, $5)
		RETURNING id`,
		req.ConversationID, userID, req.TotalAmount, req.Count, req.Message).Scan(&packetID); err != nil {
		h.log.Error("insert red packet failed", zap.Error(err))
		c.JSON(http.StatusInternalServerError, gin.H{"error": "could not create red packet"})
		return
	}

	if _, err := tx.Exec(ctx, `
		UPDATE business.wallets SET balance = balance - $2, updated_at = NOW() WHERE user_id = $1`,
		userID, req.TotalAmount); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "could not create red packet"})
		return
	}
	if _, err := tx.Exec(ctx, `
		INSERT INTO business.wallet_ledger (user_id, amount, kind, reference)
		VALUES ($1, $2, 'redpacket_send', $3)`, userID, -req.TotalAmount, packetID); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "could not create red packet"})
		return
	}

	if err := tx.Commit(ctx); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "could not create red packet"})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"id":           packetID,
		"total_amount": req.TotalAmount,
		"count":        req.Count,
		"message":      req.Message,
	})
}

// ClaimRedPacket credits the claimer a random share of the packet. Each user may
// claim a given packet only once; the sender's own packet is claimable too
// (WeChat-style). The packet row is locked for the duration so splits never race.
func (h *WalletHandler) ClaimRedPacket(c *gin.Context) {
	if !h.ready(c) {
		return
	}
	userID := getUserID(c)
	if userID == "" {
		return
	}
	packetID := c.Param("id")
	if packetID == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "packet id is required"})
		return
	}

	ctx := c.Request.Context()
	tx, err := h.db.Begin(ctx)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "wallet unavailable"})
		return
	}
	defer tx.Rollback(ctx)

	var remainingAmount, totalAmount int64
	var remainingCount int
	if err := tx.QueryRow(ctx, `
		SELECT remaining_amount, remaining_count, total_amount
		FROM business.red_packets WHERE id = $1 FOR UPDATE`, packetID).
		Scan(&remainingAmount, &remainingCount, &totalAmount); err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": "red packet not found"})
		return
	}

	// Already claimed by this user? Return their existing amount (idempotent).
	var existing int64
	if err := tx.QueryRow(ctx, `
		SELECT amount FROM business.red_packet_claims WHERE packet_id = $1 AND user_id = $2`,
		packetID, userID).Scan(&existing); err == nil {
		_ = tx.Commit(ctx)
		c.JSON(http.StatusOK, gin.H{"amount": existing, "already_claimed": true})
		return
	}

	if remainingCount <= 0 || remainingAmount <= 0 {
		c.JSON(http.StatusConflict, gin.H{"error": "this red packet is fully claimed"})
		return
	}

	share := luckyShare(remainingAmount, remainingCount)

	if _, err := tx.Exec(ctx, `
		INSERT INTO business.red_packet_claims (packet_id, user_id, amount) VALUES ($1, $2, $3)`,
		packetID, userID, share); err != nil {
		// Unique-violation means a concurrent claim by the same user won the race.
		c.JSON(http.StatusConflict, gin.H{"error": "already claimed"})
		return
	}
	if _, err := tx.Exec(ctx, `
		UPDATE business.red_packets
		SET remaining_amount = remaining_amount - $2, remaining_count = remaining_count - 1
		WHERE id = $1`, packetID, share); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "could not claim"})
		return
	}

	// Credit the claimer's wallet within the same tx.
	if _, _, err := ensureWallet(ctx, tx, userID); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "could not claim"})
		return
	}
	var balance int64
	if err := tx.QueryRow(ctx, `
		UPDATE business.wallets SET balance = balance + $2, updated_at = NOW()
		WHERE user_id = $1 RETURNING balance`, userID, share).Scan(&balance); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "could not claim"})
		return
	}
	if _, err := tx.Exec(ctx, `
		INSERT INTO business.wallet_ledger (user_id, amount, kind, reference)
		VALUES ($1, $2, 'redpacket_claim', $3)`, userID, share, packetID); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "could not claim"})
		return
	}

	if err := tx.Commit(ctx); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "could not claim"})
		return
	}

	c.JSON(http.StatusOK, gin.H{"amount": share, "balance": balance, "already_claimed": false})
}

// GetRedPacket returns a packet's status and its claims (for the detail sheet).
func (h *WalletHandler) GetRedPacket(c *gin.Context) {
	if !h.ready(c) {
		return
	}
	userID := getUserID(c)
	if userID == "" {
		return
	}
	packetID := c.Param("id")
	ctx := c.Request.Context()

	var senderID, message string
	var totalAmount, remainingAmount int64
	var totalCount, remainingCount int
	var createdAt time.Time
	if err := h.db.QueryRow(ctx, `
		SELECT sender_id, total_amount, remaining_amount, total_count, remaining_count, message, created_at
		FROM business.red_packets WHERE id = $1`, packetID).
		Scan(&senderID, &totalAmount, &remainingAmount, &totalCount, &remainingCount, &message, &createdAt); err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": "red packet not found"})
		return
	}

	claims := make([]gin.H, 0, totalCount)
	var mine int64 = -1
	rows, err := h.db.Query(ctx, `
		SELECT user_id, amount, claimed_at FROM business.red_packet_claims
		WHERE packet_id = $1 ORDER BY claimed_at ASC`, packetID)
	if err == nil {
		defer rows.Close()
		for rows.Next() {
			var uid string
			var amount int64
			var at time.Time
			if rows.Scan(&uid, &amount, &at) == nil {
				claims = append(claims, gin.H{"user_id": uid, "amount": amount, "claimed_at": at.Format(time.RFC3339)})
				if uid == userID {
					mine = amount
				}
			}
		}
	}

	c.JSON(http.StatusOK, gin.H{
		"id":               packetID,
		"sender_id":        senderID,
		"total_amount":     totalAmount,
		"remaining_amount": remainingAmount,
		"total_count":      totalCount,
		"remaining_count":  remainingCount,
		"message":          message,
		"created_at":       createdAt.Format(time.RFC3339),
		"claims":           claims,
		"claimed_by_me":    mine >= 0,
		"my_amount":        mine,
	})
}

// luckyShare splits a red packet "WeChat style": each claim gets a random amount,
// the last claim gets whatever remains, and every claim is at least 1 minor unit.
// For a non-final claim the draw is uniform in [1, 2*average], which keeps the
// expected share fair while making individual amounts feel random.
func luckyShare(remainingAmount int64, remainingCount int) int64 {
	if remainingCount <= 1 {
		return remainingAmount
	}
	// Reserve 1 unit for each of the other claimers.
	maxForThis := remainingAmount - int64(remainingCount-1)
	if maxForThis < 1 {
		return 1
	}
	// Upper bound at twice the average, capped by maxForThis.
	upper := (remainingAmount / int64(remainingCount)) * 2
	if upper < 1 {
		upper = 1
	}
	if upper > maxForThis {
		upper = maxForThis
	}
	// Uniform draw in [1, upper].
	n, err := rand.Int(rand.Reader, big.NewInt(upper))
	if err != nil {
		return upper // fall back to the average-ish cap
	}
	return n.Int64() + 1
}

func randHex(n int) string {
	b := make([]byte, n)
	if _, err := rand.Read(b); err != nil {
		return hex.EncodeToString([]byte(time.Now().String()))
	}
	return hex.EncodeToString(b)
}

// ── Flutterwave (second money-in provider) ──────────────────────────────────
//
// Same safety model as the Paystack path: the hosted checkout returns the user
// to the callback URL, the client then calls verify, and we credit ONLY the
// amount Flutterwave reports as paid. The unique ledger index on the reference
// (kind='topup') makes a repeated verify a no-op. Flutterwave amounts are in
// MAJOR units (naira), unlike Paystack's subunit (kobo) — converted here.

const flutterwaveBaseURL = "https://api.flutterwave.com/v3"

func flutterwaveSecret() string { return os.Getenv("FLUTTERWAVE_SECRET_KEY") }

// initFlutterwaveTopUp starts a Flutterwave hosted-payment checkout and returns
// the same shape as the Paystack path (authorization_url + reference), so the
// Android checkout screen is provider-agnostic.
func (h *WalletHandler) initFlutterwaveTopUp(c *gin.Context, userID string, amountMinor int64, email, currency, callbackURL string) {
	if flutterwaveSecret() == "" {
		c.JSON(http.StatusServiceUnavailable, gin.H{"error": "flutterwave is not configured"})
		return
	}
	reference := "walletflw_" + randHex(16)
	body := map[string]any{
		"tx_ref":         reference,
		"amount":         float64(amountMinor) / 100.0, // Flutterwave uses major units
		"currency":       currency,
		"redirect_url":   callbackURL,
		"customer":       map[string]any{"email": email},
		"meta":           map[string]any{"purpose": "wallet_topup", "user_id": userID},
		"customizations": map[string]any{"title": "GoChat Wallet top-up"},
	}
	payload, _ := json.Marshal(body)
	httpReq, _ := http.NewRequestWithContext(c.Request.Context(), http.MethodPost,
		flutterwaveBaseURL+"/payments", bytes.NewReader(payload))
	httpReq.Header.Set("Authorization", "Bearer "+flutterwaveSecret())
	httpReq.Header.Set("Content-Type", "application/json")

	resp, err := paystackHTTP.Do(httpReq)
	if err != nil {
		c.JSON(http.StatusBadGateway, gin.H{"error": "payment provider unreachable"})
		return
	}
	defer resp.Body.Close()
	respBody, _ := io.ReadAll(resp.Body)

	var fw struct {
		Status string `json:"status"`
		Data   struct {
			Link string `json:"link"`
		} `json:"data"`
	}
	if err := json.Unmarshal(respBody, &fw); err != nil || fw.Status != "success" || fw.Data.Link == "" {
		h.log.Warn("flutterwave top-up initialize rejected", zap.String("body", string(respBody)))
		c.JSON(http.StatusBadGateway, gin.H{"error": "could not start top-up"})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"authorization_url": fw.Data.Link,
		"reference":         reference,
		"amount":            amountMinor,
		"currency":          currency,
		"provider":          "flutterwave",
	})
}

// verifyFlutterwaveTopUp confirms a Flutterwave top-up and credits the wallet
// with the verified amount. Idempotent via the ledger reference.
func (h *WalletHandler) verifyFlutterwaveTopUp(c *gin.Context, userID, reference string) {
	if flutterwaveSecret() == "" {
		c.JSON(http.StatusServiceUnavailable, gin.H{"error": "flutterwave is not configured"})
		return
	}
	httpReq, _ := http.NewRequestWithContext(c.Request.Context(), http.MethodGet,
		flutterwaveBaseURL+"/transactions/verify_by_reference?tx_ref="+reference, nil)
	httpReq.Header.Set("Authorization", "Bearer "+flutterwaveSecret())

	resp, err := paystackHTTP.Do(httpReq)
	if err != nil {
		c.JSON(http.StatusBadGateway, gin.H{"error": "payment provider unreachable"})
		return
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(resp.Body)

	var fw struct {
		Status string `json:"status"`
		Data   struct {
			Status   string  `json:"status"`
			Amount   float64 `json:"amount"` // major units, as actually charged
			Currency string  `json:"currency"`
		} `json:"data"`
	}
	if err := json.Unmarshal(body, &fw); err != nil || fw.Status != "success" {
		c.JSON(http.StatusBadGateway, gin.H{"error": "could not verify top-up"})
		return
	}
	if fw.Data.Status != "successful" || fw.Data.Amount <= 0 {
		c.JSON(http.StatusOK, gin.H{"paid": false, "status": fw.Data.Status})
		return
	}

	amountMinor := int64(math.Round(fw.Data.Amount * 100))
	balance, err := h.creditWallet(c.Request.Context(), userID, amountMinor, "topup", reference)
	if err != nil {
		h.log.Error("credit wallet after flutterwave top-up failed", zap.Error(err), zap.String("reference", reference))
		c.JSON(http.StatusInternalServerError, gin.H{"error": "could not credit wallet"})
		return
	}

	c.JSON(http.StatusOK, gin.H{"paid": true, "balance": balance, "credited": amountMinor})
}
