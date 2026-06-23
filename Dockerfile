# FROM docker.io/library/golang:1.25-alpine AS builder

# WORKDIR /src
# COPY go.mod go.sum ./
# COPY vendor ./vendor
# COPY . .

# ARG SERVICE
# RUN test -n "${SERVICE}"
# RUN CGO_ENABLED=0 GOOS=linux go build -mod=vendor -trimpath -ldflags="-s -w" -o /out/service ./services/${SERVICE}

# FROM public.ecr.aws/docker/library/alpine:3.21
# RUN apk add --no-cache ca-certificates tzdata
# COPY --from=builder /out/service /service
# USER 65534:65534
# ENTRYPOINT ["/service"]


FROM docker.io/library/golang:1.25-alpine AS builder

WORKDIR /src
COPY go.mod go.sum ./
RUN --mount=type=cache,target=/go/pkg/mod \
    go mod download

COPY . .

ARG SERVICE
RUN test -n "${SERVICE}"
RUN --mount=type=cache,target=/root/.cache/go-build \
    --mount=type=cache,target=/go/pkg/mod \
    CGO_ENABLED=0 GOOS=linux go build -trimpath -ldflags="-s -w" -o /out/service ./services/${SERVICE}

FROM public.ecr.aws/docker/library/alpine:3.21
RUN apk add --no-cache ca-certificates tzdata
COPY --from=builder /out/service /service
USER 65534:65534
ENTRYPOINT ["/service"]
