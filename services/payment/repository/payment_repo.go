package repository

import (
	"context"
	"fmt"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"
)

type PaymentRepository struct {
	db *pgxpool.Pool
}

func NewPaymentRepository(db *pgxpool.Pool) *PaymentRepository {
	return &PaymentRepository{db: db}
}

// ── Wallets ─────────────────────────────────────────────────────────────────

type Wallet struct {
	ID       string
	UserID   string
	Balance  float64
	Currency string
}

func (r *PaymentRepository) CreateWallet(ctx context.Context, userID, currency string) (*Wallet, error) {
	if currency == "" {
		currency = "USD"
	}
	id := uuid.New().String()
	_, err := r.db.Exec(ctx,
		`INSERT INTO wallets (id, user_id, currency) VALUES ($1, $2, $3)
		 ON CONFLICT (user_id) DO NOTHING`, id, userID, currency)
	if err != nil {
		return nil, fmt.Errorf("create wallet: %w", err)
	}
	return &Wallet{ID: id, UserID: userID, Balance: 0, Currency: currency}, nil
}

func (r *PaymentRepository) GetWallet(ctx context.Context, userID string) (*Wallet, error) {
	w := &Wallet{}
	err := r.db.QueryRow(ctx,
		`SELECT id, user_id, balance, currency FROM wallets WHERE user_id = $1`, userID).
		Scan(&w.ID, &w.UserID, &w.Balance, &w.Currency)
	if err != nil {
		return nil, err
	}
	return w, nil
}

// ── Transactions ────────────────────────────────────────────────────────────

type Transaction struct {
	ID          string
	SenderID    string
	ReceiverID  string
	Amount      float64
	Currency    string
	Type        string
	Status      string
	Description string
	CreatedAt   time.Time
}

func (r *PaymentRepository) SendPayment(ctx context.Context, senderID, receiverID string, amount float64, currency, desc string) (*Transaction, error) {
	tx, err := r.db.Begin(ctx)
	if err != nil {
		return nil, err
	}
	defer tx.Rollback(ctx)

	// Check sender balance
	var balance float64
	err = tx.QueryRow(ctx, `SELECT balance FROM wallets WHERE user_id = $1 FOR UPDATE`, senderID).Scan(&balance)
	if err != nil {
		return nil, fmt.Errorf("sender wallet not found: %w", err)
	}
	if balance < amount {
		return nil, fmt.Errorf("insufficient balance: have %.2f, need %.2f", balance, amount)
	}

	// Debit sender, credit receiver
	tx.Exec(ctx, `UPDATE wallets SET balance = balance - $1, updated_at = NOW() WHERE user_id = $2`, amount, senderID)
	tx.Exec(ctx, `UPDATE wallets SET balance = balance + $1, updated_at = NOW() WHERE user_id = $2`, amount, receiverID)

	// Record transaction
	txnID := uuid.New().String()
	tx.Exec(ctx,
		`INSERT INTO transactions (id, sender_id, receiver_id, amount, currency, type, status, description)
		 VALUES ($1, $2, $3, $4, $5, 'p2p', 'completed', $6)`,
		txnID, senderID, receiverID, amount, currency, desc)

	if err := tx.Commit(ctx); err != nil {
		return nil, err
	}

	return &Transaction{ID: txnID, SenderID: senderID, ReceiverID: receiverID,
		Amount: amount, Currency: currency, Type: "p2p", Status: "completed",
		Description: desc, CreatedAt: time.Now()}, nil
}

func (r *PaymentRepository) RequestPayment(ctx context.Context, requesterID, payerID string, amount float64, currency, desc string) (*Transaction, error) {
	txnID := uuid.New().String()
	_, err := r.db.Exec(ctx,
		`INSERT INTO transactions (id, sender_id, receiver_id, amount, currency, type, status, description)
		 VALUES ($1, $2, $3, $4, $5, 'request', 'pending', $6)`,
		txnID, payerID, requesterID, amount, currency, desc)
	if err != nil {
		return nil, err
	}
	return &Transaction{ID: txnID, SenderID: payerID, ReceiverID: requesterID,
		Amount: amount, Currency: currency, Type: "request", Status: "pending",
		Description: desc, CreatedAt: time.Now()}, nil
}

func (r *PaymentRepository) GetTransactionHistory(ctx context.Context, userID string, limit, offset int) ([]*Transaction, int, error) {
	var total int
	r.db.QueryRow(ctx,
		`SELECT COUNT(*) FROM transactions WHERE sender_id = $1 OR receiver_id = $1`, userID).Scan(&total)

	rows, err := r.db.Query(ctx,
		`SELECT id, sender_id, receiver_id, amount, currency, type, status, COALESCE(description,''), created_at
		 FROM transactions WHERE sender_id = $1 OR receiver_id = $1
		 ORDER BY created_at DESC LIMIT $2 OFFSET $3`, userID, limit, offset)
	if err != nil {
		return nil, 0, err
	}
	defer rows.Close()

	var txns []*Transaction
	for rows.Next() {
		t := &Transaction{}
		rows.Scan(&t.ID, &t.SenderID, &t.ReceiverID, &t.Amount, &t.Currency,
			&t.Type, &t.Status, &t.Description, &t.CreatedAt)
		txns = append(txns, t)
	}
	return txns, total, nil
}

// ── Expense Groups ──────────────────────────────────────────────────────────

type ExpenseGroup struct {
	ID             string
	ConversationID string
	CreatedBy      string
	Name           string
	Currency       string
	TotalExpenses  float64
	IsSettled      bool
}

type ExpenseItem struct {
	ID             string
	ExpenseGroupID string
	PaidBy         string
	Description    string
	Amount         float64
	SplitAmong     []string
}

func (r *PaymentRepository) CreateExpenseGroup(ctx context.Context, convID, createdBy, name, currency string) (*ExpenseGroup, error) {
	id := uuid.New().String()
	if currency == "" {
		currency = "USD"
	}
	_, err := r.db.Exec(ctx,
		`INSERT INTO expense_groups (id, conversation_id, created_by, name, currency) VALUES ($1, $2, $3, $4, $5)`,
		id, convID, createdBy, name, currency)
	if err != nil {
		return nil, err
	}
	return &ExpenseGroup{ID: id, ConversationID: convID, CreatedBy: createdBy, Name: name, Currency: currency}, nil
}

func (r *PaymentRepository) AddExpense(ctx context.Context, groupID, paidBy, desc string, amount float64, splitAmong []string) (*ExpenseItem, error) {
	id := uuid.New().String()
	_, err := r.db.Exec(ctx,
		`INSERT INTO expense_items (id, expense_group_id, paid_by, description, amount, split_among)
		 VALUES ($1, $2, $3, $4, $5, $6)`,
		id, groupID, paidBy, desc, amount, splitAmong)
	if err != nil {
		return nil, err
	}

	r.db.Exec(ctx,
		`UPDATE expense_groups SET total_expenses = total_expenses + $1 WHERE id = $2`, amount, groupID)

	return &ExpenseItem{ID: id, ExpenseGroupID: groupID, PaidBy: paidBy,
		Description: desc, Amount: amount, SplitAmong: splitAmong}, nil
}

func (r *PaymentRepository) SettleExpense(ctx context.Context, groupID, userID string) error {
	_, err := r.db.Exec(ctx,
		`UPDATE expense_groups SET is_settled = TRUE WHERE id = $1`, groupID)
	return err
}
