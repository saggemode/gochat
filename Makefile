.PHONY: proto build run-gateway run-auth run-chat run-media migrate docker-up docker-down tidy

# ── Proto generation ─────────────────────────────────────────────────────────
proto:
	@echo "Generating protobuf stubs..."
	protoc \
		--go_out=. --go_opt=module=gochat \
		--go-grpc_out=. --go-grpc_opt=module=gochat \
		proto/*.proto
	@echo "Done."

# ── Build all services ───────────────────────────────────────────────────────
build: build-auth build-chat build-media build-gateway build-ai build-payment build-social build-miniapp build-business

build-auth:
	go build -o bin/auth ./services/auth

build-chat:
	go build -o bin/chat ./services/chat

build-media:
	go build -o bin/media ./services/media

build-gateway:
	go build -o bin/gateway ./services/gateway

build-ai:
	go build -o bin/ai ./services/ai

build-payment:
	go build -o bin/payment ./services/payment

build-social:
	go build -o bin/social ./services/social

build-miniapp:
	go build -o bin/miniapp ./services/miniapp

build-business:
	go build -o bin/business ./services/business

# ── Run services individually ────────────────────────────────────────────────
run-auth:
	go run ./services/auth

run-chat:
	go run ./services/chat

run-media:
	go run ./services/media

run-gateway:
	go run ./services/gateway

run-ai:
	go run ./services/ai

run-payment:
	go run ./services/payment

run-social:
	go run ./services/social

run-miniapp:
	go run ./services/miniapp

run-business:
	go run ./services/business

# ── Database migrations ──────────────────────────────────────────────────────
migrate-up:
	go run ./cmd/migrate up

migrate-down:
	go run ./cmd/migrate down

# ── Docker ───────────────────────────────────────────────────────────────────
docker-up:
	docker compose up -d

docker-down:
	docker compose down

docker-logs:
	docker compose logs -f

# ── Go tidy ──────────────────────────────────────────────────────────────────
tidy:
	go mod tidy

# ── Lint ─────────────────────────────────────────────────────────────────────
lint:
	golangci-lint run ./...

# ── Test ─────────────────────────────────────────────────────────────────────
test:
	go test -v -race ./...
