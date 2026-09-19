package notifier

import (
	"context"
	"crypto/rand"
	"crypto/tls"
	"encoding/base64"
	"fmt"
	"math/big"
	"net/http"
	"net/smtp"
	"net/url"
	"strings"
	"time"

	"go.uber.org/zap"

	"gochat/pkg/config"
)

// RecoveryRequest holds information needed to dispatch a recovery code.
type RecoveryRequest struct {
	RecipientEmail string
	RecipientPhone string
	DisplayName    string
	RecoveryCode   string
	ExpiresIn      time.Duration
	IPAddress      string
	UserAgent      string
}

// Notifier defines the communication interface for security and auth notifications.
type Notifier interface {
	SendRecoveryEmail(ctx context.Context, req RecoveryRequest) error
	SendRecoverySMS(ctx context.Context, req RecoveryRequest) error
	SendPINResetConfirmation(ctx context.Context, email, phone, displayName string) error
	SendPhoneOTP(ctx context.Context, phone, otp string) error
}

// DefaultNotifier implements Notifier with real SMTP, Twilio SMS, and structured dev logging.
type DefaultNotifier struct {
	cfg *config.Config
	log *zap.Logger
}

// New creates a new Notifier.
func New(cfg *config.Config, log *zap.Logger) *DefaultNotifier {
	return &DefaultNotifier{
		cfg: cfg,
		log: log,
	}
}

// GenerateSecureCode returns a cryptographically secure 6-digit numeric string (100000 - 999999).
func GenerateSecureCode() (string, error) {
	n, err := rand.Int(rand.Reader, big.NewInt(900000))
	if err != nil {
		return "", fmt.Errorf("generating secure code: %w", err)
	}
	return fmt.Sprintf("%06d", n.Int64()+100000), nil
}

// MaskEmail masks an email for safe privacy display (e.g. j***e@example.com).
func MaskEmail(email string) string {
	email = strings.TrimSpace(email)
	parts := strings.Split(email, "@")
	if len(parts) != 2 {
		return email
	}
	local, domain := parts[0], parts[1]
	if len(local) <= 2 {
		return local[:1] + "***@" + domain
	}
	return fmt.Sprintf("%c***%c@%s", local[0], local[len(local)-1], domain)
}

// MaskPhone masks a phone number for safe privacy display (e.g. +1 234 *** **89).
func MaskPhone(phone string) string {
	phone = strings.TrimSpace(phone)
	if len(phone) < 7 {
		return phone
	}
	prefix := phone[:4]
	suffix := phone[len(phone)-2:]
	return fmt.Sprintf("%s *** **%s", prefix, suffix)
}

// SendRecoveryEmail dispatches an account recovery code to the user's email.
func (n *DefaultNotifier) SendRecoveryEmail(ctx context.Context, req RecoveryRequest) error {
	if req.RecipientEmail == "" {
		return fmt.Errorf("recipient email is required")
	}

	subject := fmt.Sprintf("Your GoChat Recovery Code: %s", req.RecoveryCode)
	expiryMins := int(req.ExpiresIn.Minutes())
	if expiryMins <= 0 {
		expiryMins = 15
	}

	name := req.DisplayName
	if name == "" {
		name = "GoChat User"
	}

	textBody := fmt.Sprintf(
		"Hello %s,\n\n"+
			"We received a request to reset your GoChat PIN.\n\n"+
			"Your 6-digit recovery code is: %s\n\n"+
			"This code will expire in %d minutes.\n\n"+
			"If you did not request this recovery code, someone may be attempting to access your account. Please change your password or contact support immediately.\n\n"+
			"— GoChat Security Team",
		name, req.RecoveryCode, expiryMins,
	)

	htmlBody := fmt.Sprintf(`<!DOCTYPE html>
<html>
<head>
  <meta charset="utf-8">
  <style>
    body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif; background-color: #0b0c10; color: #e4e4e7; margin: 0; padding: 24px; }
    .card { max-width: 480px; margin: 0 auto; background: #14151e; border: 1px solid #27272a; border-radius: 20px; padding: 32px; box-shadow: 0 10px 30px rgba(0,0,0,0.5); }
    .brand { font-size: 20px; font-weight: 800; color: #10b981; margin-bottom: 24px; display: flex; align-items: center; gap: 8px; }
    .title { font-size: 18px; font-weight: 700; color: #ffffff; margin-bottom: 8px; }
    .desc { font-size: 13px; color: #a1a1aa; line-height: 1.6; margin-bottom: 24px; }
    .code-box { background: #1c1d28; border: 1px solid #10b981; border-radius: 12px; padding: 18px; text-align: center; margin-bottom: 24px; }
    .code { font-family: 'SF Mono', Consolas, Monaco, monospace; font-size: 32px; font-weight: 800; letter-spacing: 8px; color: #10b981; margin: 0; }
    .warning { font-size: 12px; color: #71717a; border-top: 1px solid #27272a; padding-top: 16px; line-height: 1.5; }
  </style>
</head>
<body>
  <div class="card">
    <div class="brand">GoChat Security</div>
    <div class="title">Account Recovery Request</div>
    <div class="desc">Hello <strong>%s</strong>, use the single-use 6-digit verification code below to reset your GoChat PIN. This code expires in <strong>%d minutes</strong>.</div>
    <div class="code-box">
      <div class="code">%s</div>
    </div>
    <div class="warning">
      If you did not make this request, ignore this email or review your linked devices immediately in the GoChat app settings.
    </div>
  </div>
</body>
</html>`, name, expiryMins, req.RecoveryCode)

	if n.cfg.SMTPHost == "" {
		n.log.Info("✉️ [MOCK EMAIL DISPATCH - SMTP not configured]",
			zap.String("to", req.RecipientEmail),
			zap.String("subject", subject),
			zap.String("recovery_code", req.RecoveryCode),
			zap.Int("expires_in_minutes", expiryMins),
		)
		return nil
	}

	return n.sendSMTP(req.RecipientEmail, subject, textBody, htmlBody)
}

