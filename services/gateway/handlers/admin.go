package handlers

import (
	"context"
	"crypto/rand"
	"net/http"
	"sort"
	"strconv"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/jackc/pgx/v5/pgxpool"
	"go.uber.org/zap"
	"golang.org/x/crypto/bcrypt"
	"google.golang.org/grpc"
	"google.golang.org/grpc/codes"
	grpcstatus "google.golang.org/grpc/status"

	authpb "gochat/gen/auth"
	authzpb "gochat/gen/authz"
	businesspb "gochat/gen/business"
	"gochat/pkg/notifier"
)

// generateTempPassword returns a readable, reasonably strong temporary password
// (no ambiguous chars). ~72 bits of entropy over 14 chars.
func generateTempPassword() string {
	const charset = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
	b := make([]byte, 14)
	if _, err := rand.Read(b); err != nil {
		return "Gochat-" + strconv.FormatInt(time.Now().UnixNano(), 36)
	}
	for i := range b {
		b[i] = charset[int(b[i])%len(charset)]
	}
	return string(b[:4]) + "-" + string(b[4:9]) + "-" + string(b[9:])
}

// assignableRoles are the roles the super admin can grant to other accounts.
var assignableRoles = []struct {
	Name        string `json:"name"`
	Description string `json:"description"`
}{
	{"super_admin", "Full platform control"},
	{"admin", "All admin permissions"},
	{"moderator", "Ban users, resolve disputes, action reports"},
	{"support", "View console, action reports"},
	{"finance", "View revenue, orders, payouts"},
}

func isAssignableRole(r string) bool {
	for _, ar := range assignableRoles {
		if ar.Name == r {
			return true
		}
	}
	return false
}

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
	db       *pgxpool.Pool
	auth     authpb.AuthServiceClient
	business businesspb.BusinessServiceClient
	authz    authzpb.AuthzServiceClient
	notifier notifier.Notifier
	services map[string]*grpc.ClientConn // for real per-service connectivity health
	log      *zap.Logger
}

func NewAdminHandler(db *pgxpool.Pool, auth authpb.AuthServiceClient, business businesspb.BusinessServiceClient, authz authzpb.AuthzServiceClient, mailer notifier.Notifier, services map[string]*grpc.ClientConn, log *zap.Logger) *AdminHandler {
	return &AdminHandler{db: db, auth: auth, business: business, authz: authz, notifier: mailer, services: services, log: log}
}

// adminCapabilities is the set of fine-grained permissions the UI gates on.
var adminCapabilities = []string{
	"admin:users:write",
	"admin:disputes:resolve",
	"admin:reports:action",
	"admin:finance:read",
	"admin:roles:write",
}

