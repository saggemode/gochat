package handlers

import (
	"net/http"
	"strconv"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/jackc/pgx/v5/pgxpool"
	"go.uber.org/zap"
)

// AdminHandler serves the platform-admin console endpoints. It reads directly
// from the shared Postgres database (schema-per-service: core.*, business.*),
// which is the pragmatic way to aggregate cross-service data for admins
// without adding admin RPCs to every microservice.
//
// All endpoints are read-only in this first increment. Mutations (ban/suspend,
// resolve dispute) require either a new column (core.users has no status yet)
// or an existing service RPC, and are added in a follow-up.
//
// The pool may be nil (no DB DSN configured); every endpoint degrades to a 503
// rather than panicking.
type AdminHandler struct {
	db  *pgxpool.Pool
	log *zap.Logger
}

func NewAdminHandler(db *pgxpool.Pool, log *zap.Logger) *AdminHandler {
	return &AdminHandler{db: db, log: log}
}

// ready returns false (after writing a 503) when no database is configured.
func (h *AdminHandler) ready(c *gin.Context) bool {
	if h.db == nil {
		c.JSON(http.StatusServiceUnavailable, gin.H{"error": "admin database not configured"})
		return false
	}
	return true
}

func clampInt(v, def, min, max int) int {
	if v < min {
		return def
	}
	if v > max {
		return max
	}
	return v
}

// GET /api/v1/admin/overview — platform KPIs for the dashboard home.
func (h *AdminHandler) Overview(c *gin.Context) {
	if !h.ready(c) {
		return
	}
	ctx := c.Request.Context()

	var (
		totalUsers, onlineNow, new7d, new30d int64
		totalOrders, paidOrders, openDisputes int64
		gmvPaid                               float64
	)

	// Users.
	_ = h.db.QueryRow(ctx, `SELECT count(*)::bigint FROM core.users`).Scan(&totalUsers)
	_ = h.db.QueryRow(ctx, `SELECT count(*)::bigint FROM core.users WHERE is_online = true`).Scan(&onlineNow)
	_ = h.db.QueryRow(ctx, `SELECT count(*)::bigint FROM core.users WHERE created_at >= now() - interval '7 days'`).Scan(&new7d)
	_ = h.db.QueryRow(ctx, `SELECT count(*)::bigint FROM core.users WHERE created_at >= now() - interval '30 days'`).Scan(&new30d)

	// Orders & GMV.
	_ = h.db.QueryRow(ctx, `SELECT count(*)::bigint FROM business.orders`).Scan(&totalOrders)
	_ = h.db.QueryRow(ctx,
		`SELECT count(*)::bigint, coalesce(sum(grand_total),0)::float8
		 FROM business.orders WHERE payment_status = 'paid'`).Scan(&paidOrders, &gmvPaid)

	// Disputes.
	_ = h.db.QueryRow(ctx, `SELECT count(*)::bigint FROM business.order_disputes WHERE status = 'open'`).Scan(&openDisputes)

	// 14-day signup series.
	type point struct {
		Date  string `json:"date"`
		Value int64  `json:"value"`
	}
	signups := []point{}
	rows, err := h.db.Query(ctx,
		`SELECT date_trunc('day', created_at)::date AS d, count(*)::bigint
		 FROM core.users
		 WHERE created_at >= now() - interval '14 days'
		 GROUP BY d ORDER BY d`)
	if err == nil {
		defer rows.Close()
		for rows.Next() {
			var d time.Time
			var n int64
			if err := rows.Scan(&d, &n); err == nil {
				signups = append(signups, point{Date: d.Format("2006-01-02"), Value: n})
			}
		}
	}

	c.JSON(http.StatusOK, gin.H{
		"users": gin.H{
			"total":      totalUsers,
			"online_now": onlineNow,
			"new_7d":     new7d,
			"new_30d":    new30d,
		},
		"orders": gin.H{
			"total":    totalOrders,
			"paid":     paidOrders,
			"gmv_paid": gmvPaid,
		},
		"disputes": gin.H{
			"open": openDisputes,
		},
		"signups_14d": signups,
	})
}

