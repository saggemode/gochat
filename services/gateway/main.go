package main

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"fmt"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/gin-gonic/gin"
	"go.uber.org/zap"
	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials"
	"google.golang.org/grpc/credentials/insecure"

	aipb "gochat/gen/ai"
	authpb "gochat/gen/auth"
	businesspb "gochat/gen/business"
	callpb "gochat/gen/call"
	channelpb "gochat/gen/channel"
	chatpb "gochat/gen/chat"
	grouppb "gochat/gen/group"
	mediapb "gochat/gen/media"
	miniapppb "gochat/gen/miniapp"
	socialpb "gochat/gen/social"
	storypb "gochat/gen/story"
	"gochat/pkg/cdn"
	"gochat/pkg/config"
	"gochat/pkg/database"
	"gochat/pkg/discovery"
	"gochat/pkg/eventbus"
	"gochat/pkg/logger"
	"gochat/pkg/metrics"
	"gochat/services/gateway/handlers"
	"gochat/services/gateway/middleware"
	"gochat/services/gateway/ws"

	"encoding/json"
	"strings"

	"github.com/redis/go-redis/v9"
	"google.golang.org/grpc/resolver"
)

func buildGRPCDialOptions(cfg *config.Config, log *zap.Logger) []grpc.DialOption {
	if !cfg.GRPCUseTLS {
		return []grpc.DialOption{grpc.WithTransportCredentials(insecure.NewCredentials())}
	}

	tlsCfg := &tls.Config{}
	if cfg.GRPCTLSServerName != "" {
		tlsCfg.ServerName = cfg.GRPCTLSServerName
	}

	// Root CAs
	if cfg.GRPCTLSCACertFile != "" {
		caPEM, err := os.ReadFile(cfg.GRPCTLSCACertFile)
		if err != nil {
			log.Fatal("failed to read GRPC_TLS_CA_CERT", zap.Error(err))
		}
		pool := x509.NewCertPool()
		if !pool.AppendCertsFromPEM(caPEM) {
			log.Fatal("failed to parse GRPC_TLS_CA_CERT (PEM)")
		}
		tlsCfg.RootCAs = pool
	}

	// Optional mTLS
	if cfg.GRPCTLSClientCert != "" && cfg.GRPCTLSClientKey != "" {
		cert, err := tls.LoadX509KeyPair(cfg.GRPCTLSClientCert, cfg.GRPCTLSClientKey)
		if err != nil {
			log.Fatal("failed to load gRPC client mTLS cert/key", zap.Error(err))
		}
		tlsCfg.Certificates = []tls.Certificate{cert}
	}

	return []grpc.DialOption{grpc.WithTransportCredentials(credentials.NewTLS(tlsCfg))}
}

