# WHAT I KNOW — GoChat

Working reference for this repo. Scanned 2026-10-20. Excluded from the scan on purpose: `website/`, `frontend/`, `flutter/`.

---

## 1. What the app is

**GoChat** is a WhatsApp-style messenger that is turning into a "super-app": chat + calls + stories + channels + communities, plus a full **marketplace / conversational commerce** layer (stores, products, cart, orders, coupons, reviews, Q&A), social features (follow, moments, nearby, audio rooms), bots/mini-apps, and AI helpers.

- **Backend:** Go 1.25 microservices talking gRPC, behind one Gin HTTP + WebSocket **API gateway**.
- **Main client we work on:** native Android app in `GoChat_Kotlin/` (Kotlin, XML Views + ViewBinding, MVVM, Hilt, Room).
- **Other clients (not scanned):** `flutter/` (the original mobile app, being migrated to Kotlin), `frontend/` (Next.js web), `website/`.
- **Product plan:** `FEATURE_ROADMAP.md` (5 phases, checkboxes show progress). Migration plan: `FLUTTER_TO_KOTLIN_MIGRATION_GUIDE.md`.

---

## 2. Repo map (what matters)

| Path | What it is |
|---|---|
| `services/` | Go microservices (one folder each, each has `main.go`, usually `server/` + `repository/`) |
| `services/gateway/` | Public HTTP API + WebSocket. `main.go` holds **all routes** |
| `proto/*.proto` | gRPC contracts; generated code goes to `gen/<service>/` (`make proto`) |
| `pkg/` | Shared Go libs (config, db, redis, jwt, eventbus, discovery, fcm, metrics, ratelimit, crypto…) |
| `migrations/<schema>/NNN_*.sql` | SQL migrations per service schema; `000_schemas.sql` runs first |
| `cmd/migrate`, `cmd/authprotogen` | Migration runner, proto helper |
| `GoChat_Kotlin/` | **The Android app** (modules `:app` and `:shared`) |
| `app/` (repo root) | Android Studio template stub (FirstFragment/SecondFragment) — not the real app |
| `scripts/` | `cleanup_all.py`, `cleanup_db.py`, `inspect_local.py`, `migrate_neon.go`, `load_test.js` (k6) |
| `scratch/`, `scratch_*.py`, `sync_users_currency.py` | One-off debugging / data-fix scripts |
| `docker-compose.yml` | Local stack: postgres, pgbouncer, redis, minio + all services + gateway |
| `render.yaml` | Production deploy on **Render** |
| `k8s/` | Kustomize manifests (gateway + each microservice) |
| `nginx/`, `pgbouncer/`, `prometheus/`, `grafana/` | Infra config |
| `.github/workflows/ci.yml` | CI: kustomize validate, `go vet`/build, Flutter check |
| `*.exe`, `bin/` | Built service binaries (local Windows builds) |

---

## 3. Backend architecture

```
Android app ──HTTPS /api/v1/*──┐
            ──WSS  /ws ────────┤
                               ▼
                     API Gateway (Gin, :8080 / Render :10000)
                     middleware: CORS, versioning, geo, Prometheus, CDN,
                                 JWT auth, Redis rate-limit
                               │ gRPC
   ┌──────┬──────┬──────┬──────┼──────┬───────┬───────┬────────┬─────────┬────────┐
  auth  authz  chat  media  group  story  call  channel  social  miniapp  business  ai
   │       PostgreSQL (Neon, one DB per service)  +  Redis (pub/sub, cache, rate-limit)
   │       Media: MinIO (S3) and/or Telegram channel as CDN storage
```

### Services and gRPC ports

| Service | gRPC addr | RPCs | Notes |
|---|---|---|---|
| auth | 50051 | 24 | Register/login (phone **or** email + password, country_code), JWT + refresh, sessions/devices, QR device linking, 2FA PIN, phone OTP, privacy, blocks, E2EE key upload/fetch, account recovery, audit logs |
| chat | 50052 | 38 | Conversations, messages, threads, edit history, reactions, forward, pin, read receipts, search, typing, folders, labels, analytics, notification profiles, polls, scheduled messages (`scheduler/`), `StreamMessages` (server stream) |
| media | 50053 | 4 | Upload/download; storage backends `storage/minio.go`, `telegram.go`, `local.go`, `database.go`; `optimizer/` for image + video |
| authz | 50054 | 6 | Policy engine (`engine/`, uses govaluate), `seeder/` |
| group | 50055 | 24 | Groups, invite links, join approvals, promote/demote, communities, broadcast lists |
| story | 50056 | 6 | Post, reshare, delete, list, view, viewers |
| call | 50057 | 6 | Start/accept/reject/end, signaling, history; `sfu/room.go` = Pion WebRTC SFU for group calls |
| channel | 50058 | 8 | Broadcast channels: create, subscribe, publish, feed |
| ai | 50059 | 5 | Summarize, suggest replies, translate, adjust tone, action items. Uses `GEMINI_API_KEY` / `OPENAI_API_KEY`, falls back to heuristics when no key |
| payment | 50060 | 9 | **Proto only** — `services/payment/` does not exist (Makefile still references it) |
| social | 50061 | 17 | Follow, moments feed/like/comment, nearby users, badges, audio rooms |
| miniapp | 50062 | 12 | Bots, mini-apps, developer webhooks, API keys |
| business | 50063 | 65 | Biggest service (~7k lines). Business profile, catalogs, marketplace products + variants, categories, stores + followers, reviews, cart, orders, coupons, wishlist, product Q&A + moderation, appointments, auto-replies/business hours, quick replies, customer queue, FX rates, shipping tracking |

