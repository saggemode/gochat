package main

import (
	"context"
	"fmt"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/gin-gonic/gin"
	"go.uber.org/zap"
	"google.golang.org/grpc"
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
	paymentpb "gochat/gen/payment"
	socialpb "gochat/gen/social"
	storypb "gochat/gen/story"
	"gochat/pkg/config"
	"gochat/pkg/database"
	"gochat/pkg/health"
	"gochat/pkg/logger"
	"gochat/services/gateway/handlers"
	"gochat/services/gateway/middleware"
	"gochat/services/gateway/ws"

	"encoding/json"
	"strings"
	"github.com/redis/go-redis/v9"
)

func main() {
	log := logger.New("api-gateway")
	defer log.Sync()

	cfg := config.Load()
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	// ── Redis ─────────────────────────────────────────────────────────────────
	redisClient, err := database.NewRedis(ctx, cfg.RedisAddr, cfg.RedisPassword, cfg.RedisDB, log)
	if err != nil {
		log.Fatal("failed to connect to redis", zap.Error(err))
	}
	defer redisClient.Close()

	// ── Health Check Server ──────────────────────────────────────────────────
	healthSrv := health.New("api-gateway", cfg.HealthPort, log)
	healthSrv.AddCheck("redis", func(ctx context.Context) error {
		return redisClient.Ping(ctx).Err()
	})
	healthSrv.Start()
	defer healthSrv.Stop()

	// ── Dial gRPC Services ────────────────────────────────────────────────────
	dialOpts := []grpc.DialOption{
		grpc.WithTransportCredentials(insecure.NewCredentials()),
	}

	authConn, err := grpc.Dial(cfg.AuthGRPCAddr, dialOpts...)
	if err != nil {
		log.Fatal("failed to connect to auth service", zap.Error(err))
	}
	defer authConn.Close()
	authClient := authpb.NewAuthServiceClient(authConn)

	chatConn, err := grpc.Dial(cfg.ChatGRPCAddr, dialOpts...)
	if err != nil {
		log.Fatal("failed to connect to chat service", zap.Error(err))
	}
	defer chatConn.Close()
	chatClient := chatpb.NewChatServiceClient(chatConn)

	groupConn, err := grpc.Dial(cfg.GroupGRPCAddr, dialOpts...)
	if err != nil {
		log.Fatal("failed to connect to group service", zap.Error(err))
	}
	defer groupConn.Close()
	groupClient := grouppb.NewGroupServiceClient(groupConn)

	storyConn, err := grpc.Dial(cfg.StoryGRPCAddr, dialOpts...)
	if err != nil {
		log.Fatal("failed to connect to story service", zap.Error(err))
	}
	defer storyConn.Close()
	storyClient := storypb.NewStoryServiceClient(storyConn)

	callConn, err := grpc.Dial(cfg.CallGRPCAddr, dialOpts...)
	if err != nil {
		log.Fatal("failed to connect to call service", zap.Error(err))
	}
	defer callConn.Close()
	callClient := callpb.NewCallServiceClient(callConn)

	channelConn, err := grpc.Dial(cfg.ChannelGRPCAddr, dialOpts...)
	if err != nil {
		log.Fatal("failed to connect to channel service", zap.Error(err))
	}
	defer channelConn.Close()
	channelClient := channelpb.NewChannelServiceClient(channelConn)

	aiConn, err := grpc.Dial(cfg.AIGRPCAddr, dialOpts...)
	if err != nil {
		log.Fatal("failed to connect to ai service", zap.Error(err))
	}
	defer aiConn.Close()
	aiClient := aipb.NewAIServiceClient(aiConn)

	paymentConn, err := grpc.Dial(cfg.PaymentGRPCAddr, dialOpts...)
	if err != nil {
		log.Fatal("failed to connect to payment service", zap.Error(err))
	}
	defer paymentConn.Close()
	paymentClient := paymentpb.NewPaymentServiceClient(paymentConn)

	socialConn, err := grpc.Dial(cfg.SocialGRPCAddr, dialOpts...)
	if err != nil {
		log.Fatal("failed to connect to social service", zap.Error(err))
	}
	defer socialConn.Close()
	socialClient := socialpb.NewSocialServiceClient(socialConn)

	miniappConn, err := grpc.Dial(cfg.MiniAppGRPCAddr, dialOpts...)
	if err != nil {
		log.Fatal("failed to connect to miniapp service", zap.Error(err))
	}
	defer miniappConn.Close()
	miniappClient := miniapppb.NewMiniAppServiceClient(miniappConn)

	businessConn, err := grpc.Dial(cfg.BusinessGRPCAddr, dialOpts...)
	if err != nil {
		log.Fatal("failed to connect to business service", zap.Error(err))
	}
	defer businessConn.Close()
	businessClient := businesspb.NewBusinessServiceClient(businessConn)

	// Media service requires a larger message size limit for client streaming uploads
	mediaOpts := append(dialOpts, grpc.WithDefaultCallOptions(
		grpc.MaxCallRecvMsgSize(110*1024*1024),
		grpc.MaxCallSendMsgSize(110*1024*1024),
	))
	mediaConn, err := grpc.Dial(cfg.MediaGRPCAddr, mediaOpts...)
	if err != nil {
		log.Fatal("failed to connect to media service", zap.Error(err))
	}
	defer mediaConn.Close()
	mediaClient := mediapb.NewMediaServiceClient(mediaConn)

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

	// Handlers
	authHandler := handlers.NewAuthHandler(authClient, log)
	chatHandler := handlers.NewChatHandler(chatClient, log)
	mediaHandler := handlers.NewMediaHandler(mediaClient, log)
	groupHandler := handlers.NewGroupHandler(groupClient, log)
	storyHandler := handlers.NewStoryHandler(storyClient, log)
	callHandler := handlers.NewCallHandler(callClient, log)
	channelHandler := handlers.NewChannelHandler(channelClient, log)
	aiHandler := handlers.NewAIHandler(aiClient, log)
	paymentHandler := handlers.NewPaymentHandler(paymentClient, log)
	socialHandler := handlers.NewSocialHandler(socialClient, log)
	miniappHandler := handlers.NewMiniAppHandler(miniappClient, log)
	businessHandler := handlers.NewBusinessHandler(businessClient, log)

	// ── Route Configurations ──────────────────────────────────────────────────

	// Public Routes
	api := r.Group("/api")
	{
		auth := api.Group("/auth")
		{
			auth.POST("/register", authHandler.Register)
			auth.POST("/login", authHandler.Login)
			auth.POST("/refresh", authHandler.Refresh)
		}
	}

	// Authenticated Routes
	authRequired := api.Group("")
	authRequired.Use(middleware.AuthMiddleware(authClient))
	{
		// Profile & Presence
		authRequired.POST("/auth/logout", authHandler.Logout)
		authRequired.GET("/users/:id", authHandler.GetUser)
		authRequired.PATCH("/users/me", authHandler.UpdateUser)
		authRequired.POST("/users/presence", authHandler.UpdatePresence)

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
		authRequired.POST("/chat/conversations/:id/messages", chatHandler.SendMessage)
		authRequired.POST("/chat/conversations/:id/messages/schedule", chatHandler.ScheduleMessage)
		authRequired.GET("/chat/conversations/:id/messages", chatHandler.GetMessages)
		authRequired.GET("/chat/threads/:parent_id", chatHandler.GetThread)
		authRequired.PUT("/chat/messages/:id", chatHandler.EditMessage)
		authRequired.DELETE("/chat/messages/:id", chatHandler.DeleteMessage)

		// Reactions
		authRequired.POST("/chat/messages/:id/reactions", chatHandler.AddReaction)
		authRequired.DELETE("/chat/messages/:id/reactions", chatHandler.RemoveReaction)

		// Message Status & Pins
		authRequired.POST("/chat/conversations/:id/read", chatHandler.MarkRead)
		authRequired.POST("/chat/messages/:id/pin", chatHandler.PinMessage)
		authRequired.DELETE("/chat/messages/:id/pin", chatHandler.UnpinMessage)

		// Search & Unread counts
		authRequired.GET("/chat/search", chatHandler.SearchMessages)
		authRequired.GET("/chat/unread", chatHandler.GetUnreadCounts)
		authRequired.POST("/chat/conversations/:id/typing", chatHandler.SendTypingIndicator)

		// Media upload
		authRequired.POST("/media/upload", mediaHandler.Upload)

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

		// ── Phase 5: AI Assistant ──────────────────────────────────────────
		authRequired.POST("/ai/summarize", aiHandler.SummarizeChat)
		authRequired.POST("/ai/suggest-replies", aiHandler.SuggestReplies)
		authRequired.POST("/ai/translate", aiHandler.TranslateMessage)
		authRequired.POST("/ai/adjust-tone", aiHandler.AdjustTone)
		authRequired.POST("/ai/action-items", aiHandler.ExtractActionItems)

		// ── Phase 6: Payments & Wallet ────────────────────────────────────
		authRequired.POST("/wallet", paymentHandler.CreateWallet)
		authRequired.GET("/wallet", paymentHandler.GetWallet)
		authRequired.POST("/wallet/send", paymentHandler.SendPayment)
		authRequired.POST("/wallet/request", paymentHandler.RequestPayment)
		authRequired.POST("/expenses", paymentHandler.CreateExpenseGroup)
		authRequired.POST("/expenses/:id/items", paymentHandler.AddExpense)
		authRequired.POST("/expenses/:id/settle", paymentHandler.SettleExpense)
		authRequired.GET("/wallet/transactions", paymentHandler.GetTransactionHistory)

		// ── Phase 8: Social ───────────────────────────────────────────────
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

		// ── Phase 9: Business Suite ────────────────────────────────────────
		authRequired.POST("/business/profile", businessHandler.CreateBusinessProfile)
		authRequired.GET("/business/profile", businessHandler.GetBusinessProfile)
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
	r.GET("/ws", middleware.AuthMiddleware(authClient), ws.ServeWs(hub, chatClient, log))

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

	ctx, cancel = context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	if err := srv.Shutdown(ctx); err != nil {
		log.Fatal("API Gateway forced to shutdown", zap.Error(err))
	}

	log.Info("API Gateway exited cleanly")
}

// corsMiddleware injects headers supporting CORS access.
func corsMiddleware(origins string) gin.HandlerFunc {
	var allowedOrigins []string
	for _, o := range strings.Split(origins, ",") {
		allowedOrigins = append(allowedOrigins, strings.TrimSpace(o))
	}

	return func(c *gin.Context) {
		reqOrigin := c.Request.Header.Get("Origin")
		
		isAllowed := false
		for _, o := range allowedOrigins {
			if o == "*" || o == reqOrigin {
				isAllowed = true
				break
			}
		}

		if isAllowed {
			c.Writer.Header().Set("Access-Control-Allow-Origin", reqOrigin)
		} else if len(allowedOrigins) > 0 {
			c.Writer.Header().Set("Access-Control-Allow-Origin", allowedOrigins[0])
		}
		
		c.Writer.Header().Set("Access-Control-Allow-Credentials", "true")
		c.Writer.Header().Set("Access-Control-Allow-Headers", "Content-Type, Content-Length, Accept-Encoding, X-CSRF-Token, Authorization, accept, origin, Cache-Control, X-Requested-With")
		c.Writer.Header().Set("Access-Control-Allow-Methods", "POST, OPTIONS, GET, PUT, PATCH, DELETE")

		if c.Request.Method == "OPTIONS" {
			c.AbortWithStatus(204)
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

// startPushNotificationWorker listens to Redis for new messages, and if any recipient is offline, logs a mock push notification.
func startPushNotificationWorker(ctx context.Context, redisClient *redis.Client, chatClient chatpb.ChatServiceClient, hub *ws.Hub, log *zap.Logger) {
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

				// Intercept only new message events
				if payload["event"] != "new_message" {
					continue
				}

				convIDStr := payload["conv_id"]
				actorIDStr := payload["actor_id"]
				msgIDStr := payload["msg_id"]
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

				// Find offline recipients
				onlineUsers := hub.GetOnlineUsers()
				onlineMap := make(map[string]bool)
				for _, uid := range onlineUsers {
					onlineMap[uid] = true
				}

				for _, memberID := range resp.Conversation.MemberIds {
					if memberID == actorIDStr {
						continue
					}

					if !onlineMap[memberID] {
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
