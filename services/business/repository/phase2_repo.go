package repository

import (
	"context"
	"encoding/json"
	"fmt"
	"math/rand"
	"strings"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"go.uber.org/zap"
)

type ProductVariant struct {
	ID             string    `json:"id"`
	ProductID      string    `json:"product_id"`
	SKU            string    `json:"sku"`
	Title          string    `json:"title"`
	AttributesJSON string    `json:"attributes_json"`
	PriceOverride  *float64  `json:"price_override,omitempty"`
	StockQuantity  int32     `json:"stock_quantity"`
	ImageURL       string    `json:"image_url"`
	IsActive       bool      `json:"is_active"`
	CreatedAt      time.Time `json:"created_at"`
	UpdatedAt      time.Time `json:"updated_at"`
}

type CartItem struct {
	ID                    string
	CartID                string
	ProductID             string
	VariantID             *string
	VariantTitle          *string
	Quantity              int32
	Product               *MarketplaceProduct
	LockedPrice           float64
	LockedDiscountPercent float64
	PriceLockedAt         time.Time
}

type Cart struct {
	ID       string
	UserID   string
	Items    []*CartItem
	Subtotal float64
}

type OrderItem struct {
	ID                    string
	OrderID               string
	ProductID             string
	ProductName           string
	ProductSKU            string
	ImageURL              string
	UnitPrice             float64
	Quantity              int32
	Subtotal              float64
	LockedPrice           float64
	LockedDiscountPercent float64
	PriceChanged          bool
}

type OrderFxQuote struct {
	TargetCurrency      string
	SourceCurrencies    []string
	BuyerTotalAmount    float64
	BuyerShippingFee    float64
	BuyerDiscountAmount float64
	BuyerGrandTotal     float64
	Rates               map[string]float64
	Provider            string
	AsOf                string
	Stale               bool
	LockedAt            string
}

type Order struct {
	ID                    string
	OrderNumber           string
	BuyerID               string
	BusinessID            string
	TotalAmount           float64
	ShippingFee           float64
	DiscountAmount        float64
	GrandTotal            float64
	CouponCode            string
	Status                string
	ShippingName          string
	ShippingPhone         string
	ShippingAddress       string
	ShippingCity          string
	ShippingState         string
	ShippingCountry       string
	PaymentMethod         string
	PaymentStatus         string
	Notes                 string
	BusinessName          string
	BusinessLogo          string
	BuyerName             string
	BuyerAvatar           string
	Items                 []*OrderItem
	CreatedAt             time.Time
	FxQuote               *OrderFxQuote
	TrackingNumber        string
	TrackingCarrier       string
	TrackingURL           string
	EstimatedDeliveryDate *time.Time
	ActualDeliveryDate    *time.Time
	ShippedAt             *time.Time
	DeliveryNotes         string
}

type Coupon struct {
	ID             string
	BusinessID     string
	Code           string
	DiscountType   string // "percentage" or "fixed"
	DiscountValue  float64
	MinSpend       float64
	MaxUses        int32
	UsedCount      int32
	ExpiresAt      *time.Time
	IsActive       bool
	CreatedAt      time.Time
	ProductID      string
	MaxUsesPerUser int32
	ProductName    string
}

// Refund represents a refund request
type Refund struct {
	ID              string
	OrderID         string
	RefundAmount    float64
	RefundReason    string
	RefundType      string // 'full', 'partial'
	Status          string // 'pending', 'approved', 'rejected', 'processed', 'failed'
	RequestedBy     string
	ProcessedBy     string
	ProcessedAt     *time.Time
	RejectionReason string
	RefundMethod    string // 'original', 'store_credit', 'bank_transfer'
	RefundReference string
	CreatedAt       time.Time
	UpdatedAt       time.Time
	Items           []*RefundItem
}

// RefundItem represents an item in a partial refund
type RefundItem struct {
	ID           string
	RefundID     string
	OrderItemID  string
	Quantity     int32
	RefundAmount float64
	Reason       string
}

// OrderModification represents a request to modify an order
type OrderModification struct {
	ID               string
	OrderID          string
	ModificationType string // 'shipping_address', 'items', 'quantity', 'cancel_item'
	OldValue         string // JSON string
	NewValue         string // JSON string
	Reason           string
	RequestedBy      string
	ApprovedBy       string
	Status           string // 'pending', 'approved', 'rejected'
	RejectionReason  string
	CreatedAt        time.Time
	UpdatedAt        time.Time
}

// ── Cart Repository ─────────────────────────────────────────────────────────

func (r *BusinessRepository) GetOrCreateCart(ctx context.Context, userID string) (string, error) {
	var cartID string
	err := r.db.QueryRow(ctx, `SELECT id FROM business.carts WHERE user_id = $1`, userID).Scan(&cartID)
	if err == nil {
		return cartID, nil
	}

	cartID = uuid.New().String()
	_, err = r.db.Exec(ctx, `INSERT INTO business.carts (id, user_id) VALUES ($1, $2) ON CONFLICT (user_id) DO NOTHING`, cartID, userID)
	if err != nil {
		return "", fmt.Errorf("create cart: %w", err)
	}

	_ = r.db.QueryRow(ctx, `SELECT id FROM business.carts WHERE user_id = $1`, userID).Scan(&cartID)
	return cartID, nil
}

func (r *BusinessRepository) AddToCart(ctx context.Context, userID, productID string, quantity int32, variantID ...string) (*Cart, error) {
	cartID, err := r.GetOrCreateCart(ctx, userID)
	if err != nil {
		return nil, err
	}

	// Fetch current product price and discount to lock them
	var price, discountPercent float64
	err = r.db.QueryRow(ctx,
		`SELECT price, COALESCE(discount_percent, 0) FROM business.products WHERE id = $1`,
		productID).Scan(&price, &discountPercent)
	if err != nil {
		return nil, fmt.Errorf("get product price for locking: %w", err)
	}

	// If a variant is specified, use its price override and validate stock
	var variantIDVal *string
	var variantTitle *string
	if len(variantID) > 0 && variantID[0] != "" {
		vid := variantID[0]
		var vTitle string
		var vPriceOverride *float64
		var vStock int32
		err = r.db.QueryRow(ctx,
			`SELECT title, price_override, stock_quantity FROM business.product_variants
			 WHERE id = $1 AND product_id = $2 AND is_active = true`,
			vid, productID).Scan(&vTitle, &vPriceOverride, &vStock)
		if err != nil {
			return nil, fmt.Errorf("variant not found or inactive: %w", err)
		}
		if vStock < quantity {
			return nil, fmt.Errorf("insufficient variant stock: %d available, %d requested", vStock, quantity)
		}
		if vPriceOverride != nil && *vPriceOverride > 0 {
			price = *vPriceOverride
		}
		variantIDVal = &vid
		variantTitle = &vTitle

		// Decrement variant stock
		_, _ = r.db.Exec(ctx,
			`UPDATE business.product_variants SET stock_quantity = stock_quantity - $1, updated_at = NOW() WHERE id = $2`,
			quantity, vid)
	}

	itemID := uuid.New().String()
	now := time.Now()

	// Insert with locked price or update quantity while preserving locked price
	_, err = r.db.Exec(ctx,
		`INSERT INTO business.cart_items (id, cart_id, product_id, quantity, locked_price, locked_discount_percent, price_locked_at, variant_id, variant_title)
		 VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9)
		 ON CONFLICT (cart_id, product_id) 
		 DO UPDATE SET 
		 	quantity = business.cart_items.quantity + EXCLUDED.quantity,
		 	locked_price = EXCLUDED.locked_price,
		 	locked_discount_percent = EXCLUDED.locked_discount_percent,
		 	price_locked_at = EXCLUDED.price_locked_at,
		 	variant_id = EXCLUDED.variant_id,
		 	variant_title = EXCLUDED.variant_title`,
		itemID, cartID, productID, quantity, price, discountPercent, now, variantIDVal, variantTitle,
	)
	if err != nil {
		return nil, fmt.Errorf("add to cart: %w", err)
	}

	return r.GetCart(ctx, userID)
}

func (r *BusinessRepository) GetCart(ctx context.Context, userID string) (*Cart, error) {
	cartID, err := r.GetOrCreateCart(ctx, userID)
	if err != nil {
		return nil, err
	}

	cart := &Cart{ID: cartID, UserID: userID, Items: []*CartItem{}}

	rows, err := r.db.Query(ctx,
		`SELECT ci.id, ci.cart_id, ci.product_id, ci.quantity, 
		        COALESCE(ci.locked_price, 0), COALESCE(ci.locked_discount_percent, 0), ci.price_locked_at
		 FROM business.cart_items ci
		 WHERE ci.cart_id = $1
		 ORDER BY ci.created_at DESC`, cartID)
	if err != nil {
		return cart, nil
	}
	defer rows.Close()

	for rows.Next() {
		item := &CartItem{}
		if err := rows.Scan(&item.ID, &item.CartID, &item.ProductID, &item.Quantity,
			&item.LockedPrice, &item.LockedDiscountPercent, &item.PriceLockedAt); err == nil {
			cart.Items = append(cart.Items, item)
		}
	}

	var subtotal float64
	for _, item := range cart.Items {
		prod, err := r.GetMarketplaceProduct(ctx, item.ProductID)
		if err == nil {
			item.Product = prod
			// Use locked price for cart subtotal calculation
			effectivePrice := item.LockedPrice
			if item.LockedDiscountPercent > 0 {
				effectivePrice = item.LockedPrice * (1.0 - (item.LockedDiscountPercent / 100.0))
			}
			subtotal += effectivePrice * float64(item.Quantity)
		}
	}
	cart.Subtotal = subtotal

	return cart, nil
}

