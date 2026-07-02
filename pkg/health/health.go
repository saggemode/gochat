package health

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"sync"
	"time"

	"go.uber.org/zap"
)

// Checker is a function that returns an error if the dependency is unhealthy.
type Checker func(ctx context.Context) error

// Status represents the health check response.
type Status struct {
	Status    string            `json:"status"`
	Service   string            `json:"service"`
	Uptime    string            `json:"uptime"`
	Checks    map[string]string `json:"checks,omitempty"`
	Timestamp string            `json:"timestamp"`
}

// Server runs a lightweight HTTP health check server alongside the gRPC service.
type Server struct {
	serviceName string
	port        string
	startTime   time.Time
	httpServer  *http.Server
	log         *zap.Logger

	mu       sync.RWMutex
	checkers map[string]Checker
}

// New creates a new health check server.
// Port should be the health check HTTP port (e.g., "9051").
func New(serviceName, port string, log *zap.Logger) *Server {
	return &Server{
		serviceName: serviceName,
		port:        port,
		startTime:   time.Now(),
		log:         log,
		checkers:    make(map[string]Checker),
	}
}

// AddCheck registers a named dependency check (e.g., "postgres", "redis").
func (s *Server) AddCheck(name string, checker Checker) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.checkers[name] = checker
}

// Start begins serving health check endpoints in a goroutine.
func (s *Server) Start() {
	mux := http.NewServeMux()

	// Liveness probe: is the process alive?
	// Returns 200 if the HTTP server can respond.
	mux.HandleFunc("/healthz", s.handleLiveness)

	// Readiness probe: can the service handle traffic?
	// Returns 200 only if all dependency checks pass.
	mux.HandleFunc("/readyz", s.handleReadiness)

	s.httpServer = &http.Server{
		Addr:         fmt.Sprintf(":%s", s.port),
		Handler:      mux,
		ReadTimeout:  5 * time.Second,
		WriteTimeout: 5 * time.Second,
	}

	go func() {
		s.log.Info("health check server started",
			zap.String("service", s.serviceName),
			zap.String("addr", s.httpServer.Addr),
		)
		if err := s.httpServer.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			s.log.Error("health check server error", zap.Error(err))
		}
	}()
}

// Stop gracefully shuts down the health check server.
func (s *Server) Stop() {
	if s.httpServer != nil {
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		s.httpServer.Shutdown(ctx)
	}
}

// handleLiveness responds to /healthz — always 200 if the process is alive.
func (s *Server) handleLiveness(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	json.NewEncoder(w).Encode(Status{
		Status:    "alive",
		Service:   s.serviceName,
		Uptime:    time.Since(s.startTime).Round(time.Second).String(),
		Timestamp: time.Now().UTC().Format(time.RFC3339),
	})
}

// handleReadiness responds to /readyz — 200 only if all checks pass.
func (s *Server) handleReadiness(w http.ResponseWriter, r *http.Request) {
	s.mu.RLock()
	checkers := make(map[string]Checker, len(s.checkers))
	for k, v := range s.checkers {
		checkers[k] = v
	}
	s.mu.RUnlock()

	ctx, cancel := context.WithTimeout(r.Context(), 3*time.Second)
	defer cancel()

	checks := make(map[string]string, len(checkers))
	allHealthy := true

	for name, check := range checkers {
		if err := check(ctx); err != nil {
			checks[name] = fmt.Sprintf("unhealthy: %v", err)
			allHealthy = false
		} else {
			checks[name] = "ok"
		}
	}

	status := Status{
		Service:   s.serviceName,
		Uptime:    time.Since(s.startTime).Round(time.Second).String(),
		Checks:    checks,
		Timestamp: time.Now().UTC().Format(time.RFC3339),
	}

	w.Header().Set("Content-Type", "application/json")
	if allHealthy {
		status.Status = "ready"
		w.WriteHeader(http.StatusOK)
	} else {
		status.Status = "not_ready"
		w.WriteHeader(http.StatusServiceUnavailable)
	}
	json.NewEncoder(w).Encode(status)
}
