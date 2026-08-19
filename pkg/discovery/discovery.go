package discovery

import (
	"context"
	"fmt"
	"time"
)

// Instance represents a single running microservice instance.
type Instance struct {
	ID        string            `json:"id"`
	Name      string            `json:"name"`
	Addr      string            `json:"addr"` // e.g. "10.0.0.5:50051" or "chat-1:50052"
	Metadata  map[string]string `json:"metadata,omitempty"`
	UpdatedAt time.Time         `json:"updated_at"`
}

// String returns formatted instance representation.
func (i Instance) String() string {
	return fmt.Sprintf("%s (%s @ %s)", i.Name, i.ID, i.Addr)
}

// Registry allows services to register themselves and send health heartbeats.
type Registry interface {
	Register(ctx context.Context, instance Instance, ttl time.Duration) error
	Deregister(ctx context.Context, serviceName, instanceID string) error
	Heartbeat(ctx context.Context, serviceName, instanceID string, ttl time.Duration) error
}

// Discovery allows clients (like the Gateway) to resolve and watch active service instances.
type Discovery interface {
	GetInstances(ctx context.Context, serviceName string) ([]Instance, error)
	Watch(ctx context.Context, serviceName string) (<-chan []Instance, error)
}
