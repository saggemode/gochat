# GoChat — Go-Live Runbook

Consolidated production checklist. Code across all three surfaces (Go backend,
Android app, Next.js admin) is verified **buildable** as of 2026-11-15; what
remains below is **operational** — deploys, secrets, and device/endpoint testing.

Live gateway: `https://gochat-kvpj.onrender.com` · DB: Supabase PostgreSQL · hosting: Render (`render.yaml`).

---

## 1. Security — do first (blocking)

- [ ] **Rotate leaked secrets.** The **Neon DB password** and the **Firebase service-account key** are present in git history. Removing them from current files is not enough — rotate them at the provider:
  - [ ] Supabase/Neon: rotate the DB password; update `DATABASE_URL`/DSN in Render env.
  - [ ] Firebase: revoke + reissue the service-account key; update the backend FCM credential in Render.
- [ ] **Restrict `/metrics`** at the network layer (it's currently reachable).
- [ ] Confirm `JWT_SECRET`, `E2EE_KEY_ENCRYPTION_KEY`, Paystack keys, MinIO creds are set as **Render env vars**, not committed.

## 2. Backend — redeploy to Render (activates this session's server work)

The live backend runs OLD code until redeployed. A redeploy runs boot-time
`AutoMigrate` (every `migrations/**/*.sql`, idempotent) and ships the new code.

- [ ] **Deploy latest `main` (`origin/main`) for every service** (not just the gateway) — each service migrates its own schema on boot.
- [ ] Migrations that must apply: `auth/005` (privacy: last_seen/about/groups), `story/004` (status audience/mentions/music), `grp/003` (group events), plus the `auth→core`, `gen_random_uuid`, trigger, and `uuid-ossp` fixes.
- [ ] **PostGIS**: run once in Supabase SQL editor so `social/001` (nearby_users GEOGRAPHY) applies:
  ```sql
  CREATE EXTENSION IF NOT EXISTS postgis;
  ```
- [ ] **Verify all tables exist** after redeploy (empty result = clean):
  ```sql
  WITH expected(schema_name, table_name) AS (VALUES
    ('core','users'),('chat','messages'),('story','stories'),('grp','group_events'),
    ('social','moments'),('business','orders') /* …full list in chat history… */
  )
  SELECT e.schema_name, e.table_name AS missing
  FROM expected e LEFT JOIN information_schema.tables t
    ON t.table_schema=e.schema_name AND t.table_name=e.table_name AND t.table_type='BASE TABLE'
  WHERE t.table_name IS NULL;
  ```
- [ ] If any schema/table is missing, check that service's Render log for `migration file failed to apply` (it now logs the exact file + error).

### Server features that only work after this redeploy
- Privacy: `PUT /users/privacy` (last_seen/about/groups), who-can-add-to-groups enforcement
- Stories: posting, status audience (contacts/except/only), mentions, music
- Groups: announcements-only send enforcement, group events (create/list/RSVP)
- Notifications: mention + reaction pushes
- Earlier 500s fixed: stories, privacy (missing columns / uuid / schema)

## 3. Android — build + device test

- [ ] **Build the release/debug APK** (`:app:assembleDebug` is verified green; produce a signed release for distribution).
- [ ] **Device-test the runtime-sensitive pieces** (compile-verified, not device-verified):
  - [ ] **Room v21→v22 auto-migration on an *upgrade*** (install old version → upgrade → confirm chats/history survive and `isArchived` works). Never test only a fresh install.
  - [ ] **Live location** foreground service (`FOREGROUND_SERVICE_LOCATION`, FusedLocation) — permission grant, 15-min window, receiver banner.
  - [ ] Calls: 1:1 + group, switch camera, Bluetooth routing, screen share, call waiting, participant mute/remove (needs 2–3 devices).
  - [ ] Stickers, recording indicator, archive, contact share, broadcast fan-out.
  - [ ] Mention/reaction notifications (requires the backend redeploy above).
- [ ] E2EE is **disabled** (`E2EESettings.DISABLED=true`) → messages are plaintext; messaging should "just work". Re-enabling is a separate, gated effort.

## 4. Admin panel (Next.js) — deploy

Not hosted anywhere yet. Builds clean (`pnpm build`).

- [ ] **Deploy** the `admin/` app (Vercel or Render).
- [ ] Set env: `NEXT_PUBLIC_API_BASE_URL=https://gochat-kvpj.onrender.com`, `NEXT_PUBLIC_USE_MOCKS=false`.
- [ ] On the **gateway**, set the **`ADMIN_EMAILS` allowlist** so your account is recognized as admin (login probes `/api/v1/admin/overview`; non-admins are rejected).
- [ ] **Smoke-test the real admin endpoints** — this link has never been exercised against the live gateway.

## 5. CI / process

- [ ] CI already exists (`.github/workflows/ci.yml`: Go build/vet/test, Android `assembleDebug`, Flutter analyze, k8s validate). **Ensure it's green before trusting `main`** — a broken compile was pushed earlier despite CI.
- [ ] Consider a pre-push hook (`go build ./...`) to catch backend breakage locally.
- [ ] `.gitignore` the `.kotlin/` session dir (build cruft that shows spurious diffs).

## 6. Known advisory / not-fully-enforced (acceptable, but know the limits)

- Group-call **mute/remove** and **mention-reshare** are client-honored (cooperative, not SFU-enforced).
- `admins_only_edit_info` toggle is **advisory** (client-gated; backend edit still requires admin).
- Several checks **fail open** on DB error (group-add privacy, announcements-only, silence-unknown) — by design for availability.
- Broadcast lists aren't persisted (one-off client fan-out; backend list API exists but unwired).
- Location/contact/event/sticker are sent as **emoji-prefixed text**, not typed messages.
