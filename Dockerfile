FROM docker.io/library/golang:1.25-alpine AS builder

WORKDIR /src
COPY go.mod go.sum ./
RUN --mount=type=cache,target=/go/pkg/mod \
    go mod download

# Install protoc and Go plugins for protobuf generation
RUN apk add --no-cache protobuf-dev
RUN go install google.golang.org/protobuf/cmd/protoc-gen-go@latest
RUN go install google.golang.org/grpc/cmd/protoc-gen-go-grpc@latest

COPY . .

# Generate protobuf files
RUN protoc \
    --go_out=. --go_opt=module=gochat \
    --go-grpc_out=. --go-grpc_opt=module=gochat \
    proto/*.proto

ARG SERVICE=gateway
RUN --mount=type=cache,target=/root/.cache/go-build \
    --mount=type=cache,target=/go/pkg/mod \
    CGO_ENABLED=0 GOOS=linux go build -trimpath -ldflags="-s -w" -o /out/service ./services/${SERVICE}

FROM public.ecr.aws/docker/library/alpine:3.21
RUN apk add --no-cache ca-certificates tzdata
COPY --from=builder /out/service /service
USER 65534:65534
ENTRYPOINT ["/service"]


