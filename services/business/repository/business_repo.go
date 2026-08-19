package repository

import (
	"context"
	"fmt"
	"strconv"
	"strings"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"
	"go.uber.org/zap"
)

type BusinessRepository struct {
	db                  *pgxpool.Pool
	notificationService *NotificationService
	logger              *zap.Logger
}

func NewBusinessRepository(db *pgxpool.Pool, notificationService *NotificationService, logger *zap.Logger) *BusinessRepository {
	repo := &BusinessRepository{
		db:                  db,
		notificationService: notificationService,
		logger:              logger,
	}
	repo.ensureSchema()
	return repo
}

func (r *BusinessRepository) ensureSchema() {
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	queries := []string{
		`ALTER TABLE business.coupons ADD COLUMN IF NOT EXISTS product_id UUID REFERENCES business.products(id) ON DELETE SET NULL;`,
		`ALTER TABLE business.coupons ADD COLUMN IF NOT EXISTS max_uses_per_user INT DEFAULT 0;`,
		`CREATE INDEX IF NOT EXISTS idx_coupons_product ON business.coupons(product_id);`,
		`ALTER TABLE business.auto_replies ADD COLUMN IF NOT EXISTS schedule_type VARCHAR(30) DEFAULT 'always';`,
		`ALTER TABLE business.auto_replies ADD COLUMN IF NOT EXISTS timezone VARCHAR(60) DEFAULT 'UTC';`,
		`ALTER TABLE business.auto_replies ADD COLUMN IF NOT EXISTS days_of_week INT[] DEFAULT ARRAY[1,2,3,4,5];`,
		`ALTER TABLE business.auto_replies ADD COLUMN IF NOT EXISTS start_time VARCHAR(10) DEFAULT '09:00';`,
		`ALTER TABLE business.auto_replies ADD COLUMN IF NOT EXISTS end_time VARCHAR(10) DEFAULT '17:00';`,
	}

	for _, q := range queries {
		if _, err := r.db.Exec(ctx, q); err != nil {
			fmt.Printf("Error ensuring business schema: %v\n", err)
		}
	}
}

type BusinessProfile struct {
	UserID       string
	BusinessName string
	Category     string
	Description  string
	Address      string
	Website      string
	Email        string
	Phone        string
	HoursJSON    string
	IsVerified   bool
	LogoURL      string
	BannerURL    string
	State        string
	CountryCode  string
	Slug         string
}

func (r *BusinessRepository) CreateBusinessProfile(ctx context.Context, p *BusinessProfile) (*BusinessProfile, error) {
	if p.Slug == "" {
		p.Slug = generateSlug(p.BusinessName, p.UserID)
	}
	if p.HoursJSON == "" || p.HoursJSON == "null" {
		p.HoursJSON = "{}"
	}
	_, err := r.db.Exec(ctx,
		`INSERT INTO business.business_profiles (user_id, business_name, category, description, address, website, email, phone, hours_json, logo_url, banner_url, state, country_code, slug)
		 VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14)
		 ON CONFLICT (user_id) DO UPDATE SET
			business_name=$2, category=$3, description=$4, address=$5, website=$6, email=$7, phone=$8, hours_json=$9, logo_url=$10, banner_url=$11, state=$12, country_code=$13, updated_at=NOW()`,
		p.UserID, p.BusinessName, p.Category, p.Description, p.Address, p.Website, p.Email, p.Phone, p.HoursJSON, p.LogoURL, p.BannerURL, p.State, p.CountryCode, p.Slug)
	if err != nil {
		return nil, fmt.Errorf("create business profile: %w", err)
	}
	return p, nil
}

func (r *BusinessRepository) UpdateBusinessProfile(ctx context.Context, p *BusinessProfile) (*BusinessProfile, error) {
	if p.HoursJSON == "" || p.HoursJSON == "null" {
		p.HoursJSON = "{}"
	}
	res, err := r.db.Exec(ctx,
		`UPDATE business.business_profiles SET
			business_name = $2,
			category     = $3,
			description  = $4,
			address      = $5,
			website      = $6,
			email        = $7,
			phone        = $8,
			hours_json   = $9,
			logo_url     = $10,
			banner_url   = $11,
			state        = $12,
			country_code = $13,
			updated_at   = NOW()
		 WHERE user_id = $1`,
		p.UserID, p.BusinessName, p.Category, p.Description, p.Address, p.Website, p.Email, p.Phone, p.HoursJSON, p.LogoURL, p.BannerURL, p.State, p.CountryCode)
	if err != nil {
		return nil, fmt.Errorf("update business profile: %w", err)
	}
	if res.RowsAffected() == 0 {
		return nil, fmt.Errorf("no business profile found for user %s", p.UserID)
	}
	return p, nil
}