func main() {
	log := logger.New("api-gateway")
	defer log.Sync()

	cfg := config.Load()
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	// ── Redis ─────────────────────────────────────────────────────────────────
	redisClient, err := database.NewRedis(ctx, cfg.RedisAddr, cfg.RedisPassword, cfg.RedisDB, log)
	if err != nil {
		log.Warn("Redis not available, operating in standalone in-memory mode", zap.Error(err))
		redisClient = nil
	} else {
		defer redisClient.Close()
	}

	// ── Service Discovery & Load Balancing ────────────────────────────────────
	var disc discovery.Discovery
	if cfg.DiscoveryType == "redis" {
		redisDisc := discovery.NewRedisRegistry(redisClient, log)
		disc = redisDisc
		log.Info("service discovery enabled via Redis registry",
			zap.Duration("ttl", cfg.DiscoveryTTL),
			zap.Duration("interval", cfg.DiscoveryInterval),
		)
	}

	resBuilder := discovery.NewBuilder(disc, log)
	resolver.Register(resBuilder)

	// ── Dial gRPC Services with Client-Side Load Balancing ────────────────────
	dialOpts := buildGRPCDialOptions(cfg, log)
	dialOpts = append(dialOpts, grpc.WithDefaultServiceConfig(`{"loadBalancingPolicy":"round_robin"}`))

	var inProcessConn *grpc.ClientConn
	if !isHostResolvable(cfg.AuthGRPCAddr) || os.Getenv("STANDALONE_MODE") == "true" {
		log.Info("remote gRPC services unreachable or standalone mode enabled — booting in-process services with Neon Cloud DB")
		inConn, inErr := startInProcessGRPC(ctx, cfg, redisClient, log)
		if inErr != nil {
			log.Warn("failed to initialize in-process services", zap.Error(inErr))
		} else {
			inProcessConn = inConn
			defer inProcessConn.Close()
		}
	}

	dialService := func(serviceName, fallbackAddr string, extraOpts ...grpc.DialOption) (*grpc.ClientConn, error) {
		if inProcessConn != nil && (!isHostResolvable(fallbackAddr) || os.Getenv("STANDALONE_MODE") == "true") {
			return inProcessConn, nil
		}
		resBuilder.SetStaticFallback(serviceName, fallbackAddr)
		target := fallbackAddr
		if cfg.DiscoveryType != "static" && disc != nil {
			target = discovery.TargetURI(serviceName)
		}
		opts := append(dialOpts, extraOpts...)
		return grpc.Dial(target, opts...)
	}

	authConn, err := dialService("auth-service", cfg.AuthGRPCAddr)
	if err != nil {
		log.Fatal("failed to connect to auth service", zap.Error(err))
	}
	defer authConn.Close()
	authClient := authpb.NewAuthServiceClient(authConn)

	chatConn, err := dialService("chat-service", cfg.ChatGRPCAddr)
	if err != nil {
		log.Fatal("failed to connect to chat service", zap.Error(err))
	}
	defer chatConn.Close()
	chatClient := chatpb.NewChatServiceClient(chatConn)

	groupConn, err := dialService("group-service", cfg.GroupGRPCAddr)
	if err != nil {
		log.Fatal("failed to connect to group service", zap.Error(err))
	}
	defer groupConn.Close()
	groupClient := grouppb.NewGroupServiceClient(groupConn)

	storyConn, err := dialService("story-service", cfg.StoryGRPCAddr)
	if err != nil {
		log.Fatal("failed to connect to story service", zap.Error(err))
	}
	defer storyConn.Close()
	storyClient := storypb.NewStoryServiceClient(storyConn)

	callConn, err := dialService("call-service", cfg.CallGRPCAddr)
	if err != nil {
		log.Fatal("failed to connect to call service", zap.Error(err))
	}
	defer callConn.Close()
	callClient := callpb.NewCallServiceClient(callConn)

	channelConn, err := dialService("channel-service", cfg.ChannelGRPCAddr)
	if err != nil {
		log.Fatal("failed to connect to channel service", zap.Error(err))
	}
	defer channelConn.Close()
	channelClient := channelpb.NewChannelServiceClient(channelConn)

	socialConn, err := dialService("social-service", cfg.SocialGRPCAddr)
	if err != nil {
		log.Fatal("failed to connect to social service", zap.Error(err))
	}
	defer socialConn.Close()
	socialClient := socialpb.NewSocialServiceClient(socialConn)

	miniappConn, err := dialService("miniapp-service", cfg.MiniAppGRPCAddr)
	if err != nil {
		log.Fatal("failed to connect to miniapp service", zap.Error(err))
	}
	defer miniappConn.Close()
	miniappClient := miniapppb.NewMiniAppServiceClient(miniappConn)

	businessConn, err := dialService("business-service", cfg.BusinessGRPCAddr)
	if err != nil {
		log.Fatal("failed to connect to business service", zap.Error(err))
	}
	defer businessConn.Close()
	businessClient := businesspb.NewBusinessServiceClient(businessConn)

	// Media service requires a larger message size limit for client streaming uploads
	mediaOpts := []grpc.DialOption{
		grpc.WithDefaultCallOptions(
			grpc.MaxCallRecvMsgSize(110*1024*1024),
			grpc.MaxCallSendMsgSize(110*1024*1024),
		),
	}
	mediaConn, err := dialService("media-service", cfg.MediaGRPCAddr, mediaOpts...)
	if err != nil {
		log.Fatal("failed to connect to media service", zap.Error(err))
	}
	defer mediaConn.Close()
	mediaClient := mediapb.NewMediaServiceClient(mediaConn)

	aiConn, err := dialService("ai-service", cfg.AIGRPCAddr)
	var aiClient aipb.AIServiceClient
	if err != nil {
		log.Warn("could not connect to ai service, running with fallback", zap.Error(err))
	} else {
		defer aiConn.Close()
		aiClient = aipb.NewAIServiceClient(aiConn)
	}

	// ── WebSocket Hub ──────────────────────────────────────────────────────────
	hub := ws.NewHub()
	go hub.Run()

	startPushNotificationWorker(ctx, redisClient, chatClient, hub, log)

	// ── Gin Engine ────────────────────────────────────────────────────────────
	gin.SetMode(gin.ReleaseMode)
	r := gin.New()

	r.Use(gin.Recovery())
	r.Use(ginLogger(log))
	r.Use(corsMiddleware(cfg.CORSOrigins))
	r.Use(middleware.GeoMiddleware())
	r.Use(metrics.PrometheusMiddleware())
	r.Use(cdn.CDNMiddleware())

	// Prometheus metrics endpoint
	r.GET("/metrics", metrics.Handler())

	// Initialize event bus
	bus := eventbus.New(redisClient, log)
	_ = bus // available for async publishing in handlers

	// Handlers
	authHandler := handlers.NewAuthHandler(authClient, log)
	chatHandler := handlers.NewChatHandler(chatClient, authClient, hub, log)
	aiHandler := handlers.NewAIHandler(aiClient, log)
	mediaHandler := handlers.NewMediaHandler(mediaClient, cfg.TelegramBotToken, log)
	groupHandler := handlers.NewGroupHandler(groupClient, log)
	storyHandler := handlers.NewStoryHandler(storyClient, log)
	callHandler := handlers.NewCallHandler(callClient, log)
	channelHandler := handlers.NewChannelHandler(channelClient, log)
	socialHandler := handlers.NewSocialHandler(socialClient, authClient, log)
	miniappHandler := handlers.NewMiniAppHandler(miniappClient, log)
	businessHandler := handlers.NewBusinessHandler(businessClient, log)
	docsHandler := handlers.NewDocsHandler()

	// ── Root Status & Health Endpoints ──────────────────────────────────────
	r.GET("/", func(c *gin.Context) {
		c.JSON(200, gin.H{
			"service": "GoChat API Gateway",
			"status":  "online",
			"version": "1.0.0",
			"docs":    "/docs",
			"swagger": "/swagger",
			"metrics": "/metrics",
		})
	})
	r.GET("/healthz", func(c *gin.Context) {
		c.JSON(200, gin.H{"status": "healthy", "service": "api-gateway"})
	})
	//hhshshh
	// ── Interactive API Documentation ─────────────────────────────────────────
	r.GET("/openapi.json", docsHandler.OpenAPIJSON)
	r.GET("/docs", docsHandler.SwaggerUI)
	r.GET("/swagger", docsHandler.SwaggerUI)
	r.GET("/redoc", docsHandler.Redoc)

	// ── Route Configurations ──────────────────────────────────────────────────

	// Public Routes
	api := r.Group("/api/v1")
	{
		auth := api.Group("/auth")
		{
			authLimiter := middleware.RateLimitMiddleware(redisClient, middleware.RateLimitConfig{
				Prefix: "rl:auth",
				Limit:  20,
				Window: time.Minute,
			})

			auth.POST("/register", authLimiter, authHandler.Register)
			auth.POST("/login", authLimiter, authHandler.Login)
			auth.POST("/refresh", authLimiter, authHandler.Refresh)
			auth.POST("/recovery/request", authLimiter, authHandler.RequestAccountRecovery)
			auth.POST("/recovery/verify", authLimiter, authHandler.VerifyAccountRecovery)
		}

		// Public Marketplace Routes
		marketplace := api.Group("/marketplace")
		{
			marketplace.GET("/products", businessHandler.ListMarketplaceProducts)
			marketplace.GET("/products/:id", businessHandler.GetMarketplaceProduct)
			marketplace.GET("/categories", businessHandler.ListCategories)
			marketplace.GET("/store", businessHandler.GetStore)
			marketplace.GET("/products/:id/reviews", businessHandler.ListReviews)
		}
	}

	// Authenticated Routes
	authRequired := api.Group("")
	authRequired.Use(
		middleware.AuthMiddleware(authClient),
		middleware.CsrfMiddleware(),
	)
	{
		// Profile & Presence
		authRequired.POST("/auth/logout", authHandler.Logout)
		authRequired.GET("/users/:id", authHandler.GetUser)
		authRequired.PATCH("/users/me", authHandler.UpdateUser)
		authRequired.DELETE("/users/me", authHandler.DeleteUser)
		authRequired.POST("/users/presence", authHandler.UpdatePresence)
		authRequired.POST("/users/sync", authHandler.SyncContacts)

		// Sessions & Security Audit Logs
		authRequired.GET("/auth/sessions", authHandler.GetActiveSessions)
		authRequired.DELETE("/auth/sessions/other", authHandler.TerminateAllOtherSessions)
		authRequired.DELETE("/auth/sessions/:id", authHandler.TerminateSession)
		authRequired.GET("/auth/audit-logs", authHandler.GetSecurityAuditLogs)
		authRequired.POST("/users/report", authHandler.ReportUser)
		authRequired.PUT("/users/privacy", authHandler.UpdatePrivacySettings)

		// 2FA & PIN
		authRequired.POST("/auth/2fa", authHandler.SetTwoStepPIN)
		authRequired.POST("/auth/2fa/verify", authHandler.VerifyTwoStepPIN)

		// Phone OTP & Registration
		authRequired.POST("/auth/phone", authHandler.RegisterPhone)
		authRequired.POST("/auth/phone/verify", authHandler.VerifyPhoneOTP)

		// Push Notifications Subscription
		authRequired.POST("/auth/push/subscribe", authHandler.SubscribePush)

		// E2EE Keys
		authRequired.POST("/auth/e2ee/keys", authHandler.UploadE2EEKeys)
		authRequired.GET("/auth/e2ee/keys/:user_id", authHandler.GetE2EEKeys)

		// Blocks & Privacy
		authRequired.POST("/privacy/blocks/:user_id", authHandler.BlockUser)
		authRequired.DELETE("/privacy/blocks/:user_id", authHandler.UnblockUser)
		authRequired.GET("/privacy/blocks", authHandler.GetBlockedUsers)

		// Conversations
		authRequired.POST("/chat/conversations", chatHandler.CreateConversation)
		authRequired.GET("/chat/conversations", chatHandler.GetConversations)
		authRequired.GET("/chat/conversations/:id", chatHandler.GetConversation)
		authRequired.POST("/chat/conversations/:id/members", chatHandler.AddMember)
		authRequired.DELETE("/chat/conversations/:id/members", chatHandler.RemoveMember)

		// Messages & Schedules
		messageLimiter := middleware.RateLimitMiddleware(redisClient, middleware.RateLimitConfig{
			Prefix:             "rl:chat:send",
			Limit:              60,
			Window:             time.Minute,
			UseUserIfAvailable: true,
		})
		authRequired.POST("/chat/conversations/:id/messages", messageLimiter, chatHandler.SendMessage)
		authRequired.POST("/chat/conversations/:id/messages/schedule", chatHandler.ScheduleMessage)
		authRequired.GET("/chat/conversations/:id/messages", chatHandler.GetMessages)
		authRequired.GET("/chat/threads/:parent_id", chatHandler.GetThread)
		authRequired.PUT("/chat/messages/:id", chatHandler.EditMessage)
		authRequired.DELETE("/chat/messages/:id", chatHandler.DeleteMessage)
		authRequired.GET("/chat/unfurl", chatHandler.UnfurlURL)
		authRequired.POST("/chat/unfurl", chatHandler.UnfurlURL)

		// Reactions
		authRequired.POST("/chat/messages/:id/reactions", chatHandler.AddReaction)
		authRequired.DELETE("/chat/messages/:id/reactions", chatHandler.RemoveReaction)

		// Forwarding
		authRequired.POST("/chat/messages/:id/forward", chatHandler.ForwardMessage)

		// Message Status & Pins
		authRequired.POST("/chat/conversations/:id/read", chatHandler.MarkRead)
		authRequired.POST("/chat/messages/:id/pin", chatHandler.PinMessage)
		authRequired.DELETE("/chat/messages/:id/pin", chatHandler.UnpinMessage)

		// Search & Unread counts
		authRequired.GET("/chat/search", chatHandler.SearchMessages)
		authRequired.GET("/chat/unread", chatHandler.GetUnreadCounts)
		authRequired.POST("/chat/conversations/:id/typing", chatHandler.SendTypingIndicator)

		// Chat Folders
		authRequired.POST("/chat/folders", chatHandler.CreateFolder)
		authRequired.GET("/chat/folders", chatHandler.ListFolders)
		authRequired.DELETE("/chat/folders/:id", chatHandler.DeleteFolder)
		authRequired.POST("/chat/folders/:id/conversations", chatHandler.AddToFolder)
		authRequired.DELETE("/chat/folders/:id/conversations", chatHandler.RemoveFromFolder)

		// Chat Labels
		authRequired.POST("/chat/labels", chatHandler.AddLabel)
		authRequired.DELETE("/chat/labels", chatHandler.RemoveLabel)
		authRequired.GET("/chat/labels", chatHandler.ListLabels)

		// Chat Analytics
		authRequired.GET("/chat/analytics", chatHandler.GetChatAnalytics)

		// AI Chat Suite
		authRequired.POST("/ai/summarize", aiHandler.SummarizeChat)
		authRequired.POST("/ai/suggest-replies", aiHandler.SuggestReplies)
		authRequired.POST("/ai/translate", aiHandler.TranslateMessage)
		authRequired.POST("/ai/adjust-tone", aiHandler.AdjustTone)
		authRequired.POST("/ai/action-items", aiHandler.ExtractActionItems)

		// Notification Profiles
		authRequired.POST("/chat/notifications", chatHandler.SetNotificationProfile)
		authRequired.GET("/chat/notifications", chatHandler.GetNotificationProfiles)

		// Polls
		authRequired.POST("/chat/conversations/:id/polls", chatHandler.CreatePoll)
		authRequired.GET("/chat/polls/:id", chatHandler.GetPoll)
		authRequired.POST("/chat/polls/:id/vote", chatHandler.VotePoll)
		authRequired.POST("/chat/polls/:id/close", chatHandler.ClosePoll)

		// Media upload & Telegram CDN download
		uploadLimiter := middleware.RateLimitMiddleware(redisClient, middleware.RateLimitConfig{
			Prefix:             "rl:media:upload",
			Limit:              20,
			Window:             time.Minute,
			UseUserIfAvailable: true,
		})
		authRequired.POST("/media/upload", uploadLimiter, mediaHandler.Upload)
		authRequired.GET("/media/download/:fileId", mediaHandler.DownloadTelegram)

		// Group management
		authRequired.POST("/groups/:id/metadata", groupHandler.UpdateGroupMetadata)
		authRequired.GET("/groups/:id/metadata", groupHandler.GetGroupMetadata)
		authRequired.POST("/groups/:id/invite-link", groupHandler.GenerateInviteLink)
		authRequired.POST("/groups/join", groupHandler.JoinByInviteCode)
		authRequired.GET("/groups/:id/approvals", groupHandler.GetPendingApprovals)
		authRequired.POST("/groups/:id/approvals/:req_id", groupHandler.ResolvePendingApproval)
		authRequired.POST("/groups/:id/members/:user_id/promote", groupHandler.PromoteMember)
		authRequired.POST("/groups/:id/members/:user_id/demote", groupHandler.DemoteMember)

		// Communities
		authRequired.POST("/communities", groupHandler.CreateCommunity)
		authRequired.POST("/communities/:id/groups", groupHandler.AddGroupToCommunity)
		authRequired.DELETE("/communities/:id/groups/:group_id", groupHandler.RemoveGroupFromCommunity)
		authRequired.GET("/communities/:id", groupHandler.GetCommunity)
		authRequired.GET("/communities", groupHandler.ListCommunities)

		// Broadcast Lists
		authRequired.POST("/broadcasts", groupHandler.CreateBroadcastList)
		authRequired.DELETE("/broadcasts/:id", groupHandler.DeleteBroadcastList)
		authRequired.POST("/broadcasts/:id/recipients", groupHandler.AddBroadcastRecipient)
		authRequired.DELETE("/broadcasts/:id/recipients/:recipient_id", groupHandler.RemoveBroadcastRecipient)
		authRequired.GET("/broadcasts/:id", groupHandler.GetBroadcastList)

		// Ephemeral status/stories
		authRequired.POST("/stories", storyHandler.PostStory)
		authRequired.DELETE("/stories/:id", storyHandler.DeleteStory)
		authRequired.GET("/stories", storyHandler.GetStories)
		authRequired.POST("/stories/:id/view", storyHandler.ViewStory)
		authRequired.GET("/stories/:id/viewers", storyHandler.GetStoryViewerList)

		// Voice & Video calling with WebRTC signaling
		authRequired.POST("/calls", callHandler.StartCall)
		authRequired.POST("/calls/:id/accept", callHandler.AcceptCall)
		authRequired.POST("/calls/:id/reject", callHandler.RejectCall)
		authRequired.POST("/calls/:id/end", callHandler.EndCall)
		authRequired.POST("/calls/:id/signaling", callHandler.SendSignalingMessage)
		authRequired.GET("/calls/history", callHandler.GetCallHistory)

		// Public Channels
		authRequired.POST("/channels", channelHandler.CreateChannel)
		authRequired.DELETE("/channels/:id", channelHandler.DeleteChannel)
		authRequired.POST("/channels/:id/subscribe", channelHandler.SubscribeChannel)
		authRequired.POST("/channels/:id/unsubscribe", channelHandler.UnsubscribeChannel)
		authRequired.POST("/channels/:id/messages", channelHandler.PublishChannelMessage)
		authRequired.GET("/channels/:id/messages", channelHandler.GetChannelMessages)
		authRequired.GET("/channels/:id", channelHandler.GetChannelMetadata)
		authRequired.GET("/channels", channelHandler.ListChannels)

		// ── Phase 8: Social ───────────────────────────────────────────────
		authRequired.GET("/social/users/search", socialHandler.SearchUsers)
		authRequired.POST("/social/follow/:id", socialHandler.FollowUser)
		authRequired.DELETE("/social/follow/:id", socialHandler.UnfollowUser)
		authRequired.GET("/social/followers", socialHandler.GetFollowers)
		authRequired.GET("/social/following", socialHandler.GetFollowing)
		authRequired.POST("/social/moments", socialHandler.CreateMoment)
		authRequired.POST("/social/moments/:id/like", socialHandler.LikeMoment)
		authRequired.POST("/social/moments/:id/comment", socialHandler.CommentMoment)
		authRequired.GET("/social/moments/feed", socialHandler.GetMomentsFeed)
		authRequired.POST("/social/nearby", socialHandler.SetNearbyVisible)
		authRequired.GET("/social/nearby", socialHandler.GetNearbyUsers)
		authRequired.POST("/social/badges", socialHandler.ApplyForBadge)
		authRequired.GET("/social/badges/:id", socialHandler.GetUserBadges)
		authRequired.POST("/social/audio-rooms", socialHandler.CreateAudioRoom)
		authRequired.POST("/social/audio-rooms/:id/join", socialHandler.JoinAudioRoom)
		authRequired.POST("/social/audio-rooms/:id/leave", socialHandler.LeaveAudioRoom)
		authRequired.GET("/social/audio-rooms", socialHandler.ListAudioRooms)

		// ── Phase 9: Mini-Apps & Developer Platform ────────────────────────
		authRequired.POST("/bots", miniappHandler.RegisterBot)
		authRequired.GET("/bots", miniappHandler.ListBots)
		authRequired.POST("/bots/:id/messages", miniappHandler.SendBotMessage)
		authRequired.POST("/miniapps", miniappHandler.RegisterMiniApp)
		authRequired.POST("/miniapps/:id/launch", miniappHandler.LaunchMiniApp)
		authRequired.GET("/miniapps", miniappHandler.ListMiniApps)
		authRequired.POST("/developer/webhooks", miniappHandler.RegisterWebhook)
		authRequired.GET("/developer/webhooks", miniappHandler.ListWebhooks)
		authRequired.DELETE("/developer/webhooks/:id", miniappHandler.DeleteWebhook)
		authRequired.POST("/developer/api-keys", miniappHandler.CreateAPIKey)
		authRequired.GET("/developer/api-keys", miniappHandler.ListAPIKeys)
		authRequired.DELETE("/developer/api-keys/:id", miniappHandler.RevokeAPIKey)

		// ── Phase 9: Business Suite & Marketplace ──────────────────────────
		authRequired.POST("/business/profile", businessHandler.CreateBusinessProfile)
		authRequired.GET("/business/profile", businessHandler.GetBusinessProfile)
		authRequired.POST("/business/products", businessHandler.CreateMarketplaceProduct)
		authRequired.PUT("/business/products/:id", businessHandler.UpdateMarketplaceProduct)
		authRequired.DELETE("/business/products/:id", businessHandler.DeleteMarketplaceProduct)
		authRequired.GET("/business/products", businessHandler.GetMyProducts)
		authRequired.POST("/marketplace/products/:id/view", businessHandler.TrackProductView)
		authRequired.POST("/marketplace/products/:id/reviews", businessHandler.CreateReview)

		// Product Variants
		authRequired.POST("/business/products/:id/variants", businessHandler.CreateProductVariant)
		authRequired.GET("/business/products/:id/variants", businessHandler.ListProductVariants)
		authRequired.PUT("/business/products/:id/variants/:variantId", businessHandler.UpdateProductVariant)
		authRequired.DELETE("/business/products/:id/variants/:variantId", businessHandler.DeleteProductVariant)

		// Cart, Orders & Coupons
		authRequired.POST("/marketplace/cart", businessHandler.AddToCart)
		authRequired.GET("/marketplace/cart", businessHandler.GetCart)
		authRequired.PUT("/marketplace/cart/items/:id", businessHandler.UpdateCartItem)
		authRequired.DELETE("/marketplace/cart/items/:id", businessHandler.RemoveFromCart)
		authRequired.DELETE("/marketplace/cart", businessHandler.ClearCart)

		checkoutLimiter := middleware.RateLimitMiddleware(redisClient, middleware.RateLimitConfig{
			Prefix:             "rl:checkout",
			Limit:              10,
			Window:             time.Minute,
			UseUserIfAvailable: true,
		})
		authRequired.POST("/marketplace/orders", checkoutLimiter, businessHandler.CreateOrders)
		authRequired.GET("/marketplace/orders", businessHandler.ListBuyerOrders)
		authRequired.GET("/marketplace/orders/:id", businessHandler.GetOrder)
		authRequired.GET("/business/orders", businessHandler.ListSellerOrders)
		authRequired.PUT("/business/orders/:id/status", businessHandler.UpdateOrderStatus)
		authRequired.PUT("/business/orders/:id/tracking", businessHandler.UpdateOrderTracking)

		authRequired.POST("/business/coupons", businessHandler.CreateCoupon)
		authRequired.GET("/business/coupons", businessHandler.ListBusinessCoupons)
		authRequired.POST("/marketplace/coupons/validate", businessHandler.ValidateCoupon)

		// Wishlist
		authRequired.POST("/marketplace/products/:id/wishlist", businessHandler.ToggleWishlist)
		authRequired.GET("/marketplace/wishlist", businessHandler.GetWishlist)

		// Product Q&A
		qaLimiter := middleware.RateLimitMiddleware(redisClient, middleware.RateLimitConfig{
			Prefix:             "rl:qa",
			Limit:              5,
			Window:             time.Hour,
			UseUserIfAvailable: true,
		})
		authRequired.POST("/marketplace/products/:id/questions", qaLimiter, businessHandler.AskProductQuestion)
		authRequired.GET("/marketplace/products/:id/questions", businessHandler.GetProductQuestions)
		authRequired.POST("/marketplace/questions/:id/answer", businessHandler.AnswerProductQuestion)
		authRequired.POST("/marketplace/questions/:id/flag", businessHandler.FlagProductQuestion)
		authRequired.POST("/marketplace/questions/:id/moderate", businessHandler.ModerateProductQuestion)

		authRequired.POST("/business/catalogs", businessHandler.CreateCatalog)
		authRequired.POST("/business/catalogs/:id/products", businessHandler.AddProduct)
		authRequired.GET("/business/catalogs/:id/products", businessHandler.ListProducts)
		authRequired.POST("/business/appointments", businessHandler.CreateAppointmentSlot)
		authRequired.POST("/business/appointments/:id/book", businessHandler.BookAppointment)
		authRequired.GET("/business/appointments", businessHandler.ListAppointments)
		authRequired.POST("/business/auto-replies", businessHandler.SetAutoReply)
		authRequired.GET("/business/auto-replies", businessHandler.GetAutoReplies)
		authRequired.POST("/business/queue/enqueue", businessHandler.EnqueueCustomer)
		authRequired.POST("/business/queue/dequeue", businessHandler.DequeueCustomer)
		authRequired.POST("/business/queue/position", businessHandler.GetQueuePosition)
	}

	// Real-time WebSocket connection endpoint (secured via AuthMiddleware)
	r.GET("/ws", middleware.AuthMiddleware(authClient), ws.ServeWs(hub, chatClient, cfg.CORSOrigins, log))

	// ── Server Start ──────────────────────────────────────────────────────────
	srv := &http.Server{
		Addr:    fmt.Sprintf(":%s", cfg.HTTPPort),
		Handler: r,
	}

	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)

	go func() {
		log.Info("API Gateway started", zap.String("port", cfg.HTTPPort))
		if err := srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			log.Fatal("API Gateway server execution failed", zap.Error(err))
		}
	}()

	// ── Graceful Shutdown ─────────────────────────────────────────────────────
	<-quit
	log.Info("shutting down API Gateway...")

	shutdownCtx, shutdownCancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer shutdownCancel()

	if err := srv.Shutdown(shutdownCtx); err != nil {
		log.Fatal("API Gateway forced to shutdown", zap.Error(err))
	}

	log.Info("API Gateway exited cleanly")
}