func (r *BusinessRepository) UpdateCartItem(ctx context.Context, userID, cartItemID string, quantity int32) (*Cart, error) {
	if quantity <= 0 {
		return r.RemoveFromCart(ctx, userID, cartItemID)
	}

	// Validate available stock for item product/variant
	var productID string
	var variantID *string
	var productQty int32
	err := r.db.QueryRow(ctx,
		`SELECT ci.product_id, ci.variant_id, COALESCE(p.quantity, 0)
		 FROM business.cart_items ci
		 JOIN business.products p ON p.id = ci.product_id
		 WHERE ci.id = $1 AND ci.cart_id IN (SELECT id FROM business.carts WHERE user_id = $2)`,
		cartItemID, userID).Scan(&productID, &variantID, &productQty)
	if err != nil {
		return nil, fmt.Errorf("cart item not found: %w", err)
	}

	maxStock := productQty
	if variantID != nil && *variantID != "" {
		var vStock int32
		err = r.db.QueryRow(ctx,
			`SELECT stock_quantity FROM business.product_variants WHERE id = $1`,
			*variantID).Scan(&vStock)
		if err == nil {
			maxStock = vStock
		}
	}

	if quantity > maxStock {
		return nil, fmt.Errorf("cannot exceed available stock (%d in stock)", maxStock)
	}

	_, err = r.db.Exec(ctx,
		`UPDATE business.cart_items SET quantity = $1
		 WHERE id = $2 AND cart_id IN (SELECT id FROM business.carts WHERE user_id = $3)`,
		quantity, cartItemID, userID,
	)
	if err != nil {
		return nil, fmt.Errorf("update cart item: %w", err)
	}

	return r.GetCart(ctx, userID)
}

func (r *BusinessRepository) RemoveFromCart(ctx context.Context, userID, cartItemID string) (*Cart, error) {
	_, err := r.db.Exec(ctx,
		`DELETE FROM business.cart_items
		 WHERE id = $1 AND cart_id IN (SELECT id FROM business.carts WHERE user_id = $2)`,
		cartItemID, userID,
	)
	if err != nil {
		return nil, fmt.Errorf("remove from cart: %w", err)
	}

	return r.GetCart(ctx, userID)
}

func (r *BusinessRepository) ClearCart(ctx context.Context, userID string) error {
	_, err := r.db.Exec(ctx, `DELETE FROM business.cart_items WHERE cart_id IN (SELECT id FROM business.carts WHERE user_id = $1)`, userID)
	return err
}

// ── Orders Repository ───────────────────────────────────────────────────────

type SingleOrderInput struct {
	BusinessID  string
	CouponCode  string
	ShippingFee float64
	FxQuote     *OrderFxQuote
	Items       []struct {
		ProductID string
		Quantity  int32
	}
}

type CreateOrdersInput struct {
	BuyerID         string
	ShippingName    string
	ShippingPhone   string
	ShippingAddress string
	ShippingCity    string
	ShippingState   string
	ShippingCountry string
	PaymentMethod   string
	Notes           string
	Orders          []SingleOrderInput
}

func generateOrderNumber() string {
	const charset = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
	b := make([]byte, 8)
	for i := range b {
		b[i] = charset[rand.Intn(len(charset))]
	}
	return "ORD-" + string(b)
}

func (r *BusinessRepository) CreateOrders(ctx context.Context, input *CreateOrdersInput) ([]*Order, error) {
	tx, err := r.db.Begin(ctx)
	if err != nil {
		return nil, fmt.Errorf("begin tx: %w", err)
	}
	defer tx.Rollback(ctx)

	var createdOrders []*Order
	var allPriceChanges []PriceChangeInfo

	for _, oInput := range input.Orders {
		orderID := uuid.New().String()
		orderNum := generateOrderNumber()

		var totalAmount float64
		var orderItems []*OrderItem
		var priceChanges []PriceChangeInfo

		for _, itemInput := range oInput.Items {
			pID := strings.TrimSpace(itemInput.ProductID)
			if pID == "" {
				return nil, fmt.Errorf("invalid or missing product ID in order request")
			}

			var pName, pSKU, pCurr, actualBizID string
			var price, disc float64
			var stock int32

			err := tx.QueryRow(ctx,
				`SELECT name, COALESCE(sku, ''), price, discount_percent, currency, quantity, COALESCE(business_id, owner_id)
				 FROM business.products WHERE id = $1 FOR UPDATE`, pID).
				Scan(&pName, &pSKU, &price, &disc, &pCurr, &stock, &actualBizID)
			if err != nil {
				return nil, fmt.Errorf("product %s not found: %w", pID, err)
			}

			if oInput.BusinessID == "" || oInput.BusinessID == "00000000-0000-0000-0000-000000000000" {
				oInput.BusinessID = actualBizID
			}

			if stock < itemInput.Quantity {
				return nil, fmt.Errorf("insufficient stock for product %s (available: %d, requested: %d)", pName, stock, itemInput.Quantity)
			}

			// Primary image
			var imgURL string
			_ = tx.QueryRow(ctx, `SELECT url FROM business.product_images WHERE product_id = $1 ORDER BY sort_order ASC LIMIT 1`, itemInput.ProductID).Scan(&imgURL)

			// Get locked price from cart if available
			var lockedPrice, lockedDiscount float64
			err = tx.QueryRow(ctx,
				`SELECT COALESCE(locked_price, 0), COALESCE(locked_discount_percent, 0)
				 FROM business.cart_items ci
				 JOIN business.carts c ON c.id = ci.cart_id
				 WHERE c.user_id = $1 AND ci.product_id = $2`,
				input.BuyerID, itemInput.ProductID).Scan(&lockedPrice, &lockedDiscount)

			// If no locked price in cart, use current price
			if lockedPrice == 0 {
				lockedPrice = price
				lockedDiscount = disc
			}

			effectivePrice := price
			if disc > 0 {
				effectivePrice = price * (1.0 - (disc / 100.0))
			}

			lockedEffectivePrice := lockedPrice
			if lockedDiscount > 0 {
				lockedEffectivePrice = lockedPrice * (1.0 - (lockedDiscount / 100.0))
			}

			// Use locked price for order (honor the cart price)
			orderPrice := lockedEffectivePrice
			subtotal := orderPrice * float64(itemInput.Quantity)
			totalAmount += subtotal

			// Detect if price changed from locked price
			priceChanged := (effectivePrice != lockedEffectivePrice)

			// Collect price change information for notification
			if priceChanged {
				changePercent := ((effectivePrice - lockedEffectivePrice) / lockedEffectivePrice) * 100
				changeType := "increase"
				if changePercent < 0 {
					changeType = "decrease"
				}

				priceChanges = append(priceChanges, PriceChangeInfo{
					ProductID:        itemInput.ProductID,
					ProductName:      pName,
					LockedPrice:      lockedPrice,
					CurrentPrice:     price,
					LockedDiscount:   lockedDiscount,
					CurrentDiscount:  disc,
					LockedEffective:  lockedEffectivePrice,
					CurrentEffective: effectivePrice,
					ChangePercent:    changePercent,
					ChangeType:       changeType,
				})
			}

			// Deduct stock & increment order count
			_, err = tx.Exec(ctx, `UPDATE business.products SET quantity = quantity - $1, order_count = order_count + $1 WHERE id = $2`, itemInput.Quantity, itemInput.ProductID)
			if err != nil {
				return nil, fmt.Errorf("update stock for product %s: %w", itemInput.ProductID, err)
			}

			itemRecord := &OrderItem{
				ID:                    uuid.New().String(),
				OrderID:               orderID,
				ProductID:             itemInput.ProductID,
				ProductName:           pName,
				ProductSKU:            pSKU,
				ImageURL:              imgURL,
				UnitPrice:             orderPrice,
				Quantity:              itemInput.Quantity,
				Subtotal:              subtotal,
				LockedPrice:           lockedPrice,
				LockedDiscountPercent: lockedDiscount,
				PriceChanged:          priceChanged,
			}
			orderItems = append(orderItems, itemRecord)
		}

		// Calculate discount from coupon if provided
		var discountAmount float64
		if oInput.CouponCode != "" {
			var cType string
			var cVal, cMin float64
			var cMax, cUsed int32
			err := tx.QueryRow(ctx,
				`SELECT discount_type, discount_value, min_spend, max_uses, used_count
				 FROM business.coupons
				 WHERE business_id = $1 AND LOWER(code) = LOWER($2) AND is_active = TRUE`,
				oInput.BusinessID, strings.TrimSpace(oInput.CouponCode)).
				Scan(&cType, &cVal, &cMin, &cMax, &cUsed)
			if err == nil {
				if totalAmount >= cMin && (cMax == 0 || cUsed < cMax) {
					if cType == "percentage" {
						discountAmount = totalAmount * (cVal / 100.0)
					} else {
						discountAmount = cVal
					}
					if discountAmount > totalAmount {
						discountAmount = totalAmount
					}
					// Increment coupon used count
					_, _ = tx.Exec(ctx, `UPDATE business.coupons SET used_count = used_count + 1 WHERE business_id = $1 AND LOWER(code) = LOWER($2)`, oInput.BusinessID, strings.TrimSpace(oInput.CouponCode))
				}
			}
		}

		grandTotal := totalAmount + oInput.ShippingFee - discountAmount
		if grandTotal < 0 {
			grandTotal = 0
		}

		now := time.Now()
		var fxQuoteStr *string
		if oInput.FxQuote != nil {
			b, _ := json.Marshal(oInput.FxQuote)
			s := string(b)
			fxQuoteStr = &s
		}
		_, err = tx.Exec(ctx,
			`INSERT INTO business.orders (
				id, order_number, buyer_id, business_id,
				total_amount, shipping_fee, discount_amount, grand_total, coupon_code,
				status, shipping_name, shipping_phone, shipping_address, shipping_city,
				shipping_state, shipping_country, payment_method, payment_status, notes, created_at, updated_at, fx_quote
			) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, 'paid', $10, $11, $12, $13, $14, $15, $16, 'paid', $17, $18, $18, $19)`,
			orderID, orderNum, input.BuyerID, oInput.BusinessID,
			totalAmount, oInput.ShippingFee, discountAmount, grandTotal, oInput.CouponCode,
			input.ShippingName, input.ShippingPhone, input.ShippingAddress, input.ShippingCity,
			input.ShippingState, input.ShippingCountry, input.PaymentMethod, input.Notes, now, fxQuoteStr,
		)
		if err != nil {
			return nil, fmt.Errorf("insert order: %w", err)
		}

		for _, item := range orderItems {
			_, err = tx.Exec(ctx,
				`INSERT INTO business.order_items (id, order_id, product_id, product_name, product_sku, image_url, unit_price, quantity, subtotal, locked_price, locked_discount_percent, price_changed)
				 VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12)`,
				item.ID, item.OrderID, item.ProductID, item.ProductName, item.ProductSKU, item.ImageURL, item.UnitPrice, item.Quantity, item.Subtotal, item.LockedPrice, item.LockedDiscountPercent, item.PriceChanged,
			)
			if err != nil {
				return nil, fmt.Errorf("insert order item: %w", err)
			}
		}

		ord := &Order{
			ID:              orderID,
			OrderNumber:     orderNum,
			BuyerID:         input.BuyerID,
			BusinessID:      oInput.BusinessID,
			TotalAmount:     totalAmount,
			ShippingFee:     oInput.ShippingFee,
			DiscountAmount:  discountAmount,
			GrandTotal:      grandTotal,
			CouponCode:      oInput.CouponCode,
			Status:          "paid",
			ShippingName:    input.ShippingName,
			ShippingPhone:   input.ShippingPhone,
			ShippingAddress: input.ShippingAddress,
			ShippingCity:    input.ShippingCity,
			ShippingState:   input.ShippingState,
			ShippingCountry: input.ShippingCountry,
			PaymentMethod:   input.PaymentMethod,
			PaymentStatus:   "paid",
			Notes:           input.Notes,
			Items:           orderItems,
			CreatedAt:       now,
			FxQuote:         oInput.FxQuote,
		}
		createdOrders = append(createdOrders, ord)

		// Collect price changes from this order
		allPriceChanges = append(allPriceChanges, priceChanges...)
	}

	if err := tx.Commit(ctx); err != nil {
		return nil, fmt.Errorf("commit tx: %w", err)
	}

	// Send price change notifications if any prices changed
	if len(allPriceChanges) > 0 && r.notificationService != nil {
		go func() {
			ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
			defer cancel()
			if err := r.notificationService.SendPriceChangeNotification(ctx, input.BuyerID, allPriceChanges); err != nil {
				if r.logger != nil {
					r.logger.Error("Failed to send price change notification",
						zap.String("user_id", input.BuyerID),
						zap.Int("changes", len(allPriceChanges)),
						zap.Error(err))
				}
			}
		}()
	}

	// Clear buyer cart after successful order creation
	_ = r.ClearCart(ctx, input.BuyerID)

	return createdOrders, nil
}

