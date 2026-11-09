# GoChat — Developer Guide

Onboarding and setup reference for developers joining GoChat. For a deeper code
tour see [`WHAT_I_KNOW.md`](WHAT_I_KNOW.md); this guide focuses on **getting the
system running, the external accounts you need, and the environment configuration**.

> Last reviewed: 2026-11-09. Some subsystems are feature-complete in code but **not
> yet device/runtime-tested** — those are called out explicitly below.

---

## 1. What GoChat is

A WhatsApp/WeChat-style "super-app": chat + calls + stories + channels, a full
**marketplace / conversational-commerce** layer (stores, products, cart, orders,
coupons, reviews), social features, bots/mini-apps, and AI helpers. Messaging is
**end-to-end encrypted** (Signal protocol) with WhatsApp-style delete-after-delivery.

There are **three codebases** in this repo:

| Codebase | Path | Stack | What it is |
|---|---|---|---|
| **Backend** | `services/`, `pkg/`, `proto/`, `migrations/` | Go 1.25 microservices (gRPC) behind one Gin HTTP+WebSocket gateway | The server |
| **Android app** | `GoChat_Kotlin/` | Kotlin, XML Views + ViewBinding, MVVM, Hilt, Room, Retrofit, WebRTC, Signal | The primary client |
| **Admin console** | `admin/` | Next.js 16 + React 19, Tailwind 4, TanStack Query | Internal ops dashboard |

> Not actively developed (ignore when exploring): `flutter/` (original app, being
> migrated to Kotlin), `frontend/` (Next.js web), `website/`, and the repo-root
> `app/` + root Gradle files (Android Studio template stub — the **real** app is in
> `GoChat_Kotlin/`).

---

## 2. Architecture

```
 Android app ──HTTPS /api/v1/*──┐
             ──WSS  /ws ────────┤
 Admin panel ──HTTPS /api/v1/admin/*
                                ▼
                     API Gateway  (Gin; :8080 local, :10000 on Render)
             middleware: CORS, versioning, geo, Prometheus, CDN, JWT auth, rate-limit
                                │ gRPC (or in-process bufconn in standalone mode)
   ┌─────┬─────┬─────┬─────┬────┼────┬─────┬──────┬──────┬──────┬────────┬────┐
  auth authz chat media group story call channel social miniapp business  ai …
                                │
      PostgreSQL (Supabase)  +  Redis (pub/sub, cache, rate-limit)  +  object storage
```

- **One public entry point:** the **gateway**. It holds *every* REST route
  (`services/gateway/main.go`, ~230 routes) and the WebSocket hub. Clients never talk
  to the microservices directly.
- **Microservices** communicate over gRPC. Contracts live in `proto/*.proto`;
  generated Go code is in `gen/<service>/` (regenerate with `make proto`).
- **Standalone mode:** if the remote gRPC services aren't reachable, the gateway can
  boot **all services in-process** over an in-memory `bufconn` and talk straight to
  the database (`services/gateway/standalone.go`). This is how a single Render
  gateway can run the whole backend by itself. Toggle with `STANDALONE_MODE`.

### Services & ports

| Service | gRPC | HTTP | Responsibility |
|---|---|---|---|
| auth | 50051 | 4001 | Register/login (phone **or** email + password), JWT + refresh, sessions, **device/E2EE key management**, 2FA PIN, phone OTP, **change number**, blocks, recovery |
| chat | 50052 | 4002 | Conversations, messages, threads, reactions, read receipts, polls, scheduled msgs, **delete-after-delivery**, `StreamMessages` |
| media | 50053 | — | Upload/download; backends: MinIO (S3), Telegram channel, local, DB |
| authz | 50054 | 4005 | Policy/RBAC engine (used by the admin console) |
| group | 50055 | 4006 | Groups, invite links, communities, broadcast lists |
| story | 50056 | 4007 | Stories post/view/viewers |
| call | 50057 | 4008 | Call signaling + history; `sfu/room.go` = Pion WebRTC **SFU** for group calls |
| channel | 50058 | 4009 | Broadcast channels |
| ai | 50059 | 4010 | Summaries/replies/translate (Gemini/OpenAI keys; heuristic fallback) |
| social | 50061 | 4012 | Follow, moments, nearby, audio rooms |
| miniapp | 50062 | 4013 | Bots, mini-apps, developer webhooks |
| business | 50063 | 4004 | **Biggest service** — marketplace: products, orders, coupons, reviews, cart, payouts |