// audit records an admin mutation to authz.admin_audit_log (best-effort; a
// failure is logged but never blocks the action).
func (h *AdminHandler) audit(c *gin.Context, action, targetType, targetID, detail string) {
	if h.db == nil {
		return
	}
	_, err := h.db.Exec(c.Request.Context(),
		`INSERT INTO authz.admin_audit_log (actor_id, actor_email, action, target_type, target_id, detail)
		 VALUES ($1, $2, $3, $4, $5, $6)`,
		c.GetString("user_id"), c.GetString("email"), action, targetType, targetID, detail)
	if err != nil {
		h.log.Warn("admin audit write failed", zap.String("action", action), zap.Error(err))
	}
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
		totalUsers, onlineNow, new7d, new30d  int64
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
		`SELECT u.id, u.email, u.display_name, coalesce(u.avatar_url,''), coalesce(u.phone,''),
		        u.phone_verified, u.is_online, u.last_seen, coalesce(u.country_code,''), u.created_at,
		        coalesce(u.account_status,'active'),
		        (bp.user_id IS NOT NULL) AS is_seller,
		        (SELECT count(*) FROM business.orders o WHERE o.buyer_id = u.id)::int AS orders,
		        coalesce((SELECT array_agg(ur.role_name) FROM authz.user_roles ur WHERE ur.user_id = u.id), '{}') AS roles
		 FROM core.users u
		 LEFT JOIN business.business_profiles bp ON bp.user_id = u.id
		 WHERE ($1 = '' OR u.display_name ILIKE '%'||$1||'%'
		        OR u.email ILIKE '%'||$1||'%'
		        OR coalesce(u.phone,'') ILIKE '%'||$1||'%')
		 ORDER BY u.created_at DESC
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
		Status        string    `json:"status"`
		IsSeller      bool      `json:"is_seller"`
		Orders        int       `json:"orders"`
		Roles         []string  `json:"roles"`
	}

	users := []adminUser{}
	for rows.Next() {
		var u adminUser
		if err := rows.Scan(&u.ID, &u.Email, &u.Name, &u.AvatarURL, &u.Phone,
			&u.PhoneVerified, &u.Online, &u.LastActiveAt, &u.Country, &u.JoinedAt, &u.Status,
			&u.IsSeller, &u.Orders, &u.Roles); err != nil {
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
		ID         string     `json:"id"`
		OrderID    string     `json:"order_id"`
		OrderRef   string     `json:"order_ref"`
		Reason     string     `json:"reason"`
		Status     string     `json:"status"`
		Store      string     `json:"store"`
		Buyer      string     `json:"buyer"`
		Amount     float64    `json:"amount"`
		OpenedAt   time.Time  `json:"opened_at"`
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

// GET /api/v1/admin/orders?status=&limit=&offset= — cross-store order list.
func (h *AdminHandler) ListOrders(c *gin.Context) {
	if !h.ready(c) {
		return
	}
	ctx := c.Request.Context()

	status := c.Query("status")
	limit := clampInt(atoiDefault(c.Query("limit"), 50), 50, 1, 200)
	offset := clampInt(atoiDefault(c.Query("offset"), 0), 0, 0, 1_000_000)

	rows, err := h.db.Query(ctx,
		`SELECT o.id, o.order_number,
		        coalesce(bu.display_name, o.shipping_name, o.buyer_id::text) AS buyer,
		        coalesce(bp.business_name, o.business_id::text) AS store,
		        o.grand_total::float8, o.status, coalesce(o.payment_status,''),
		        (SELECT count(*) FROM business.order_items oi WHERE oi.order_id = o.id)::int AS items,
		        o.created_at
		 FROM business.orders o
		 LEFT JOIN business.business_profiles bp ON bp.user_id = o.business_id
		 LEFT JOIN core.users bu ON bu.id = o.buyer_id
		 WHERE ($1 = '' OR o.status = $1)
		 ORDER BY o.created_at DESC
		 LIMIT $2 OFFSET $3`,
		status, limit, offset)
	if err != nil {
		h.log.Error("admin list orders failed", zap.Error(err))
		c.JSON(http.StatusInternalServerError, gin.H{"error": "failed to load orders"})
		return
	}
	defer rows.Close()

	type adminOrder struct {
		ID            string    `json:"id"`
		Reference     string    `json:"reference"`
		Buyer         string    `json:"buyer"`
		Store         string    `json:"store"`
		Total         float64   `json:"total"`
		Status        string    `json:"status"`
		PaymentStatus string    `json:"payment_status"`
		Items         int       `json:"items"`
		CreatedAt     time.Time `json:"created_at"`
	}

	orders := []adminOrder{}
	for rows.Next() {
		var o adminOrder
		if err := rows.Scan(&o.ID, &o.Reference, &o.Buyer, &o.Store, &o.Total,
			&o.Status, &o.PaymentStatus, &o.Items, &o.CreatedAt); err != nil {
			h.log.Warn("admin order row scan failed", zap.Error(err))
			continue
		}
		orders = append(orders, o)
	}

	c.JSON(http.StatusOK, gin.H{"orders": orders})
}

// POST /api/v1/admin/users/:id/status — set a user's moderation status.
// Body: {"status": "active" | "suspended" | "banned"}.
//
// NOTE: this records the status on core.users. It does NOT yet revoke an active
// session or reject a banned user's token — enforcement (ValidateToken / Login
// checking account_status) is a follow-up in the auth service.
func (h *AdminHandler) UpdateUserStatus(c *gin.Context) {
	if !h.ready(c) {
		return
	}
	id := c.Param("id")
	if id == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "user id required"})
		return
	}

	var body struct {
		Status string `json:"status"`
	}
	if err := c.ShouldBindJSON(&body); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid body"})
		return
	}
	switch body.Status {
	case "active", "suspended", "banned":
	default:
		c.JSON(http.StatusBadRequest, gin.H{"error": "status must be active, suspended or banned"})
		return
	}

	tag, err := h.db.Exec(c.Request.Context(),
		`UPDATE core.users SET account_status = $1, updated_at = now() WHERE id = $2`,
		body.Status, id)
	if err != nil {
		h.log.Error("admin update user status failed", zap.String("user_id", id), zap.Error(err))
		c.JSON(http.StatusInternalServerError, gin.H{"error": "failed to update status"})
		return
	}
	if tag.RowsAffected() == 0 {
		c.JSON(http.StatusNotFound, gin.H{"error": "user not found"})
		return
	}

	h.audit(c, "user:status", "user", id, body.Status)
	h.log.Info("admin changed user status",
		zap.String("user_id", id),
		zap.String("status", body.Status),
		zap.String("by", c.GetString("email")))

	c.JSON(http.StatusOK, gin.H{"id": id, "status": body.Status})
}

// POST /api/v1/admin/disputes/:id/resolve — resolve or reject any dispute.
// Body: {"action": "resolved" | "rejected", "note": "..."}.
//
// Reuses the business service's ResolveDispute RPC (which also notifies the
// buyer). That RPC is scoped to a store, so the admin first looks up the
// dispute's business_id and passes it through — letting an admin act on any
// store's dispute without bypassing the service's logic.
func (h *AdminHandler) ResolveDispute(c *gin.Context) {
	if !h.ready(c) {
		return
	}
	if h.business == nil {
		c.JSON(http.StatusServiceUnavailable, gin.H{"error": "business service unavailable"})
		return
	}
	disputeID := c.Param("id")
	if disputeID == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "dispute id required"})
		return
	}

	var body struct {
		Action string `json:"action"`
		Note   string `json:"note"`
	}
	if err := c.ShouldBindJSON(&body); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid body"})
		return
	}
	if body.Action != "resolved" && body.Action != "rejected" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "action must be resolved or rejected"})
		return
	}

	// Resolve the owning store so the RPC's ownership check passes.
	var businessID string
	if err := h.db.QueryRow(c.Request.Context(),
		`SELECT business_id::text FROM business.order_disputes WHERE id = $1`,
		disputeID).Scan(&businessID); err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": "dispute not found"})
		return
	}

	resp, err := h.business.ResolveDispute(c.Request.Context(), &businesspb.ResolveDisputeRequest{
		DisputeId:  disputeID,
		BusinessId: businessID,
		Action:     body.Action,
		Note:       body.Note,
	})
	if err != nil {
		h.log.Error("admin resolve dispute failed", zap.String("dispute_id", disputeID), zap.Error(err))
		c.JSON(http.StatusInternalServerError, gin.H{"error": "failed to resolve dispute"})
		return
	}

	h.audit(c, "dispute:resolve", "dispute", disputeID, body.Action)
	h.log.Info("admin resolved dispute",
		zap.String("dispute_id", disputeID),
		zap.String("action", body.Action),
		zap.String("by", c.GetString("email")))

	c.JSON(http.StatusOK, resp.GetDispute())
}

type seriesPoint struct {
	Date  string  `json:"date"`
	Value float64 `json:"value"`
}

// GET /api/v1/admin/metrics/growth — daily new users + cumulative total (30d).
func (h *AdminHandler) GrowthSeries(c *gin.Context) {
	if !h.ready(c) {
		return
	}
	rows, err := h.db.Query(c.Request.Context(),
		`SELECT d::date,
		        coalesce(c.n, 0)::float8 AS new_users,
		        (SELECT count(*) FROM core.users u WHERE u.created_at < (d::date + interval '1 day'))::float8 AS cumulative
		 FROM generate_series((now() - interval '29 days')::date, now()::date, '1 day') d
		 LEFT JOIN (
		     SELECT created_at::date dd, count(*) n
		     FROM core.users WHERE created_at >= now() - interval '30 days' GROUP BY dd
		 ) c ON c.dd = d::date
		 ORDER BY d`)
	if err != nil {
		h.log.Error("admin growth series failed", zap.Error(err))
		c.JSON(http.StatusInternalServerError, gin.H{"error": "failed to load growth"})
		return
	}
	defer rows.Close()

	newUsers := []seriesPoint{}
	cumulative := []seriesPoint{}
	for rows.Next() {
		var d time.Time
		var n, cum float64
		if err := rows.Scan(&d, &n, &cum); err != nil {
			continue
		}
		day := d.Format("2006-01-02")
		newUsers = append(newUsers, seriesPoint{Date: day, Value: n})
		cumulative = append(cumulative, seriesPoint{Date: day, Value: cum})
	}

	c.JSON(http.StatusOK, gin.H{
		"series": []gin.H{
			{"name": "New Users", "points": newUsers},
			{"name": "Cumulative Users", "points": cumulative},
		},
	})
}

// GET /api/v1/admin/metrics/revenue — daily paid GMV (30d).
func (h *AdminHandler) RevenueSeries(c *gin.Context) {
	if !h.ready(c) {
		return
	}
	rows, err := h.db.Query(c.Request.Context(),
		`SELECT d::date, coalesce(sum(o.grand_total), 0)::float8
		 FROM generate_series((now() - interval '29 days')::date, now()::date, '1 day') d
		 LEFT JOIN business.orders o
		   ON o.created_at::date = d::date AND o.payment_status = 'paid'
		 GROUP BY d ORDER BY d`)
	if err != nil {
		h.log.Error("admin revenue series failed", zap.Error(err))
		c.JSON(http.StatusInternalServerError, gin.H{"error": "failed to load revenue"})
		return
	}
	defer rows.Close()

	points := []seriesPoint{}
	for rows.Next() {
		var d time.Time
		var v float64
		if err := rows.Scan(&d, &v); err != nil {
			continue
		}
		points = append(points, seriesPoint{Date: d.Format("2006-01-02"), Value: v})
	}
	c.JSON(http.StatusOK, gin.H{"points": points})
}

// GET /api/v1/admin/metrics/messages — message counts by type, last 24h,
// grouped into Text / Media / Voice.
func (h *AdminHandler) MessageMix(c *gin.Context) {
	if !h.ready(c) {
		return
	}
	rows, err := h.db.Query(c.Request.Context(),
		`SELECT type::text, count(*)::bigint
		 FROM chat.messages WHERE created_at >= now() - interval '24 hours'
		 GROUP BY type`)
	if err != nil {
		h.log.Error("admin message mix failed", zap.Error(err))
		c.JSON(http.StatusInternalServerError, gin.H{"error": "failed to load message mix"})
		return
	}
	defer rows.Close()

	groups := map[string]int64{"Text": 0, "Media": 0, "Voice": 0}
	for rows.Next() {
		var t string
		var n int64
		if err := rows.Scan(&t, &n); err != nil {
			continue
		}
		switch t {
		case "text":
			groups["Text"] += n
		case "image", "video", "file":
			groups["Media"] += n
		case "voice", "audio":
			groups["Voice"] += n
		}
	}

	mix := []gin.H{
		{"name": "Text", "value": groups["Text"]},
		{"name": "Media", "value": groups["Media"]},
		{"name": "Voice", "value": groups["Voice"]},
	}
	c.JSON(http.StatusOK, gin.H{"mix": mix})
}

// GET /api/v1/admin/activity — recent cross-service events.
func (h *AdminHandler) Activity(c *gin.Context) {
	if !h.ready(c) {
		return
	}
	rows, err := h.db.Query(c.Request.Context(),
		`(SELECT 'signup' kind, display_name title, coalesce(country_code,'') subtitle,
		         created_at at, 0::float8 amount
		  FROM core.users ORDER BY created_at DESC LIMIT 8)
		 UNION ALL
		 (SELECT 'order', coalesce(o.order_number,''), coalesce(bp.business_name, o.business_id::text),
		         o.created_at, o.grand_total::float8
		  FROM business.orders o
		  LEFT JOIN business.business_profiles bp ON bp.user_id = o.business_id
		  ORDER BY o.created_at DESC LIMIT 8)
		 UNION ALL
		 (SELECT 'dispute', d.reason, coalesce(bp.business_name, d.business_id::text),
		         d.created_at, 0::float8
		  FROM business.order_disputes d
		  LEFT JOIN business.business_profiles bp ON bp.user_id = d.business_id
		  ORDER BY d.created_at DESC LIMIT 6)
		 UNION ALL
		 (SELECT 'report', r.reason, r.target_type, r.created_at, 0::float8
		  FROM social.reports r ORDER BY r.created_at DESC LIMIT 6)
		 ORDER BY at DESC LIMIT 15`)
	if err != nil {
		h.log.Error("admin activity failed", zap.Error(err))
		c.JSON(http.StatusInternalServerError, gin.H{"error": "failed to load activity"})
		return
	}
	defer rows.Close()

	type item struct {
		ID       string    `json:"id"`
		Kind     string    `json:"kind"`
		Title    string    `json:"title"`
		Subtitle string    `json:"subtitle"`
		At       time.Time `json:"at"`
		Amount   float64   `json:"amount"`
	}
	items := []item{}
	i := 0
	for rows.Next() {
		var it item
		if err := rows.Scan(&it.Kind, &it.Title, &it.Subtitle, &it.At, &it.Amount); err != nil {
			continue
		}
		it.ID = it.Kind + "-" + strconv.Itoa(i)
		i++
		items = append(items, it)
	}
	c.JSON(http.StatusOK, gin.H{"items": items})
}

// GET /api/v1/admin/reports?status=&limit=&offset= — moderation review queue.
func (h *AdminHandler) ListReports(c *gin.Context) {
	if !h.ready(c) {
		return
	}
	status := c.Query("status")
	limit := clampInt(atoiDefault(c.Query("limit"), 50), 50, 1, 200)
	offset := clampInt(atoiDefault(c.Query("offset"), 0), 0, 0, 1_000_000)

	rows, err := h.db.Query(c.Request.Context(),
		`SELECT r.id, r.target_type, r.target_id, r.reason, r.status, r.created_at,
		        coalesce(rep.display_name, r.reporter_id::text) AS reporter,
		        coalesce(tu.display_name, r.target_id) AS reported_name
		 FROM social.reports r
		 LEFT JOIN core.users rep ON rep.id = r.reporter_id
		 LEFT JOIN core.users tu ON (r.target_type = 'user' AND tu.id::text = r.target_id)
		 WHERE ($1 = '' OR r.status = $1)
		 ORDER BY r.created_at DESC
		 LIMIT $2 OFFSET $3`,
		status, limit, offset)
	if err != nil {
		h.log.Error("admin list reports failed", zap.Error(err))
		c.JSON(http.StatusInternalServerError, gin.H{"error": "failed to load reports"})
		return
	}
	defer rows.Close()

	type report struct {
		ID           string    `json:"id"`
		Target       string    `json:"target"`
		TargetID     string    `json:"target_id"`
		Reason       string    `json:"reason"`
		Status       string    `json:"status"`
		CreatedAt    time.Time `json:"created_at"`
		Reporter     string    `json:"reporter"`
		ReportedName string    `json:"reported_name"`
	}
	reports := []report{}
	for rows.Next() {
		var r report
		if err := rows.Scan(&r.ID, &r.Target, &r.TargetID, &r.Reason, &r.Status,
			&r.CreatedAt, &r.Reporter, &r.ReportedName); err != nil {
			continue
		}
		reports = append(reports, r)
	}
	c.JSON(http.StatusOK, gin.H{"reports": reports})
}

// POST /api/v1/admin/reports/:id/status — set a report's review status.
// Body: {"status": "actioned" | "dismissed" | "reviewed" | "pending"}.
func (h *AdminHandler) UpdateReportStatus(c *gin.Context) {
	if !h.ready(c) {
		return
	}
	id := c.Param("id")
	var body struct {
		Status string `json:"status"`
	}
	if err := c.ShouldBindJSON(&body); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid body"})
		return
	}
	switch body.Status {
	case "actioned", "dismissed", "reviewed", "pending":
	default:
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid status"})
		return
	}

	reviewer := c.GetString("user_id")
	var reviewerArg interface{}
	if reviewer != "" {
		reviewerArg = reviewer
	}
	tag, err := h.db.Exec(c.Request.Context(),
		`UPDATE social.reports SET status = $1, reviewer_id = $2, updated_at = now() WHERE id = $3`,
		body.Status, reviewerArg, id)
	if err != nil {
		h.log.Error("admin update report failed", zap.String("report_id", id), zap.Error(err))
		c.JSON(http.StatusInternalServerError, gin.H{"error": "failed to update report"})
		return
	}
	if tag.RowsAffected() == 0 {
		c.JSON(http.StatusNotFound, gin.H{"error": "report not found"})
		return
	}
	h.audit(c, "report:status", "report", id, body.Status)
	c.JSON(http.StatusOK, gin.H{"id": id, "status": body.Status})
}

// GET /api/v1/admin/health — real health: DB ping + per-service gRPC
// connection state.
func (h *AdminHandler) SystemHealth(c *gin.Context) {
	type svc struct {
		Name   string `json:"name"`
		Status string `json:"status"`
	}
	out := []svc{}

	// Database.
	dbStatus := "down"
	if h.db != nil {
		ctx, cancel := context.WithTimeout(c.Request.Context(), 2*time.Second)
		defer cancel()
		if err := h.db.Ping(ctx); err == nil {
			dbStatus = "healthy"
		}
	}
	out = append(out, svc{Name: "database", Status: dbStatus})

	// gRPC services: map connectivity state to a health label.
	names := make([]string, 0, len(h.services))
	for name := range h.services {
		names = append(names, name)
	}
	sort.Strings(names)
	for _, name := range names {
		conn := h.services[name]
		label := "down"
		if conn != nil {
			switch conn.GetState().String() {
			case "READY", "IDLE":
				label = "healthy"
			case "CONNECTING":
				label = "degraded"
			}
		}
		out = append(out, svc{Name: name, Status: label})
	}

	c.JSON(http.StatusOK, gin.H{"services": out})
}

// GET /api/v1/admin/me — the signed-in admin's identity, roles and the
// fine-grained capabilities the UI should enable. Bootstrap admins (ADMIN_EMAILS)
// report all capabilities true.
func (h *AdminHandler) Me(c *gin.Context) {
	userID := c.GetString("user_id")
	bootstrap := c.GetBool("is_bootstrap_admin")

	roles := []string{}
	caps := map[string]bool{}

	if h.authz != nil && !bootstrap {
		if r, err := h.authz.GetUserRoles(c.Request.Context(), &authzpb.GetUserRolesRequest{UserId: userID}); err == nil {
			roles = r.GetRoles()
		}
		for _, perm := range adminCapabilities {
			resp, err := h.authz.Authorize(c.Request.Context(), &authzpb.AuthorizeRequest{UserId: userID, Action: perm})
			caps[perm] = err == nil && resp.GetAllowed()
		}
	} else {
		// Break-glass bootstrap admin: full capabilities.
		roles = []string{"super_admin"}
		for _, perm := range adminCapabilities {
			caps[perm] = true
		}
	}

	// Whether this account must set a new password before using the console.
	mustChange := false
	if h.db != nil && userID != "" {
		_ = h.db.QueryRow(c.Request.Context(),
			`SELECT coalesce(must_change_password, false) FROM core.users WHERE id = $1`, userID).Scan(&mustChange)
	}

	c.JSON(http.StatusOK, gin.H{
		"user_id":              userID,
		"email":                c.GetString("email"),
		"roles":                roles,
		"permissions":          caps,
		"bootstrap":            bootstrap,
		"must_change_password": mustChange,
	})
}

// GET /api/v1/admin/audit?limit= — recent admin actions.
func (h *AdminHandler) ListAudit(c *gin.Context) {
	if !h.ready(c) {
		return
	}
	limit := clampInt(atoiDefault(c.Query("limit"), 100), 100, 1, 500)

	rows, err := h.db.Query(c.Request.Context(),
		`SELECT id, actor_email, action, target_type, target_id, detail, created_at
		 FROM authz.admin_audit_log ORDER BY created_at DESC LIMIT $1`, limit)
	if err != nil {
		h.log.Error("admin list audit failed", zap.Error(err))
		c.JSON(http.StatusInternalServerError, gin.H{"error": "failed to load audit log"})
		return
	}
	defer rows.Close()

	type entry struct {
		ID         string    `json:"id"`
		ActorEmail string    `json:"actor_email"`
		Action     string    `json:"action"`
		TargetType string    `json:"target_type"`
		TargetID   string    `json:"target_id"`
		Detail     string    `json:"detail"`
		CreatedAt  time.Time `json:"created_at"`
	}
	entries := []entry{}
	for rows.Next() {
		var e entry
		if err := rows.Scan(&e.ID, &e.ActorEmail, &e.Action, &e.TargetType, &e.TargetID, &e.Detail, &e.CreatedAt); err != nil {
			continue
		}
		entries = append(entries, e)
	}
	c.JSON(http.StatusOK, gin.H{"entries": entries})
}

func (h *AdminHandler) superAdminCount(ctx context.Context) (int, error) {
	var n int
	err := h.db.QueryRow(ctx, `SELECT count(*) FROM authz.user_roles WHERE role_name = 'super_admin'`).Scan(&n)
	return n, err
}

// GET /api/v1/admin/setup/status — PUBLIC. Reports whether a first-run setup
// (create the first super admin) is needed.
func (h *AdminHandler) SetupStatus(c *gin.Context) {
	if h.db == nil {
		c.JSON(http.StatusOK, gin.H{"needs_setup": false})
		return
	}
	n, err := h.superAdminCount(c.Request.Context())
	if err != nil {
		h.log.Warn("setup status check failed", zap.Error(err))
		c.JSON(http.StatusOK, gin.H{"needs_setup": false})
		return
	}
	c.JSON(http.StatusOK, gin.H{"needs_setup": n == 0})
}

// POST /api/v1/admin/setup — PUBLIC, one-time. Creates the first super admin.
// Body: {email, password, name}. Refuses once any super admin exists.
func (h *AdminHandler) Setup(c *gin.Context) {
	if !h.ready(c) {
		return
	}
	if h.auth == nil || h.authz == nil {
		c.JSON(http.StatusServiceUnavailable, gin.H{"error": "auth/authz unavailable"})
		return
	}
	n, err := h.superAdminCount(c.Request.Context())
	if err == nil && n > 0 {
		c.JSON(http.StatusConflict, gin.H{"error": "setup already completed"})
		return
	}

	var body struct {
		Email    string `json:"email"`
		Password string `json:"password"`
		Name     string `json:"name"`
	}
	if err := c.ShouldBindJSON(&body); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid body"})
		return
	}
	if body.Email == "" || len(body.Password) < 8 {
		c.JSON(http.StatusBadRequest, gin.H{"error": "email and a password of at least 8 characters are required"})
		return
	}

	userID, access, refresh, usr, err := h.registerOrLookup(c.Request.Context(), body.Email, body.Password, body.Name)
	if err != nil {
		h.log.Error("setup: register failed", zap.Error(err))
		c.JSON(http.StatusInternalServerError, gin.H{"error": "failed to create account"})
		return
	}

	if _, err := h.authz.AssignRole(c.Request.Context(), &authzpb.AssignRoleRequest{UserId: userID, Role: "super_admin"}); err != nil {
		h.log.Error("setup: assign super_admin failed", zap.String("user_id", userID), zap.Error(err))
		c.JSON(http.StatusInternalServerError, gin.H{"error": "account created but role assignment failed"})
		return
	}
	h.audit(c, "setup:super_admin", "user", userID, body.Email)
	h.log.Info("admin setup: created super admin", zap.String("email", body.Email))

	c.JSON(http.StatusOK, gin.H{"access_token": access, "refresh_token": refresh, "user": usr})
}

// registerOrLookup creates the account, or (if it already exists) looks it up
// and logs in to obtain tokens. Returns userID, tokens and the user object.
func (h *AdminHandler) registerOrLookup(ctx context.Context, email, password, name string) (string, string, string, *authpb.User, error) {
	resp, err := h.auth.Register(ctx, &authpb.RegisterRequest{Email: email, Password: password, DisplayName: name})
	if err == nil && resp.GetUser() != nil {
		return resp.GetUser().GetId(), resp.GetAccessToken(), resp.GetRefreshToken(), resp.GetUser(), nil
	}
	// Account may already exist — look it up and log in for tokens.
	gu, guErr := h.auth.GetUser(ctx, &authpb.GetUserRequest{UserId: email})
	if guErr != nil || gu.GetUser() == nil {
		return "", "", "", nil, err // return the original register error
	}
	lr, _ := h.auth.Login(ctx, &authpb.LoginRequest{Email: email, Password: password})
	if lr != nil {
		return gu.GetUser().GetId(), lr.GetAccessToken(), lr.GetRefreshToken(), gu.GetUser(), nil
	}
	return gu.GetUser().GetId(), "", "", gu.GetUser(), nil
}

// POST /api/v1/admin/users — super admin only. Create a staff account with an
// auto-generated temporary password (emailed to the staff member), assign
// roles, and flag the account to force a password change on first login.
// Body: {email, name, roles[]}.
func (h *AdminHandler) CreateUser(c *gin.Context) {
	if !h.ready(c) {
		return
	}
	if h.auth == nil || h.authz == nil {
		c.JSON(http.StatusServiceUnavailable, gin.H{"error": "auth/authz unavailable"})
		return
	}
	var body struct {
		Email string   `json:"email"`
		Name  string   `json:"name"`
		Roles []string `json:"roles"`
	}
	if err := c.ShouldBindJSON(&body); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid body"})
		return
	}
	if body.Email == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "email is required"})
		return
	}
	for _, r := range body.Roles {
		if !isAssignableRole(r) {
			c.JSON(http.StatusBadRequest, gin.H{"error": "unknown role: " + r})
			return
		}
	}

	tempPassword := generateTempPassword()
	resp, err := h.auth.Register(c.Request.Context(), &authpb.RegisterRequest{Email: body.Email, Password: tempPassword, DisplayName: body.Name})
	if err != nil {
		if grpcstatus.Code(err) == codes.AlreadyExists {
			c.JSON(http.StatusConflict, gin.H{"error": "an account with that email already exists"})
			return
		}
		h.log.Error("admin create user failed", zap.Error(err))
		c.JSON(http.StatusInternalServerError, gin.H{"error": "failed to create account"})
		return
	}
	userID := resp.GetUser().GetId()

	for _, r := range body.Roles {
		if _, err := h.authz.AssignRole(c.Request.Context(), &authzpb.AssignRoleRequest{UserId: userID, Role: r}); err != nil {
			h.log.Warn("admin create user: assign role failed", zap.String("role", r), zap.Error(err))
		}
	}

	// Force a password change on first login.
	if _, err := h.db.Exec(c.Request.Context(),
		`UPDATE core.users SET must_change_password = true WHERE id = $1`, userID); err != nil {
		h.log.Warn("create user: could not set must_change_password", zap.Error(err))
	}

	emailSent := h.sendTempPasswordEmail(c.Request.Context(), body.Email, body.Name, tempPassword)
	h.audit(c, "user:create", "user", userID, body.Email+" roles="+joinRoles(body.Roles))

	c.JSON(http.StatusCreated, gin.H{
		"id":            userID,
		"email":         body.Email,
		"roles":         body.Roles,
		"temp_password": tempPassword, // shown to the super admin as a fallback
		"email_sent":    emailSent,
	})
}

func (h *AdminHandler) sendTempPasswordEmail(ctx context.Context, to, name, tempPassword string) bool {
	if h.notifier == nil {
		return false
	}
	if name == "" {
		name = "there"
	}
	subject := "Your GoChat admin account"
	text := "Hello " + name + ",\n\n" +
		"An admin account has been created for you on the GoChat console.\n\n" +
		"Email: " + to + "\n" +
		"Temporary password: " + tempPassword + "\n\n" +
		"You'll be asked to set a new password the first time you sign in.\n\n— GoChat"
	if err := h.notifier.SendEmail(ctx, to, subject, text, ""); err != nil {
		h.log.Warn("failed to email temporary password", zap.String("to", to), zap.Error(err))
		return false
	}
	return true
}

// POST /api/v1/admin/password — the signed-in admin sets their own password
// (used for the forced first-login change and for self-service changes).
// Body: {new_password}.
func (h *AdminHandler) ChangePassword(c *gin.Context) {
	if !h.ready(c) {
		return
	}
	userID := c.GetString("user_id")
	if userID == "" {
		c.JSON(http.StatusUnauthorized, gin.H{"error": "authentication required"})
		return
	}
	var body struct {
		NewPassword string `json:"new_password"`
	}
	if err := c.ShouldBindJSON(&body); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid body"})
		return
	}
	if len(body.NewPassword) < 8 {
		c.JSON(http.StatusBadRequest, gin.H{"error": "password must be at least 8 characters"})
		return
	}
	hash, err := bcrypt.GenerateFromPassword([]byte(body.NewPassword), bcrypt.DefaultCost)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "failed to hash password"})
		return
	}
	tag, err := h.db.Exec(c.Request.Context(),
		`UPDATE core.users SET password_hash = $1, must_change_password = false, updated_at = now() WHERE id = $2`,
		string(hash), userID)
	if err != nil {
		h.log.Error("change password failed", zap.String("user_id", userID), zap.Error(err))
		c.JSON(http.StatusInternalServerError, gin.H{"error": "failed to update password"})
		return
	}
	if tag.RowsAffected() == 0 {
		c.JSON(http.StatusNotFound, gin.H{"error": "user not found"})
		return
	}
	h.audit(c, "password:change", "user", userID, "")
	c.JSON(http.StatusOK, gin.H{"ok": true})
}

// POST /api/v1/admin/users/:id/roles — super admin only. Sets the user's roles
// to exactly the supplied set. Body: {roles[]}.
func (h *AdminHandler) SetUserRoles(c *gin.Context) {
	if !h.ready(c) {
		return
	}
	if h.authz == nil {
		c.JSON(http.StatusServiceUnavailable, gin.H{"error": "authz unavailable"})
		return
	}
	id := c.Param("id")
	var body struct {
		Roles []string `json:"roles"`
	}
	if err := c.ShouldBindJSON(&body); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid body"})
		return
	}
	desired := map[string]bool{}
	for _, r := range body.Roles {
		if !isAssignableRole(r) {
			c.JSON(http.StatusBadRequest, gin.H{"error": "unknown role: " + r})
			return
		}
		desired[r] = true
	}

	cur, err := h.authz.GetUserRoles(c.Request.Context(), &authzpb.GetUserRolesRequest{UserId: id})
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "failed to read current roles"})
		return
	}
	current := map[string]bool{}
	for _, r := range cur.GetRoles() {
		current[r] = true
	}

	// Guard: never remove the last super admin (avoids lockout).
	if current["super_admin"] && !desired["super_admin"] {
		if n, _ := h.superAdminCount(c.Request.Context()); n <= 1 {
			c.JSON(http.StatusBadRequest, gin.H{"error": "cannot remove the last super admin"})
			return
		}
	}

	for r := range desired {
		if !current[r] {
			if _, err := h.authz.AssignRole(c.Request.Context(), &authzpb.AssignRoleRequest{UserId: id, Role: r}); err != nil {
				h.log.Warn("set roles: assign failed", zap.String("role", r), zap.Error(err))
			}
		}
	}
	for r := range current {
		if !desired[r] && isAssignableRole(r) {
			if _, err := h.authz.RevokeRole(c.Request.Context(), &authzpb.RevokeRoleRequest{UserId: id, Role: r}); err != nil {
				h.log.Warn("set roles: revoke failed", zap.String("role", r), zap.Error(err))
			}
		}
	}
	h.audit(c, "user:roles", "user", id, joinRoles(body.Roles))
	c.JSON(http.StatusOK, gin.H{"id": id, "roles": body.Roles})
}

// GET /api/v1/admin/staff — accounts that hold at least one admin role.
// Separate from the regular user directory. Super admin only.
func (h *AdminHandler) ListStaff(c *gin.Context) {
	if !h.ready(c) {
		return
	}
	roleNames := make([]string, 0, len(assignableRoles))
	for _, r := range assignableRoles {
		roleNames = append(roleNames, r.Name)
	}

	rows, err := h.db.Query(c.Request.Context(),
		`SELECT u.id, u.email, u.display_name, coalesce(u.avatar_url,''),
		        u.is_online, u.last_seen, u.created_at, coalesce(u.account_status,'active'),
		        array_agg(ur.role_name ORDER BY ur.role_name) AS roles
		 FROM core.users u
		 JOIN authz.user_roles ur ON ur.user_id = u.id
		 WHERE ur.role_name = ANY($1)
		 GROUP BY u.id
		 ORDER BY u.created_at DESC`,
		roleNames)
	if err != nil {
		h.log.Error("admin list staff failed", zap.Error(err))
		c.JSON(http.StatusInternalServerError, gin.H{"error": "failed to load staff"})
		return
	}
	defer rows.Close()

	type staff struct {
		ID           string    `json:"id"`
		Email        string    `json:"email"`
		Name         string    `json:"name"`
		AvatarURL    string    `json:"avatar_url"`
		Online       bool      `json:"online"`
		LastActiveAt time.Time `json:"last_active_at"`
		JoinedAt     time.Time `json:"joined_at"`
		Status       string    `json:"status"`
		Roles        []string  `json:"roles"`
	}
	out := []staff{}
	for rows.Next() {
		var s staff
		if err := rows.Scan(&s.ID, &s.Email, &s.Name, &s.AvatarURL, &s.Online,
			&s.LastActiveAt, &s.JoinedAt, &s.Status, &s.Roles); err != nil {
			h.log.Warn("staff row scan failed", zap.Error(err))
			continue
		}
		out = append(out, s)
	}
	c.JSON(http.StatusOK, gin.H{"staff": out})
}

// GET /api/v1/admin/roles — the roles a super admin can assign.
func (h *AdminHandler) ListRoles(c *gin.Context) {
	c.JSON(http.StatusOK, gin.H{"roles": assignableRoles})
}

func joinRoles(r []string) string {
	out := ""
	for i, s := range r {
		if i > 0 {
			out += ","
		}
		out += s
	}
	if out == "" {
		return "(none)"
	}
	return out
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