func (r *BusinessRepository) GetBusinessProfile(ctx context.Context, userID string) (*BusinessProfile, error) {
	p := &BusinessProfile{}
	err := r.db.QueryRow(ctx,
		`SELECT bp.user_id, bp.business_name, COALESCE(bp.category,''), COALESCE(bp.description,''),
		 COALESCE(bp.address,''), COALESCE(bp.website,''), COALESCE(bp.email, u.email, ''), COALESCE(bp.phone, u.phone, ''),
		 COALESCE(bp.hours_json::text,'{}'), bp.is_verified, COALESCE(bp.logo_url,''), COALESCE(bp.banner_url,''),
		 COALESCE(bp.state,''), COALESCE(NULLIF(bp.country_code,''), u.country_code, ''), COALESCE(bp.slug,'')
		 FROM business.business_profiles bp
		 LEFT JOIN core.users u ON u.id = bp.user_id
		 WHERE bp.user_id = $1`, userID).
		Scan(&p.UserID, &p.BusinessName, &p.Category, &p.Description,
			&p.Address, &p.Website, &p.Email, &p.Phone, &p.HoursJSON, &p.IsVerified,
			&p.LogoURL, &p.BannerURL, &p.State, &p.CountryCode, &p.Slug)
	if err != nil {
		return nil, err
	}
	return p, nil
}

func (r *BusinessRepository) GetBusinessProfileBySlug(ctx context.Context, slug string) (*BusinessProfile, error) {
	p := &BusinessProfile{}
	err := r.db.QueryRow(ctx,
		`SELECT user_id, business_name, COALESCE(category,''), COALESCE(description,''),
		 COALESCE(address,''), COALESCE(website,''), COALESCE(email,''), COALESCE(phone,''),
		 COALESCE(hours_json::text,'{}'), is_verified, COALESCE(logo_url,''), COALESCE(banner_url,''),
		 COALESCE(state,''), COALESCE(country_code,''), COALESCE(slug,'')
		 FROM business.business_profiles WHERE slug = $1`, slug).
		Scan(&p.UserID, &p.BusinessName, &p.Category, &p.Description,
			&p.Address, &p.Website, &p.Email, &p.Phone, &p.HoursJSON, &p.IsVerified,
			&p.LogoURL, &p.BannerURL, &p.State, &p.CountryCode, &p.Slug)
	if err != nil {
		return nil, err
	}
	return p, nil
}

func generateSlug(name, userID string) string {
	slug := ""
	for _, ch := range name {
		if (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z') || (ch >= '0' && ch <= '9') {
			if ch >= 'A' && ch <= 'Z' {
				ch += 32
			}
			slug += string(ch)
		} else if ch == ' ' || ch == '-' || ch == '_' {
			if len(slug) > 0 && slug[len(slug)-1] != '-' {
				slug += "-"
			}
		}
	}
	if slug == "" {
		slug = "store"
	}
	if len(userID) >= 8 {
		slug += "-" + userID[:8]
	}
	return slug
}

type Product struct {
	ID          string
	CatalogID   string
	Name        string
	Description string
	Price       float64
	Currency    string
	ImageURL    string
	InStock     bool
}

func (r *BusinessRepository) CreateCatalog(ctx context.Context, userID, name string) (string, error) {
	id := uuid.New().String()
	_, err := r.db.Exec(ctx,
		`INSERT INTO business.product_catalogs (id, user_id, name) VALUES ($1, $2, $3)`,
		id, userID, name)
	return id, err
}

func (r *BusinessRepository) AddProduct(ctx context.Context, catalogID, name, desc string, price float64, currency, imageURL string) (*Product, error) {
	id := uuid.New().String()
	if currency == "" {
		currency = "USD"
	}
	_, err := r.db.Exec(ctx,
		`INSERT INTO business.product_items (id, catalog_id, name, description, price, currency, image_url)
		 VALUES ($1, $2, $3, $4, $5, $6, $7)`,
		id, catalogID, name, desc, price, currency, imageURL)
	if err != nil {
		return nil, err
	}
	return &Product{ID: id, CatalogID: catalogID, Name: name, Description: desc,
		Price: price, Currency: currency, ImageURL: imageURL, InStock: true}, nil
}