func (r *BusinessRepository) GetOrder(ctx context.Context, orderID, userID string) (*Order, error) {
	o := &Order{}
	err := r.db.QueryRow(ctx,
		`SELECT 
			o.id, o.order_number, o.buyer_id, o.business_id,
			o.total_amount, o.shipping_fee, o.discount_amount, o.grand_total, COALESCE(o.coupon_code, ''),
			o.status, o.shipping_name, o.shipping_phone, o.shipping_address,
			COALESCE(o.shipping_city, ''), COALESCE(o.shipping_state, ''), COALESCE(o.shipping_country, ''),
			COALESCE(o.payment_method, 'card'), COALESCE(o.payment_status, 'paid'), COALESCE(o.notes, ''),
			COALESCE(b.business_name, ''), COALESCE(b.logo_url, ''),
			COALESCE(u.display_name, ''), COALESCE(u.avatar_url, ''), o.created_at, o.fx_quote,
			COALESCE(o.tracking_number, ''), COALESCE(o.tracking_carrier, ''), COALESCE(o.tracking_url, ''),
			o.estimated_delivery_date, o.actual_delivery_date, o.shipped_at, COALESCE(o.delivery_notes, '')
		 FROM business.orders o
		 LEFT JOIN business.business_profiles b ON b.user_id = o.business_id
		 LEFT JOIN core.users u ON u.id = o.buyer_id
		 WHERE o.id = $1 AND (o.buyer_id = $2 OR o.business_id = $2)`, orderID, userID).
		Scan(
			&o.ID, &o.OrderNumber, &o.BuyerID, &o.BusinessID,
			&o.TotalAmount, &o.ShippingFee, &o.DiscountAmount, &o.GrandTotal, &o.CouponCode,
			&o.Status, &o.ShippingName, &o.ShippingPhone, &o.ShippingAddress,
			&o.ShippingCity, &o.ShippingState, &o.ShippingCountry,
			&o.PaymentMethod, &o.PaymentStatus, &o.Notes,
			&o.BusinessName, &o.BusinessLogo,
			&o.BuyerName, &o.BuyerAvatar, &o.CreatedAt, &o.FxQuote,
			&o.TrackingNumber, &o.TrackingCarrier, &o.TrackingURL,
			&o.EstimatedDeliveryDate, &o.ActualDeliveryDate, &o.ShippedAt, &o.DeliveryNotes,
		)
	if err != nil {
		return nil, fmt.Errorf("get order: %w", err)
	}

	rows, err := r.db.Query(ctx,
		`SELECT id, order_id, COALESCE(product_id::text, ''), product_name, COALESCE(product_sku, ''), COALESCE(image_url, ''), unit_price, quantity, subtotal, 
		        COALESCE(locked_price, 0), COALESCE(locked_discount_percent, 0), COALESCE(price_changed, FALSE)
		 FROM business.order_items WHERE order_id = $1`, orderID)
	if err == nil {
		defer rows.Close()
		for rows.Next() {
			item := &OrderItem{}
			if err := rows.Scan(&item.ID, &item.OrderID, &item.ProductID, &item.ProductName, &item.ProductSKU, &item.ImageURL, &item.UnitPrice, &item.Quantity, &item.Subtotal, &item.LockedPrice, &item.LockedDiscountPercent, &item.PriceChanged); err == nil {
				o.Items = append(o.Items, item)
			}
		}
	}

	return o, nil
}

func (r *BusinessRepository) ListBuyerOrders(ctx context.Context, buyerID string, limit, offset int32) ([]*Order, int32, error) {
	var total int32
	_ = r.db.QueryRow(ctx, `SELECT COUNT(*) FROM business.orders WHERE buyer_id = $1`, buyerID).Scan(&total)

	rows, err := r.db.Query(ctx,
		`SELECT 
			o.id, o.order_number, o.buyer_id, o.business_id,
			o.total_amount, o.shipping_fee, o.discount_amount, o.grand_total, COALESCE(o.coupon_code, ''),
			o.status, o.shipping_name, o.shipping_phone, o.shipping_address,
			COALESCE(o.shipping_city, ''), COALESCE(o.shipping_state, ''), COALESCE(o.shipping_country, ''),
			COALESCE(o.payment_method, 'card'), COALESCE(o.payment_status, 'paid'), COALESCE(o.notes, ''),
			COALESCE(b.business_name, ''), COALESCE(b.logo_url, ''), o.created_at, o.fx_quote
		 FROM business.orders o
		 LEFT JOIN business.business_profiles b ON b.user_id = o.business_id
		 WHERE o.buyer_id = $1
		 ORDER BY o.created_at DESC
		 LIMIT $2 OFFSET $3`, buyerID, limit, offset)
	if err != nil {
		return nil, 0, fmt.Errorf("list buyer orders: %w", err)
	}
	defer rows.Close()

	var orders []*Order
	for rows.Next() {
		o := &Order{}
		var fxBytes []byte
		if err := rows.Scan(
			&o.ID, &o.OrderNumber, &o.BuyerID, &o.BusinessID,
			&o.TotalAmount, &o.ShippingFee, &o.DiscountAmount, &o.GrandTotal, &o.CouponCode,
			&o.Status, &o.ShippingName, &o.ShippingPhone, &o.ShippingAddress,
			&o.ShippingCity, &o.ShippingState, &o.ShippingCountry,
			&o.PaymentMethod, &o.PaymentStatus, &o.Notes,
			&o.BusinessName, &o.BusinessLogo, &o.CreatedAt, &fxBytes,
		); err == nil {
			if len(fxBytes) > 0 {
				var quote OrderFxQuote
				if err := json.Unmarshal(fxBytes, &quote); err == nil {
					o.FxQuote = &quote
				}
			}
			orders = append(orders, o)
		}
	}

	for _, o := range orders {
		itemRows, err := r.db.Query(ctx,
			`SELECT id, order_id, COALESCE(product_id::text, ''), product_name, COALESCE(product_sku, ''), COALESCE(image_url, ''), unit_price, quantity, subtotal,
			        COALESCE(locked_price, 0), COALESCE(locked_discount_percent, 0), COALESCE(price_changed, FALSE)
			 FROM business.order_items WHERE order_id = $1`, o.ID)
		if err == nil {
			for itemRows.Next() {
				item := &OrderItem{}
				if err := itemRows.Scan(&item.ID, &item.OrderID, &item.ProductID, &item.ProductName, &item.ProductSKU, &item.ImageURL, &item.UnitPrice, &item.Quantity, &item.Subtotal, &item.LockedPrice, &item.LockedDiscountPercent, &item.PriceChanged); err == nil {
					o.Items = append(o.Items, item)
				}
			}
			itemRows.Close()
		}
	}

	return orders, total, nil
}