Plain HTTP (not gRPC): `notification` (FCM token register/send — dispatch is **mocked**), `search` (global search via Postgres `ILIKE` on products/users; gateway proxies `/api/v1/search`).

HTTP ports from `.env`: auth 4001, chat 4002, business 4004, authz 4005, group 4006, story 4007, call 4008, channel 4009, ai 4010, payment 4011, social 4012, miniapp 4013.

### Gateway specifics (`services/gateway/`)
- `main.go` (~900 lines): wiring + **every REST route** (~230). Public routes: `/auth/*`, marketplace browsing, `/fx/rates`, `/search`, media download/thumbnail, carrier webhook. Everything else is under `authRequired` (JWT via `middleware/auth.go`).
- Rate limits (Redis): auth endpoints, sending messages, uploads, checkout, product questions.
- Docs: `/docs`, `/swagger`, `/redoc`, `/openapi.json`. Health: `/healthz`. Metrics: `/metrics`.
- Handlers in `handlers/`: `auth.go`, `chat.go` (largest), `business.go`, `marketplace.go`, `orders.go`, `group.go`, `social.go`, `media.go`, `call.go`, `channel.go`, `story.go`, `miniapp.go`, `ai.go`, `fx.go`, `unfurl.go` (link previews), `search.go`.
- **Standalone mode** (`standalone.go`): if the remote gRPC services aren't reachable, the gateway boots **all services in-process** over a `bufconn` listener, connected straight to Neon. This is how the single Render gateway can run on its own. If Redis is missing it falls back to in-memory mode.
- **Push worker**: subscribes to Redis `chat:*` and `marketplace:*`; when the recipient is offline it "sends" a push (currently logs `MOCK PUSH NOTIFICATION`).
- Service discovery: optional Redis registry (`pkg/discovery`, `DISCOVERY_TYPE=redis`) with client-side load balancing.

### WebSocket protocol (`services/gateway/ws/`)
- Endpoint `GET /ws` (also `/api/v1/ws`), JWT required.
- `hub.go` = local connections; `relay.go` = cross-instance fan-out over Redis channels `ws:user:<id>` and `ws:broadcast`.
- `client.go` handles inbound frames by `type`:
  - `message` / `new_message` / `chat_message` → persisted via chat gRPC, then a `message_status` ack is sent back
  - `typing`
  - `reaction` / `remove_reaction` → persisted via gRPC AddReaction/RemoveReaction
  - `delete_message` / `message_deleted`, `edit_message` / `message_edited`
  - `query_presence` / `get_presence` → `presence` events
  - Server-sent: `presence`, `message_status`, `error`, `session_revoked`, `all_other_sessions_revoked`
- Also listens to chat `StreamMessages` gRPC stream per client.

### Data
- PostgreSQL on **Neon** (eu-central-1), one database per service (`auth-gochat-db`, `chat-gochat-db`, `business-gochat-db`, …), each with its own schema (`auth`, `chat`, `grp`, `business`, …) plus a shared `core.users` stub.
- Extensions: uuid-ossp, pgcrypto, pg_trgm, btree_gin, postgis.
- `pkg/database/automigrate.go`: services auto-migrate on boot.
- Business migrations go up to `018_drop_user_fk_constraints.sql` (variants, shipment tracking, price locking, refunds, store followers, review enhancements, etc.).
- Order state machine (`services/business/repository/order_state_machine.go`): `pending → paid → processing → shipped → out_for_delivery → delivered`, plus `cancelled` and `refunded`.
- Users carry `country_code` and a derived **currency** (`migrations/auth/007_add_currency.sql`; `sync_users_currency.py` backfills it). The marketplace does FX conversion (`handlers/fx.go`).

### Build / run
- `make proto` · `make build` · `make run-gateway` (etc.) · `make migrate-up` · `make docker-up` · `make test` · `make lint`
- Docker: one root `Dockerfile` with `ARG SERVICE=<name>` (it runs protoc inside the build).
- Config: `pkg/config/config.go` reads env vars (`.env` locally; see `.env.example`).

---

## 4. Deployment (Render, `render.yaml`)
- `gochat-gateway`: public web service (Docker, `SERVICE=gateway`, port 10000). Prod URL used by the Android app: **https://gochat-kvpj.onrender.com**
- `gochat-auth`, `-chat`, `-media`, `-authz`, `-group`, `-story`, `-call`, `-channel`, `-social`, `-miniapp`, `-business`: private services (`pserv`)
- `gochat-redis`, and `gochat-frontend` (Next.js, pnpm)
- Not in render.yaml: ai, search, notification, payment.
- CORS origins include `https://gochat-app.onrender.com`.