func (r *BusinessRepository) ListProducts(ctx context.Context, catalogID string, limit, offset int) ([]*Product, int, error) {
	var total int
	r.db.QueryRow(ctx, `SELECT COUNT(*) FROM business.product_items WHERE catalog_id = $1`, catalogID).Scan(&total)

	rows, err := r.db.Query(ctx,
		`SELECT id, catalog_id, name, COALESCE(description,''), price, currency, COALESCE(image_url,''), in_stock
		 FROM business.product_items WHERE catalog_id = $1 ORDER BY name LIMIT $2 OFFSET $3`,
		catalogID, limit, offset)
	if err != nil {
		return nil, 0, err
	}
	defer rows.Close()

	var products []*Product
	for rows.Next() {
		p := &Product{}
		rows.Scan(&p.ID, &p.CatalogID, &p.Name, &p.Description, &p.Price, &p.Currency, &p.ImageURL, &p.InStock)
		products = append(products, p)
	}
	return products, total, nil
}

type Appointment struct {
	ID              string
	BusinessID      string
	Title           string
	Description     string
	StartTime       time.Time
	EndTime         time.Time
	MaxBookings     int
	CurrentBookings int
}

func (r *BusinessRepository) CreateAppointmentSlot(ctx context.Context, bizID, title, desc string, start, end time.Time, maxBookings int) (*Appointment, error) {
	id := uuid.New().String()
	_, err := r.db.Exec(ctx,
		`INSERT INTO business.appointments (id, business_id, title, description, start_time, end_time, max_bookings)
		 VALUES ($1, $2, $3, $4, $5, $6, $7)`,
		id, bizID, title, desc, start, end, maxBookings)
	if err != nil {
		return nil, err
	}
	return &Appointment{ID: id, BusinessID: bizID, Title: title, Description: desc,
		StartTime: start, EndTime: end, MaxBookings: maxBookings}, nil
}

func (r *BusinessRepository) BookAppointment(ctx context.Context, userID, apptID, notes string) error {
	tx, err := r.db.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)

	var current, max int
	tx.QueryRow(ctx, `SELECT current_bookings, max_bookings FROM business.appointments WHERE id = $1`, apptID).Scan(&current, &max)
	if current >= max {
		return fmt.Errorf("appointment is fully booked")
	}

	_, err = tx.Exec(ctx,
		`INSERT INTO business.appointment_bookings (id, appointment_id, user_id, notes) VALUES ($1, $2, $3, $4)`,
		uuid.New().String(), apptID, userID, notes)
	if err != nil {
		return err
	}

	tx.Exec(ctx, `UPDATE business.appointments SET current_bookings = current_bookings + 1 WHERE id = $1`, apptID)
	return tx.Commit(ctx)
}

func (r *BusinessRepository) ListAppointments(ctx context.Context, bizID string, limit, offset int) ([]*Appointment, int, error) {
	var total int
	r.db.QueryRow(ctx, `SELECT COUNT(*) FROM business.appointments WHERE business_id = $1`, bizID).Scan(&total)

	rows, err := r.db.Query(ctx,
		`SELECT id, business_id, title, COALESCE(description,''), start_time, end_time, max_bookings, current_bookings
		 FROM business.appointments WHERE business_id = $1 ORDER BY start_time LIMIT $2 OFFSET $3`,
		bizID, limit, offset)
	if err != nil {
		return nil, 0, err
	}
	defer rows.Close()

	var appts []*Appointment
	for rows.Next() {
		a := &Appointment{}
		rows.Scan(&a.ID, &a.BusinessID, &a.Title, &a.Description, &a.StartTime, &a.EndTime, &a.MaxBookings, &a.CurrentBookings)
		appts = append(appts, a)
	}
	return appts, total, nil
}

type AutoReply struct {
	ID           string
	TriggerType  string
	TriggerValue string
	ReplyText    string
	IsActive     bool
	ScheduleType string
	Timezone     string
	DaysOfWeek   []int32
	StartTime    string
	EndTime      string
	IsActiveNow  bool
}

