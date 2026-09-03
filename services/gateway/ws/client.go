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
)

// Client represents a single connected WebSocket client.
type Client struct {
	hub    *Hub
	conn   *websocket.Conn
	send   chan []byte
	userID string
	log    *zap.Logger
	cancel context.CancelFunc
}

// readPump pumps messages from the websocket connection to the hub/discard.
// It ensures pong deadliness is met.
func (c *Client) readPump() {
	defer func() {
		c.cancel()
		c.hub.unregister <- c
		c.conn.Close()
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

			w, err := c.conn.NextWriter(websocket.TextMessage)
			if err != nil {
				return
			}
			w.Write(message)

			// Add queued messages to the current websocket message
			n := len(c.send)
			for i := 0; i < n; i++ {
				w.Write([]byte{'\n'})
				w.Write(<-c.send)
			}

			if err := w.Close(); err != nil {
				return
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
func ServeWs(hub *Hub, chatClient chatpb.ChatServiceClient, allowedOrigins string, log *zap.Logger) gin.HandlerFunc {
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
			hub:    hub,
			conn:   conn,
			send:   make(chan []byte, 256),
			userID: userID,
			log:    log,
			cancel: cancel,
		}

		client.hub.register <- client

		go client.writePump()
		go client.readPump()
		go client.listenGrpcStream(ctx, chatClient)
	}
}
