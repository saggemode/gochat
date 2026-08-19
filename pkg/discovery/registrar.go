package discovery

import (
	"context"
	"os"
	"time"

	"github.com/google/uuid"
	"go.uber.org/zap"
)

// Registrar manages the lifecycle of a service instance registration & heartbeats.
type Registrar struct {
	registry Registry
	instance Instance
	ttl      time.Duration
	interval time.Duration
	log      *zap.Logger
	cancel   context.CancelFunc
}

// NewRegistrar creates a service registrar.
func NewRegistrar(registry Registry, serviceName, addr string, ttl, interval time.Duration, log *zap.Logger) *Registrar {
	hostname, _ := os.Hostname()
	instanceID := fmtInstanceID(serviceName, hostname)

	return &Registrar{
		registry: registry,
		instance: Instance{
			ID:   instanceID,
			Name: serviceName,
			Addr: addr,
			Metadata: map[string]string{
				"hostname": hostname,
			},
		},
		ttl:      ttl,
		interval: interval,
		log:      log.Named("registrar"),
	}
}

func fmtInstanceID(serviceName, hostname string) string {
	if hostname != "" {
		return hostname + "-" + uuid.New().String()[:8]
	}
	return serviceName + "-" + uuid.New().String()[:8]
}

// Start registers the service and starts a background heartbeat loop.
func (r *Registrar) Start(ctx context.Context) error {
	if err := r.registry.Register(ctx, r.instance, r.ttl); err != nil {
		return err
	}

	loopCtx, cancel := context.WithCancel(context.Background())
	r.cancel = cancel

	go r.heartbeatLoop(loopCtx)
	return nil
}

func (r *Registrar) heartbeatLoop(ctx context.Context) {
	ticker := time.NewTicker(r.interval)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			err := r.registry.Heartbeat(ctx, r.instance.Name, r.instance.ID, r.ttl)
			if err != nil {
				r.log.Warn("heartbeat failed, re-registering instance",
					zap.String("service", r.instance.Name),
					zap.Error(err),
				)
				// Try to re-register
				_ = r.registry.Register(ctx, r.instance, r.ttl)
			}
		}
	}
}

// Stop stops the heartbeat loop and deregisters the service instance.
func (r *Registrar) Stop(ctx context.Context) error {
	if r.cancel != nil {
		r.cancel()
	}
	return r.registry.Deregister(ctx, r.instance.Name, r.instance.ID)
}
