// Package safego runs background goroutines with panic recovery.
//
// A panic in a detached goroutine (started with a bare `go func(){...}()`) is NOT
// caught by HTTP/gRPC middleware — it crashes the entire process, dropping every
// connected user. Background fan-out work (push notifications, delivery acks, etc.)
// runs on arbitrary/remote data and must never be able to do that. Use safego.Go
// for any goroutine whose failure should be contained and logged, not fatal.
package safego

import (
	"fmt"

	"go.uber.org/zap"
)

// Go runs fn in a new goroutine, recovering and logging any panic so it cannot
// crash the process. name is a short label for the log. A nil logger is tolerated.
func Go(log *zap.Logger, name string, fn func()) {
	go Run(log, name, fn)
}

// Run executes fn on the CURRENT goroutine with panic recovery. Use it inside an
// existing goroutine (e.g. a worker loop body) that you don't want a single bad
// iteration to kill.
func Run(log *zap.Logger, name string, fn func()) {
	defer Recover(log, name)
	fn()
}

// Recover recovers and logs a panic. Use it as the FIRST line of an existing
// goroutine body: `defer safego.Recover(log, "my-worker")`. Because the deferred
// call is Recover itself, its recover() catches a panic unwinding that goroutine.
func Recover(log *zap.Logger, name string) {
	if r := recover(); r != nil {
		if log != nil {
			log.Error("recovered panic in background goroutine",
				zap.String("goroutine", name),
				zap.String("panic", fmt.Sprint(r)),
				zap.Stack("stack"),
			)
		}
	}
}
