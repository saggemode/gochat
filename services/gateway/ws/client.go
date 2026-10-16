package ws

import (
	"context"
	"encoding/json"
	"net/http"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/gorilla/websocket"
	"go.uber.org/zap"

	"strings"

	authpb "gochat/gen/auth"
	chatpb "gochat/gen/chat"
)

const (
	// Time allowed to write a message to the peer.
	writeWait = 10 * time.Second

	// Time allowed to read the next pong message from the peer.
	pongWait = 60 * time.Second

	// Send pings to peer with this period. Must be less than pongWait.
	pingPeriod = (pongWait * 9) / 10

	// Maximum message size allowed from peer (64 MB for images, voice notes, and media payloads).
	maxMessageSize = 64 * 1024 * 1024

	// Minimum interval between relayed typing events for the same
	// user+conversation pair. Events arriving sooner are silently dropped.
	typingThrottleInterval = 3 * time.Second
)

// Client represents a single connected WebSocket client.
type Client struct {
	hub        *Hub
	conn       *websocket.Conn
	send       chan []byte
	userID     string
	authClient authpb.AuthServiceClient
	chatClient chatpb.ChatServiceClient
	log        *zap.Logger
	cancel     context.CancelFunc

	// typingThrottle tracks the last time a typing event was relayed for
	// each conversation ID. Only accessed from readPump (single goroutine)
	// so no mutex is needed.
	typingThrottle map[string]time.Time
}