// SendRecoverySMS dispatches an account recovery code via SMS.
func (n *DefaultNotifier) SendRecoverySMS(ctx context.Context, req RecoveryRequest) error {
	if req.RecipientPhone == "" {
		return fmt.Errorf("recipient phone number is required")
	}

	expiryMins := int(req.ExpiresIn.Minutes())
	if expiryMins <= 0 {
		expiryMins = 15
	}

	msg := fmt.Sprintf("Your GoChat verification code is %s. Valid for %d minutes. Do not share this code with anyone.", req.RecoveryCode, expiryMins)

	if n.cfg.TwilioAccountSID == "" || n.cfg.TwilioAuthToken == "" {
		n.log.Info("📱 [MOCK SMS DISPATCH - Twilio not configured]",
			zap.String("to", req.RecipientPhone),
			zap.String("message", msg),
			zap.String("recovery_code", req.RecoveryCode),
		)
		return nil
	}

	return n.sendTwilioSMS(ctx, req.RecipientPhone, msg)
}

// SendPhoneOTP dispatches a phone verification OTP.
func (n *DefaultNotifier) SendPhoneOTP(ctx context.Context, phone, otp string) error {
	msg := fmt.Sprintf("Your GoChat registration code is %s. Never share this code with anyone.", otp)

	if n.cfg.TwilioAccountSID == "" || n.cfg.TwilioAuthToken == "" {
		n.log.Info("📱 [MOCK PHONE OTP DISPATCH]",
			zap.String("to", phone),
			zap.String("otp_code", otp),
		)
		return nil
	}

	return n.sendTwilioSMS(ctx, phone, msg)
}

// SendPINResetConfirmation sends a security alert confirming that the user's PIN was reset.
func (n *DefaultNotifier) SendPINResetConfirmation(ctx context.Context, email, phone, displayName string) error {
	name := displayName
	if name == "" {
		name = "GoChat User"
	}

	if email != "" {
		subject := "GoChat Security Alert: Your PIN was successfully reset"
		textBody := fmt.Sprintf(
			"Hello %s,\n\n"+
				"Your GoChat security PIN was successfully reset on %s.\n\n"+
				"All active sessions and linked devices have been invalidated for your protection. If you did not make this change, contact support immediately.\n\n"+
				"— GoChat Security Team",
			name, time.Now().UTC().Format(time.RFC1123),
		)

		if n.cfg.SMTPHost == "" {
			n.log.Info("✉️ [MOCK EMAIL ALERT - PIN Reset Confirmation]",
				zap.String("to", email),
				zap.String("subject", subject),
			)
		} else {
			_ = n.sendSMTP(email, subject, textBody, "")
		}
	}

	if phone != "" {
		msg := "GoChat Alert: Your PIN was successfully reset. If this was not you, secure your account immediately."
		if n.cfg.TwilioAccountSID == "" || n.cfg.TwilioAuthToken == "" {
			n.log.Info("📱 [MOCK SMS ALERT - PIN Reset Confirmation]",
				zap.String("to", phone),
				zap.String("message", msg),
			)
		} else {
			_ = n.sendTwilioSMS(ctx, phone, msg)
		}
	}

	return nil
}