func (r *BusinessRepository) ListSellerOrders(ctx context.Context, businessID, status string, limit, offset int32) ([]*Order, int32, error) {
	whereClause := "WHERE o.business_id = $1"
	args := []interface{}{businessID}
	argIdx := 2

	if status != "" {
		whereClause += fmt.Sprintf(" AND o.status = $%d", argIdx)
		args = append(args, status)
		argIdx++
	}

	var total int32
	_ = r.db.QueryRow(ctx, fmt.Sprintf("SELECT COUNT(*) FROM business.orders o %s", whereClause), args...).Scan(&total)

	query := fmt.Sprintf(`
		SELECT 
			o.id, o.order_number, o.buyer_id, o.business_id,
			o.total_amount, o.shipping_fee, o.discount_amount, o.grand_total, COALESCE(o.coupon_code, ''),
			o.status, o.shipping_name, o.shipping_phone, o.shipping_address,
			COALESCE(o.shipping_city, ''), COALESCE(o.shipping_state, ''), COALESCE(o.shipping_country, ''),
			COALESCE(o.payment_method, 'card'), COALESCE(o.payment_status, 'paid'), COALESCE(o.notes, ''),
			COALESCE(u.display_name, ''), COALESCE(u.avatar_url, ''), o.created_at, o.fx_quote,
			COALESCE(o.tracking_number, ''), COALESCE(o.tracking_carrier, ''), COALESCE(o.tracking_url, ''),
			o.estimated_delivery_date, o.actual_delivery_date, o.shipped_at, COALESCE(o.delivery_notes, '')
		FROM business.orders o
		LEFT JOIN core.users u ON u.id = o.buyer_id
		%s
		ORDER BY o.created_at DESC
		LIMIT $%d OFFSET $%d
	`, whereClause, argIdx, argIdx+1)

	args = append(args, limit, offset)

	rows, err := r.db.Query(ctx, query, args...)
	if err != nil {
		return nil, 0, fmt.Errorf("list seller orders: %w", err)
	}
	defer rows.Close()

	var orders []*Order
	for rows.Next() {
		o := &Order{}
		var fxBytes []byte
		if err := rows.Scan(
			&o.ID, &o.OrderNumber, &o.BuyerID, &o.BusinessID,
			&o.TotalAmount, &o.ShippingFee, &o.DiscountAmount, &o.GrandTotal, &o.CouponCode,
			&o.Status, &o.ShippingName, &o.ShippingPhone, &o.ShippingAddress,
			&o.ShippingCity, &o.ShippingState, &o.ShippingCountry,
			&o.PaymentMethod, &o.PaymentStatus, &o.Notes,
			&o.BuyerName, &o.BuyerAvatar, &o.CreatedAt, &fxBytes,
			&o.TrackingNumber, &o.TrackingCarrier, &o.TrackingURL,
			&o.EstimatedDeliveryDate, &o.ActualDeliveryDate, &o.ShippedAt, &o.DeliveryNotes,
		); err == nil {
			if len(fxBytes) > 0 {
				var quote OrderFxQuote
				if err := json.Unmarshal(fxBytes, &quote); err == nil {
					o.FxQuote = &quote
				}
			}
			orders = append(orders, o)
		}
	}

	for _, o := range orders {
		itemRows, err := r.db.Query(ctx,
			`SELECT id, order_id, COALESCE(product_id::text, ''), product_name, COALESCE(product_sku, ''), COALESCE(image_url, ''), unit_price, quantity, subtotal,
			        COALESCE(locked_price, 0), COALESCE(locked_discount_percent, 0), COALESCE(price_changed, FALSE)
			 FROM business.order_items WHERE order_id = $1`, o.ID)
		if err == nil {
			for itemRows.Next() {
				item := &OrderItem{}
				if err := itemRows.Scan(&item.ID, &item.OrderID, &item.ProductID, &item.ProductName, &item.ProductSKU, &item.ImageURL, &item.UnitPrice, &item.Quantity, &item.Subtotal, &item.LockedPrice, &item.LockedDiscountPercent, &item.PriceChanged); err == nil {
					o.Items = append(o.Items, item)
				}
			}
			itemRows.Close()
		}
	}

	return orders, total, nil
}

func (r *BusinessRepository) UpdateOrderStatus(ctx context.Context, orderID, businessID, newStatus string) (*Order, error) {
	// Validate status transition using state machine
	currentStatus, err := r.getCurrentOrderStatus(ctx, orderID)
	if err != nil {
		return nil, fmt.Errorf("get current status: %w", err)
	}

	// Check if transition is valid
	if !CanTransitionTo(OrderStatus(currentStatus), OrderStatus(newStatus)) {
		return nil, fmt.Errorf("invalid status transition from %s to %s", currentStatus, newStatus)
	}

	// Update order status
	res, err := r.db.Exec(ctx,
		`UPDATE business.orders SET status = $1, updated_at = NOW()
		 WHERE id = $2 AND business_id = $3`, newStatus, orderID, businessID)
	if err != nil {
		return nil, fmt.Errorf("update order status: %w", err)
	}
	if res.RowsAffected() == 0 {
		return nil, fmt.Errorf("order not found or unauthorized")
	}

	// Record status change in history
	changeID := uuid.New().String()
	_, err = r.db.Exec(ctx,
		`INSERT INTO business.order_status_history (id, order_id, from_status, to_status, created_at)
		 VALUES ($1, $2, $3, $4, NOW())`,
		changeID, orderID, currentStatus, newStatus)
	if err != nil {
		// Log error but don't fail the status update
		if r.logger != nil {
			r.logger.Warn("Failed to record status history", zap.Error(err))
		}
	}

	return r.GetOrder(ctx, orderID, businessID)
}

// getCurrentOrderStatus retrieves the current status of an order
func (r *BusinessRepository) getCurrentOrderStatus(ctx context.Context, orderID string) (string, error) {
	var status string
	err := r.db.QueryRow(ctx, `SELECT status FROM business.orders WHERE id = $1`, orderID).Scan(&status)
	if err != nil {
		return "", err
	}
	return status, nil
}

// TransitionOrderStatusWithReason updates order status with reason and changed_by tracking
func (r *BusinessRepository) TransitionOrderStatusWithReason(ctx context.Context, orderID, userID, newStatus, reason string) (*Order, error) {
	// Get current status
	currentStatus, err := r.getCurrentOrderStatus(ctx, orderID)
	if err != nil {
		return nil, fmt.Errorf("get current status: %w", err)
	}

	// Validate transition
	if !CanTransitionTo(OrderStatus(currentStatus), OrderStatus(newStatus)) {
		return nil, fmt.Errorf("invalid status transition from %s to %s", currentStatus, newStatus)
	}

	// Update order status
	res, err := r.db.Exec(ctx,
		`UPDATE business.orders SET status = $1, updated_at = NOW()
		 WHERE id = $2`, newStatus, orderID)
	if err != nil {
		return nil, fmt.Errorf("update order status: %w", err)
	}
	if res.RowsAffected() == 0 {
		return nil, fmt.Errorf("order not found")
	}

	// Record status change with reason
	changeID := uuid.New().String()
	_, err = r.db.Exec(ctx,
		`INSERT INTO business.order_status_history (id, order_id, from_status, to_status, changed_by, change_reason, created_at)
		 VALUES ($1, $2, $3, $4, $5, $6, NOW())`,
		changeID, orderID, currentStatus, newStatus, userID, reason)
	if err != nil {
		if r.logger != nil {
			r.logger.Warn("Failed to record status history", zap.Error(err))
		}
	}

	// Get updated order
	return r.GetOrder(ctx, orderID, userID)
}

// GetOrderStatusHistory retrieves the status change history for an order
func (r *BusinessRepository) GetOrderStatusHistory(ctx context.Context, orderID string) ([]OrderStatusChange, error) {
	rows, err := r.db.Query(ctx,
		`SELECT id, order_id, from_status, to_status, changed_by, change_reason, created_at
		 FROM business.order_status_history
		 WHERE order_id = $1
		 ORDER BY created_at ASC`, orderID)
	if err != nil {
		return nil, fmt.Errorf("get status history: %w", err)
	}
	defer rows.Close()

	var history []OrderStatusChange
	for rows.Next() {
		var change OrderStatusChange
		err := rows.Scan(&change.ID, &change.OrderID, &change.FromStatus, &change.ToStatus,
			&change.ChangedBy, &change.ChangeReason, &change.CreatedAt)
		if err == nil {
			history = append(history, change)
		}
	}

	return history, nil
}

// ── Refund Repository ───────────────────────────────────────────────────────