---

## 5. Android app — `GoChat_Kotlin/`

- Package `com.example.gochat`. compileSdk/targetSdk 35, minSdk 26, Java 17. Room DB `gochat_database`.
- Modules: `:app` (171 Kotlin files, ~42.6k lines) and `:shared` (KMP-style `commonMain` holding a `Message` model and `GoChatWebSocket`).
- **Stack:** XML Views + ViewBinding, MVVM (ViewModel + coroutines/Flow), **Hilt** DI (`di/`), Retrofit + OkHttp + kotlinx.serialization, Ktor client (websockets), Room + Paging 3 (with `ProductRemoteMediator`), WorkManager (+ Hilt workers), Coil, Media3 ExoPlayer, WebRTC, **Signal protocol** (E2EE), Firebase Messaging, Biometric, EncryptedSharedPreferences, Google Maps/Location, ZXing (QR), MPAndroidChart, Jsoup (link previews).
- **API:** `data/api/ApiConstants.kt` (`BASE_URL = PROD_BASE_URL`; switch to `EMULATOR_BASE_URL` `http://10.0.2.2:8080` for local work), `GoChatApiService.kt` (~97 Retrofit endpoints), `AuthInterceptor`, `TokenAuthenticator` (refresh flow), `TokenManager`.
- **WebSocket:** `data/websocket/GoChatWebSocket.kt`; reads the event name from `type` / `event` / `event_type`.
- **Room** (`data/db/AppDatabase.kt`, **version 19**, `fallbackToDestructiveMigration`, so a schema bump wipes local data): entities Conversation, Message, CallRecord, Store, Product, CartItem, Order, ProductRemoteKeys, BlockedUser. DAOs: `ChatDao`, `MarketplaceDao`.
- **MessageType:** text, image, video, audio, voice, file, location, contact, poll, sticker, product, order, payment_request, ping, catalog, system.
- **Repositories:** Auth, Chat (offline-first, ~1.7k lines), Marketplace (~1.7k), Group, Story, Call, Media.
- **Screens (`ui/`):**
  - auth: `LoginActivity` (country picker)
  - chat: `ChatListFragment`, `ChatRoomActivity` (~2.7k lines, biggest file), `MessageAdapter` (~2.1k), `ChatRoomViewModel` (~1.7k), group create/info, contact profile, media viewer, shared media, starred messages, polls, forward, GIF/catalog/product pickers, quick replies, tone assistant (AI), wallpaper/bubble themes, doodle, location picker, bot management, export, report/block
  - marketplace: `MarketplaceFragment` (~1.2k), product details, storefront, cart, checkout, orders, order details, seller insights
  - calls (`CallsFragment`, `CallActivity`), stories (list, viewer, viewers), contacts, settings + privacy, linked devices, security verification (safety numbers), backup/restore (Google Drive), storage management
- **core/:** backup (auto + Drive), contact sync worker, crypto (`EncryptionManager`, `SignalStore`), currency, chat export, haptics, media (record/play/merge audio, image compression, auto-download), network monitor, notifications (FCM service, direct reply, actions), chat lock (biometric), sounds, workers (message sync, disappearing messages, marketplace sync), themes/wallpapers, WebRTC client + reconnection handler.
- Bottom nav (per the roadmap): Chats | Marketplace | Stories | Calls | Settings.

---

## 6. Roadmap status (from `FEATURE_ROADMAP.md`)
- **Done:** in-chat + global search, media viewer + shared media, voice note speed/scrubbing, biometric chat lock, storage manager, disappearing-message presets, call history tab.
- **Open:**
  - In-chat payment request cards (`PAYMENT_REQUEST` exists in the enum, but the card viewholder and gateway `/marketplace/orders/pay` are not built)
  - Seller catalog in contact profile
  - Pinned messages UI (the backend pin/unpin endpoints already exist)
  - Offline outbox queue + auto-retry
  - Voice note transcription
  - Group AI catch-up summary (`/chat/conversations/:id/summary`)

---

## 7. Current state / things to watch
- Uncommitted change: `AppDatabase.kt` version 18 → 19. Gradle cache files under `.gradle/` and `build/` are also showing as modified; those are noise and should not be committed.
- Latest commits are marketplace work (repository, view model, layouts) and DB cleanup scripts.
- **Secrets in git (worth fixing):**
  - `services/gateway/standalone.go` hardcodes the Neon DB user and password as fallback DSNs.
  - `config/firebase-service-account.json`, `services/notification/firebase-service-account.json` and `k8s/secrets.yaml` are tracked in git.
  - `.env` is not tracked (good).
- Push notifications are mocked (the gateway worker and the notification service only log).
- `services/payment/` is missing even though the proto, `.env` vars and Makefile targets exist.
- Search uses `ILIKE`, not full-text search.
- Root `app/` + root Gradle files duplicate `GoChat_Kotlin/` setup; the real app is in `GoChat_Kotlin/`.
- Room uses destructive migration, so every entity change needs a version bump and wipes the local cache.