func (r *BusinessRepository) SetAutoReply(ctx context.Context, userID, triggerType, triggerValue, replyText, scheduleType, timezone string, daysOfWeek []int32, startTime, endTime string) (*AutoReply, error) {
	id := uuid.New().String()

	if scheduleType == "" {
		scheduleType = "always"
	}
	if timezone == "" {
		timezone = "UTC"
	}
	if len(daysOfWeek) == 0 {
		daysOfWeek = []int32{1, 2, 3, 4, 5} // Mon-Fri
	}
	if startTime == "" {
		startTime = "09:00"
	}
	if endTime == "" {
		endTime = "17:00"
	}

	_, err := r.db.Exec(ctx,
		`INSERT INTO business.auto_replies (id, user_id, trigger_type, trigger_value, reply_text, schedule_type, timezone, days_of_week, start_time, end_time, is_active)
		 VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, TRUE)`,
		id, userID, triggerType, triggerValue, replyText, scheduleType, timezone, daysOfWeek, startTime, endTime)
	if err != nil {
		return nil, err
	}

	isActiveNow := ComputeIsActiveNow(scheduleType, timezone, startTime, endTime, daysOfWeek, true, time.Now())

	return &AutoReply{
		ID:           id,
		TriggerType:  triggerType,
		TriggerValue: triggerValue,
		ReplyText:    replyText,
		IsActive:     true,
		ScheduleType: scheduleType,
		Timezone:     timezone,
		DaysOfWeek:   daysOfWeek,
		StartTime:    startTime,
		EndTime:      endTime,
		IsActiveNow:  isActiveNow,
	}, nil
}

func (r *BusinessRepository) GetAutoReplies(ctx context.Context, userID string) ([]*AutoReply, error) {
	rows, err := r.db.Query(ctx,
		`SELECT id, trigger_type, COALESCE(trigger_value,''), reply_text, is_active,
		        COALESCE(schedule_type, 'always'), COALESCE(timezone, 'UTC'),
		        COALESCE(days_of_week, ARRAY[1,2,3,4,5]), COALESCE(start_time, '09:00'), COALESCE(end_time, '17:00')
		 FROM business.auto_replies WHERE user_id = $1 AND is_active = TRUE`, userID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	now := time.Now()
	var rules []*AutoReply
	for rows.Next() {
		a := &AutoReply{}
		err := rows.Scan(
			&a.ID, &a.TriggerType, &a.TriggerValue, &a.ReplyText, &a.IsActive,
			&a.ScheduleType, &a.Timezone, &a.DaysOfWeek, &a.StartTime, &a.EndTime,
		)
		if err != nil {
			return nil, err
		}
		a.IsActiveNow = ComputeIsActiveNow(a.ScheduleType, a.Timezone, a.StartTime, a.EndTime, a.DaysOfWeek, a.IsActive, now)
		rules = append(rules, a)
	}
	return rules, nil
}

func parseMinutes(timeStr string, defaultMin int) int {
	if timeStr == "" {
		return defaultMin
	}
	parts := strings.Split(timeStr, ":")
	if len(parts) != 2 {
		return defaultMin
	}
	h, err1 := strconv.Atoi(strings.TrimSpace(parts[0]))
	m, err2 := strconv.Atoi(strings.TrimSpace(parts[1]))
	if err1 != nil || err2 != nil || h < 0 || h > 23 || m < 0 || m > 59 {
		return defaultMin
	}
	return h*60 + m
}

// IsWithinBusinessHours checks if a timestamp falls within configured business hours.
func IsWithinBusinessHours(timezone, startTime, endTime string, daysOfWeek []int32, now time.Time) bool {
	loc, err := time.LoadLocation(timezone)
	if err != nil {
		loc = time.UTC
	}
	localNow := now.In(loc)

	// Go Weekday: Sunday=0, Monday=1, ..., Saturday=6
	currentDay := int32(localNow.Weekday())

	dayMatch := false
	if len(daysOfWeek) == 0 {
		// default Mon-Fri (1,2,3,4,5)
		dayMatch = currentDay >= 1 && currentDay <= 5
	} else {
		for _, d := range daysOfWeek {
			if d == currentDay || (d == 7 && currentDay == 0) {
				dayMatch = true
				break
			}
		}
	}
	if !dayMatch {
		return false
	}

	currMin := localNow.Hour()*60 + localNow.Minute()
	startMin := parseMinutes(startTime, 9*60)
	endMin := parseMinutes(endTime, 17*60)

	if startMin <= endMin {
		return currMin >= startMin && currMin < endMin
	}
	// Overnight hours (e.g. 22:00 to 06:00)
	return currMin >= startMin || currMin < endMin
}

// ComputeIsActiveNow calculates if an auto reply rule is active at the given moment.
func ComputeIsActiveNow(scheduleType, timezone, startTime, endTime string, daysOfWeek []int32, isActive bool, now time.Time) bool {
	if !isActive {
		return false
	}
	switch strings.ToLower(scheduleType) {
	case "outside_business_hours", "outside_hours", "away":
		return !IsWithinBusinessHours(timezone, startTime, endTime, daysOfWeek, now)
	case "during_business_hours", "business_hours", "custom_hours":
		return IsWithinBusinessHours(timezone, startTime, endTime, daysOfWeek, now)
	default: // "always"
		return true
	}
}
