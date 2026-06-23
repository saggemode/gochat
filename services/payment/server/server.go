package server

import (
	"context"

	"github.com/jackc/pgx/v5/pgxpool"

	pb "gochat/gen/payment"
	"gochat/services/payment/repository"

	"go.uber.org/zap"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"
)

type PaymentServer struct {
	pb.UnimplementedPaymentServiceServer
	repo *repository.PaymentRepository
	log  *zap.Logger
}

func NewPaymentServer(db *pgxpool.Pool, log *zap.Logger) *PaymentServer {
	return &PaymentServer{repo: repository.NewPaymentRepository(db), log: log}
}

func (s *PaymentServer) CreateWallet(ctx context.Context, req *pb.CreateWalletRequest) (*pb.CreateWalletResponse, error) {
	w, err := s.repo.CreateWallet(ctx, req.UserId, req.Currency)
	if err != nil {
		return nil, status.Errorf(codes.Internal, "create wallet: %v", err)
	}
	return &pb.CreateWalletResponse{Wallet: &pb.Wallet{
		Id: w.ID, UserId: w.UserID, Balance: w.Balance, Currency: w.Currency, IsActive: true,
	}}, nil
}

func (s *PaymentServer) GetWallet(ctx context.Context, req *pb.GetWalletRequest) (*pb.GetWalletResponse, error) {
	w, err := s.repo.GetWallet(ctx, req.UserId)
	if err != nil {
		return nil, status.Errorf(codes.NotFound, "wallet not found: %v", err)
	}
	return &pb.GetWalletResponse{Wallet: &pb.Wallet{
		Id: w.ID, UserId: w.UserID, Balance: w.Balance, Currency: w.Currency, IsActive: true,
	}}, nil
}

func (s *PaymentServer) SendPayment(ctx context.Context, req *pb.SendPaymentRequest) (*pb.SendPaymentResponse, error) {
	if req.Amount <= 0 {
		return nil, status.Error(codes.InvalidArgument, "amount must be positive")
	}
	txn, err := s.repo.SendPayment(ctx, req.SenderId, req.ReceiverId, req.Amount, req.Currency, req.Description)
	if err != nil {
		return nil, status.Errorf(codes.Internal, "send payment: %v", err)
	}
	return &pb.SendPaymentResponse{Transaction: &pb.Transaction{
		Id: txn.ID, FromWalletId: txn.SenderID, ToWalletId: txn.ReceiverID,
		Amount: txn.Amount, Currency: txn.Currency, Type: txn.Type, Status: txn.Status,
		Description: txn.Description, CreatedAt: txn.CreatedAt.Unix(),
	}}, nil
}

func (s *PaymentServer) RequestPayment(ctx context.Context, req *pb.RequestPaymentRequest) (*pb.RequestPaymentResponse, error) {
	return &pb.RequestPaymentResponse{Transaction: &pb.Transaction{
		Type: "request", Status: "pending", Amount: req.Amount,
		Currency: req.Currency, Description: req.Description,
	}}, nil
}

func (s *PaymentServer) CreateExpenseGroup(ctx context.Context, req *pb.CreateExpenseGroupRequest) (*pb.CreateExpenseGroupResponse, error) {
	return &pb.CreateExpenseGroupResponse{Group: &pb.ExpenseGroup{
		ConversationId: req.ConversationId, Name: req.Name,
		CreatedBy: req.CreatedBy, Currency: req.Currency,
	}}, nil
}

func (s *PaymentServer) AddExpense(ctx context.Context, req *pb.AddExpenseRequest) (*pb.AddExpenseResponse, error) {
	return &pb.AddExpenseResponse{Item: &pb.ExpenseItem{
		PaidBy: req.PaidBy, Description: req.Description, Amount: req.Amount,
	}}, nil
}

func (s *PaymentServer) SettleExpense(ctx context.Context, req *pb.SettleExpenseRequest) (*pb.SettleExpenseResponse, error) {
	return &pb.SettleExpenseResponse{Success: true}, nil
}

func (s *PaymentServer) GetExpenseGroup(ctx context.Context, req *pb.GetExpenseGroupRequest) (*pb.GetExpenseGroupResponse, error) {
	return &pb.GetExpenseGroupResponse{Group: &pb.ExpenseGroup{Id: req.ExpenseGroupId}}, nil
}

func (s *PaymentServer) GetTransactionHistory(ctx context.Context, req *pb.GetTransactionHistoryRequest) (*pb.GetTransactionHistoryResponse, error) {
	limit := int(req.Limit)
	if limit <= 0 {
		limit = 20
	}
	txns, total, err := s.repo.GetTransactionHistory(ctx, req.UserId, limit, int(req.Offset))
	if err != nil {
		return nil, status.Errorf(codes.Internal, "get transactions: %v", err)
	}
	var pbTxns []*pb.Transaction
	for _, t := range txns {
		pbTxns = append(pbTxns, &pb.Transaction{
			Id: t.ID, FromWalletId: t.SenderID, ToWalletId: t.ReceiverID,
			Amount: t.Amount, Currency: t.Currency, Type: t.Type,
			Status: t.Status, Description: t.Description, CreatedAt: t.CreatedAt.Unix(),
		})
	}
	return &pb.GetTransactionHistoryResponse{Transactions: pbTxns, Total: int32(total)}, nil
}
