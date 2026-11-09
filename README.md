# GoChat

A WhatsApp/WeChat-style **super-app**: end-to-end-encrypted chat, voice/video &
group calls, stories, channels, a full **marketplace** (stores, products, orders,
coupons, payments), social features, bots/mini-apps, and AI helpers.

## Repository layout

| Codebase | Path | Stack |
|---|---|---|
| **Backend** | `services/`, `pkg/`, `proto/`, `migrations/` | Go 1.25 microservices (gRPC) behind one Gin HTTP + WebSocket gateway |
| **Android app** (primary client) | `GoChat_Kotlin/` | Kotlin · MVVM · Hilt · Room · Retrofit · WebRTC · Signal (E2EE) |
| **Admin console** | `admin/` | Next.js 16 · React 19 · Tailwind · TanStack Query |

> Not actively developed: `flutter/`, `frontend/`, `website/`, and the repo-root
> `app/` stub (the real app is in `GoChat_Kotlin/`).

## Quick start (local backend)

```bash
cp .env.example .env     # fill in values
make docker-up           # Postgres + Redis + MinIO + all services + gateway
```

Gateway → http://localhost:8080 · health `/healthz` · API docs `/docs`.

Run the Android app from `GoChat_Kotlin/` in Android Studio (point
`ApiConstants.BASE_URL` at your gateway), and the admin console with
`cd admin && npm run dev`.

## Documentation

- **[DEVELOPERS.md](DEVELOPERS.md)** — setup, environment variables, external
  services/accounts, deployment, and the per-codebase build/run guide. **Start here.**
- [WHAT_I_KNOW.md](WHAT_I_KNOW.md) — deep code tour (architecture, routes, data model).
- [FEATURE_ROADMAP.md](FEATURE_ROADMAP.md) — product plan & status.
- [MULTI_DEVICE_PLAN.md](MULTI_DEVICE_PLAN.md) · [E2EE_DELETE_AFTER_DELIVERY_PLAN.md](E2EE_DELETE_AFTER_DELIVERY_PLAN.md) — E2EE/multi-device design.

## Status

Production backend deploys on **Render** (`render.yaml`); the live gateway is
`https://gochat-kvpj.onrender.com`, backed by **Supabase** PostgreSQL. Several
subsystems (E2EE/multi-device, group video calls, change-number) are
**feature-complete in code but not yet device/runtime-tested** — see DEVELOPERS.md §10.
