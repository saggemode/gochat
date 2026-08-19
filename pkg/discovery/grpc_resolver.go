package discovery

import (
	"context"
	"fmt"
	"strings"
	"sync"
	"time"

	"go.uber.org/zap"
	"google.golang.org/grpc/resolver"
)

const Scheme = "discovery"

// Builder implements gRPC resolver.Builder.
type Builder struct {
	discovery    Discovery
	log          *zap.Logger
	staticBackup map[string]string // serviceName -> fallback address (e.g. "auth-service" -> "localhost:50051")
	mu           sync.RWMutex
}

// NewBuilder constructs a gRPC resolver.Builder using the given Discovery engine.
func NewBuilder(d Discovery, log *zap.Logger) *Builder {
	return &Builder{
		discovery:    d,
		log:          log.Named("grpc-resolver"),
		staticBackup: make(map[string]string),
	}
}

// SetStaticFallback registers a fallback address if no active instances are discovered.
func (b *Builder) SetStaticFallback(serviceName, addr string) {
	b.mu.Lock()
	defer b.mu.Unlock()
	b.staticBackup[serviceName] = addr
}

// Scheme returns the gRPC resolver scheme ("discovery").
func (b *Builder) Scheme() string {
	return Scheme
}

// Build creates a new gRPC Resolver for the given target (e.g., "discovery:///chat-service").
func (b *Builder) Build(target resolver.Target, cc resolver.ClientConn, opts resolver.BuildOptions) (resolver.Resolver, error) {
	serviceName := strings.TrimPrefix(target.URL.Path, "/")
	if serviceName == "" {
		serviceName = target.Endpoint()
	}

	b.mu.RLock()
	fallbackAddr := b.staticBackup[serviceName]
	b.mu.RUnlock()

	ctx, cancel := context.WithCancel(context.Background())
	r := &grpcResolver{
		serviceName:  serviceName,
		fallbackAddr: fallbackAddr,
		discovery:    b.discovery,
		cc:           cc,
		ctx:          ctx,
		cancel:       cancel,
		log:          b.log.With(zap.String("service", serviceName)),
	}

	go r.start()
	return r, nil
}

type grpcResolver struct {
	serviceName  string
	fallbackAddr string
	discovery    Discovery
	cc           resolver.ClientConn
	ctx          context.Context
	cancel       context.CancelFunc
	log          *zap.Logger
}

func (r *grpcResolver) start() {
	if r.discovery == nil {
		r.useFallback()
		return
	}

	watchChan, err := r.discovery.Watch(r.ctx, r.serviceName)
	if err != nil {
		r.log.Warn("failed to start discovery watch, using static fallback", zap.Error(err))
		r.useFallback()
		return
	}

	for {
		select {
		case <-r.ctx.Done():
			return
		case instances, ok := <-watchChan:
			if !ok {
				return
			}
			r.updateAddresses(instances)
		}
	}
}

func (r *grpcResolver) updateAddresses(instances []Instance) {
	var addrs []resolver.Address

	for _, inst := range instances {
		addrs = append(addrs, resolver.Address{
			Addr:       inst.Addr,
			ServerName: r.serviceName,
		})
	}

	// If no instances found, use static fallback if available
	if len(addrs) == 0 && r.fallbackAddr != "" {
		r.log.Debug("no dynamic instances found, using fallback address",
			zap.String("fallback", r.fallbackAddr))
		addrs = []resolver.Address{
			{Addr: r.fallbackAddr, ServerName: r.serviceName},
		}
	}

	if len(addrs) > 0 {
		r.log.Debug("updated gRPC connection state addresses",
			zap.Int("count", len(addrs)),
			zap.Any("addresses", addrs),
		)
		err := r.cc.UpdateState(resolver.State{Addresses: addrs})
		if err != nil {
			r.log.Error("failed to update client connection state", zap.Error(err))
		}
	}
}

func (r *grpcResolver) useFallback() {
	if r.fallbackAddr == "" {
		return
	}
	r.cc.UpdateState(resolver.State{
		Addresses: []resolver.Address{
			{Addr: r.fallbackAddr, ServerName: r.serviceName},
		},
	})
}

// ResolveNow handles re-resolving requests from gRPC transport.
func (r *grpcResolver) ResolveNow(opts resolver.ResolveNowOptions) {
	if r.discovery == nil {
		return
	}
	ctx, cancel := context.WithTimeout(r.ctx, 3*time.Second)
	defer cancel()

	instances, err := r.discovery.GetInstances(ctx, r.serviceName)
	if err == nil && len(instances) > 0 {
		r.updateAddresses(instances)
	}
}

// Close stops the resolver watch loop.
func (r *grpcResolver) Close() {
	r.cancel()
}

// TargetURI returns a formatted discovery URI for gRPC dial (e.g. "discovery:///auth-service").
func TargetURI(serviceName string) string {
	return fmt.Sprintf("%s:///%s", Scheme, serviceName)
}
