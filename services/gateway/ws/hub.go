package ws

import (
	"sync"
)

// Hub maintains the set of active WebSocket clients.
type Hub struct {
	// Registered clients (canonical set, used by Broadcast)
	clients map[*Client]bool

	// Secondary index: userID -> set of that user's active clients.
	// Enables O(1) SendToUser, IsUserOnline, and GetOnlineUsers
	// instead of O(N) scans over all clients.
	userClients map[string]map[*Client]bool

	// Register requests from the clients
	register chan *Client

	// Unregister requests from clients
	unregister chan *Client

	// Redis Pub/Sub relay for cross-gateway message delivery.
	// Nil when running without Redis (standalone mode).
	relay *Relay

	mu sync.RWMutex
}

// NewHub constructs a new WebSocket Hub.
func NewHub() *Hub {
	return &Hub{
		clients:     make(map[*Client]bool),
		userClients: make(map[string]map[*Client]bool),
		register:    make(chan *Client),
		unregister:  make(chan *Client),
	}
}

// SetRelay attaches a Redis Pub/Sub relay to the Hub for cross-gateway delivery.
// Must be called before Run(). Safe to pass nil (standalone mode).
func (h *Hub) SetRelay(r *Relay) {
	h.relay = r
}

// Run starts the Hub state machine loop.
func (h *Hub) Run() {
	for {
		select {
		case client := <-h.register:
			h.mu.Lock()
			h.clients[client] = true
			isFirstConnection := h.userClients[client.userID] == nil || len(h.userClients[client.userID]) == 0
			if h.userClients[client.userID] == nil {
				h.userClients[client.userID] = make(map[*Client]bool)
			}
			h.userClients[client.userID][client] = true
			h.mu.Unlock()

			// Subscribe to Redis channel when user's first connection arrives on this gateway
			if isFirstConnection && h.relay != nil {
				h.relay.SubscribeUser(client.userID)
			}
		case client := <-h.unregister:
			h.mu.Lock()
			if _, ok := h.clients[client]; ok {
				delete(h.clients, client)
				if conns := h.userClients[client.userID]; conns != nil {
					delete(conns, client)
					if len(conns) == 0 {
						delete(h.userClients, client.userID)
						// Unsubscribe from Redis when user's last connection drops from this gateway
						if h.relay != nil {
							go h.relay.UnsubscribeUser(client.userID)
						}
					}
				}
				close(client.send)
			}
			h.mu.Unlock()
		}
	}
}

// GetOnlineUsers returns a list of user IDs that currently have active WebSocket connections.
// Note: this only returns users on this gateway instance.
// O(U) where U = unique online users, instead of O(N) over all connections.
func (h *Hub) GetOnlineUsers() []string {
	h.mu.RLock()
	defer h.mu.RUnlock()

	users := make([]string, 0, len(h.userClients))
	for userID := range h.userClients {
		users = append(users, userID)
	}
	return users
}

// IsUserOnline returns whether the user has at least one active WebSocket connection
// on this gateway instance. O(1) map lookup instead of O(N) scan.
func (h *Hub) IsUserOnline(userID string) bool {
	h.mu.RLock()
	defer h.mu.RUnlock()

	return len(h.userClients[userID]) > 0
}

// Broadcast sends a JSON payload to all active clients on this gateway instance,
// and publishes to Redis so other gateway instances can deliver to their clients.
func (h *Hub) Broadcast(message []byte, excludeUserID string) {
	// Deliver to local clients
	h.localBroadcast(message, excludeUserID)

	// Publish to Redis for other gateway instances
	if h.relay != nil {
		h.relay.PublishBroadcast(message, excludeUserID)
	}
}

// SendToUser sends a JSON payload to a specific user's active connections.
// First tries local delivery; if no local connections exist, publishes via Redis
// so the gateway instance where the user IS connected can deliver it.
// Returns true if at least one local client connection received the message.
func (h *Hub) SendToUser(userID string, message []byte) bool {
	sent := h.localSendToUser(userID, message)

	// If user not connected locally, try cross-gateway delivery via Redis
	if !sent && h.relay != nil {
		h.relay.PublishToUser(userID, message)
	}

	return sent
}

// ── Internal methods (called by Relay to avoid infinite publish loops) ────────

// localBroadcast delivers a message to all clients on this gateway instance only.
// Does NOT publish to Redis — used by the Relay when receiving cross-gateway broadcasts.
func (h *Hub) localBroadcast(message []byte, excludeUserID string) {
	h.mu.RLock()
	defer h.mu.RUnlock()

	for client := range h.clients {
		if excludeUserID != "" && client.userID == excludeUserID {
			continue
		}
		select {
		case client.send <- message:
		default:
		}
	}
}

// localSendToUser delivers a message to a specific user's connections on this gateway only.
// Does NOT publish to Redis — used by the Relay when receiving cross-gateway user messages.
func (h *Hub) localSendToUser(userID string, message []byte) bool {
	h.mu.RLock()
	defer h.mu.RUnlock()

	sent := false
	for client := range h.userClients[userID] {
		select {
		case client.send <- message:
			sent = true
		default:
		}
	}
	return sent
}

