# GoChat multi-device E2EE — phased implementation plan

**Status:** PLAN ONLY. No code changed. This is the roadmap for taking GoChat from
single-device E2EE (works in testing) to WhatsApp-style multi-device.

**Prerequisite:** the single-device E2EE stack (1:1 + groups, self-heal, disappearing
messages, verification) must be device-tested and stable first. Multi-device multiplies
the surface area of every crypto path; do not start Phase 1 on an unverified base.

---

## 1. Why this is big (honest scope)

Today everything is **per-user, single-device**:
- `EncryptionManager` hardcodes device id `1` everywhere: `SignalProtocolAddress(userId, 1)`.
- `SignalStore` keys sessions/identities by `address.name` = the **user id** (no device dimension).
- Server `core.user_prekeys` is keyed by `user_id` only; `ConsumePreKeyBundle` returns **one** bundle; `StoreE2EEKeys` is DELETE-all-then-insert for that user; `core.users.prekey_identity/signed/signature` store **one** identity.
- `GetE2EEKeys` returns a single bundle; the sender encrypts **once** per message.

Multi-device means a user has N devices, each with its **own** identity + prekeys, and every
message must be encrypted **to each of the recipient's devices AND to the sender's own other
devices** (so your phone and laptop stay in sync). That touches the key schema, the fan-out
encryption loop, a device-linking flow, and history sync — each a real subsystem.

Estimate: **weeks**, not hours. Each phase below is independently shippable and testable.

**What already helps us:** the client already sends an `X-Device-Id` header (e.g.
`dev_69c12cd3c6df46c7`) on every request — a stable per-install id. That becomes the device
dimension. Reuse it; don't invent a new one.

---

## 2. Target architecture

- **Keys are per (user, device).** Each device has its own identity key pair, signed prekey,
  and one-time prekeys, published under its `device_id`.
- **A "recipient" is a set of devices.** To send to user B, the sender fetches **all of B's
  devices' bundles** and encrypts the message once per device (N ciphertexts). It also
  encrypts to its **own other devices** (self-sync).
- **Sender Keys (groups) already fan out per member**; extend to per member-device.
- **Delete-after-delivery becomes delete-after-all-devices-delivered** (see Phase 5).
- **New device onboarding:** link via QR (the verification QR infra + `X-Device-Id` are a
  starting point), then restore history from the user's **encrypted backup** (Option A) and/or
  **device-to-device transfer** (Option B). Server keeps no plaintext, so without one of these a
  linked device starts empty.

---

## 3. Phases

### Phase 0 — Decisions (before any code)
Pin these; they shape the schema:
1. **Device cap** (WhatsApp = 4 companions). Suggest 5 total.
2. **Self-sync**: do we encrypt a user's own messages to their other devices? (Needed for a
   laptop to see phone's sent messages.) Recommend **yes** — it's the point of multi-device.
3. **History on a new device**: encrypted backup (A), device-transfer (B), or both?
   Recommend **A first** (GoChat already has `ChatBackupManager` + Drive + `AutoBackupWorker`),
   B later.
4. **Primary device** concept? WhatsApp historically needed the phone online; newer builds
   don't. Simpler to start with a **primary (phone)** that authorizes links.

### Phase 1 — Per-device key schema (server + client), still 1 device each
Make the data model device-aware **without** changing behavior yet (each user still has one
device in practice).

**Server (`services/auth`):**
- New migration: `core.device_prekeys` (replaces the per-user prekey storage), keyed by
  `(user_id, device_id, key_id)`; plus `core.user_devices(user_id, device_id, identity_key,
  signed_prekey, signed_prekey_signature, registration_id, created_at, last_seen)`.
  Keep `core.users.prekey_*` temporarily for back-compat / migration.
- `UploadE2EEKeys` → take `device_id` (from the `X-Device-Id` header the gateway already has);
  upsert into `user_devices` + append one-time keys into `device_prekeys` (APPEND, never
  delete-all — we already learned the delete-all re-adds consumed keys; see e2ee notes).
- `GetE2EEKeys(targetUserId)` → return a **list** of per-device bundles (one per device),
  each consuming one of that device's one-time prekeys.
- proto `auth.proto`: `GetE2EEKeysResponse` becomes `repeated DeviceBundle bundles` with
  `device_id, registration_id, prekey_identity, prekey_signed, prekey_signature, one_time_key`.
  Regenerate stubs (`make proto`).

