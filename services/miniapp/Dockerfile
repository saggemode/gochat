# Build stage
FROM golang:1.22-alpine AS builder

WORKDIR /app

COPY go.mod go.sum ./
COPY vendor ./vendor
COPY . .

RUN CGO_ENABLED=0 GOOS=linux go build -mod=vendor -trimpath -ldflags="-s -w" -o /app/miniapp-service ./services/miniapp

# Run stage
FROM alpine:3.19
WORKDIR /app
RUN apk --no-cache add ca-certificates tzdata
COPY --from=builder /app/miniapp-service .
EXPOSE 50062
CMD ["./miniapp-service"]