// readPump pumps messages from the websocket connection to the hub/discard.
// It ensures pong deadliness is met.
func (c *Client) readPump() {
	// Set online in DB and broadcast presence event
	if c.authClient != nil {
		go func(uid string) {
			ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
			defer cancel()
			_, _ = c.authClient.SetPresence(ctx, &authpb.SetPresenceRequest{UserId: uid, IsOnline: true})
		}(c.userID)
	}
	onlineEvt, _ := json.Marshal(map[string]interface{}{
		"type":      "presence",
		"user_id":   c.userID,
		"is_online": true,
	})
	c.hub.Broadcast(onlineEvt, c.userID)

	// Send presence of all currently online users to this newly connected client immediately
	for _, onlineUID := range c.hub.GetOnlineUsers() {
		if onlineUID != c.userID {
			pEvt, _ := json.Marshal(map[string]interface{}{
				"type":      "presence",
				"user_id":   onlineUID,
				"is_online": true,
			})
			select {
			case c.send <- pEvt:
			default:
			}
		}
	}

	defer func() {
		c.cancel()
		c.hub.unregister <- c
		c.conn.Close()

		// If user has no remaining open connections, mark offline in DB and broadcast
		go func(uid string) {
			time.Sleep(150 * time.Millisecond)
			if !c.hub.IsUserOnline(uid) {
				now := time.Now().Unix()
				if c.authClient != nil {
					ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
					defer cancel()
					_, _ = c.authClient.SetPresence(ctx, &authpb.SetPresenceRequest{UserId: uid, IsOnline: false})
				}
				offlineEvt, _ := json.Marshal(map[string]interface{}{
					"type":      "presence",
					"user_id":   uid,
					"is_online": false,
					"last_seen": now,
				})
				c.hub.Broadcast(offlineEvt, "")
			}
		}(c.userID)
	}()
	c.conn.SetReadLimit(maxMessageSize)
	c.conn.SetReadDeadline(time.Now().Add(pongWait))
	c.conn.SetPongHandler(func(string) error {
		c.conn.SetReadDeadline(time.Now().Add(pongWait))
		return nil
	})
	for {
		_, rawMessage, err := c.conn.ReadMessage()
		if err != nil {
			if websocket.IsUnexpectedCloseError(err, websocket.CloseGoingAway, websocket.CloseAbnormalClosure) {
				c.log.Warn("websocket read error", zap.Error(err))
			}
			break
		}

		if len(rawMessage) > 0 {
			var payload map[string]interface{}
			if err := json.Unmarshal(rawMessage, &payload); err == nil {
				// Inject sender id if missing
				if _, ok := payload["sender_id"]; !ok || payload["sender_id"] == "" {
					payload["sender_id"] = c.userID
				}

				msgType, _ := payload["type"].(string)

				// ── Typing indicator throttle ───────────────────────────────
				// Relay at most one typing event per typingThrottleInterval
				// for each conversation to avoid flooding recipients.
				if msgType == "typing" {
					convID, _ := payload["conversation_id"].(string)
					now := time.Now()
					if last, ok := c.typingThrottle[convID]; ok && now.Sub(last) < typingThrottleInterval {
						continue // drop — too soon since last relay
					}
					c.typingThrottle[convID] = now

					// Housekeeping: purge stale entries every 64 events to
					// prevent unbounded growth if user chats in many convos.
					if len(c.typingThrottle) > 64 {
						for k, v := range c.typingThrottle {
							if now.Sub(v) > typingThrottleInterval*2 {
								delete(c.typingThrottle, k)
							}
						}
					}
				}

				// ── Persist reactions via gRPC ──────────────────────────────
				// Reactions must be stored in the database via the Chat
				// service. The Chat service also publishes reaction events
				// to Redis → StreamMessages for cross-instance fan-out.
				if c.chatClient != nil && (msgType == "reaction" || msgType == "remove_reaction") {
					if rErr := c.persistReaction(msgType, payload); rErr != nil {
						c.log.Error("failed to persist reaction via gRPC",
							zap.Error(rErr),
							zap.String("user_id", c.userID),
							zap.String("reaction_type", msgType),
						)
						errResp, _ := json.Marshal(map[string]interface{}{
							"type":  "error",
							"error": "failed to persist reaction",
						})
						select {
						case c.send <- errResp:
						default:
						}
					}
					// Reaction fan-out happens via Redis → gRPC
					// StreamMessages, so no local broadcast needed.
					continue
				}

				// ── Handle delete message via gRPC ──────────────────────────
				if c.chatClient != nil && (msgType == "message_deleted" || msgType == "delete_message") {
					msgID, _ := payload["message_id"].(string)
					if msgID == "" {
						msgID, _ = payload["id"].(string)
					}
					if msgID != "" {
						go func(mID string) {
							ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
							defer cancel()
							_, _ = c.chatClient.DeleteMessage(ctx, &chatpb.DeleteMessageRequest{
								MessageId: mID,
								DeleterId: c.userID,
							})
						}(msgID)
					}
					payload["type"] = "message_deleted"
					payload["is_deleted"] = true
				}

				// ── Handle edit message via gRPC ────────────────────────────
				if c.chatClient != nil && (msgType == "message_edited" || msgType == "edit_message") {
					msgID, _ := payload["message_id"].(string)
					if msgID == "" {
						msgID, _ = payload["id"].(string)
					}
					content, _ := payload["content"].(string)
					if msgID != "" && content != "" {
						go func(mID, cnt string) {
							ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
							defer cancel()
							_, _ = c.chatClient.EditMessage(ctx, &chatpb.EditMessageRequest{
								MessageId: mID,
								EditorId:  c.userID,
								Content:   cnt,
							})
						}(msgID, content)
					}
					payload["type"] = "message_edited"
					payload["is_edited"] = true
				}

				// ── Persist chat messages via gRPC ──────────────────────────
				// Actual chat content must be saved to the chat service before
				// fan-out so offline users can retrieve messages later.
				// The chat service also publishes to Redis, which feeds the
				// gRPC StreamMessages — clients should dedup by message ID.
				if c.chatClient != nil && isChatContent(msgType, payload) {
					persisted, pErr := c.persistMessage(payload)
					if pErr != nil {
						c.log.Error("failed to persist WS message via gRPC",
							zap.Error(pErr),
							zap.String("user_id", c.userID),
						)
						errResp, _ := json.Marshal(map[string]interface{}{
							"type":  "error",
							"error": "failed to send message",
						})
						select {
						case c.send <- errResp:
						default:
						}
						continue // don't broadcast an unpersisted message
					}
					payload = persisted
				}

				// Check if targeted to a specific recipient user
				targetUserID, hasTarget := payload["recipient_id"].(string)
				delivered := false
				if hasTarget && targetUserID != "" {
					if b, err := json.Marshal(payload); err == nil {
						delivered = c.hub.SendToUser(targetUserID, b)
					}
				}
				// If not targeted or recipient not connected by that specific ID, broadcast to active peers
				if !delivered {
					if b, err := json.Marshal(payload); err == nil {
						c.hub.Broadcast(b, c.userID)
					}
				}

				// ── Delivery acknowledgment ────────────────────────────────
				// If the payload is a chat message and was delivered, notify sender
				if delivered && (msgType == "message" || msgType == "new_message" || msgType == "chat_message" || msgType == "") {
					msgID, _ := payload["message_id"].(string)
					if msgID == "" {
						msgID, _ = payload["id"].(string)
					}
					convID, _ := payload["conversation_id"].(string)
					if msgID != "" {
						ack, _ := json.Marshal(map[string]interface{}{
							"type":            "message_status",
							"message_id":      msgID,
							"conversation_id": convID,
							"status":          "delivered",
						})
						c.hub.SendToUser(c.userID, ack)
					}
				}

				// ── Query Presence ──────────────────────────────────────────
				if msgType == "query_presence" || msgType == "get_presence" {
					targetUID, _ := payload["user_id"].(string)
					if targetUID == "" {
						targetUID, _ = payload["target_user_id"].(string)
					}
					if targetUID != "" {
						isOnline := c.hub.IsUserOnline(targetUID)
						resp, _ := json.Marshal(map[string]interface{}{
							"type":      "presence",
							"user_id":   targetUID,
							"is_online": isOnline,
						})
						select {
						case c.send <- resp:
						default:
						}
					}
					// Also reply with all currently online users
					for _, onlineUID := range c.hub.GetOnlineUsers() {
						if onlineUID != c.userID {
							pEvt, _ := json.Marshal(map[string]interface{}{
								"type":      "presence",
								"user_id":   onlineUID,
								"is_online": true,
							})
							select {
							case c.send <- pEvt:
							default:
							}
						}
					}
				}

			}
		}
	}
}

