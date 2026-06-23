# Build stage
FROM golang:1.22-alpine AS builder

WORKDIR /app

COPY go.mod go.sum ./
COPY vendor ./vendor
COPY . .

RUN CGO_ENABLED=0 GOOS=linux go build -mod=vendor -trimpath -ldflags="-s -w" -o /app/story-service ./services/story

# Run stage
FROM alpine:3.19
WORKDIR /app
RUN apk --no-cache add ca-certificates tzdata
COPY --from=builder /app/story-service .
EXPOSE 50056
CMD ["./story-service"]