// CreateRefund creates a new refund request
func (r *BusinessRepository) CreateRefund(ctx context.Context, orderID, userID string, refundAmount float64, refundReason, refundType string, items []*RefundItem) (*Refund, error) {
	// Validate order exists and user is authorized
	var orderUserID string
	err := r.db.QueryRow(ctx, `SELECT buyer_id FROM business.orders WHERE id = $1`, orderID).Scan(&orderUserID)
	if err != nil {
		return nil, fmt.Errorf("order not found: %w", err)
	}
	if orderUserID != userID {
		return nil, fmt.Errorf("unauthorized: user is not the order buyer")
	}

	// Check if order can be refunded
	currentStatus, err := r.getCurrentOrderStatus(ctx, orderID)
	if err != nil {
		return nil, fmt.Errorf("get order status: %w", err)
	}
	if !CanRefund(OrderStatus(currentStatus)) {
		return nil, fmt.Errorf("order cannot be refunded from status %s", currentStatus)
	}

	tx, err := r.db.Begin(ctx)
	if err != nil {
		return nil, fmt.Errorf("begin tx: %w", err)
	}
	defer tx.Rollback(ctx)

	refundID := uuid.New().String()
	now := time.Now()

	// Create refund
	_, err = tx.Exec(ctx,
		`INSERT INTO business.refunds (id, order_id, refund_amount, refund_reason, refund_type, status, requested_by, created_at, updated_at)
		 VALUES ($1, $2, $3, $4, $5, 'pending', $6, $7, $8)`,
		refundID, orderID, refundAmount, refundReason, refundType, userID, now, now)
	if err != nil {
		return nil, fmt.Errorf("create refund: %w", err)
	}

	// Add refund items if partial refund
	if refundType == "partial" && len(items) > 0 {
		for _, item := range items {
			itemID := uuid.New().String()
			_, err = tx.Exec(ctx,
				`INSERT INTO business.refund_items (id, refund_id, order_item_id, quantity, refund_amount, reason)
				 VALUES ($1, $2, $3, $4, $5, $6)`,
				itemID, refundID, item.OrderItemID, item.Quantity, item.RefundAmount, item.Reason)
			if err != nil {
				return nil, fmt.Errorf("create refund item: %w", err)
			}
		}
	}

	if err := tx.Commit(ctx); err != nil {
		return nil, fmt.Errorf("commit tx: %w", err)
	}

	return r.GetRefund(ctx, refundID)
}

// GetRefund retrieves a refund by ID
func (r *BusinessRepository) GetRefund(ctx context.Context, refundID string) (*Refund, error) {
	refund := &Refund{}
	err := r.db.QueryRow(ctx,
		`SELECT id, order_id, refund_amount, refund_reason, refund_type, status, 
		        requested_by, processed_by, processed_at, rejection_reason, 
		        refund_method, refund_reference, created_at, updated_at
		 FROM business.refunds WHERE id = $1`, refundID).Scan(
		&refund.ID, &refund.OrderID, &refund.RefundAmount, &refund.RefundReason, &refund.RefundType,
		&refund.Status, &refund.RequestedBy, &refund.ProcessedBy, &refund.ProcessedAt,
		&refund.RejectionReason, &refund.RefundMethod, &refund.RefundReference,
		&refund.CreatedAt, &refund.UpdatedAt)
	if err != nil {
		return nil, err
	}

	// Get refund items if partial refund
	if refund.RefundType == "partial" {
		rows, err := r.db.Query(ctx,
			`SELECT id, refund_id, order_item_id, quantity, refund_amount, reason
			 FROM business.refund_items WHERE refund_id = $1`, refundID)
		if err == nil {
			defer rows.Close()
			for rows.Next() {
				item := &RefundItem{}
				if err := rows.Scan(&item.ID, &item.RefundID, &item.OrderItemID, &item.Quantity, &item.RefundAmount, &item.Reason); err == nil {
					refund.Items = append(refund.Items, item)
				}
			}
		}
	}

	return refund, nil
}

// ProcessRefund processes a refund (approve/reject/process)
func (r *BusinessRepository) ProcessRefund(ctx context.Context, refundID, userID, status, rejectionReason, refundMethod, refundReference string) (*Refund, error) {
	// Get current refund
	refund, err := r.GetRefund(ctx, refundID)
	if err != nil {
		return nil, fmt.Errorf("get refund: %w", err)
	}

	// Validate status transition
	validTransitions := map[string][]string{
		"pending":  {"approved", "rejected"},
		"approved": {"processed", "rejected"},
	}

	allowed, exists := validTransitions[refund.Status]
	if !exists {
		return nil, fmt.Errorf("refund cannot be processed from status %s", refund.Status)
	}

	valid := false
	for _, s := range allowed {
		if s == status {
			valid = true
			break
		}
	}
	if !valid {
		return nil, fmt.Errorf("invalid status transition from %s to %s", refund.Status, status)
	}

	tx, err := r.db.Begin(ctx)
	if err != nil {
		return nil, fmt.Errorf("begin tx: %w", err)
	}
	defer tx.Rollback(ctx)

	var processedAt *time.Time
	if status == "processed" {
		now := time.Now()
		processedAt = &now
	}

	// Update refund
	_, err = tx.Exec(ctx,
		`UPDATE business.refunds 
		 SET status = $1, processed_by = $2, processed_at = $3, rejection_reason = $4, 
		     refund_method = $5, refund_reference = $6, updated_at = NOW()
		 WHERE id = $7`,
		status, userID, processedAt, rejectionReason, refundMethod, refundReference, refundID)
	if err != nil {
		return nil, fmt.Errorf("update refund: %w", err)
	}

	// If refund is processed, update order status to refunded
	if status == "processed" {
		_, err = tx.Exec(ctx, `UPDATE business.orders SET status = 'refunded', updated_at = NOW() WHERE id = $1`, refund.OrderID)
		if err != nil {
			return nil, fmt.Errorf("update order status: %w", err)
		}
	}

	if err := tx.Commit(ctx); err != nil {
		return nil, fmt.Errorf("commit tx: %w", err)
	}

	return r.GetRefund(ctx, refundID)
}

// ListRefunds retrieves refunds with optional filtering
func (r *BusinessRepository) ListRefunds(ctx context.Context, orderID, userID, status string, limit, offset int32) ([]*Refund, int32, error) {
	var conditions []string
	var args []interface{}
	argIndex := 1

	if orderID != "" {
		conditions = append(conditions, fmt.Sprintf("order_id = $%d", argIndex))
		args = append(args, orderID)
		argIndex++
	}
	if userID != "" {
		conditions = append(conditions, fmt.Sprintf("requested_by = $%d", argIndex))
		args = append(args, userID)
		argIndex++
	}
	if status != "" {
		conditions = append(conditions, fmt.Sprintf("status = $%d", argIndex))
		args = append(args, status)
		argIndex++
	}

	whereClause := ""
	if len(conditions) > 0 {
		whereClause = "WHERE " + strings.Join(conditions, " AND ")
	}

	// Get total count
	var total int32
	countQuery := fmt.Sprintf("SELECT COUNT(*) FROM business.refunds %s", whereClause)
	err := r.db.QueryRow(ctx, countQuery, args...).Scan(&total)
	if err != nil {
		return nil, 0, fmt.Errorf("count refunds: %w", err)
	}

	// Get refunds with pagination
	query := fmt.Sprintf(`SELECT id, order_id, refund_amount, refund_reason, refund_type, status, 
		requested_by, processed_by, processed_at, rejection_reason, 
		refund_method, refund_reference, created_at, updated_at
		FROM business.refunds %s ORDER BY created_at DESC LIMIT $%d OFFSET $%d`,
		whereClause, argIndex, argIndex+1)
	args = append(args, limit, offset)

	rows, err := r.db.Query(ctx, query, args...)
	if err != nil {
		return nil, 0, fmt.Errorf("list refunds: %w", err)
	}
	defer rows.Close()

	var refunds []*Refund
	for rows.Next() {
		refund := &Refund{}
		err := rows.Scan(&refund.ID, &refund.OrderID, &refund.RefundAmount, &refund.RefundReason, &refund.RefundType,
			&refund.Status, &refund.RequestedBy, &refund.ProcessedBy, &refund.ProcessedAt,
			&refund.RejectionReason, &refund.RefundMethod, &refund.RefundReference,
			&refund.CreatedAt, &refund.UpdatedAt)
		if err == nil {
			refunds = append(refunds, refund)
		}
	}

	return refunds, total, nil
}

// ── Order Modification Repository ─────────────────────────────────────────────

// CreateOrderModification creates a new order modification request
func (r *BusinessRepository) CreateOrderModification(ctx context.Context, orderID, modificationType, oldValue, newValue, reason, userID string) (*OrderModification, error) {
	// Validate order exists
	var orderStatus string
	err := r.db.QueryRow(ctx, `SELECT status FROM business.orders WHERE id = $1`, orderID).Scan(&orderStatus)
	if err != nil {
		return nil, fmt.Errorf("order not found: %w", err)
	}

	// Check if order can be modified
	if !CanModify(OrderStatus(orderStatus)) {
		return nil, fmt.Errorf("order cannot be modified from status %s", orderStatus)
	}

	modID := uuid.New().String()
	now := time.Now()

	_, err = r.db.Exec(ctx,
		`INSERT INTO business.order_modifications (id, order_id, modification_type, old_value, new_value, reason, requested_by, status, created_at, updated_at)
		 VALUES ($1, $2, $3, $4, $5, $6, $7, 'pending', $8, $9)`,
		modID, orderID, modificationType, oldValue, newValue, reason, userID, now, now)
	if err != nil {
		return nil, fmt.Errorf("create modification: %w", err)
	}

	return r.GetOrderModification(ctx, modID)
}

