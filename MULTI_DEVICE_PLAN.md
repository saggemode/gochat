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
- New-device flow: primary device shows a QR (reuse the `SecurityVerificationActivity` QR
  infra). QR carries a short-lived linking token + the primary's identity fingerprint.
- New device scans → registers its `device_id` + uploads its own key bundle (Phase 1 upload) →
  server marks it an authorized device of the user → appears in other users' fan-out sets.
- **Auth:** linking must be authenticated as the same account (the new device is already
  logged in as the user; the QR just authorizes the E2EE device registration + optionally
  triggers history transfer).
- Add a "Linked devices" screen (list `user_devices`, allow unlink → server removes the device
  so senders stop encrypting to it; client wipes that device's session state).

### Phase 4 — History onboarding for a new device
Server stores no plaintext, so a freshly linked device is empty. Pick per Phase 0 #3:
- **A — Encrypted backup restore:** reuse `ChatBackupManager` (AES-256-GCM + PBKDF2). The new
  device restores from the user's backup with the passphrase/recovery code. Already mostly
  built; wire it into the link flow.
- **B — Device-to-device transfer:** stream history over the pairwise session (or a local
  channel) from an existing device. No server copy. Larger effort; defer.

### Phase 5 — Delete-after-delivery across devices
- The server currently purges a message once every intended **recipient** has it. With
  multi-device, "delivered" must mean **every recipient device** acked (extend
  `chat.message_deliveries` to `(message_id, user_id, device_id)` and the purge query to
  require all devices of all members).
- Undelivered-TTL unchanged.

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
