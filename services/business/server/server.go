package server

import (
	"context"

	"time"

	"github.com/jackc/pgx/v5/pgxpool"

	pb "gochat/gen/business"
	"gochat/services/business/repository"

	"go.uber.org/zap"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"
)

type BusinessServer struct {
	pb.UnimplementedBusinessServiceServer
	repo *repository.BusinessRepository
	log  *zap.Logger
}

func NewBusinessServer(db *pgxpool.Pool, log *zap.Logger) *BusinessServer {
	notificationService := repository.NewNotificationService(db, log)
	return &BusinessServer{repo: repository.NewBusinessRepository(db, notificationService, log), log: log}
}

func (s *BusinessServer) CreateBusinessProfile(ctx context.Context, req *pb.CreateBusinessProfileRequest) (*pb.CreateBusinessProfileResponse, error) {
	p, err := s.repo.CreateBusinessProfile(ctx, &repository.BusinessProfile{
		UserID: req.UserId, BusinessName: req.BusinessName, Category: req.Category,
		Description: req.Description, Address: req.Address, Website: req.Website,
		Email: req.Email, Phone: req.Phone, HoursJSON: req.HoursJson,
		// LogoURL: req.LogoUrl, BannerURL: req.BannerUrl, State: req.State, CountryCode: req.CountryCode,
	})
	if err != nil {
		return nil, status.Errorf(codes.Internal, "create profile: %v", err)
	}
	return &pb.CreateBusinessProfileResponse{Profile: profileToPB(p)}, nil
}

func (s *BusinessServer) UpdateBusinessProfile(ctx context.Context, req *pb.UpdateBusinessProfileRequest) (*pb.UpdateBusinessProfileResponse, error) {
	p, err := s.repo.UpdateBusinessProfile(ctx, &repository.BusinessProfile{
		UserID: req.UserId, BusinessName: req.BusinessName, Category: req.Category,
		Description: req.Description, Address: req.Address, Website: req.Website,
		Email: req.Email, Phone: req.Phone, HoursJSON: req.HoursJson,
		// LogoURL: req.LogoUrl, BannerURL: req.BannerUrl, State: req.State, CountryCode: req.CountryCode,
	})
	if err != nil {
		return nil, status.Errorf(codes.Internal, "update profile: %v", err)
	}
	return &pb.UpdateBusinessProfileResponse{Profile: profileToPB(p)}, nil
}

func (s *BusinessServer) GetBusinessProfile(ctx context.Context, req *pb.GetBusinessProfileRequest) (*pb.GetBusinessProfileResponse, error) {
	p, err := s.repo.GetBusinessProfile(ctx, req.UserId)
	if err != nil {
		return nil, status.Errorf(codes.NotFound, "profile not found: %v", err)
	}
	return &pb.GetBusinessProfileResponse{Profile: profileToPB(p)}, nil
}

func (s *BusinessServer) CreateCatalog(ctx context.Context, req *pb.CreateCatalogRequest) (*pb.CreateCatalogResponse, error) {
	id, err := s.repo.CreateCatalog(ctx, req.UserId, req.Name)
	if err != nil {
		return nil, status.Errorf(codes.Internal, "create catalog: %v", err)
	}
	return &pb.CreateCatalogResponse{Catalog: &pb.ProductCatalog{Id: id, UserId: req.UserId, Name: req.Name}}, nil
}

func (s *BusinessServer) AddProduct(ctx context.Context, req *pb.AddProductRequest) (*pb.AddProductResponse, error) {
	p, err := s.repo.AddProduct(ctx, req.CatalogId, req.Name, req.Description, req.Price, req.Currency, req.ImageUrl)
	if err != nil {
		return nil, status.Errorf(codes.Internal, "add product: %v", err)
	}
	return &pb.AddProductResponse{Product: productToPB(p)}, nil
}

func (s *BusinessServer) UpdateProduct(ctx context.Context, req *pb.UpdateProductRequest) (*pb.UpdateProductResponse, error) {
	return &pb.UpdateProductResponse{Product: &pb.Product{
		Id: req.ProductId, Name: req.Name, Description: req.Description,
		Price: req.Price, InStock: req.InStock,
	}}, nil
}

func (s *BusinessServer) ListProducts(ctx context.Context, req *pb.ListProductsRequest) (*pb.ListProductsResponse, error) {
	limit := int(req.Limit)
	if limit <= 0 {
		limit = 20
	}
	products, total, err := s.repo.ListProducts(ctx, req.CatalogId, limit, int(req.Offset))
	if err != nil {
		return nil, status.Errorf(codes.Internal, "list products: %v", err)
	}
	var pbProducts []*pb.Product
	for _, p := range products {
		pbProducts = append(pbProducts, productToPB(p))
	}
	return &pb.ListProductsResponse{Products: pbProducts, Total: int32(total)}, nil
}

func (s *BusinessServer) CreateAppointmentSlot(ctx context.Context, req *pb.CreateAppointmentSlotRequest) (*pb.CreateAppointmentSlotResponse, error) {
	start := time.Unix(req.StartTime, 0)
	end := time.Unix(req.EndTime, 0)
	maxBookings := int(req.MaxBookings)
	if maxBookings <= 0 {
		maxBookings = 1
	}
	a, err := s.repo.CreateAppointmentSlot(ctx, req.BusinessId, req.Title, req.Description, start, end, maxBookings)
	if err != nil {
		return nil, status.Errorf(codes.Internal, "create appointment: %v", err)
	}
	return &pb.CreateAppointmentSlotResponse{Appointment: apptToPB(a)}, nil
}