// writePump pumps messages from the client's send channel to the websocket.
// It also handles pings.
func (c *Client) writePump() {
	ticker := time.NewTicker(pingPeriod)
	defer func() {
		ticker.Stop()
		c.conn.Close()
	}()
	for {
		select {
		case message, ok := <-c.send:
			c.conn.SetWriteDeadline(time.Now().Add(writeWait))
			if !ok {
				c.conn.WriteMessage(websocket.CloseMessage, []byte{})
				return
			}

			// Send first message as its own frame
			if err := c.conn.WriteMessage(websocket.TextMessage, message); err != nil {
				return
			}

			// Drain queued messages, each as a separate WebSocket frame.
			// Previously these were concatenated with '\n' into a single frame,
			// which broke clients that expect one JSON object per frame.
			n := len(c.send)
			for i := 0; i < n; i++ {
				if err := c.conn.WriteMessage(websocket.TextMessage, <-c.send); err != nil {
					return
				}
			}
		case <-ticker.C:
			c.conn.SetWriteDeadline(time.Now().Add(writeWait))
			if err := c.conn.WriteMessage(websocket.PingMessage, nil); err != nil {
				return
			}
		}
	}
}

// listenGrpcStream connects to Chat Service gRPC stream and pushes events into client send channel.
func (c *Client) listenGrpcStream(ctx context.Context, chatClient chatpb.ChatServiceClient) {
	// Recover from panics so a single bad stream doesn't crash the entire gateway.
	defer func() {
		if r := recover(); r != nil {
			c.log.Error("recovered from panic in gRPC stream listener",
				zap.Any("panic", r),
				zap.String("user_id", c.userID),
			)
		}
	}()

	stream, err := chatClient.StreamMessages(ctx, &chatpb.StreamMessagesRequest{UserId: c.userID})
	if err != nil {
		c.log.Error("failed to connect to gRPC stream", zap.Error(err), zap.String("user_id", c.userID))
		return
	}

	c.log.Info("gRPC stream subscription established", zap.String("user_id", c.userID))

	for {
		event, err := stream.Recv()
		if err != nil {
			// Context cancellation is standard for client cleanup
			if ctx.Err() == nil {
				c.log.Warn("gRPC message stream read error", zap.Error(err))
			}
			break
		}

		data, err := json.Marshal(event)
		if err != nil {
			c.log.Error("failed to marshal message event to JSON", zap.Error(err))
			continue
		}

		select {
		case c.send <- data:
		case <-ctx.Done():
			return
		default:
			c.log.Warn("send channel full, discarding message event", zap.String("user_id", c.userID))
		}
	}
}

// ServeWs upgrades HTTP connections to WebSockets and registers the client.
func ServeWs(hub *Hub, chatClient chatpb.ChatServiceClient, authClient authpb.AuthServiceClient, allowedOrigins string, log *zap.Logger) gin.HandlerFunc {
	return func(c *gin.Context) {
		userID := c.GetString("user_id")
		if userID == "" {
			c.JSON(http.StatusUnauthorized, gin.H{"error": "unauthorised user in context"})
			return
		}

		upgrader := websocket.Upgrader{
			ReadBufferSize:  1024,
			WriteBufferSize: 1024,
			CheckOrigin: func(r *http.Request) bool {
				origin := r.Header.Get("Origin")
				if origin == "" {
					return true // Allow non-browser clients (like curl, desktop apps)
				}
				if strings.HasPrefix(origin, "http://localhost:") || strings.HasPrefix(origin, "https://localhost:") || strings.HasPrefix(origin, "http://127.0.0.1:") || strings.HasPrefix(origin, "https://127.0.0.1:") {
					return true
				}
				originsList := strings.Split(allowedOrigins, ",")
				for _, o := range originsList {
					o = strings.TrimSpace(o)
					if o == "*" || o == origin {
						return true
					}
				}
				log.Warn("WebSocket origin not allowed", zap.String("origin", origin))
				return false
			},
		}

		conn, err := upgrader.Upgrade(c.Writer, c.Request, nil)
		if err != nil {
			log.Error("failed to upgrade to websocket", zap.Error(err))
			return
		}

		ctx, cancel := context.WithCancel(context.Background())
		client := &Client{
			hub:            hub,
			conn:           conn,
			send:           make(chan []byte, 256),
			userID:         userID,
			authClient:     authClient,
			chatClient:     chatClient,
			log:            log,
			cancel:         cancel,
			typingThrottle: make(map[string]time.Time),
		}

		client.hub.register <- client

		go client.writePump()
		go client.readPump()
		go client.listenGrpcStream(ctx, chatClient)
	}
}

