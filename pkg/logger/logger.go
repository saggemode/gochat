package logger

import (
	"os"

	"go.uber.org/zap"
	"go.uber.org/zap/zapcore"
)

// New creates a production-ready structured logger.
// In development (LOG_LEVEL=debug) it uses a human-friendly console encoder.
func New(service string) *zap.Logger {
	level := zapcore.InfoLevel
	if os.Getenv("LOG_LEVEL") == "debug" {
		level = zapcore.DebugLevel
	}

	isDev := os.Getenv("APP_ENV") == "development" || os.Getenv("APP_ENV") == ""

	var encoder zapcore.Encoder
	encoderCfg := zap.NewProductionEncoderConfig()
	encoderCfg.TimeKey = "ts"
	encoderCfg.EncodeTime = zapcore.ISO8601TimeEncoder
	encoderCfg.EncodeLevel = zapcore.CapitalColorLevelEncoder

	if isDev {
		encoder = zapcore.NewConsoleEncoder(encoderCfg)
	} else {
		encoderCfg.EncodeLevel = zapcore.LowercaseLevelEncoder
		encoder = zapcore.NewJSONEncoder(encoderCfg)
	}

	core := zapcore.NewCore(
		encoder,
		zapcore.AddSync(os.Stdout),
		level,
	)

	log := zap.New(core, zap.AddCaller(), zap.AddStacktrace(zapcore.ErrorLevel))
	return log.With(zap.String("service", service))
}

// Sugar returns a sugared logger for convenience.
func Sugar(service string) *zap.SugaredLogger {
	return New(service).Sugar()
}
