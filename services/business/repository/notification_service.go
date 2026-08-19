package repository

import (
	"context"
	"fmt"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"
	"go.uber.org/zap"
)

// NotificationType represents different types of business notifications
type NotificationType string

const (
	NotificationPriceChange    NotificationType = "price_change"
	NotificationOrderCreated   NotificationType = "order_created"
	NotificationOrderShipped   NotificationType = "order_shipped"
	NotificationOrderDelivered NotificationType = "order_delivered"
	NotificationLowStock       NotificationType = "low_stock"
)

// NotificationPriority represents urgency level
type NotificationPriority string

const (
	PriorityLow    NotificationPriority = "low"
	PriorityMedium NotificationPriority = "medium"
	PriorityHigh   NotificationPriority = "high"
)

// NotificationChannel represents delivery channels
type NotificationChannel string

const (
	ChannelInApp NotificationChannel = "in_app"
	ChannelEmail NotificationChannel = "email"
	ChannelSMS   NotificationChannel = "sms"
	ChannelPush  NotificationChannel = "push"
)

// Notification represents a business notification
type Notification struct {
	ID           string
	UserID       string
	BusinessID   string
	Type         NotificationType
	Priority     NotificationPriority
	Title        string
	Body         string
	Data         map[string]interface{} // Additional structured data
	Channels     []NotificationChannel
	Read         bool
	ReadAt       *time.Time
	CreatedAt    time.Time
	ScheduledFor *time.Time
}

// NotificationPreference represents user notification preferences
type NotificationPreference struct {
	UserID                      string
	EnablePriceChangeAlerts     bool
	EnableOrderUpdates          bool
	EnablePromotionalAlerts     bool
	PreferredChannels           []NotificationChannel
	PriceChangeThresholdPercent float64    // Only notify if price change exceeds this %
	QuietHoursStart             *time.Time // Start of quiet hours (no notifications)
	QuietHoursEnd               *time.Time // End of quiet hours
}

// NotificationService handles sending business notifications
type NotificationService struct {
	db     *pgxpool.Pool
	logger *zap.Logger
}

// NewNotificationService creates a new notification service
func NewNotificationService(db *pgxpool.Pool, logger *zap.Logger) *NotificationService {
	return &NotificationService{
		db:     db,
		logger: logger,
	}
}

// SendPriceChangeNotification sends a notification when cart prices differ from current prices
func (ns *NotificationService) SendPriceChangeNotification(ctx context.Context, userID string, changes []PriceChangeInfo) error {
	// Check user preferences for price change notifications
	prefs, err := ns.getUserPreferences(ctx, userID)
	if err != nil {
		ns.logger.Warn("Failed to get user notification preferences", zap.Error(err))
		// Continue with default behavior
	}

	// Filter changes based on user threshold if preferences exist
	filteredChanges := ns.filterPriceChanges(changes, prefs)

	if len(filteredChanges) == 0 {
		return nil
	}

	// Create notification
	notification := &Notification{
		ID:        uuid.New().String(),
		UserID:    userID,
		Type:      NotificationPriceChange,
		Priority:  PriorityMedium,
		Title:     "Price Changes Detected",
		Body:      ns.formatPriceChangeMessage(filteredChanges),
		Data:      ns.formatPriceChangeData(filteredChanges),
		Channels:  ns.getPreferredChannels(prefs),
		Read:      false,
		CreatedAt: time.Now(),
	}

	// Check if we're in quiet hours
	if ns.isQuietHours(prefs) {
		notification.ScheduledFor = ns.calculateNextActiveTime(prefs)
	}

	// Send notification
	return ns.sendNotification(ctx, notification)
}

// PriceChangeInfo contains information about a single price change
type PriceChangeInfo struct {
	ProductID        string
	ProductName      string
	LockedPrice      float64
	CurrentPrice     float64
	LockedDiscount   float64
	CurrentDiscount  float64
	LockedEffective  float64
	CurrentEffective float64
	ChangePercent    float64
	ChangeType       string // "increase" or "decrease"
}

