package ws

import (
	"sync"
)

// Hub maintains the set of active WebSocket clients.
type Hub struct {
	// Registered clients
	clients map[*Client]bool

	// Register requests from the clients
	register chan *Client

	// Unregister requests from clients
	unregister chan *Client

	mu sync.RWMutex
}

// NewHub constructs a new WebSocket Hub.
func NewHub() *Hub {
	return &Hub{
		clients:    make(map[*Client]bool),
		register:   make(chan *Client),
		unregister: make(chan *Client),
	}
}

// Run starts the Hub state machine loop.
func (h *Hub) Run() {
	for {
		select {
		case client := <-h.register:
			h.mu.Lock()
			h.clients[client] = true
			h.mu.Unlock()
		case client := <-h.unregister:
			h.mu.Lock()
			if _, ok := h.clients[client]; ok {
				delete(h.clients, client)
				close(client.send)
			}
			h.mu.Unlock()
		}
	}
}

// GetOnlineUsers returns a list of user IDs that currently have active WebSocket connections.
func (h *Hub) GetOnlineUsers() []string {
	h.mu.RLock()
	defer h.mu.RUnlock()

	usersMap := make(map[string]bool)
	for client := range h.clients {
		usersMap[client.userID] = true
	}

	users := make([]string, 0, len(usersMap))
	for userID := range usersMap {
		users = append(users, userID)
	}
	return users
}

// Broadcast sends a JSON payload to all active clients, optionally excluding a specific sender.
func (h *Hub) Broadcast(message []byte, excludeUserID string) {
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

// SendToUser sends a JSON payload directly to a specific user's active client connections.
func (h *Hub) SendToUser(userID string, message []byte) {
	h.mu.RLock()
	defer h.mu.RUnlock()

	for client := range h.clients {
		if client.userID == userID {
			select {
			case client.send <- message:
			default:
			}
		}
	}
}