Plain HTTP (not gRPC): `notification` (FCM — **dispatch is mocked**, it logs),
`search` (Postgres `ILIKE`). `services/payment/` is referenced by proto/Makefile but
**does not exist** — Paystack payment logic lives in the **gateway**
(`handlers/payments.go`), not a separate service.

---

## 3. External services & accounts you need

These are the third-party "websites"/providers the system integrates with. For local
dev you only strictly need **Postgres + Redis** (both provided by `docker-compose`).

| Provider | URL | Used for | Required? | Config |
|---|---|---|---|---|
| **Supabase** | https://supabase.com | Production PostgreSQL (the live DB) | Prod: yes · Local: no (use docker Postgres) | `*_DB_DSN` / `POSTGRES_DSN` |
| **Render** | https://render.com | Production hosting (`render.yaml`). Live gateway: **https://gochat-kvpj.onrender.com** | Prod deploy only | `render.yaml` |
| **Redis** | — | pub/sub fan-out, cache, rate-limit, presence | yes (or in-memory fallback) | `REDIS_ADDR`, `REDIS_PASSWORD` |
| **MinIO** (or any S3) | https://min.io | Media/object storage | optional (local path fallback) | `MINIO_*` |
| **Paystack** | https://paystack.com | Marketplace payments (API: `api.paystack.co`) | marketplace checkout | `PAYSTACK_SECRET_KEY`, dashboard webhook |
| **Firebase (FCM)** | https://console.firebase.google.com | Push notifications | push (mocked without it) | `FIREBASE_CREDENTIALS_JSON` / `FIREBASE_SERVICE_ACCOUNT_FILE`, Android `google-services.json` |
| **Google Drive API** | https://console.cloud.google.com | Encrypted chat backup/restore (Android) | optional | Google OAuth client in the Android app |
| **Gemini / OpenAI** | https://ai.google.dev · https://platform.openai.com | AI features (falls back to heuristics) | optional | `GEMINI_API_KEY` / `OPENAI_API_KEY` |
| **STUN/TURN** | e.g. https://www.metered.ca / coturn | WebRTC call connectivity (NAT traversal) | 1:1 & group calls | `STUN_URLS`, `TURN_URLS`, `TURN_USERNAME`, `TURN_PASSWORD` |

> **DB note:** the repo was originally on **Neon** (one database per service). It has
> since moved to **Supabase**, where all services share **one** `postgres` database
> using a **schema per service** (`auth`, `chat`, `core`, `business`, `authz`, …).
> Supabase owns the reserved `auth` schema, so GoChat's own auth tables live in
> **`core`**, not `auth`. Boot-time AutoMigrate re-runs every `migrations/<schema>/*.sql`
> idempotently.

---

## 4. Environment variables

Local config lives in `.env` (git-ignored). Copy the template and fill it in:

```bash
cp .env.example .env
```

