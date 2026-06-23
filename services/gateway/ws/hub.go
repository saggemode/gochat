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