// GET /api/v1/admin/users?search=&limit=&offset= — paginated user directory.
func (h *AdminHandler) ListUsers(c *gin.Context) {
	if !h.ready(c) {
		return
	}
	ctx := c.Request.Context()

	search := c.Query("search")
	limit := clampInt(atoiDefault(c.Query("limit"), 50), 50, 1, 200)
	offset := clampInt(atoiDefault(c.Query("offset"), 0), 0, 0, 1_000_000)

	var total int64
	_ = h.db.QueryRow(ctx,
		`SELECT count(*)::bigint FROM core.users
		 WHERE ($1 = '' OR display_name ILIKE '%'||$1||'%'
		        OR email ILIKE '%'||$1||'%'
		        OR coalesce(phone,'') ILIKE '%'||$1||'%')`,
		search).Scan(&total)

	rows, err := h.db.Query(ctx,
		`SELECT id, email, display_name, coalesce(avatar_url,''), coalesce(phone,''),
		        phone_verified, is_online, last_seen, coalesce(country_code,''), created_at
		 FROM core.users
		 WHERE ($1 = '' OR display_name ILIKE '%'||$1||'%'
		        OR email ILIKE '%'||$1||'%'
		        OR coalesce(phone,'') ILIKE '%'||$1||'%')
		 ORDER BY created_at DESC
		 LIMIT $2 OFFSET $3`,
		search, limit, offset)
	if err != nil {
		h.log.Error("admin list users failed", zap.Error(err))
		c.JSON(http.StatusInternalServerError, gin.H{"error": "failed to load users"})
		return
	}
	defer rows.Close()

	type adminUser struct {
		ID            string    `json:"id"`
		Email         string    `json:"email"`
		Name          string    `json:"name"`
		AvatarURL     string    `json:"avatar_url"`
		Phone         string    `json:"phone"`
		PhoneVerified bool      `json:"phone_verified"`
		Online        bool      `json:"online"`
		LastActiveAt  time.Time `json:"last_active_at"`
		Country       string    `json:"country"`
		JoinedAt      time.Time `json:"joined_at"`
	}

	users := []adminUser{}
	for rows.Next() {
		var u adminUser
		if err := rows.Scan(&u.ID, &u.Email, &u.Name, &u.AvatarURL, &u.Phone,
			&u.PhoneVerified, &u.Online, &u.LastActiveAt, &u.Country, &u.JoinedAt); err != nil {
			h.log.Warn("admin user row scan failed", zap.Error(err))
			continue
		}
		users = append(users, u)
	}

	c.JSON(http.StatusOK, gin.H{
		"users":  users,
		"total":  total,
		"limit":  limit,
		"offset": offset,
	})
}

// GET /api/v1/admin/disputes?status=&limit=&offset= — cross-store disputes.
func (h *AdminHandler) ListDisputes(c *gin.Context) {
	if !h.ready(c) {
		return
	}
	ctx := c.Request.Context()

	status := c.Query("status")
	limit := clampInt(atoiDefault(c.Query("limit"), 50), 50, 1, 200)
	offset := clampInt(atoiDefault(c.Query("offset"), 0), 0, 0, 1_000_000)

	rows, err := h.db.Query(ctx,
		`SELECT d.id, d.order_id, coalesce(o.order_number,''), d.reason, d.status,
		        coalesce(bp.business_name, d.business_id::text) AS store,
		        coalesce(bu.display_name, d.buyer_id::text) AS buyer,
		        coalesce(o.grand_total,0)::float8 AS amount,
		        d.created_at, d.resolved_at
		 FROM business.order_disputes d
		 LEFT JOIN business.orders o ON o.id = d.order_id
		 LEFT JOIN business.business_profiles bp ON bp.user_id = d.business_id
		 LEFT JOIN core.users bu ON bu.id = d.buyer_id
		 WHERE ($1 = '' OR d.status = $1)
		 ORDER BY d.created_at DESC
		 LIMIT $2 OFFSET $3`,
		status, limit, offset)
	if err != nil {
		h.log.Error("admin list disputes failed", zap.Error(err))
		c.JSON(http.StatusInternalServerError, gin.H{"error": "failed to load disputes"})
		return
	}
	defer rows.Close()

	type adminDispute struct {
		ID        string     `json:"id"`
		OrderID   string     `json:"order_id"`
		OrderRef  string     `json:"order_ref"`
		Reason    string     `json:"reason"`
		Status    string     `json:"status"`
		Store     string     `json:"store"`
		Buyer     string     `json:"buyer"`
		Amount    float64    `json:"amount"`
		OpenedAt  time.Time  `json:"opened_at"`
		ResolvedAt *time.Time `json:"resolved_at,omitempty"`
	}

	disputes := []adminDispute{}
	for rows.Next() {
		var d adminDispute
		if err := rows.Scan(&d.ID, &d.OrderID, &d.OrderRef, &d.Reason, &d.Status,
			&d.Store, &d.Buyer, &d.Amount, &d.OpenedAt, &d.ResolvedAt); err != nil {
			h.log.Warn("admin dispute row scan failed", zap.Error(err))
			continue
		}
		disputes = append(disputes, d)
	}

	c.JSON(http.StatusOK, gin.H{"disputes": disputes})
}

func atoiDefault(s string, def int) int {
	if s == "" {
		return def
	}
	if n, err := strconv.Atoi(s); err == nil {
		return n
	}
	return def
}