Key groups (see `.env.example` for the full list and `pkg/config/config.go` for how
they're read):

**Database** — one DSN per service. Locally these point at `pgbouncer:6432`; in prod
they point at the Supabase pooler.
```
AUTH_DB_DSN, CHAT_DB_DSN, AUTHZ_DB_DSN, GROUP_DB_DSN, STORY_DB_DSN, CALL_DB_DSN,
CHANNEL_DB_DSN, AI_DB_DSN, SOCIAL_DB_DSN, MINIAPP_DB_DSN, BUSINESS_DB_DSN, MEDIA_DB_DSN
POSTGRES_USER / POSTGRES_PASSWORD / POSTGRES_DB   # docker-compose Postgres
```

**Redis / storage**
```
REDIS_ADDR, REDIS_PASSWORD, REDIS_DB
MINIO_ENDPOINT, MINIO_ACCESS_KEY, MINIO_SECRET_KEY, MINIO_BUCKET, MINIO_USE_SSL, MINIO_PUBLIC_URL
```

**Auth / security**
```
JWT_SECRET            # long random string in prod
JWT_EXPIRY_HOURS, JWT_REFRESH_EXPIRY_DAYS
E2EE_KEY_ENCRYPTION_KEY   # base64 32-byte AES-256-GCM key; empty = store prekeys plaintext (dev only)
```

**Gateway**
```
HTTP_PORT=8080
CORS_ORIGINS=http://localhost:5173      # add the admin panel origin here
STANDALONE_MODE                          # run all services in-process
<SERVICE>_GRPC_ADDR=localhost:500xx      # one per service (remote-services mode)
```

**Admin console access**
```
ADMIN_EMAILS=you@example.com,ops@example.com   # bootstrap allowlist; empty = admin API locked
```
> RBAC runs through the `authz` service; `ADMIN_EMAILS` is the **break-glass bootstrap**
> (granted `super_admin` on boot). On first run the console walks you through creating
> the super-admin account.

**Payments (Paystack)**
```
PAYSTACK_SECRET_KEY        # sk_test_… for staging, sk_live_… for prod
PAYSTACK_CALLBACK_URL      # optional; defaults to https://gochat.app/paystack/callback
PLATFORM_COMMISSION_PERCENT
```

**Push / AI / calls / misc**
```
FIREBASE_CREDENTIALS_JSON or FIREBASE_SERVICE_ACCOUNT_FILE
GEMINI_API_KEY / OPENAI_API_KEY
STUN_URLS, TURN_URLS, TURN_USERNAME, TURN_PASSWORD
CHAT_MESSAGE_RETENTION     # delete-after-delivery stale TTL (default 720h)
DEFAULT_COUNTRY_CODE, APP_ENV, LOG_LEVEL
```

> ⚠️ **Secrets hygiene:** never commit real keys. Some service-account JSON files and a
> `k8s/secrets.yaml` are currently tracked in git and should be rotated + removed from
> history. `.env` itself is git-ignored (good).

---

## 5. Local development — backend

### Prerequisites
- **Go 1.25+**
- **Docker** + Docker Compose (for Postgres/Redis/MinIO)
- **protoc** + `protoc-gen-go` + `protoc-gen-go-grpc` on your `PATH` (only needed to
  regenerate protos)
- (Windows) the Bash shell from Git Bash; a local PostgreSQL client is handy

### Option A — full stack with Docker (simplest)
Brings up Postgres, PgBouncer, Redis, MinIO, every microservice, and the gateway:
```bash
make docker-up        # or: docker compose up --build
make docker-logs      # tail logs
make docker-down
```
Gateway → http://localhost:8080 · health → `/healthz` · API docs → `/docs`, `/swagger`, `/redoc`, `/openapi.json`.

### Option B — gateway standalone (all services in-process)
Point the DSNs at a reachable Postgres, then run just the gateway with
`STANDALONE_MODE=true`. It boots every service over an in-memory bufconn — no separate
service processes needed. Good for quick API work against a cloud DB.

### Option C — run services individually
```bash
make build            # build all service binaries into bin/
make run-auth         # run one service (make run-chat, run-gateway, …)
```

### Database migrations
```bash
make migrate-up       # apply   (services also auto-migrate on boot)
make migrate-down     # roll back
```
Migrations live in `migrations/<schema>/NNN_*.sql`; `000_schemas.sql` runs first.

### Protos, tests, lint
```bash
make proto            # regenerate gRPC stubs after editing proto/*.proto
make test             # go test ./...
make lint             # go vet + fmt check
go build ./services/<name>/...   # build a single service
```

---

## 6. Android app — `GoChat_Kotlin/`

- Package `com.example.gochat`. compileSdk/targetSdk 35, minSdk 26, **Java 17**.
- Open the **`GoChat_Kotlin/`** folder in Android Studio (not the repo root).
- Build from CLI:
  ```bash
  cd GoChat_Kotlin
  ./gradlew :app:compileDebugKotlin      # compile check
  ./gradlew :app:assembleDebug           # build the APK
  ```

### Pointing the app at a backend
`app/src/main/java/com/example/gochat/data/api/ApiConstants.kt`:
```kotlin
const val PROD_BASE_URL     = "https://gochat-kvpj.onrender.com"  // Render (default)
const val EMULATOR_BASE_URL = "http://10.0.2.2:8080"             // local gateway from an emulator
const val BASE_URL = PROD_BASE_URL                               // ← switch this for local dev
```
The WebSocket URL is derived from `BASE_URL` (http→ws, https→wss).

### Things to know
- **Room is destructive** (`AppDatabase.kt`, `fallbackToDestructiveMigration`): any
  entity/schema change needs a **version bump** and **wipes local cache** on upgrade.
  Prefer `@AutoMigration` and never reintroduce a destructive fallback that drops
  message history.
- **Firebase:** drop your `google-services.json` into `GoChat_Kotlin/app/` for push.
- **E2EE is on by default** (Signal protocol). Multi-device, QR device pairing, and
  per-device verification exist but are **not device-tested** — verify on two real
  phones before trusting multi-device flows (a clean reinstall on both is required to
  clear orphaned sessions).
- (Windows) if Gradle fails with *"jlink does not exist"*, the Android Studio Gradle
  JDK is pointing at a broken JRE — fix `.gradle/config.properties`.

---

## 7. Admin console — `admin/`

Next.js ops dashboard, **wired to the real gateway admin API by default** (mocks are an
opt-in fallback).

```bash
cd admin
npm install          # (or pnpm)
npm run dev          # http://localhost:3000
npm run build && npm run start   # production build
npx eslint .         # Next 16 build does NOT run eslint — lint separately
```

Environment (`.env.local` in `admin/`):
```
NEXT_PUBLIC_API_BASE_URL=http://localhost:8080   # the gateway
NEXT_PUBLIC_USE_MOCKS=false                       # true = seeded fixtures, no backend needed
```

To use it against a real backend:
1. Set `ADMIN_EMAILS` on the **gateway** to include your account's email.
2. Add the admin origin (e.g. `http://localhost:3000`) to the gateway's `CORS_ORIGINS`.
3. Sign in with that account — the console probes `/api/v1/admin/overview` and rejects
   non-admins. RBAC (super_admin/admin/support/finance/moderator) gates finance panels
   and mutations.

---

## 8. Deployment (Render — `render.yaml`)

- **`gochat-gateway`** — public web service (Docker, `SERVICE=gateway`, port **10000**).
  Live URL: **https://gochat-kvpj.onrender.com**.
- Private services (`pserv`): `gochat-auth`, `-chat`, `-media`, `-authz`, `-group`,
  `-story`, `-call`, `-channel`, `-social`, `-miniapp`, `-business`, plus `gochat-redis`
  and `gochat-frontend`. (`ai`, `search`, `notification`, `payment` are **not** in
  `render.yaml`.)
- One root `Dockerfile` builds any service via `ARG SERVICE=<name>` (runs protoc inside
  the build).
- Set all production env vars (Supabase DSNs, `JWT_SECRET`, `PAYSTACK_SECRET_KEY`,
  `ADMIN_EMAILS`, `CORS_ORIGINS`, Firebase creds, TURN creds) in the Render dashboard.
- `k8s/` has Kustomize manifests as an alternative target.

### Payments go-live checklist
1. Set `PAYSTACK_SECRET_KEY` on the gateway (`sk_live_…`).
2. Paystack dashboard → **Webhooks** → `https://<gateway>/api/v1/payments/paystack/webhook`
   (HMAC-SHA512 verified; the handler is idempotent).
3. Optional `PAYSTACK_CALLBACK_URL`.
4. Sellers create payout subaccounts via the in-app `CreateSellerSubaccount` flow for
   split settlement.

---

## 9. Cheat sheet

| Task | Command |
|---|---|
| Full local stack | `make docker-up` |
| Regenerate gRPC stubs | `make proto` |
| Build one Go service | `go build ./services/<name>/...` |
| Vet + tests | `make lint` · `make test` |
| Apply migrations | `make migrate-up` |
| Android compile check | `cd GoChat_Kotlin && ./gradlew :app:compileDebugKotlin` |
| Android APK | `./gradlew :app:assembleDebug` |
| Admin dev server | `cd admin && npm run dev` |

---

## 10. Gotchas & current state

- **Push notifications are mocked** — the gateway worker and notification service only
  **log** ("MOCK PUSH"). Wire real FCM creds to send.
- **Search is `ILIKE`**, not full-text.
- **`services/payment/` doesn't exist** despite proto/Makefile references; payments run
  in the gateway.
- **Supabase schema reality:** app auth tables are in `core`, not `auth` (reserved).
  Don't try to `CREATE` in `auth`.
- **Untested-but-built subsystems** (code compiles; no device/runtime test): all E2EE
  (1:1, group, multi-device), QR device pairing, per-device delete-after-delivery,
  group video calls, change-number notifications. Treat "compiles" ≠ "works" — these
  need real multi-device testing.
- CI (`.github/workflows/ci.yml`): kustomize validate, `go vet`/build, Flutter check.
- Deeper architecture notes and the full route/handler inventory live in
  [`WHAT_I_KNOW.md`](WHAT_I_KNOW.md); feature plans in `FEATURE_ROADMAP.md`,
  `MULTI_DEVICE_PLAN.md`, `E2EE_DELETE_AFTER_DELIVERY_PLAN.md`.