// corsMiddleware injects headers supporting CORS access.
func corsMiddleware(origins string) gin.HandlerFunc {
	return func(c *gin.Context) {
		reqOrigin := c.Request.Header.Get("Origin")
		if reqOrigin != "" {
			c.Writer.Header().Set("Access-Control-Allow-Origin", reqOrigin)
		} else {
			c.Writer.Header().Set("Access-Control-Allow-Origin", "*")
		}

		c.Writer.Header().Set("Access-Control-Allow-Credentials", "true")
		c.Writer.Header().Set("Access-Control-Allow-Headers", "Content-Type, Content-Length, Accept-Encoding, X-CSRF-Token, Authorization, accept, origin, Cache-Control, X-Requested-With, X-Country-Code, Range")
		c.Writer.Header().Set("Access-Control-Allow-Methods", "POST, OPTIONS, GET, PUT, PATCH, DELETE, HEAD")
		c.Writer.Header().Set("Access-Control-Expose-Headers", "Content-Length, Authorization, Content-Range, Accept-Ranges")

		if c.Request.Method == "OPTIONS" {
			c.AbortWithStatus(http.StatusNoContent)
			return
		}

		c.Next()
	}
}

// ginLogger customises HTTP access logs using Zap.
func ginLogger(log *zap.Logger) gin.HandlerFunc {
	return func(c *gin.Context) {
		start := time.Now()
		path := c.Request.URL.Path
		query := c.Request.URL.RawQuery

		c.Next()

		latency := time.Since(start)
		status := c.Writer.Status()

		log.Info("HTTP request",
			zap.Int("status", status),
			zap.String("method", c.Request.Method),
			zap.String("path", path),
			zap.String("query", query),
			zap.Duration("latency", latency),
			zap.String("ip", c.ClientIP()),
		)
	}
}