// GetOrderModification retrieves an order modification by ID
func (r *BusinessRepository) GetOrderModification(ctx context.Context, modID string) (*OrderModification, error) {
	mod := &OrderModification{}
	err := r.db.QueryRow(ctx,
		`SELECT id, order_id, modification_type, old_value, new_value, reason, 
		        requested_by, approved_by, status, rejection_reason, created_at, updated_at
		 FROM business.order_modifications WHERE id = $1`, modID).Scan(
		&mod.ID, &mod.OrderID, &mod.ModificationType, &mod.OldValue, &mod.NewValue, &mod.Reason,
		&mod.RequestedBy, &mod.ApprovedBy, &mod.Status, &mod.RejectionReason,
		&mod.CreatedAt, &mod.UpdatedAt)
	if err != nil {
		return nil, err
	}
	return mod, nil
}

// ProcessOrderModification processes an order modification (approve/reject)
func (r *BusinessRepository) ProcessOrderModification(ctx context.Context, modID, userID, status, rejectionReason string) (*OrderModification, error) {
	// Get current modification
	mod, err := r.GetOrderModification(ctx, modID)
	if err != nil {
		return nil, fmt.Errorf("get modification: %w", err)
	}

	// Validate status
	if mod.Status != "pending" {
		return nil, fmt.Errorf("modification has already been processed")
	}

	if status != "approved" && status != "rejected" {
		return nil, fmt.Errorf("invalid status: must be 'approved' or 'rejected'")
	}

	tx, err := r.db.Begin(ctx)
	if err != nil {
		return nil, fmt.Errorf("begin tx: %w", err)
	}
	defer tx.Rollback(ctx)

	// Update modification
	_, err = tx.Exec(ctx,
		`UPDATE business.order_modifications 
		 SET status = $1, approved_by = $2, rejection_reason = $3, updated_at = NOW()
		 WHERE id = $4`,
		status, userID, rejectionReason, modID)
	if err != nil {
		return nil, fmt.Errorf("update modification: %w", err)
	}

	// If approved, apply the modification
	if status == "approved" {
		err = r.applyOrderModification(ctx, tx, mod)
		if err != nil {
			return nil, fmt.Errorf("apply modification: %w", err)
		}
	}

	if err := tx.Commit(ctx); err != nil {
		return nil, fmt.Errorf("commit tx: %w", err)
	}

	return r.GetOrderModification(ctx, modID)
}

// applyOrderModification applies an approved modification to the order
func (r *BusinessRepository) applyOrderModification(ctx context.Context, tx interface{}, mod *OrderModification) error {
	switch mod.ModificationType {
	case "shipping_address":
		// Parse the new address from NewValue (expected JSON format)
		// Format: {"name":"...","phone":"...","address":"...","city":"...","state":"...","country":"..."}
		// For simplicity, we'll update individual fields based on the modification
		// In production, you'd want proper JSON parsing and validation

		// Update shipping address fields
		_, err := tx.(pgx.Tx).Exec(ctx,
			`UPDATE business.orders 
			 SET shipping_name = COALESCE($1, shipping_name),
			     shipping_phone = COALESCE($2, shipping_phone),
			     shipping_address = COALESCE($3, shipping_address),
			     shipping_city = COALESCE($4, shipping_city),
			     shipping_state = COALESCE($5, shipping_state),
			     shipping_country = COALESCE($6, shipping_country),
			     updated_at = NOW()
			 WHERE id = $7`,
			mod.NewValue, // In real implementation, parse JSON and extract fields
			mod.NewValue,
			mod.NewValue,
			mod.NewValue,
			mod.NewValue,
			mod.NewValue,
			mod.OrderID)
		if err != nil {
			return fmt.Errorf("update shipping address: %w", err)
		}

	case "quantity":
		// Update item quantity
		// NewValue format: "order_item_id:new_quantity"
		parts := strings.Split(mod.NewValue, ":")
		if len(parts) != 2 {
			return fmt.Errorf("invalid quantity format, expected order_item_id:new_quantity")
		}

		orderItemID := parts[0]
		var newQuantity int
		_, err := fmt.Sscanf(parts[1], "%d", &newQuantity)
		if err != nil {
			return fmt.Errorf("invalid quantity value: %w", err)
		}

		if newQuantity <= 0 {
			return fmt.Errorf("quantity must be greater than 0")
		}

		// Get current item details to recalculate subtotal
		var unitPrice float64
		err = tx.(pgx.Tx).QueryRow(ctx,
			`SELECT unit_price FROM business.order_items WHERE id = $1`,
			orderItemID).Scan(&unitPrice)
		if err != nil {
			return fmt.Errorf("get order item: %w", err)
		}

		newSubtotal := unitPrice * float64(newQuantity)

		// Update the item
		_, err = tx.(pgx.Tx).Exec(ctx,
			`UPDATE business.order_items 
			 SET quantity = $1, subtotal = $2
			 WHERE id = $3`,
			newQuantity, newSubtotal, orderItemID)
		if err != nil {
			return fmt.Errorf("update item quantity: %w", err)
		}

		// Recalculate order total
		err = r.recalculateOrderTotal(ctx, tx, mod.OrderID)
		if err != nil {
			return fmt.Errorf("recalculate order total: %w", err)
		}

	case "cancel_item":
		// Cancel a specific item by setting quantity to 0 or removing it
		// NewValue is the order_item_id
		orderItemID := mod.NewValue

		// Get the item subtotal before removal
		var subtotal float64
		err := tx.(pgx.Tx).QueryRow(ctx,
			`SELECT subtotal FROM business.order_items WHERE id = $1`,
			orderItemID).Scan(&subtotal)
		if err != nil {
			return fmt.Errorf("get order item: %w", err)
		}

		// Remove the item
		_, err = tx.(pgx.Tx).Exec(ctx,
			`DELETE FROM business.order_items WHERE id = $1`,
			orderItemID)
		if err != nil {
			return fmt.Errorf("delete order item: %w", err)
		}

		// Recalculate order total
		err = r.recalculateOrderTotal(ctx, tx, mod.OrderID)
		if err != nil {
			return fmt.Errorf("recalculate order total: %w", err)
		}

	case "add_item":
		// Add a new item to the order
		// NewValue format: "product_id:quantity"
		parts := strings.Split(mod.NewValue, ":")
		if len(parts) != 2 {
			return fmt.Errorf("invalid add_item format, expected product_id:quantity")
		}

		productID := parts[0]
		var quantity int
		_, err := fmt.Sscanf(parts[1], "%d", &quantity)
		if err != nil {
			return fmt.Errorf("invalid quantity value: %w", err)
		}

		if quantity <= 0 {
			return fmt.Errorf("quantity must be greater than 0")
		}

		// Get product details
		var productName, productSKU, imageURL string
		var price float64
		err = tx.(pgx.Tx).QueryRow(ctx,
			`SELECT name, sku, image_url, price 
			 FROM business.products 
			 WHERE id = $1`,
			productID).Scan(&productName, &productSKU, &imageURL, &price)
		if err != nil {
			return fmt.Errorf("get product details: %w", err)
		}

		subtotal := price * float64(quantity)

		// Add the item
		_, err = tx.(pgx.Tx).Exec(ctx,
			`INSERT INTO business.order_items 
			 (id, order_id, product_id, product_name, product_sku, image_url, unit_price, quantity, subtotal)
			 VALUES (gen_random_uuid(), $1, $2, $3, $4, $5, $6, $7, $8)`,
			mod.OrderID, productID, productName, productSKU, imageURL, price, quantity, subtotal)
		if err != nil {
			return fmt.Errorf("add order item: %w", err)
		}

		// Recalculate order total
		err = r.recalculateOrderTotal(ctx, tx, mod.OrderID)
		if err != nil {
			return fmt.Errorf("recalculate order total: %w", err)
		}

	default:
		return fmt.Errorf("unsupported modification type: %s", mod.ModificationType)
	}

	// Log the successful application
	if r.logger != nil {
		r.logger.Info("Successfully applied order modification",
			zap.String("modification_id", mod.ID),
			zap.String("type", mod.ModificationType),
			zap.String("order_id", mod.OrderID))
	}

	return nil
}

// recalculateOrderTotal recalculates the order's total amount based on current items
func (r *BusinessRepository) recalculateOrderTotal(ctx context.Context, tx interface{}, orderID string) error {
	var totalAmount float64
	err := tx.(pgx.Tx).QueryRow(ctx,
		`SELECT COALESCE(SUM(subtotal), 0) 
		 FROM business.order_items 
		 WHERE order_id = $1`,
		orderID).Scan(&totalAmount)
	if err != nil {
		return fmt.Errorf("calculate items total: %w", err)
	}

	// Get shipping fee and discount amount
	var shippingFee, discountAmount float64
	err = tx.(pgx.Tx).QueryRow(ctx,
		`SELECT COALESCE(shipping_fee, 0), COALESCE(discount_amount, 0) 
		 FROM business.orders 
		 WHERE id = $1`,
		orderID).Scan(&shippingFee, &discountAmount)
	if err != nil {
		return fmt.Errorf("get order fees: %w", err)
	}

	grandTotal := totalAmount + shippingFee - discountAmount

	// Update the order
	_, err = tx.(pgx.Tx).Exec(ctx,
		`UPDATE business.orders 
		 SET total_amount = $1, grand_total = $2, updated_at = NOW()
		 WHERE id = $3`,
		totalAmount, grandTotal, orderID)
	if err != nil {
		return fmt.Errorf("update order totals: %w", err)
	}

	return nil
}