// getUserPreferences retrieves user notification preferences from database
func (ns *NotificationService) getUserPreferences(ctx context.Context, userID string) (*NotificationPreference, error) {
	prefs := &NotificationPreference{
		UserID:                      userID,
		EnablePriceChangeAlerts:     true,
		PreferredChannels:           []NotificationChannel{ChannelInApp},
		PriceChangeThresholdPercent: 5.0,
	}

	if ns.db == nil {
		return prefs, nil
	}

	err := ns.db.QueryRow(ctx,
		`SELECT enable_price_change_alerts, enable_order_updates, enable_promotional_alerts,
		        preferred_channels, price_change_threshold_percent, quiet_hours_start, quiet_hours_end
		 FROM business.notification_preferences WHERE user_id = $1`,
		userID).Scan(
		&prefs.EnablePriceChangeAlerts,
		&prefs.EnableOrderUpdates,
		&prefs.EnablePromotionalAlerts,
		&prefs.PreferredChannels,
		&prefs.PriceChangeThresholdPercent,
		&prefs.QuietHoursStart,
		&prefs.QuietHoursEnd,
	)

	if err != nil {
		// Return default preferences if not found
		return prefs, nil
	}

	return prefs, nil
}

// filterPriceChanges filters price changes based on user preferences
func (ns *NotificationService) filterPriceChanges(changes []PriceChangeInfo, prefs *NotificationPreference) []PriceChangeInfo {
	if prefs == nil || !prefs.EnablePriceChangeAlerts {
		return []PriceChangeInfo{}
	}

	var filtered []PriceChangeInfo
	threshold := prefs.PriceChangeThresholdPercent

	for _, change := range changes {
		if abs(change.ChangePercent) >= threshold {
			filtered = append(filtered, change)
		}
	}

	return filtered
}

// formatPriceChangeMessage creates a human-readable message for price changes
func (ns *NotificationService) formatPriceChangeMessage(changes []PriceChangeInfo) string {
	if len(changes) == 1 {
		change := changes[0]
		direction := "increased"
		if change.ChangeType == "decrease" {
			direction = "decreased"
		}
		return fmt.Sprintf("The price of %s has %s by %.1f%% since you added it to your cart. You'll still pay the original locked price.",
			change.ProductName, direction, abs(change.ChangePercent))
	}

	return fmt.Sprintf("Prices have changed for %d items in your cart. You'll still pay the original locked prices.", len(changes))
}

// formatPriceChangeData formats price change information for structured data
func (ns *NotificationService) formatPriceChangeData(changes []PriceChangeInfo) map[string]interface{} {
	changeData := make([]map[string]interface{}, len(changes))
	for i, change := range changes {
		changeData[i] = map[string]interface{}{
			"product_id":        change.ProductID,
			"product_name":      change.ProductName,
			"locked_price":      change.LockedPrice,
			"current_price":     change.CurrentPrice,
			"locked_effective":  change.LockedEffective,
			"current_effective": change.CurrentEffective,
			"change_percent":    change.ChangePercent,
			"change_type":       change.ChangeType,
		}
	}

	return map[string]interface{}{
		"price_changes": changeData,
		"total_changes": len(changes),
	}
}

// getPreferredChannels returns user's preferred notification channels
func (ns *NotificationService) getPreferredChannels(prefs *NotificationPreference) []NotificationChannel {
	if prefs != nil && len(prefs.PreferredChannels) > 0 {
		return prefs.PreferredChannels
	}
	return []NotificationChannel{ChannelInApp}
}

// isQuietHours checks if current time is within user's quiet hours
func (ns *NotificationService) isQuietHours(prefs *NotificationPreference) bool {
	if prefs == nil || prefs.QuietHoursStart == nil || prefs.QuietHoursEnd == nil {
		return false
	}

	now := time.Now()
	currentTime := now.Hour()*60 + now.Minute()

	startTime := prefs.QuietHoursStart.Hour()*60 + prefs.QuietHoursStart.Minute()
	endTime := prefs.QuietHoursEnd.Hour()*60 + prefs.QuietHoursEnd.Minute()

	if startTime < endTime {
		return currentTime >= startTime && currentTime <= endTime
	}
	// Handle overnight quiet hours (e.g., 22:00 to 08:00)
	return currentTime >= startTime || currentTime <= endTime
}

// calculateNextActiveTime calculates the next time outside quiet hours
func (ns *NotificationService) calculateNextActiveTime(prefs *NotificationPreference) *time.Time {
	if prefs == nil || prefs.QuietHoursEnd == nil {
		return nil
	}

	now := time.Now()
	nextActive := time.Date(now.Year(), now.Month(), now.Day(),
		prefs.QuietHoursEnd.Hour(), prefs.QuietHoursEnd.Minute(), 0, 0, now.Location())

	// If quiet hours end time has already passed today, schedule for tomorrow
	if nextActive.Before(now) {
		nextActive = nextActive.Add(24 * time.Hour)
	}

	return &nextActive
}