// isChatContent returns true when the WebSocket payload represents a chat
// message that must be persisted (text or media with a conversation ID).
// Reactions are handled separately by persistReaction.
// Ephemeral events (typing, presence, calls, etc.) return false.
func isChatContent(msgType string, payload map[string]interface{}) bool {
	switch msgType {
	case "message", "new_message", "chat_message", "":
		convID, _ := payload["conversation_id"].(string)
		if convID == "" {
			return false
		}
		content, _ := payload["content"].(string)
		mediaURL, _ := payload["media_url"].(string)
		return content != "" || mediaURL != ""
	default:
		return false
	}
}

// persistReaction calls the Chat service AddReaction or RemoveReaction gRPC
// endpoint depending on the event type. This ensures reactions sent over
// WebSocket are stored in the database and trigger Redis pub/sub events for
// cross-instance fan-out.
func (c *Client) persistReaction(msgType string, payload map[string]interface{}) error {
	msgID, _ := payload["message_id"].(string)
	if msgID == "" {
		msgID, _ = payload["id"].(string)
	}
	emoji, _ := payload["emoji"].(string)
	if msgID == "" || emoji == "" {
		return nil // nothing to persist
	}

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	switch msgType {
	case "reaction":
		_, err := c.chatClient.AddReaction(ctx, &chatpb.AddReactionRequest{
			MessageId: msgID,
			UserId:    c.userID,
			Emoji:     emoji,
		})
		return err
	case "remove_reaction":
		_, err := c.chatClient.RemoveReaction(ctx, &chatpb.RemoveReactionRequest{
			MessageId: msgID,
			UserId:    c.userID,
			Emoji:     emoji,
		})
		return err
	default:
		return nil
	}
}

// persistMessage calls the Chat service SendMessage gRPC to save the message
// in the database. It returns the payload enriched with server-assigned fields
// (message ID, created_at, status) so downstream recipients receive a
// fully-formed message object.
func (c *Client) persistMessage(payload map[string]interface{}) (map[string]interface{}, error) {
	convID, _ := payload["conversation_id"].(string)
	content, _ := payload["content"].(string)

	req := &chatpb.SendMessageRequest{
		ConversationId: convID,
		SenderId:       c.userID,
		Content:        content,
	}

	// Map optional fields from the WebSocket payload.
	if v, ok := payload["media_url"].(string); ok {
		req.MediaUrl = v
	}
	if v, ok := payload["media_mime"].(string); ok {
		req.MediaMime = v
	}
	if v, ok := payload["media_size"].(float64); ok {
		req.MediaSize = int64(v)
	}
	if v, ok := payload["parent_id"].(string); ok {
		req.ParentId = v
	}
	if v, ok := payload["expires_at"].(float64); ok {
		req.ExpiresAt = int64(v)
	}
	if v, ok := payload["message_type"].(float64); ok {
		req.Type = chatpb.MessageType(int32(v))
	}
	if mentions, ok := payload["mentioned_user_ids"].([]interface{}); ok {
		for _, m := range mentions {
			if mid, ok := m.(string); ok {
				req.MentionedUserIds = append(req.MentionedUserIds, mid)
			}
		}
	}

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	resp, err := c.chatClient.SendMessage(ctx, req)
	if err != nil {
		return nil, err
	}

	// Enrich the payload with server-assigned fields.
	if resp != nil && resp.Message != nil {
		payload["id"] = resp.Message.Id
		payload["message_id"] = resp.Message.Id
		payload["status"] = resp.Message.Status.String()
		payload["created_at"] = resp.Message.CreatedAt
		payload["send_at"] = resp.Message.SendAt
		if resp.Message.ExpiresAt > 0 {
			payload["expires_at"] = resp.Message.ExpiresAt
		}
	}

	return payload, nil
}