// sendSMTP sends a multipart/alternative email via SMTP.
func (n *DefaultNotifier) sendSMTP(to, subject, textBody, htmlBody string) error {
	from := n.cfg.SMTPFrom
	if from == "" {
		from = "GoChat Security <security@gochat.app>"
	}

	boundary := fmt.Sprintf("gochat_boundary_%d", time.Now().UnixNano())

	var raw strings.Builder
	raw.WriteString(fmt.Sprintf("From: %s\r\n", from))
	raw.WriteString(fmt.Sprintf("To: %s\r\n", to))
	raw.WriteString(fmt.Sprintf("Subject: %s\r\n", subject))
	raw.WriteString("MIME-Version: 1.0\r\n")

	if htmlBody != "" {
		raw.WriteString(fmt.Sprintf("Content-Type: multipart/alternative; boundary=\"%s\"\r\n\r\n", boundary))
		raw.WriteString(fmt.Sprintf("--%s\r\n", boundary))
		raw.WriteString("Content-Type: text/plain; charset=\"UTF-8\"\r\n\r\n")
		raw.WriteString(textBody + "\r\n\r\n")
		raw.WriteString(fmt.Sprintf("--%s\r\n", boundary))
		raw.WriteString("Content-Type: text/html; charset=\"UTF-8\"\r\n\r\n")
		raw.WriteString(htmlBody + "\r\n\r\n")
		raw.WriteString(fmt.Sprintf("--%s--\r\n", boundary))
	} else {
		raw.WriteString("Content-Type: text/plain; charset=\"UTF-8\"\r\n\r\n")
		raw.WriteString(textBody + "\r\n")
	}

	addr := fmt.Sprintf("%s:%d", n.cfg.SMTPHost, n.cfg.SMTPPort)

	var auth smtp.Auth
	if n.cfg.SMTPUser != "" && n.cfg.SMTPPassword != "" {
		auth = smtp.PlainAuth("", n.cfg.SMTPUser, n.cfg.SMTPPassword, n.cfg.SMTPHost)
	}

	// If SMTPSkipVerify is true, establish custom TLS connection
	if n.cfg.SMTPSkipVerify {
		tlsConfig := &tls.Config{
			InsecureSkipVerify: true,
			ServerName:         n.cfg.SMTPHost,
		}
		conn, err := tls.Dial("tcp", addr, tlsConfig)
		if err != nil {
			return fmt.Errorf("tls dial smtp: %w", err)
		}
		client, err := smtp.NewClient(conn, n.cfg.SMTPHost)
		if err != nil {
			return fmt.Errorf("smtp new client: %w", err)
		}
		defer client.Close()

		if auth != nil {
			if err = client.Auth(auth); err != nil {
				return fmt.Errorf("smtp auth: %w", err)
			}
		}
		if err = client.Mail(from); err != nil {
			return fmt.Errorf("smtp mail from: %w", err)
		}
		if err = client.Rcpt(to); err != nil {
			return fmt.Errorf("smtp rcpt to: %w", err)
		}
		w, err := client.Data()
		if err != nil {
			return fmt.Errorf("smtp data: %w", err)
		}
		_, err = w.Write([]byte(raw.String()))
		if err != nil {
			return fmt.Errorf("smtp write: %w", err)
		}
		return w.Close()
	}

	return smtp.SendMail(addr, auth, from, []string{to}, []byte(raw.String()))
}

// sendTwilioSMS dispatches an SMS using Twilio's standard REST API.
func (n *DefaultNotifier) sendTwilioSMS(ctx context.Context, to, body string) error {
	apiURL := fmt.Sprintf("https://api.twilio.com/2010-04-01/Accounts/%s/Messages.json", n.cfg.TwilioAccountSID)

	data := url.Values{}
	data.Set("To", to)
	data.Set("From", n.cfg.TwilioFromNumber)
	data.Set("Body", body)

	req, err := http.NewRequestWithContext(ctx, http.MethodPost, apiURL, strings.NewReader(data.Encode()))
	if err != nil {
		return fmt.Errorf("create twilio request: %w", err)
	}

	auth := base64.StdEncoding.EncodeToString([]byte(fmt.Sprintf("%s:%s", n.cfg.TwilioAccountSID, n.cfg.TwilioAuthToken)))
	req.Header.Set("Authorization", "Basic "+auth)
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")

	client := &http.Client{Timeout: 10 * time.Second}
	resp, err := client.Do(req)
	if err != nil {
		return fmt.Errorf("execute twilio request: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return fmt.Errorf("twilio api returned non-200 status: %d", resp.StatusCode)
	}

	return nil
}