// sendNotification sends a notification through the specified channels
func (ns *NotificationService) sendNotification(ctx context.Context, notification *Notification) error {
	// Store notification in database
	if err := ns.storeNotification(ctx, notification); err != nil {
		ns.logger.Error("Failed to store notification", zap.Error(err))
		return err
	}

	// Send through each channel
	for _, channel := range notification.Channels {
		switch channel {
		case ChannelInApp:
			if err := ns.sendInAppNotification(ctx, notification); err != nil {
				ns.logger.Error("Failed to send in-app notification", zap.Error(err))
			}
		case ChannelEmail:
			if err := ns.sendEmailNotification(ctx, notification); err != nil {
				ns.logger.Error("Failed to send email notification", zap.Error(err))
			}
		case ChannelSMS:
			if err := ns.sendSMSNotification(ctx, notification); err != nil {
				ns.logger.Error("Failed to send SMS notification", zap.Error(err))
			}
		case ChannelPush:
			if err := ns.sendPushNotification(ctx, notification); err != nil {
				ns.logger.Error("Failed to send push notification", zap.Error(err))
			}
		}
	}

	return nil
}

// storeNotification stores notification in database
func (ns *NotificationService) storeNotification(ctx context.Context, notification *Notification) error {
	if ns.db == nil {
		ns.logger.Info("Storing notification (no database)",
			zap.String("id", notification.ID),
			zap.String("type", string(notification.Type)),
			zap.String("user_id", notification.UserID))
		return nil
	}

	// TODO: Implement actual database storage when notifications table is created
	ns.logger.Info("Storing notification",
		zap.String("id", notification.ID),
		zap.String("type", string(notification.Type)),
		zap.String("user_id", notification.UserID))
	return nil
}

// sendInAppNotification sends an in-app notification
func (ns *NotificationService) sendInAppNotification(ctx context.Context, notification *Notification) error {
	ns.logger.Info("Sending in-app notification",
		zap.String("id", notification.ID),
		zap.String("user_id", notification.UserID))
	return nil
}

// sendEmailNotification sends an email notification
func (ns *NotificationService) sendEmailNotification(ctx context.Context, notification *Notification) error {
	ns.logger.Info("Sending email notification",
		zap.String("id", notification.ID),
		zap.String("user_id", notification.UserID))
	return nil
}

// sendSMSNotification sends an SMS notification
func (ns *NotificationService) sendSMSNotification(ctx context.Context, notification *Notification) error {
	ns.logger.Info("Sending SMS notification",
		zap.String("id", notification.ID),
		zap.String("user_id", notification.UserID))
	return nil
}

// sendPushNotification sends a push notification
func (ns *NotificationService) sendPushNotification(ctx context.Context, notification *Notification) error {
	ns.logger.Info("Sending push notification",
		zap.String("id", notification.ID),
		zap.String("user_id", notification.UserID))
	return nil
}

// SetNotificationPreferences sets user notification preferences
func (ns *NotificationService) SetNotificationPreferences(ctx context.Context, prefs *NotificationPreference) error {
	if ns.db == nil {
		return fmt.Errorf("database not available")
	}

	_, err := ns.db.Exec(ctx,
		`INSERT INTO business.notification_preferences 
		 (user_id, enable_price_change_alerts, enable_order_updates, enable_promotional_alerts,
		  preferred_channels, price_change_threshold_percent, quiet_hours_start, quiet_hours_end)
		 VALUES ($1, $2, $3, $4, $5, $6, $7, $8)
		 ON CONFLICT (user_id) DO UPDATE SET
		  enable_price_change_alerts = EXCLUDED.enable_price_change_alerts,
		  enable_order_updates = EXCLUDED.enable_order_updates,
		  enable_promotional_alerts = EXCLUDED.enable_promotional_alerts,
		  preferred_channels = EXCLUDED.preferred_channels,
		  price_change_threshold_percent = EXCLUDED.price_change_threshold_percent,
		  quiet_hours_start = EXCLUDED.quiet_hours_start,
		  quiet_hours_end = EXCLUDED.quiet_hours_end,
		  updated_at = NOW()`,
		prefs.UserID,
		prefs.EnablePriceChangeAlerts,
		prefs.EnableOrderUpdates,
		prefs.EnablePromotionalAlerts,
		prefs.PreferredChannels,
		prefs.PriceChangeThresholdPercent,
		prefs.QuietHoursStart,
		prefs.QuietHoursEnd,
	)

	return err
}

// Helper function for absolute value
func abs(x float64) float64 {
	if x < 0 {
		return -x
	}
	return x
}
