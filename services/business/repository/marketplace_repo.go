package repository

import (
	"context"
	"fmt"
	"strings"
	"time"

	"github.com/google/uuid"
)

type MarketplaceProduct struct {
	ID              string
	BusinessID      string
	OwnerID         string
	Name            string
	Description     string
	CategoryID      string
	SubCategoryID   string
	BrandID         string
	Price           float64
	DiscountPercent float64
	Currency        string
	Quantity        int32
	SKU             string
	Color           string
	Size            string
	Weight          float64
	ShippingFee     float64
	IsPublished     bool
	ViewCount       int32
	OrderCount      int32
	RatingAvg       float64
	ReviewCount     int32
	ImageURLs       []string
	SellerName      string
	SellerAvatar    string
	SellerSlug      string
	CategoryName    string
	BrandName       string
	CreatedAt       time.Time
}

type Category struct {
	ID            string
	Name          string
	Icon          string
	SortOrder     int32
	SubCategories []*SubCategory
}

type SubCategory struct {
	ID         string
	CategoryID string
	Name       string
	SortOrder  int32
}

type Review struct {
	ID         string
	ProductID  string
	UserID     string
	UserName   string
	UserAvatar string
	Rating     int32
	Comment    string
	CreatedAt  time.Time
}

type Store struct {
	Profile      *BusinessProfile
	Products     []*MarketplaceProduct
	ProductCount int32
	AvgRating    float64
	TotalReviews int32
}

// ── Products CRUD ────────────────────────────────────────────────────────────

func deriveCurrencyFromCountry(country string) string {
	c := strings.ToUpper(strings.TrimSpace(country))
	switch {
	case c == "NG" || c == "NIGERIA":
		return "NGN"
	case c == "GB" || c == "UNITED KINGDOM" || c == "UK":
		return "GBP"
	case c == "DE" || c == "FR" || c == "IT" || c == "ES" || c == "EU" || c == "GERMANY" || c == "FRANCE":
		return "EUR"
	case c == "IN" || c == "INDIA":
		return "INR"
	case c == "KE" || c == "KENYA":
		return "KES"
	case c == "ZA" || c == "SOUTH AFRICA":
		return "ZAR"
	case c == "GH" || c == "GHANA":
		return "GHS"
	case c == "CA" || c == "CANADA":
		return "CAD"
	case c == "AU" || c == "AUSTRALIA":
		return "AUD"
	case c == "JP" || c == "JAPAN":
		return "JPY"
	case c == "CN" || c == "CHINA":
		return "CNY"
	case c == "AE" || c == "UAE" || c == "UNITED ARAB EMIRATES":
		return "AED"
	default:
		return "USD"
	}
}