func startPushNotificationWorker(ctx context.Context, redisClient *redis.Client, chatClient chatpb.ChatServiceClient, hub *ws.Hub, log *zap.Logger) {
	if redisClient == nil {
		log.Info("Push Notification background worker skipped (no Redis client configured)")
		return
	}
	pubsub := redisClient.PSubscribe(ctx, "chat:*")
	go func() {
		defer pubsub.Close()
		log.Info("Push Notification background worker started")

		for {
			select {
			case <-ctx.Done():
				return
			case msg := <-pubsub.Channel():
				var payload map[string]string
				if err := json.Unmarshal([]byte(msg.Payload), &payload); err != nil {
					continue
				}

				// Handle real-time Call events (e.g. from call-service published to "chat:calls")
				if strings.HasPrefix(payload["event"], "call_") {
					targetUserID := payload["target_user_id"]
					if targetUserID != "" {
						hub.SendToUser(targetUserID, []byte(msg.Payload))
						log.Debug("Forwarded call event to target user",
							zap.String("event", payload["event"]),
							zap.String("target_user_id", targetUserID),
						)
					}
					continue
				}

				convIDStr := payload["conv_id"]
				actorIDStr := payload["actor_id"]
				msgIDStr := payload["msg_id"]
				eventType := payload["event"]
				if convIDStr == "" || actorIDStr == "" {
					continue
				}

				// Fetch conversation details to get member IDs
				resp, err := chatClient.GetConversation(ctx, &chatpb.GetConversationRequest{
					ConversationId: convIDStr,
					UserId:         actorIDStr,
				})
				if err != nil {
					continue
				}

				// Determine online/offline recipients and deliver events
				onlineUsers := hub.GetOnlineUsers()
				onlineMap := make(map[string]bool)
				for _, uid := range onlineUsers {
					onlineMap[uid] = true
				}

				for _, memberID := range resp.Conversation.MemberIds {
					if memberID == actorIDStr {
						continue
					}

					if onlineMap[memberID] {
						// Deliver the event to online WebSocket clients in real-time
						hub.SendToUser(memberID, []byte(msg.Payload))
						log.Debug("Delivered real-time event to online user",
							zap.String("event", eventType),
							zap.String("recipient_id", memberID),
							zap.String("conversation_id", convIDStr),
						)
					} else if eventType == "new_message" {
						// Only log push notifications for new messages
						log.Info("MOCK PUSH NOTIFICATION TRIGGERED (RECIPIENT OFFLINE)",
							zap.String("recipient_id", memberID),
							zap.String("conversation_id", convIDStr),
							zap.String("message_id", msgIDStr),
							zap.String("sender_id", actorIDStr),
						)
					}
				}
			}
		}
	}()
}