// ListOrderModifications retrieves order modifications with optional filtering
func (r *BusinessRepository) ListOrderModifications(ctx context.Context, orderID, status string, limit, offset int32) ([]*OrderModification, int32, error) {
	var conditions []string
	var args []interface{}
	argIndex := 1

	if orderID != "" {
		conditions = append(conditions, fmt.Sprintf("order_id = $%d", argIndex))
		args = append(args, orderID)
		argIndex++
	}
	if status != "" {
		conditions = append(conditions, fmt.Sprintf("status = $%d", argIndex))
		args = append(args, status)
		argIndex++
	}

	whereClause := ""
	if len(conditions) > 0 {
		whereClause = "WHERE " + strings.Join(conditions, " AND ")
	}

	// Get total count
	var total int32
	countQuery := fmt.Sprintf("SELECT COUNT(*) FROM business.order_modifications %s", whereClause)
	err := r.db.QueryRow(ctx, countQuery, args...).Scan(&total)
	if err != nil {
		return nil, 0, fmt.Errorf("count modifications: %w", err)
	}

	// Get modifications with pagination
	query := fmt.Sprintf(`SELECT id, order_id, modification_type, old_value, new_value, reason, 
		requested_by, approved_by, status, rejection_reason, created_at, updated_at
		FROM business.order_modifications %s ORDER BY created_at DESC LIMIT $%d OFFSET $%d`,
		whereClause, argIndex, argIndex+1)
	args = append(args, limit, offset)

	rows, err := r.db.Query(ctx, query, args...)
	if err != nil {
		return nil, 0, fmt.Errorf("list modifications: %w", err)
	}
	defer rows.Close()

	var mods []*OrderModification
	for rows.Next() {
		mod := &OrderModification{}
		err := rows.Scan(&mod.ID, &mod.OrderID, &mod.ModificationType, &mod.OldValue, &mod.NewValue, &mod.Reason,
			&mod.RequestedBy, &mod.ApprovedBy, &mod.Status, &mod.RejectionReason,
			&mod.CreatedAt, &mod.UpdatedAt)
		if err == nil {
			mods = append(mods, mod)
		}
	}

	return mods, total, nil
}

func (r *BusinessRepository) UpdateOrderTracking(ctx context.Context, orderID, businessID, trackingNumber, trackingCarrier, trackingURL, deliveryNotes string, estimatedDeliveryDate *time.Time) (*Order, error) {
	// If status is being set to shipped, update shipped_at timestamp
	var shippedAt *time.Time
	if trackingNumber != "" {
		now := time.Now()
		shippedAt = &now
	}

	res, err := r.db.Exec(ctx,
		`UPDATE business.orders 
		 SET tracking_number = $1, 
		     tracking_carrier = $2, 
		     tracking_url = $3, 
		     estimated_delivery_date = $4,
		     delivery_notes = $5,
		     shipped_at = COALESCE($6, shipped_at),
		     updated_at = NOW()
		 WHERE id = $7 AND business_id = $8`,
		trackingNumber, trackingCarrier, trackingURL, estimatedDeliveryDate, deliveryNotes, shippedAt, orderID, businessID)
	if err != nil {
		return nil, fmt.Errorf("update order tracking: %w", err)
	}
	if res.RowsAffected() == 0 {
		return nil, fmt.Errorf("order not found or unauthorized")
	}

	return r.GetOrder(ctx, orderID, businessID)
}

// ── Coupons Repository ──────────────────────────────────────────────────────

func (r *BusinessRepository) CreateCoupon(ctx context.Context, c *Coupon) (*Coupon, error) {
	c.ID = uuid.New().String()
	c.Code = strings.ToUpper(strings.TrimSpace(c.Code))
	if c.DiscountType == "" {
		c.DiscountType = "percentage"
	}
	c.IsActive = true
	c.CreatedAt = time.Now()

	var prodID *string
	if c.ProductID != "" {
		p := c.ProductID
		prodID = &p
	}

	_, err := r.db.Exec(ctx,
		`INSERT INTO business.coupons (id, business_id, code, discount_type, discount_value, min_spend, max_uses, expires_at, is_active, product_id, max_uses_per_user)
		 VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11)
		 ON CONFLICT (business_id, code) DO UPDATE SET discount_type = $4, discount_value = $5, min_spend = $6, max_uses = $7, expires_at = $8, is_active = $9, product_id = $10, max_uses_per_user = $11`,
		c.ID, c.BusinessID, c.Code, c.DiscountType, c.DiscountValue, c.MinSpend, c.MaxUses, c.ExpiresAt, c.IsActive, prodID, c.MaxUsesPerUser,
	)
	if err != nil {
		return nil, fmt.Errorf("create coupon: %w", err)
	}

	return c, nil
}

func (r *BusinessRepository) ListBusinessCoupons(ctx context.Context, businessID string) ([]*Coupon, error) {
	rows, err := r.db.Query(ctx,
		`SELECT c.id, c.business_id, c.code, c.discount_type, c.discount_value, c.min_spend, c.max_uses, c.used_count, c.expires_at, c.is_active, c.created_at,
		        COALESCE(c.product_id::text, ''), COALESCE(c.max_uses_per_user, 0), COALESCE(p.name, 'All Store Products')
		 FROM business.coupons c
		 LEFT JOIN business.products p ON p.id = c.product_id
		 WHERE c.business_id = $1
		 ORDER BY c.created_at DESC`, businessID)
	if err != nil {
		return nil, fmt.Errorf("list coupons: %w", err)
	}
	defer rows.Close()

	var coupons []*Coupon
	for rows.Next() {
		c := &Coupon{}
		if err := rows.Scan(&c.ID, &c.BusinessID, &c.Code, &c.DiscountType, &c.DiscountValue, &c.MinSpend, &c.MaxUses, &c.UsedCount, &c.ExpiresAt, &c.IsActive, &c.CreatedAt, &c.ProductID, &c.MaxUsesPerUser, &c.ProductName); err == nil {
			coupons = append(coupons, c)
		}
	}
	return coupons, nil
}

func (r *BusinessRepository) ValidateCoupon(ctx context.Context, businessID, code string, subtotal float64, userID string, productIDs []string) (bool, string, float64, *Coupon, error) {
	c := &Coupon{}
	err := r.db.QueryRow(ctx,
		`SELECT c.id, c.business_id, c.code, c.discount_type, c.discount_value, c.min_spend, c.max_uses, c.used_count, c.expires_at, c.is_active, c.created_at,
		        COALESCE(c.product_id::text, ''), COALESCE(c.max_uses_per_user, 0), COALESCE(p.name, 'All Store Products')
		 FROM business.coupons c
		 LEFT JOIN business.products p ON p.id = c.product_id
		 WHERE c.business_id = $1 AND LOWER(c.code) = LOWER($2)`, businessID, strings.TrimSpace(code)).
		Scan(&c.ID, &c.BusinessID, &c.Code, &c.DiscountType, &c.DiscountValue, &c.MinSpend, &c.MaxUses, &c.UsedCount, &c.ExpiresAt, &c.IsActive, &c.CreatedAt, &c.ProductID, &c.MaxUsesPerUser, &c.ProductName)

	if err != nil {
		return false, "Invalid coupon code", 0, nil, nil
	}

	if !c.IsActive {
		return false, "Coupon is inactive", 0, nil, nil
	}

	if c.ExpiresAt != nil && c.ExpiresAt.Before(time.Now()) {
		return false, "Coupon has expired", 0, nil, nil
	}

	if c.MaxUses > 0 && c.UsedCount >= c.MaxUses {
		return false, "Coupon usage limit reached", 0, nil, nil
	}

	// Check per-user limit
	if c.MaxUsesPerUser > 0 && userID != "" {
		var userUsed int32
		_ = r.db.QueryRow(ctx, `SELECT COUNT(*)::int FROM business.orders WHERE buyer_id = $1 AND LOWER(coupon_code) = LOWER($2)`, userID, strings.TrimSpace(code)).Scan(&userUsed)
		if userUsed >= c.MaxUsesPerUser {
			return false, fmt.Sprintf("You have reached the usage limit (%d max) for this coupon", c.MaxUsesPerUser), 0, nil, nil
		}
	}

	// Check product specific applicability
	if c.ProductID != "" {
		hasTargetProduct := false
		for _, pid := range productIDs {
			if strings.EqualFold(pid, c.ProductID) {
				hasTargetProduct = true
				break
			}
		}
		if !hasTargetProduct && len(productIDs) > 0 {
			return false, fmt.Sprintf("Coupon is valid only for specific product (%s)", c.ProductName), 0, nil, nil
		}
	}

	if subtotal < c.MinSpend {
		return false, fmt.Sprintf("Minimum spend of $%.2f required for this coupon", c.MinSpend), 0, nil, nil
	}

	var discount float64
	if c.DiscountType == "percentage" {
		discount = subtotal * (c.DiscountValue / 100.0)
	} else {
		discount = c.DiscountValue
	}

	if discount > subtotal {
		discount = subtotal
	}

	return true, "Coupon applied successfully", discount, c, nil
}

// ── Wishlist Repository ─────────────────────────────────────────────────────