func (r *BusinessRepository) CreateMarketplaceProduct(ctx context.Context, p *MarketplaceProduct) (*MarketplaceProduct, error) {
	// Verify user has created a business profile, or auto-create a default one
	var profileExists bool
	_ = r.db.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM business.business_profiles WHERE user_id = $1)`, p.OwnerID).Scan(&profileExists)
	if !profileExists {
		var displayName string
		_ = r.db.QueryRow(ctx, `SELECT COALESCE(display_name, 'Merchant Store') FROM core.users WHERE id = $1`, p.OwnerID).Scan(&displayName)
		if displayName == "" {
			displayName = "Merchant Store"
		}
		slugID := p.OwnerID
		if len(slugID) > 8 {
			slugID = slugID[:8]
		}
		slug := strings.ToLower(strings.ReplaceAll(displayName, " ", "-")) + "-" + slugID
		_, _ = r.db.Exec(ctx, `
			INSERT INTO business.business_profiles (
				user_id, business_name, slug, category, is_verified, created_at, updated_at
			) VALUES ($1, $2, $3, 'Retail', FALSE, NOW(), NOW())
			ON CONFLICT (user_id) DO NOTHING
		`, p.OwnerID, displayName, slug)
	}

	p.ID = uuid.New().String()
	p.BusinessID = p.OwnerID
	if p.Currency == "" {
		var countryCode string
		_ = r.db.QueryRow(ctx, `SELECT COALESCE(country_code,'') FROM business.business_profiles WHERE user_id = $1`, p.OwnerID).Scan(&countryCode)
		p.Currency = deriveCurrencyFromCountry(countryCode)
	}
	if p.Quantity <= 0 {
		p.Quantity = 100
	}
	p.IsPublished = true
	p.CreatedAt = time.Now()

	tx, err := r.db.Begin(ctx)
	if err != nil {
		return nil, fmt.Errorf("begin tx: %w", err)
	}
	defer tx.Rollback(ctx)

	_, err = tx.Exec(ctx,
		`INSERT INTO business.products (
			id, business_id, owner_id, name, description,
			category_id, sub_category_id, brand_id,
			price, discount_percent, currency, quantity, sku,
			color, size, weight, shipping_fee, is_published
		) VALUES ($1, $2, $3, $4, $5, NULLIF($6,'')::uuid, NULLIF($7,'')::uuid, NULLIF($8,'')::uuid, $9, $10, $11, $12, $13, $14, $15, $16, $17, $18)`,
		p.ID, p.BusinessID, p.OwnerID, p.Name, p.Description,
		p.CategoryID, p.SubCategoryID, p.BrandID,
		p.Price, p.DiscountPercent, p.Currency, p.Quantity, p.SKU,
		p.Color, p.Size, p.Weight, p.ShippingFee, p.IsPublished,
	)
	if err != nil {
		return nil, fmt.Errorf("insert product: %w", err)
	}

	for i, imgURL := range p.ImageURLs {
		if imgURL == "" {
			continue
		}
		isPrimary := i == 0
		imgID := uuid.New().String()
		_, err = tx.Exec(ctx,
			`INSERT INTO business.product_images (id, product_id, url, sort_order, is_primary) VALUES ($1, $2, $3, $4, $5)`,
			imgID, p.ID, imgURL, i, isPrimary)
		if err != nil {
			return nil, fmt.Errorf("insert product image: %w", err)
		}
	}

	if err := tx.Commit(ctx); err != nil {
		return nil, fmt.Errorf("commit tx: %w", err)
	}

	return r.GetMarketplaceProduct(ctx, p.ID)
}

func (r *BusinessRepository) UpdateMarketplaceProduct(ctx context.Context, p *MarketplaceProduct) (*MarketplaceProduct, error) {
	tx, err := r.db.Begin(ctx)
	if err != nil {
		return nil, fmt.Errorf("begin tx: %w", err)
	}
	defer tx.Rollback(ctx)

	res, err := tx.Exec(ctx,
		`UPDATE business.products SET
			name = $2, description = $3, category_id = NULLIF($4,'')::uuid, sub_category_id = NULLIF($5,'')::uuid,
			brand_id = NULLIF($6,'')::uuid, price = $7, discount_percent = $8, currency = $9,
			quantity = $10, sku = $11, color = $12, size = $13, weight = $14, shipping_fee = $15,
			is_published = $16, updated_at = NOW()
		 WHERE id = $1 AND owner_id = $17`,
		p.ID, p.Name, p.Description, p.CategoryID, p.SubCategoryID, p.BrandID,
		p.Price, p.DiscountPercent, p.Currency, p.Quantity, p.SKU,
		p.Color, p.Size, p.Weight, p.ShippingFee, p.IsPublished, p.OwnerID,
	)
	if err != nil {
		return nil, fmt.Errorf("update product: %w", err)
	}
	if res.RowsAffected() == 0 {
		return nil, fmt.Errorf("product not found or unauthorized")
	}

	if len(p.ImageURLs) > 0 {
		_, _ = tx.Exec(ctx, `DELETE FROM business.product_images WHERE product_id = $1`, p.ID)
		for i, imgURL := range p.ImageURLs {
			if imgURL == "" {
				continue
			}
			isPrimary := i == 0
			imgID := uuid.New().String()
			_, _ = tx.Exec(ctx,
				`INSERT INTO business.product_images (id, product_id, url, sort_order, is_primary) VALUES ($1, $2, $3, $4, $5)`,
				imgID, p.ID, imgURL, i, isPrimary)
		}
	}

	if err := tx.Commit(ctx); err != nil {
		return nil, fmt.Errorf("commit tx: %w", err)
	}

	return r.GetMarketplaceProduct(ctx, p.ID)
}

func (r *BusinessRepository) DeleteMarketplaceProduct(ctx context.Context, id, ownerID string) error {
	res, err := r.db.Exec(ctx, `DELETE FROM business.products WHERE id = $1 AND owner_id = $2`, id, ownerID)
	if err != nil {
		return fmt.Errorf("delete product: %w", err)
	}
	if res.RowsAffected() == 0 {
		return fmt.Errorf("product not found or unauthorized")
	}
	return nil
}

func (r *BusinessRepository) GetMarketplaceProduct(ctx context.Context, id string) (*MarketplaceProduct, error) {
	p := &MarketplaceProduct{}
	var catID, subCatID, brandID *string

	err := r.db.QueryRow(ctx,
		`SELECT 
			p.id, p.business_id, p.owner_id, p.name, COALESCE(p.description, ''),
			p.category_id::text, p.sub_category_id::text, p.brand_id::text,
			p.price, p.discount_percent, p.currency, p.quantity, COALESCE(p.sku, ''),
			COALESCE(p.color, ''), COALESCE(p.size, ''), COALESCE(p.weight, 0), COALESCE(p.shipping_fee, 0),
			p.is_published, p.view_count, p.order_count, p.rating_avg, p.review_count,
			COALESCE(b.business_name, u.display_name), COALESCE(b.logo_url, u.avatar_url, ''), COALESCE(b.slug, ''),
			COALESCE(c.name, ''), COALESCE(br.name, ''), p.created_at
		 FROM business.products p
		 LEFT JOIN business.business_profiles b ON b.user_id = p.business_id
		 LEFT JOIN core.users u ON u.id = p.owner_id
		 LEFT JOIN business.categories c ON c.id = p.category_id
		 LEFT JOIN business.brands br ON br.id = p.brand_id
		 WHERE p.id = $1`, id).
		Scan(
			&p.ID, &p.BusinessID, &p.OwnerID, &p.Name, &p.Description,
			&catID, &subCatID, &brandID,
			&p.Price, &p.DiscountPercent, &p.Currency, &p.Quantity, &p.SKU,
			&p.Color, &p.Size, &p.Weight, &p.ShippingFee,
			&p.IsPublished, &p.ViewCount, &p.OrderCount, &p.RatingAvg, &p.ReviewCount,
			&p.SellerName, &p.SellerAvatar, &p.SellerSlug,
			&p.CategoryName, &p.BrandName, &p.CreatedAt,
		)
	if err != nil {
		return nil, fmt.Errorf("get product: %w", err)
	}

	if catID != nil {
		p.CategoryID = *catID
	}
	if subCatID != nil {
		p.SubCategoryID = *subCatID
	}
	if brandID != nil {
		p.BrandID = *brandID
	}

	// Fetch product images
	rows, err := r.db.Query(ctx, `SELECT url FROM business.product_images WHERE product_id = $1 ORDER BY sort_order ASC`, id)
	if err == nil {
		defer rows.Close()
		for rows.Next() {
			var imgURL string
			if err := rows.Scan(&imgURL); err == nil {
				p.ImageURLs = append(p.ImageURLs, imgURL)
			}
		}
	}

	return p, nil
}

func (r *BusinessRepository) ListBusinessProducts(ctx context.Context, businessID string, limit, offset int32) ([]*MarketplaceProduct, int32, error) {
	var total int32
	_ = r.db.QueryRow(ctx, `SELECT COUNT(*) FROM business.products WHERE business_id = $1`, businessID).Scan(&total)

	rows, err := r.db.Query(ctx,
		`SELECT 
			p.id, p.business_id, p.owner_id, p.name, COALESCE(p.description, ''),
			COALESCE(p.category_id::text, ''), COALESCE(p.sub_category_id::text, ''), COALESCE(p.brand_id::text, ''),
			p.price, p.discount_percent, p.currency, p.quantity, COALESCE(p.sku, ''),
			COALESCE(p.color, ''), COALESCE(p.size, ''), COALESCE(p.weight, 0), COALESCE(p.shipping_fee, 0),
			p.is_published, p.view_count, p.order_count, p.rating_avg, p.review_count,
			COALESCE(b.business_name, u.display_name), COALESCE(b.logo_url, u.avatar_url, ''), COALESCE(b.slug, ''),
			COALESCE(c.name, ''), COALESCE(br.name, ''), p.created_at
		 FROM business.products p
		 LEFT JOIN business.business_profiles b ON b.user_id = p.business_id
		 LEFT JOIN core.users u ON u.id = p.owner_id
		 LEFT JOIN business.categories c ON c.id = p.category_id
		 LEFT JOIN business.brands br ON br.id = p.brand_id
		 WHERE p.business_id = $1
		 ORDER BY p.created_at DESC
		 LIMIT $2 OFFSET $3`, businessID, limit, offset)
	if err != nil {
		return nil, 0, fmt.Errorf("list business products: %w", err)
	}
	defer rows.Close()

	var products []*MarketplaceProduct
	for rows.Next() {
		p := &MarketplaceProduct{}
		if err := rows.Scan(
			&p.ID, &p.BusinessID, &p.OwnerID, &p.Name, &p.Description,
			&p.CategoryID, &p.SubCategoryID, &p.BrandID,
			&p.Price, &p.DiscountPercent, &p.Currency, &p.Quantity, &p.SKU,
			&p.Color, &p.Size, &p.Weight, &p.ShippingFee,
			&p.IsPublished, &p.ViewCount, &p.OrderCount, &p.RatingAvg, &p.ReviewCount,
			&p.SellerName, &p.SellerAvatar, &p.SellerSlug,
			&p.CategoryName, &p.BrandName, &p.CreatedAt,
		); err == nil {
			products = append(products, p)
		}
	}

	// Fetch primary images
	for _, p := range products {
		var imgURL string
		err := r.db.QueryRow(ctx, `SELECT url FROM business.product_images WHERE product_id = $1 ORDER BY sort_order ASC LIMIT 1`, p.ID).Scan(&imgURL)
		if err == nil && imgURL != "" {
			p.ImageURLs = []string{imgURL}
		}
	}

	return products, total, nil
}

// ── Global Marketplace Feed (with Amazon/Shopify-style ranking score) ────────

func (r *BusinessRepository) ListMarketplaceProducts(ctx context.Context, categoryID, sortBy, search string, limit, offset int32) ([]*MarketplaceProduct, int32, error) {
	whereClause := "WHERE p.is_published = TRUE"
	args := []interface{}{}
	argIdx := 1

	if categoryID != "" {
		whereClause += fmt.Sprintf(" AND p.category_id = $%d", argIdx)
		args = append(args, categoryID)
		argIdx++
	}

	if search != "" {
		whereClause += fmt.Sprintf(" AND (p.name ILIKE $%d OR p.description ILIKE $%d)", argIdx, argIdx)
		args = append(args, "%"+search+"%")
		argIdx++
	}

	var countQuery = fmt.Sprintf("SELECT COUNT(*) FROM business.products p %s", whereClause)
	var total int32
	_ = r.db.QueryRow(ctx, countQuery, args...).Scan(&total)

	orderBy := "ORDER BY score DESC"
	switch sortBy {
	case "newest":
		orderBy = "ORDER BY p.created_at DESC"
	case "top_rated":
		orderBy = "ORDER BY p.rating_avg DESC, p.review_count DESC"
	case "price_low":
		orderBy = "ORDER BY p.price ASC"
	case "price_high":
		orderBy = "ORDER BY p.price DESC"
	case "best_selling":
		orderBy = "ORDER BY p.order_count DESC, score DESC"
	}

	query := fmt.Sprintf(`
		SELECT 
			p.id, p.business_id, p.owner_id, p.name, COALESCE(p.description, ''),
			COALESCE(p.category_id::text, ''), COALESCE(p.sub_category_id::text, ''), COALESCE(p.brand_id::text, ''),
			p.price, p.discount_percent, p.currency, p.quantity, COALESCE(p.sku, ''),
			COALESCE(p.color, ''), COALESCE(p.size, ''), COALESCE(p.weight, 0), COALESCE(p.shipping_fee, 0),
			p.is_published, p.view_count, p.order_count, p.rating_avg, p.review_count,
			COALESCE(b.business_name, u.display_name), COALESCE(b.logo_url, u.avatar_url, ''), COALESCE(b.slug, ''),
			COALESCE(c.name, ''), COALESCE(br.name, ''), p.created_at,
			(
				p.order_count * 3 +
				p.view_count * 0.1 +
				p.review_count * 2 +
				p.rating_avg * 10 +
				CASE WHEN p.created_at > NOW() - INTERVAL '7 days' THEN 20 ELSE 0 END
			) AS score
		FROM business.products p
		LEFT JOIN business.business_profiles b ON b.user_id = p.business_id
		LEFT JOIN core.users u ON u.id = p.owner_id
		LEFT JOIN business.categories c ON c.id = p.category_id
		LEFT JOIN business.brands br ON br.id = p.brand_id
		%s
		%s
		LIMIT $%d OFFSET $%d
	`, whereClause, orderBy, argIdx, argIdx+1)

	args = append(args, limit, offset)

	rows, err := r.db.Query(ctx, query, args...)
	if err != nil {
		return nil, 0, fmt.Errorf("list marketplace products: %w", err)
	}
	defer rows.Close()

	var products []*MarketplaceProduct
	for rows.Next() {
		p := &MarketplaceProduct{}
		var score float64
		if err := rows.Scan(
			&p.ID, &p.BusinessID, &p.OwnerID, &p.Name, &p.Description,
			&p.CategoryID, &p.SubCategoryID, &p.BrandID,
			&p.Price, &p.DiscountPercent, &p.Currency, &p.Quantity, &p.SKU,
			&p.Color, &p.Size, &p.Weight, &p.ShippingFee,
			&p.IsPublished, &p.ViewCount, &p.OrderCount, &p.RatingAvg, &p.ReviewCount,
			&p.SellerName, &p.SellerAvatar, &p.SellerSlug,
			&p.CategoryName, &p.BrandName, &p.CreatedAt, &score,
		); err == nil {
			products = append(products, p)
		}
	}

	// Fetch primary images for each product
	for _, p := range products {
		var imgURL string
		err := r.db.QueryRow(ctx, `SELECT url FROM business.product_images WHERE product_id = $1 ORDER BY sort_order ASC LIMIT 1`, p.ID).Scan(&imgURL)
		if err == nil && imgURL != "" {
			p.ImageURLs = []string{imgURL}
		}
	}

	return products, total, nil
}

// ── Categories ──────────────────────────────────────────────────────────────

func (r *BusinessRepository) ListCategories(ctx context.Context) ([]*Category, error) {
	rows, err := r.db.Query(ctx, `SELECT id, name, COALESCE(icon, '📦'), sort_order FROM business.categories ORDER BY sort_order ASC`)
	if err != nil {
		return nil, fmt.Errorf("list categories: %w", err)
	}
	defer rows.Close()

	var categories []*Category
	for rows.Next() {
		c := &Category{}
		if err := rows.Scan(&c.ID, &c.Name, &c.Icon, &c.SortOrder); err == nil {
			categories = append(categories, c)
		}
	}

	// Fetch subcategories
	for _, c := range categories {
		subRows, err := r.db.Query(ctx, `SELECT id, category_id, name, sort_order FROM business.sub_categories WHERE category_id = $1 ORDER BY sort_order ASC`, c.ID)
		if err == nil {
			for subRows.Next() {
				sc := &SubCategory{}
				if err := subRows.Scan(&sc.ID, &sc.CategoryID, &sc.Name, &sc.SortOrder); err == nil {
					c.SubCategories = append(c.SubCategories, sc)
				}
			}
			subRows.Close()
		}
	}

	return categories, nil
}

// ── Store View ──────────────────────────────────────────────────────────────

func (r *BusinessRepository) GetStore(ctx context.Context, slug, userID string) (*Store, error) {
	var profile *BusinessProfile
	var err error

	if slug != "" {
		profile, err = r.GetBusinessProfileBySlug(ctx, slug)
	} else if userID != "" {
		profile, err = r.GetBusinessProfile(ctx, userID)
	} else {
		return nil, fmt.Errorf("slug or user_id is required")
	}

	if err != nil {
		return nil, fmt.Errorf("store profile not found: %w", err)
	}

	products, total, err := r.ListBusinessProducts(ctx, profile.UserID, 100, 0)
	if err != nil {
		products = []*MarketplaceProduct{}
		total = 0
	}

	var avgRating float64
	var totalReviews int32
	_ = r.db.QueryRow(ctx,
		`SELECT COALESCE(AVG(p.rating_avg), 0), COALESCE(SUM(p.review_count), 0)
		 FROM business.products p WHERE p.business_id = $1`, profile.UserID).Scan(&avgRating, &totalReviews)

	return &Store{
		Profile:      profile,
		Products:     products,
		ProductCount: total,
		AvgRating:    avgRating,
		TotalReviews: totalReviews,
	}, nil
}

// ── Engagement ──────────────────────────────────────────────────────────────

func (r *BusinessRepository) TrackProductView(ctx context.Context, productID, userID string) error {
	_, _ = r.db.Exec(ctx, `INSERT INTO business.product_views (product_id, user_id) VALUES ($1, NULLIF($2,'')::uuid)`, productID, userID)
	_, _ = r.db.Exec(ctx, `UPDATE business.products SET view_count = view_count + 1 WHERE id = $1`, productID)
	return nil
}

func (r *BusinessRepository) CreateReview(ctx context.Context, productID, userID string, rating int32, comment string) (*Review, error) {
	id := uuid.New().String()
	now := time.Now()

	_, err := r.db.Exec(ctx,
		`INSERT INTO business.reviews (id, product_id, user_id, rating, comment)
		 VALUES ($1, $2, $3, $4, $5)
		 ON CONFLICT (product_id, user_id) DO UPDATE SET rating = $4, comment = $5, created_at = NOW()`,
		id, productID, userID, rating, comment,
	)
	if err != nil {
		return nil, fmt.Errorf("create review: %w", err)
	}

	// Update average rating and review count on product
	_, _ = r.db.Exec(ctx,
		`UPDATE business.products SET
			rating_avg = (SELECT COALESCE(AVG(rating), 0) FROM business.reviews WHERE product_id = $1),
			review_count = (SELECT COUNT(*) FROM business.reviews WHERE product_id = $1)
		 WHERE id = $1`, productID,
	)

	var userName, userAvatar string
	_ = r.db.QueryRow(ctx, `SELECT display_name, COALESCE(avatar_url, '') FROM core.users WHERE id = $1`, userID).Scan(&userName, &userAvatar)

	return &Review{
		ID:         id,
		ProductID:  productID,
		UserID:     userID,
		UserName:   userName,
		UserAvatar: userAvatar,
		Rating:     rating,
		Comment:    comment,
		CreatedAt:  now,
	}, nil
}

func (r *BusinessRepository) ListReviews(ctx context.Context, productID string, limit, offset int32) ([]*Review, int32, error) {
	var total int32
	_ = r.db.QueryRow(ctx, `SELECT COUNT(*) FROM business.reviews WHERE product_id = $1`, productID).Scan(&total)

	rows, err := r.db.Query(ctx,
		`SELECT r.id, r.product_id, r.user_id, COALESCE(u.display_name, 'Anonymous'), COALESCE(u.avatar_url, ''), r.rating, COALESCE(r.comment, ''), r.created_at
		 FROM business.reviews r
		 LEFT JOIN core.users u ON u.id = r.user_id
		 WHERE r.product_id = $1
		 ORDER BY r.created_at DESC
		 LIMIT $2 OFFSET $3`, productID, limit, offset)
	if err != nil {
		return nil, 0, fmt.Errorf("list reviews: %w", err)
	}
	defer rows.Close()

	var reviews []*Review
	for rows.Next() {
		rv := &Review{}
		if err := rows.Scan(&rv.ID, &rv.ProductID, &rv.UserID, &rv.UserName, &rv.UserAvatar, &rv.Rating, &rv.Comment, &rv.CreatedAt); err == nil {
			reviews = append(reviews, rv)
		}
	}
	return reviews, total, nil
}
