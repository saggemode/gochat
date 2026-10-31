package handlers

import (
	"context"
	"net/http"
	"sort"
	"strconv"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/jackc/pgx/v5/pgxpool"
	"go.uber.org/zap"
	"google.golang.org/grpc"

	businesspb "gochat/gen/business"
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
	db       *pgxpool.Pool
	business businesspb.BusinessServiceClient
	services map[string]*grpc.ClientConn // for real per-service connectivity health
	log      *zap.Logger
}

func NewAdminHandler(db *pgxpool.Pool, business businesspb.BusinessServiceClient, services map[string]*grpc.ClientConn, log *zap.Logger) *AdminHandler {
	return &AdminHandler{db: db, business: business, services: services, log: log}
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
		        (SELECT count(*) FROM business.orders o WHERE o.buyer_id = u.id)::int AS orders
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
	}

	users := []adminUser{}
	for rows.Next() {
		var u adminUser
		if err := rows.Scan(&u.ID, &u.Email, &u.Name, &u.AvatarURL, &u.Phone,
			&u.PhoneVerified, &u.Online, &u.LastActiveAt, &u.Country, &u.JoinedAt, &u.Status,
			&u.IsSeller, &u.Orders); err != nil {
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

	actor, _ := c.Get("email")
	h.log.Info("admin changed user status",
		zap.String("user_id", id),
		zap.String("status", body.Status),
		zap.Any("by", actor))

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

	actor, _ := c.Get("email")
	h.log.Info("admin resolved dispute",
		zap.String("dispute_id", disputeID),
		zap.String("action", body.Action),
		zap.Any("by", actor))

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

func atoiDefault(s string, def int) int {
	if s == "" {
		return def
	}
	if n, err := strconv.Atoi(s); err == nil {
		return n
	}
	return def
}