func (s *BusinessServer) BookAppointment(ctx context.Context, req *pb.BookAppointmentRequest) (*pb.BookAppointmentResponse, error) {
	if err := s.repo.BookAppointment(ctx, req.UserId, req.AppointmentId, req.Notes); err != nil {
		return nil, status.Errorf(codes.Internal, "book appointment: %v", err)
	}
	return &pb.BookAppointmentResponse{Success: true}, nil
}

func (s *BusinessServer) ListAppointments(ctx context.Context, req *pb.ListAppointmentsRequest) (*pb.ListAppointmentsResponse, error) {
	limit := int(req.Limit)
	if limit <= 0 {
		limit = 20
	}
	appts, total, err := s.repo.ListAppointments(ctx, req.BusinessId, limit, int(req.Offset))
	if err != nil {
		return nil, status.Errorf(codes.Internal, "list appointments: %v", err)
	}
	var pbAppts []*pb.Appointment
	for _, a := range appts {
		pbAppts = append(pbAppts, apptToPB(a))
	}
	return &pb.ListAppointmentsResponse{Appointments: pbAppts, Total: int32(total)}, nil
}

func (s *BusinessServer) SetAutoReply(ctx context.Context, req *pb.SetAutoReplyRequest) (*pb.SetAutoReplyResponse, error) {
	rule, err := s.repo.SetAutoReply(ctx, req.UserId, req.TriggerType, req.TriggerValue, req.ReplyText, "always", "UTC", []int32{1, 2, 3, 4, 5}, "09:00", "17:00")
	if err != nil {
		return nil, status.Errorf(codes.Internal, "set auto reply: %v", err)
	}
	return &pb.SetAutoReplyResponse{Rule: &pb.AutoReply{
		Id: rule.ID, TriggerType: rule.TriggerType, TriggerValue: rule.TriggerValue,
		ReplyText: rule.ReplyText, IsActive: rule.IsActive,
	}}, nil
}

func (s *BusinessServer) GetAutoReplies(ctx context.Context, req *pb.GetAutoRepliesRequest) (*pb.GetAutoRepliesResponse, error) {
	rules, err := s.repo.GetAutoReplies(ctx, req.UserId)
	if err != nil {
		return nil, status.Errorf(codes.Internal, "get auto replies: %v", err)
	}
	var pbRules []*pb.AutoReply
	for _, r := range rules {
		pbRules = append(pbRules, &pb.AutoReply{
			Id: r.ID, TriggerType: r.TriggerType, TriggerValue: r.TriggerValue,
			ReplyText: r.ReplyText, IsActive: r.IsActive,
		})
	}
	return &pb.GetAutoRepliesResponse{Rules: pbRules}, nil
}

func (s *BusinessServer) EnqueueCustomer(ctx context.Context, req *pb.EnqueueCustomerRequest) (*pb.EnqueueCustomerResponse, error) {
	return &pb.EnqueueCustomerResponse{Entry: &pb.QueueEntry{
		CustomerId: req.CustomerId, Position: 1, Status: "waiting", JoinedAt: time.Now().Unix(),
	}}, nil
}

func (s *BusinessServer) DequeueCustomer(ctx context.Context, req *pb.DequeueCustomerRequest) (*pb.DequeueCustomerResponse, error) {
	return &pb.DequeueCustomerResponse{Entry: &pb.QueueEntry{Status: "serving"}}, nil
}

func (s *BusinessServer) GetQueuePosition(ctx context.Context, req *pb.GetQueuePositionRequest) (*pb.GetQueuePositionResponse, error) {
	return &pb.GetQueuePositionResponse{Position: 1, TotalInQueue: 1}, nil
}

func profileToPB(p *repository.BusinessProfile) *pb.BusinessProfile {
	return &pb.BusinessProfile{
		UserId: p.UserID, BusinessName: p.BusinessName, Category: p.Category,
		Description: p.Description, Address: p.Address, Website: p.Website,
		Email: p.Email, Phone: p.Phone, HoursJson: p.HoursJSON, IsVerified: p.IsVerified,
		// LogoUrl: p.LogoURL, BannerUrl: p.BannerURL, State: p.State, CountryCode: p.CountryCode, Slug: p.Slug,
	}
}

func productToPB(p *repository.Product) *pb.Product {
	return &pb.Product{
		Id: p.ID, CatalogId: p.CatalogID, Name: p.Name, Description: p.Description,
		Price: p.Price, Currency: p.Currency, ImageUrl: p.ImageURL, InStock: p.InStock,
	}
}

func apptToPB(a *repository.Appointment) *pb.Appointment {
	return &pb.Appointment{
		Id: a.ID, BusinessId: a.BusinessID, Title: a.Title, Description: a.Description,
		StartTime: a.StartTime.Unix(), EndTime: a.EndTime.Unix(),
		MaxBookings: int32(a.MaxBookings), CurrentBookings: int32(a.CurrentBookings),
	}
}