**Client:**
- `SignalStore` keys become `${name}_${deviceId}` for sessions/identities (migrate existing
  `session_<user>` → `session_<user>_1`).
- `EncryptionManager`: replace the hardcoded `1` with the local device id (derived from the
  same source as `X-Device-Id`); `establishSession`/`encryptMessage`/`decryptMessage` take a
  device id. For now the local device id stays `1` for existing installs → behavior unchanged.

**Ship/test:** existing 1:1 chats keep working; the schema is now device-capable.

### Phase 2 — Fan-out encryption (encrypt to all of a recipient's devices)

**Server side DONE (2026-11-08, builds, needs redeploy):** migration `011` adds
`device_number INT` to `core.user_devices` (1 = first device → single-device keeps Signal
deviceId 1, so nothing breaks). `SavePreKeyBundle` assigns the next device_number per user.
`ConsumeDeviceBundles` + `GetE2EEKeysResponse.bundles` now return a bundle per device
(device_number as the Signal deviceId, one one-time key consumed each). Legacy single bundle
still served for current clients.

**Client side — CODE-COMPLETE BUT DORMANT (2026-11-08, compiles; UNTESTED):** the
fan-out path is written and N=1-safe, so it cannot activate (or regress the 1:1 path)
until a user actually has >1 device, which needs Phase 3 linking.
- `EncryptionManager`: `localDeviceNumber()` (prefs `e2ee_prefs`/`e2ee_device_number`,
  default 1), `fetchDeviceBundles()` (reads the `bundles` list), `encryptForUser()`
  (N=1 → delegates to `encryptMessage()` = legacy raw ciphertext; N>1 → marked envelope
  `\u0001E2EEMD1:{v,sd,to:{"<userId>:<devNum>":ct}}`, including self-sync to the
  sender's own other devices), `encryptForDevice()`/`establishSessionForDevice()`,
  `isEnvelope()`, `decryptEnvelope()`.
- `SignalStore`: device-aware session/identity keys with a legacy fallback for device 1
  (done — see §Phase 2 CAVEAT below).
- `decryptMessage(senderUserId, ct, senderDeviceNumber=1)` is device-aware (default 1
  keeps every existing caller unchanged).
- `ChatRepository`: direct-text send routes through `encryptForUser`; `tryDecryptMessage`
  intercepts the envelope marker before the base64 check and routes to `decryptEnvelope`.
- STILL TODO when linking lands: learn + persist this device's own device_number from the
  upload response (today always 1); extend group SKDM to member×device; media envelope.

**Original client notes (the risky part; needs device testing before it truly lands):**
- **Signal deviceId = `device_number`** (not the `X-Device-Id` string — Signal needs an int).
  The device learns its own device_number from the upload/response and uses it for its local
  identity, replacing the hardcoded `1`. First device = 1, so existing installs are unchanged.
- **CAVEAT (session orphaning):** `SignalStore` keys today are `session_<user>` (no device
  suffix). Moving to `session_<user>_<deviceNumber>` must either migrate existing keys
  (`session_<user>` → `session_<user>_1`) or have `loadSession`/`containsSession` fall back to
  the unsuffixed key. Get this wrong and every working session breaks.
- **Keep single-device on the EXISTING path:** only use the multi-device envelope when the
  recipient actually has >1 device; for N=1 keep today's raw-ciphertext-in-content path so the
  stable crypto is untouched until linking (Phase 3) exists.
- `EncryptionManager.encryptMessage(targetUserId, text)` → `encryptForUser(targetUserId)`:
  fetch all of B's device bundles (Phase 1 list), establish a session per device, produce a
  **map `{deviceId: ciphertext}`**.
- Also encrypt to the **sender's own other devices** (self-sync).
- **Wire format:** the message `content` carries a per-device envelope, e.g.
  `{ "e2ee": { "<userId:deviceId>": "<ciphertext>" , ... } }`. The recipient device picks its
  own entry by `(myUserId, myDeviceId)` and decrypts that. Non-matching entries ignored.
- Receiver (`ChatRepository` decrypt paths): parse the envelope, pick own device's ciphertext,
  run through the existing decrypt-once + persisted-dedup + mutex machinery (unchanged).
- Groups: sender-key distribution (SKDM) already loops members; extend to **member × device**
  (distribute each member-device a copy of the sender key over its pairwise session).

**Risk:** message size grows with device count; the decrypt-once/dedup logic must key on the
**per-device ciphertext**, not the whole envelope.

### Phase 3 — Device linking (QR pairing)

**Step 1 — device_number learning/persistence — DONE (2026-11-08, all 4 targets build):**
the hard prerequisite for N>1. A device now learns and stores its own Signal deviceId so
two devices can't both think they're device 1.
- Server: `SavePreKeyBundle` now `RETURNING device_number`; `UploadE2EEKeys` returns it
  (`UploadE2EEKeysResponse.device_number`); gateway `POST /auth/e2ee/keys` returns it as JSON.
- Client: `AuthRepository.uploadE2EEKeys` returns `Result<Int>`; `EncryptionManager`
  `persistDeviceNumber()` stores it in `e2ee_prefs`/`e2ee_device_number`, so `localDeviceNumber()`
  is now real (first/primary device = 1 → single-device unchanged). Persisted only on first-create
  upload (we don't re-upload each launch — see the OTK-revival lesson), which is correct because
  the number is assigned once.

**Step 2 — Linked-devices registry (server DONE; Android UI TODO):**
- proto: new RPCs `ListDevices`/`RemoveDevice` + `DeviceInfo` message. Regenerated.
- Server: `UserRepository.ListDevices` / `RemoveDevice` (RemoveDevice refuses device_number 1 —
  can't unlink the primary; deletes `device_prekeys`+`user_devices` so senders stop fanning out).
- Gateway: `GET /auth/e2ee/devices`, `DELETE /auth/e2ee/devices/:device_id`. **NOTE: namespaced
  under `/auth/e2ee/` on purpose** — `/auth/devices` is ALREADY the pre-existing *session*
  management feature (GetActiveSessions/TerminateSession, web login sessions: ip/browser/is_current).
  That is a DIFFERENT subsystem from this Signal-key device registry; do not conflate them. Gin
  would panic on the duplicate route — hence the separate path.
- Android "E2EE devices" screen — DONE (2026-11-08, `:app:compileDebugKotlin` + manifest merge OK):
  `ui/devices/E2EEDevicesActivity` (+ `activity_e2ee_devices.xml`, `item_e2ee_device.xml`), model
  `data/model/E2EEDevice`, `AuthRepository.getE2EEDevices()`/`removeE2EEDevice()`, API
  `GET/DELETE api/v1/auth/e2ee/devices`. Entry point: Settings → "Encryption devices" tile
  (`tileE2EEDevices`), separate from the existing "Linked Devices" (session) tile. Marks our own
  install ("This device" via X-Device-Id match); primary (device_number 1) shows no Unlink.
  Kept fully separate from `getLinkedDevices()/unlinkDevice()` (those hit the SESSION routes).

**Step 3 — QR pairing UX — DONE (2026-11-08, `:app:compileDebugKotlin` + manifest OK; UNTESTED):**
both devices are logged into the same account and a companion already publishes its keys at startup,
so pairing is the human-verified TRUST step (not the key transport).
- `ui/devices/DevicePairingActivity` (+ `activity_device_pairing.xml`): shows THIS device's QR
  (`gochat-e2ee-device://pair?uid&dev&did&ikf&ik`, built by `core/crypto/DevicePairingPayload`) with
  its identity fingerprint, and a "Scan the other device" button (ZXing `BarcodeEncoder` to render,
  `ScanContract` to scan — same infra as `SecurityVerificationActivity`/`ContactQrActivity`).
- On scan: rejects a different account / self; then **verifies the scanned fingerprint against the
  server's stored identity for that device** via `EncryptionManager.serverIdentityForOwnDevice()`
  (reads `GetE2EEKeys.bundles`) — a real MITM/key-swap check — and the fingerprint's self-consistency.
  Clean verify → "Link device"; any mismatch → warning + "Link anyway".
- Confirm → `EncryptionManager.recordPairedDevice(deviceId, identityB64)` (stores trust in
  `e2ee_paired_devices`, flips the account multi-device). Entry: "Link a device" button on the
  E2EE devices screen; that screen refreshes in `onResume`.
- **Auth:** linking is authenticated as the same account (the new device is already logged in).
- Distinct from the pre-existing `linkDeviceWithQr()` + `POST /auth/qr/link` (web *session* linking).

**🚩 GO-LIVE GATE for N>1 — WIRED (2026-11-08):** `encryptForUser` now gates on
`EncryptionManager.isAccountMultiDevice()` (sticky: true when `localDeviceNumber() > 1`, or the
"Encryption devices" screen saw >1 device, or a device was paired). While false (every account today)
it delegates to `encryptMessage()` with **no device-bundle fetch** — which also fixed a latent Phase-2
cost bug (the old code fetched bundles on every send, consuming a one-time prekey each time). Once the
account is multi-device the SENDER always uses the envelope, so `sd` is carried and the receiver builds
the session against the correct `(senderId, sd)` address — closing the ratchet-collision gap.
- **Remaining refinement:** recipient-is-multi-device-but-sender-isn't still sends raw to the
  recipient's device 1 only (a delivery gap, not corruption); and the envelope path still fetches
  recipient bundles per send (OTK cost). Both are dormant-path items to optimize when multi-device
  is actually exercised.

### Phase 4 — History onboarding for a new device — DONE (2026-11-09, `:app:compileDebugKotlin` OK; UNTESTED)
Server stores no plaintext, so a freshly linked device is empty. Chose **A (encrypted backup restore)**.
- **What already covered a FRESH install:** `LoginActivity.navigateToMain()` routes a first login
  (`!hasCompletedMigrationCheck`) to `RestoreBackupActivity`, which scans **Google Drive** + local,
  downloads, decrypts (passphrase), and restores into Room. So a brand-new companion install already
  onboards history from Drive.
- **What was added (the real gap):** a device LINKED AFTER setup, or one that SKIPPED restore, had no
  way to re-trigger onboarding, and the first-login path does a destructive `CLEAR_TASK` relaunch that's
  wrong mid-session. Added `RestoreBackupActivity.startForLinkedDevice()` + `EXTRA_LINKED_DEVICE` mode:
  companion-aware copy, a back button, and a non-destructive finish (returns to caller, doesn't reset
  the task or re-run the migration gate). Surfaced on the Encryption-devices screen as a "Restore chat
  history" button + a companion hint shown when `localDeviceNumber() > 1`.
- **B — Device-to-device transfer:** stream history over the pairwise session. No server copy. Deferred.
- **Note:** restore MERGES into Room (insert/REPLACE by id), so it's safe to run on a device that
  already has some live post-link messages.

### Phase 5 — Delete-after-delivery across devices
- The server currently purges a message once every intended **recipient** has it. With
  multi-device, "delivered" must mean **every recipient device** acked (extend
  `chat.message_deliveries` to `(message_id, user_id, device_id)` and the purge query to
  require all devices of all members).
- Undelivered-TTL unchanged.
- **⚠️ RECOMMEND DEFERRING until multi-device is device-tested.** This touches the irreversible
  purge path. Every current delivery hook (gateway WS fan-out, `GetMessages` pull, `MarkRead`,
  client `AckDelivery`) is per-**user**, which is correct and safe for single-device (today).
  Making purge per-device blind — while multi-device is dormant/untested — risks either purging a
  message before a real 2nd device got it (data loss) or never purging (storage leak), on a path
  that can't be validated from the dev machine. Do it only alongside real two-device testing.

### Phase 6 — Verification & trust across devices
- Safety numbers become per-device (or an aggregate of all the peer's device identities).
- "Safety number changed" must distinguish **a new linked device** (expected) from a **key
  change on an existing device** (suspicious) — show "X added a new device" vs the existing
  change banner.

---

## 4. Rollout rules
- Each phase builds + ships independently; **device-test 1:1 after every phase** before the
  next (two phones, then a third "second device" for the same account from Phase 3).
- Keep the per-user path working during Phase 1–2 (treat a single-device user as N=1).
- Never delete-all on prekey upload (append only) — a lesson already paid for in single-device.
- Everything crypto needs real two/three-device testing; it cannot be validated from the dev
  machine.

## 5. Biggest risks
- **Re-destabilizing the working 1:1 crypto** — mitigated by N=1 compatibility in Phase 1–2.
- **Fan-out size / performance** with many devices.
- **History onboarding** is the hard UX: without backup/transfer, a linked device is blank.
- **Lost-device / unlink** security: stop encrypting to removed devices promptly.

## 6. Open decisions for you
1. Device cap? (suggest 5)
2. Self-sync to own devices? (suggest yes)
3. History onboarding: backup (A), transfer (B), or both? (suggest A first)
4. Primary-device model or peer devices? (suggest primary = phone, to start)

Nothing proceeds to code until #1–#4 are chosen and single-device E2EE is device-verified.
