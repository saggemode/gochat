# Build stage
FROM golang:1.22-alpine AS builder

WORKDIR /app

COPY go.mod go.sum ./
COPY vendor ./vendor
COPY . .

RUN CGO_ENABLED=0 GOOS=linux go build -mod=vendor -trimpath -ldflags="-s -w" -o /app/social-service ./services/social

# Run stage
FROM alpine:3.19
WORKDIR /app
RUN apk --no-cache add ca-certificates tzdata
COPY --from=builder /app/social-service .
EXPOSE 50061
CMD ["./social-service"]
