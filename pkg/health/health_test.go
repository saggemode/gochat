package health

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"go.uber.org/zap"
)

func newTestServer() *Server {
	log := zap.NewNop()
	return New("test-service", "0", log)
}

func TestHandleLiveness(t *testing.T) {
	s := newTestServer()
	s.startTime = time.Now().Add(-5 * time.Minute)

	req := httptest.NewRequest(http.MethodGet, "/healthz", nil)
	w := httptest.NewRecorder()
	s.handleLiveness(w, req)

	if w.Code != http.StatusOK {
		t.Errorf("liveness status = %d, want %d", w.Code, http.StatusOK)
	}

	var status Status
	if err := json.NewDecoder(w.Body).Decode(&status); err != nil {
		t.Fatalf("decode response: %v", err)
	}
	if status.Status != "alive" {
		t.Errorf("status = %q, want %q", status.Status, "alive")
	}
	if status.Service != "test-service" {
		t.Errorf("service = %q, want %q", status.Service, "test-service")
	}
}

func TestHandleReadiness_AllHealthy(t *testing.T) {
	s := newTestServer()
	s.AddCheck("postgres", func(ctx context.Context) error { return nil })
	s.AddCheck("redis", func(ctx context.Context) error { return nil })

	req := httptest.NewRequest(http.MethodGet, "/readyz", nil)
	w := httptest.NewRecorder()
	s.handleReadiness(w, req)

	if w.Code != http.StatusOK {
		t.Errorf("readiness status = %d, want %d", w.Code, http.StatusOK)
	}

	var status Status
	json.NewDecoder(w.Body).Decode(&status)
	if status.Status != "ready" {
		t.Errorf("status = %q, want %q", status.Status, "ready")
	}
	if status.Checks["postgres"] != "ok" {
		t.Errorf("postgres check = %q, want %q", status.Checks["postgres"], "ok")
	}
}

func TestHandleReadiness_OneUnhealthy(t *testing.T) {
	s := newTestServer()
	s.AddCheck("postgres", func(ctx context.Context) error { return nil })
	s.AddCheck("redis", func(ctx context.Context) error {
		return context.DeadlineExceeded
	})

	req := httptest.NewRequest(http.MethodGet, "/readyz", nil)
	w := httptest.NewRecorder()
	s.handleReadiness(w, req)

	if w.Code != http.StatusServiceUnavailable {
		t.Errorf("readiness status = %d, want %d", w.Code, http.StatusServiceUnavailable)
	}

	var status Status
	json.NewDecoder(w.Body).Decode(&status)
	if status.Status != "not_ready" {
		t.Errorf("status = %q, want %q", status.Status, "not_ready")
	}
	if status.Checks["redis"] == "ok" {
		t.Error("redis should be unhealthy")
	}
}

func TestHandleReadiness_NoCheckers(t *testing.T) {
	s := newTestServer()

	req := httptest.NewRequest(http.MethodGet, "/readyz", nil)
	w := httptest.NewRecorder()
	s.handleReadiness(w, req)

	if w.Code != http.StatusOK {
		t.Errorf("readiness with no checkers = %d, want %d", w.Code, http.StatusOK)
	}
}
