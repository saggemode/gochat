package repository

import (
	"context"
	"fmt"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"
)

type BusinessRepository struct {
	db *pgxpool.Pool
}

func NewBusinessRepository(db *pgxpool.Pool) *BusinessRepository {
	return &BusinessRepository{db: db}
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
}

func (r *BusinessRepository) CreateBusinessProfile(ctx context.Context, p *BusinessProfile) (*BusinessProfile, error) {
	_, err := r.db.Exec(ctx,
		`INSERT INTO business_profiles (user_id, business_name, category, description, address, website, email, phone, hours_json)
		 VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9)
		 ON CONFLICT (user_id) DO UPDATE SET business_name=$2, category=$3, description=$4, address=$5, website=$6, email=$7, phone=$8, hours_json=$9, updated_at=NOW()`,
		p.UserID, p.BusinessName, p.Category, p.Description, p.Address, p.Website, p.Email, p.Phone, p.HoursJSON)
	if err != nil {
		return nil, fmt.Errorf("create business profile: %w", err)
	}
	return p, nil
}

func (r *BusinessRepository) GetBusinessProfile(ctx context.Context, userID string) (*BusinessProfile, error) {
	p := &BusinessProfile{}
	err := r.db.QueryRow(ctx,
		`SELECT user_id, business_name, COALESCE(category,''), COALESCE(description,''),
		 COALESCE(address,''), COALESCE(website,''), COALESCE(email,''), COALESCE(phone,''),
		 COALESCE(hours_json::text,'{}'), is_verified
		 FROM business_profiles WHERE user_id = $1`, userID).
		Scan(&p.UserID, &p.BusinessName, &p.Category, &p.Description,
			&p.Address, &p.Website, &p.Email, &p.Phone, &p.HoursJSON, &p.IsVerified)
	if err != nil {
		return nil, err
	}
	return p, nil
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
		`INSERT INTO product_catalogs (id, user_id, name) VALUES ($1, $2, $3)`,
		id, userID, name)
	return id, err
}

func (r *BusinessRepository) AddProduct(ctx context.Context, catalogID, name, desc string, price float64, currency, imageURL string) (*Product, error) {
	id := uuid.New().String()
	if currency == "" {
		currency = "USD"
	}
	_, err := r.db.Exec(ctx,
		`INSERT INTO product_items (id, catalog_id, name, description, price, currency, image_url)
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
	r.db.QueryRow(ctx, `SELECT COUNT(*) FROM product_items WHERE catalog_id = $1`, catalogID).Scan(&total)

	rows, err := r.db.Query(ctx,
		`SELECT id, catalog_id, name, COALESCE(description,''), price, currency, COALESCE(image_url,''), in_stock
		 FROM product_items WHERE catalog_id = $1 ORDER BY name LIMIT $2 OFFSET $3`,
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
		`INSERT INTO appointments (id, business_id, title, description, start_time, end_time, max_bookings)
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
	tx.QueryRow(ctx, `SELECT current_bookings, max_bookings FROM appointments WHERE id = $1`, apptID).Scan(&current, &max)
	if current >= max {
		return fmt.Errorf("appointment is fully booked")
	}

	_, err = tx.Exec(ctx,
		`INSERT INTO appointment_bookings (id, appointment_id, user_id, notes) VALUES ($1, $2, $3, $4)`,
		uuid.New().String(), apptID, userID, notes)
	if err != nil {
		return err
	}

	tx.Exec(ctx, `UPDATE appointments SET current_bookings = current_bookings + 1 WHERE id = $1`, apptID)
	return tx.Commit(ctx)
}

func (r *BusinessRepository) ListAppointments(ctx context.Context, bizID string, limit, offset int) ([]*Appointment, int, error) {
	var total int
	r.db.QueryRow(ctx, `SELECT COUNT(*) FROM appointments WHERE business_id = $1`, bizID).Scan(&total)

	rows, err := r.db.Query(ctx,
		`SELECT id, business_id, title, COALESCE(description,''), start_time, end_time, max_bookings, current_bookings
		 FROM appointments WHERE business_id = $1 ORDER BY start_time LIMIT $2 OFFSET $3`,
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
}

func (r *BusinessRepository) SetAutoReply(ctx context.Context, userID, triggerType, triggerValue, replyText string) (*AutoReply, error) {
	id := uuid.New().String()
	_, err := r.db.Exec(ctx,
		`INSERT INTO auto_replies (id, user_id, trigger_type, trigger_value, reply_text) VALUES ($1, $2, $3, $4, $5)`,
		id, userID, triggerType, triggerValue, replyText)
	if err != nil {
		return nil, err
	}
	return &AutoReply{ID: id, TriggerType: triggerType, TriggerValue: triggerValue, ReplyText: replyText, IsActive: true}, nil
}

func (r *BusinessRepository) GetAutoReplies(ctx context.Context, userID string) ([]*AutoReply, error) {
	rows, err := r.db.Query(ctx,
		`SELECT id, trigger_type, COALESCE(trigger_value,''), reply_text, is_active
		 FROM auto_replies WHERE user_id = $1 AND is_active = TRUE`, userID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var rules []*AutoReply
	for rows.Next() {
		a := &AutoReply{}
		rows.Scan(&a.ID, &a.TriggerType, &a.TriggerValue, &a.ReplyText, &a.IsActive)
		rules = append(rules, a)
	}
	return rules, nil
}
