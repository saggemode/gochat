package ws

import (
	"context"
	"encoding/json"
	"net/http"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/gorilla/websocket"
	"go.uber.org/zap"

	chatpb "gochat/gen/chat"
)

const (
	// Time allowed to write a message to the peer.
	writeWait = 10 * time.Second

	// Time allowed to read the next pong message from the peer.
	pongWait = 60 * time.Second

	// Send pings to peer with this period. Must be less than pongWait.
	pingPeriod = (pongWait * 9) / 10

	// Maximum message size allowed from peer.
	maxMessageSize = 4096
)

var upgrader = websocket.Upgrader{
	ReadBufferSize:  1024,
	WriteBufferSize: 1024,
	CheckOrigin: func(r *http.Request) bool {
		return true // Rely on CORS middleware configuration
	},
}

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
		_, _, err := c.conn.ReadMessage()
		if err != nil {
			if websocket.IsUnexpectedCloseError(err, websocket.CloseGoingAway, websocket.CloseAbnormalClosure) {
				c.log.Warn("websocket read error", zap.Error(err))
			}
			break
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
func ServeWs(hub *Hub, chatClient chatpb.ChatServiceClient, log *zap.Logger) gin.HandlerFunc {
	return func(c *gin.Context) {
		userIDVal, exists := c.Get("user_id")
		if !exists {
			c.JSON(http.StatusUnauthorized, gin.H{"error": "unauthorised user in context"})
			return
		}
		userID := userIDVal.(string)

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