func (r *BusinessRepository) ToggleWishlist(ctx context.Context, userID, productID string) (bool, error) {
	var exists bool
	_ = r.db.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM business.wishlists WHERE user_id = $1 AND product_id = $2)`, userID, productID).Scan(&exists)

	if exists {
		_, err := r.db.Exec(ctx, `DELETE FROM business.wishlists WHERE user_id = $1 AND product_id = $2`, userID, productID)
		if err != nil {
			return false, fmt.Errorf("remove wishlist: %w", err)
		}
		return false, nil
	}

	_, err := r.db.Exec(ctx, `INSERT INTO business.wishlists (user_id, product_id) VALUES ($1, $2)`, userID, productID)
	if err != nil {
		return false, fmt.Errorf("add wishlist: %w", err)
	}
	return true, nil
}

func (r *BusinessRepository) GetWishlist(ctx context.Context, userID string) ([]*MarketplaceProduct, error) {
	rows, err := r.db.Query(ctx,
		`SELECT 
			p.id, p.business_id, p.owner_id, p.name, COALESCE(p.description, ''),
			COALESCE(p.category_id::text, ''), COALESCE(p.sub_category_id::text, ''), COALESCE(p.brand_id::text, ''),
			p.price, p.discount_percent, p.currency, p.quantity, COALESCE(p.sku, ''),
			COALESCE(p.color, ''), COALESCE(p.size, ''), COALESCE(p.weight, 0), COALESCE(p.shipping_fee, 0),
			p.is_published, p.view_count, p.order_count, p.rating_avg, p.review_count,
			COALESCE(b.business_name, u.display_name), COALESCE(b.logo_url, u.avatar_url, ''), COALESCE(b.slug, ''),
			COALESCE(c.name, ''), COALESCE(br.name, ''), p.created_at
		 FROM business.wishlists w
		 JOIN business.products p ON p.id = w.product_id
		 LEFT JOIN business.business_profiles b ON b.user_id = p.business_id
		 LEFT JOIN core.users u ON u.id = p.owner_id
		 LEFT JOIN business.categories c ON c.id = p.category_id
		 LEFT JOIN business.brands br ON br.id = p.brand_id
		 WHERE w.user_id = $1
		 ORDER BY w.created_at DESC`, userID)
	if err != nil {
		return nil, fmt.Errorf("get wishlist: %w", err)
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

	for _, p := range products {
		var imgURL string
		err := r.db.QueryRow(ctx, `SELECT url FROM business.product_images WHERE product_id = $1 ORDER BY sort_order ASC LIMIT 1`, p.ID).Scan(&imgURL)
		if err == nil && imgURL != "" {
			p.ImageURLs = []string{imgURL}
		}
	}

	return products, nil
}

// ── Product Q&A Repository ──────────────────────────────────────────────────

type ProductQuestion struct {
	ID         string  `json:"id"`
	ProductID  string  `json:"product_id"`
	UserID     string  `json:"user_id"`
	Question   string  `json:"question"`
	Answer     *string `json:"answer,omitempty"`
	AnsweredBy *string `json:"answered_by,omitempty"`
	UserName   string  `json:"user_name,omitempty"`
	AnswerName string  `json:"answer_name,omitempty"`
	CreatedAt  string  `json:"created_at"`
	UpdatedAt  string  `json:"updated_at"`
	IsFlagged  bool    `json:"is_flagged"`
	FlagReason *string `json:"flag_reason,omitempty"`
	Status     string  `json:"status"`
	FlagCount  int32   `json:"flag_count"`
}

func (r *BusinessRepository) AskProductQuestion(ctx context.Context, productID, userID, question string) (*ProductQuestion, error) {
	q := &ProductQuestion{}
	err := r.db.QueryRow(ctx,
		`INSERT INTO business.product_questions (product_id, user_id, question)
		 VALUES ($1, $2, $3)
		 RETURNING id, product_id, user_id, question, created_at, updated_at`,
		productID, userID, question,
	).Scan(&q.ID, &q.ProductID, &q.UserID, &q.Question, &q.CreatedAt, &q.UpdatedAt)
	if err != nil {
		return nil, fmt.Errorf("ask question: %w", err)
	}
	return q, nil
}

func (r *BusinessRepository) AnswerProductQuestion(ctx context.Context, questionID, answeredBy, answer string) error {
	_, err := r.db.Exec(ctx,
		`UPDATE business.product_questions
		 SET answer = $1, answered_by = $2, updated_at = NOW()
		 WHERE id = $3`,
		answer, answeredBy, questionID,
	)
	if err != nil {
		return fmt.Errorf("answer question: %w", err)
	}
	return nil
}

func (r *BusinessRepository) GetProductQuestions(ctx context.Context, productID string) ([]*ProductQuestion, error) {
	rows, err := r.db.Query(ctx,
		`SELECT pq.id, pq.product_id, pq.user_id, pq.question,
		        pq.answer, pq.answered_by,
		        COALESCE(u.display_name, 'Anonymous'), COALESCE(a.display_name, ''),
		        pq.created_at, pq.updated_at,
		        COALESCE(pq.is_flagged, FALSE), pq.flag_reason, COALESCE(pq.status, 'pending'), COALESCE(pq.flag_count, 0)
		 FROM business.product_questions pq
		 LEFT JOIN core.users u ON u.id = pq.user_id
		 LEFT JOIN core.users a ON a.id = pq.answered_by
		 WHERE pq.product_id = $1 AND (pq.status = 'approved' OR pq.status = 'pending')
		 ORDER BY pq.created_at DESC`, productID)
	if err != nil {
		return nil, fmt.Errorf("get questions: %w", err)
	}
	defer rows.Close()

	var questions []*ProductQuestion
	for rows.Next() {
		q := &ProductQuestion{}
		if err := rows.Scan(
			&q.ID, &q.ProductID, &q.UserID, &q.Question,
			&q.Answer, &q.AnsweredBy,
			&q.UserName, &q.AnswerName,
			&q.CreatedAt, &q.UpdatedAt,
			&q.IsFlagged, &q.FlagReason, &q.Status, &q.FlagCount,
		); err == nil {
			questions = append(questions, q)
		}
	}
	return questions, nil
}

func (r *BusinessRepository) FlagProductQuestion(ctx context.Context, questionID, userID, reason string) error {
	_, err := r.db.Exec(ctx,
		`UPDATE business.product_questions
		 SET is_flagged = TRUE, flag_reason = $1, flag_count = flag_count + 1
		 WHERE id = $2`,
		reason, questionID,
	)
	if err != nil {
		return fmt.Errorf("flag question: %w", err)
	}
	return nil
}

func (r *BusinessRepository) ModerateProductQuestion(ctx context.Context, questionID, businessID, action, reason string) error {
	var status string
	switch action {
	case "approve":
		status = "approved"
	case "reject":
		status = "rejected"
	case "delete":
		status = "deleted"
	default:
		return fmt.Errorf("invalid action: %s", action)
	}

	_, err := r.db.Exec(ctx,
		`UPDATE business.product_questions
		 SET status = $1, moderated_at = NOW(), moderated_by = $2
		 WHERE id = $3 AND product_id IN (SELECT id FROM business.products WHERE business_id = $4)`,
		status, businessID, questionID, businessID,
	)
	if err != nil {
		return fmt.Errorf("moderate question: %w", err)
	}
	return nil
}

// ── Product Variant CRUD ────────────────────────────────────────────────────

func (r *BusinessRepository) CreateProductVariant(ctx context.Context, v *ProductVariant) (*ProductVariant, error) {
	v.ID = uuid.New().String()
	v.CreatedAt = time.Now()
	v.UpdatedAt = v.CreatedAt

	_, err := r.db.Exec(ctx,
		`INSERT INTO business.product_variants (id, product_id, sku, title, attributes_json, price_override, stock_quantity, image_url, is_active, created_at, updated_at)
		 VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11)`,
		v.ID, v.ProductID, v.SKU, v.Title, v.AttributesJSON, v.PriceOverride, v.StockQuantity, v.ImageURL, v.IsActive, v.CreatedAt, v.UpdatedAt,
	)
	if err != nil {
		return nil, fmt.Errorf("create product variant: %w", err)
	}
	return v, nil
}

func (r *BusinessRepository) ListProductVariants(ctx context.Context, productID string) ([]*ProductVariant, error) {
	rows, err := r.db.Query(ctx,
		`SELECT id, product_id, sku, title, attributes_json, price_override, stock_quantity, image_url, is_active, created_at, updated_at
		 FROM business.product_variants
		 WHERE product_id = $1
		 ORDER BY created_at ASC`, productID)
	if err != nil {
		return nil, fmt.Errorf("list product variants: %w", err)
	}
	defer rows.Close()

	var variants []*ProductVariant
	for rows.Next() {
		v := &ProductVariant{}
		if err := rows.Scan(&v.ID, &v.ProductID, &v.SKU, &v.Title, &v.AttributesJSON, &v.PriceOverride, &v.StockQuantity, &v.ImageURL, &v.IsActive, &v.CreatedAt, &v.UpdatedAt); err != nil {
			r.logger.Warn("scan variant row", zap.Error(err))
			continue
		}
		variants = append(variants, v)
	}
	return variants, nil
}

func (r *BusinessRepository) UpdateProductVariant(ctx context.Context, v *ProductVariant) (*ProductVariant, error) {
	v.UpdatedAt = time.Now()
	_, err := r.db.Exec(ctx,
		`UPDATE business.product_variants
		 SET sku = $1, title = $2, attributes_json = $3, price_override = $4, stock_quantity = $5, image_url = $6, is_active = $7, updated_at = $8
		 WHERE id = $9 AND product_id = $10`,
		v.SKU, v.Title, v.AttributesJSON, v.PriceOverride, v.StockQuantity, v.ImageURL, v.IsActive, v.UpdatedAt, v.ID, v.ProductID,
	)
	if err != nil {
		return nil, fmt.Errorf("update product variant: %w", err)
	}
	return v, nil
}

func (r *BusinessRepository) DeleteProductVariant(ctx context.Context, variantID, productID string) error {
	_, err := r.db.Exec(ctx,
		`DELETE FROM business.product_variants WHERE id = $1 AND product_id = $2`,
		variantID, productID,
	)
	if err != nil {
		return fmt.Errorf("delete product variant: %w", err)
	}
	return nil
}

