# GoChat → WhatsApp-style messaging: always-on E2EE + delete-after-delivery

**Status:** PLAN ONLY. No code changed, no data deleted. This documents the
re-architecture before any destructive work.

**Decision captured:** move GoChat from *plaintext-by-default + opt-in E2EE +
indefinite server retention* to *always-on E2EE + server deletes messages once
delivered*. This reverses the earlier "E2EE stays opt-in, never force globally"
rule — on purpose.

---

## 1. What we gain and what we give up

**Gain:** true privacy — the server stores only ciphertext it cannot read, and
keeps a message only until it's delivered (then deletes it).

**Give up (these features stop working and must be removed or reworked):**
- Server-side chat search (`/chat/search`) — can't search ciphertext.
- Cloud chat history / reinstall + multi-device restore from the server.
- Chat backup/restore that reads message text.
- AI tone assistant and any server feature that reads message content.
- Admin analytics on message content: the **message-mix** chart, the activity
  feed's message events, and any content-based moderation. (Report-based
  moderation still works; message *content* does not.)
- Group chats need a different crypto scheme (sender keys) — previously E2EE
  was deliberately avoided for groups.

**Still visible to the server even with E2EE (honesty — E2EE is not anonymity):**
metadata — who messaged whom, when, message sizes, online/last-seen, media
blob sizes. WhatsApp has this too.

---

## 2. Target architecture

### 2.1 Encryption (always-on)
Reuse and complete the existing scaffolding (`auth.user_prekeys`,
`core.users.prekey_identity/signed/signature`, `UploadE2EEKeys`/`GetE2EEKeys`,
the app's `EncryptionManager`). Target a Signal-style design:

- **Identity key** per user-device (long-term). **Signed prekey** (rotated).
  **One-time prekeys** (replenished). Keys are **per device**, not per user —
  multi-device means each device has its own keys and is a separate recipient.
- **Session setup:** X3DH to derive an initial shared secret from the
  recipient's published prekeys (no plaintext round-trip needed).
- **Message encryption:** Double Ratchet (forward secrecy + post-compromise
  security). Each 1:1 message encrypted to each of the recipient's devices.
- **Groups:** Sender Keys — each sender distributes a sender key to members via
  pairwise sessions, then encrypts group messages once with it.
- **Media:** encrypt the blob with a random symmetric key client-side; upload
  ciphertext to the media service; send the key inside the E2EE message. The
  media service only ever sees ciphertext.
- **Key verification:** safety numbers / fingerprint compare (optional phase).

> Principle: **no silent plaintext fallback.** If a recipient's keys are
> missing, the message queues (undelivered) until keys are available, or fails
> loudly — it is never sent in the clear. A partial E2EE that falls back to
> plaintext is a false promise and is out of scope.

### 2.2 Delete-after-delivery
- Server stores each ciphertext message with per-recipient-**device** delivery
  tracking (`delivered_devices`, `pending_devices`).
- A message row is **deleted** once every intended recipient device has
  acknowledged delivery (ACK on WS receive / on FCM-triggered fetch).
- **Undelivered** messages are retained until delivered **or** a TTL
  (e.g., 30 days, matching WhatsApp's "we don't keep them forever") — then
  dropped.
- Groups: delete when all members' devices have ACKed (or TTL).
- Sender's own copy lives only on the sender's device (local Room). Recipient's
  copy lives only on the recipient's device after delivery.

### 2.3 Multi-device & reinstall (critical consequence)
Because the server deletes delivered messages, a reinstalled/new device has
**nothing to restore from the server.** WhatsApp solves this with
**user-held encrypted backups** and **device linking**. We must ship one before
enabling deletion, or users lose all history on reinstall:
- **Option A — Encrypted backup:** client encrypts its history with a key
  derived from a user passphrase / recovery code; server (or their cloud)
  stores only ciphertext; restore decrypts locally. (GoChat already has
  `encrypted_backups` + a backup/restore flow to build on.)
- **Option B — Device transfer:** link a new device via the existing QR pairing
  and stream history device-to-device. No server copy.
- Decision needed: A, B, or both. **This is a hard prerequisite for Phase 4.**

---

## 3. Staged rollout (each phase shippable; reversible until Phase 6)

| Phase | What | Destructive? | Reversible? |
|------|------|------|------|
| 0 | This plan + decisions (backup option, group scope, TTL) | no | — |
| 1 | Complete/harden E2EE; **E2EE-on by default for new 1:1 chats**; remove plaintext fallback for those; keep existing chats working | no | yes |
| 2 | **Encrypted media**; **key/history backup** (§2.3) so deletion is safe | no | yes |
| 3 | **Group E2EE** (sender keys) | no | yes |
| 4 | **Delivery-device tracking + delete-after-delivery** for E2EE messages; undelivered TTL | server stops retaining *new* delivered msgs | partly |
| 5 | Remove/disable server features that can't work: chat search, AI-on-content, admin message-mix/activity-content | no (feature removal) | yes |
| 6 | **Purge existing delivered plaintext history** (the "delete now" decision) | **YES, irreversible** | **no** |

Each phase builds + ships independently. We do **not** start Phase 4 until
Phase 2 (backup) exists, or reinstalling an app wipes the user's history.

---

## 4. Phase 6 — the irreversible purge (gated)

The intended SQL (illustrative; run only after a full verified backup and
explicit go-ahead):

```sql
-- Delete delivered messages older than the undelivered-TTL window.
-- DELIVERED-only; undelivered stay until delivered or TTL.
DELETE FROM chat.messages
WHERE status IN ('delivered','read');
```

Guardrails before this ever runs:
1. A **full database backup** taken and verified restorable.
2. Phases 1–4 live (otherwise you're deleting history that clients still rely
   on server-side).
3. Explicit, specific confirmation at that moment — not inferred from this plan.
4. It will likely require **you** to run it (the sandbox blocks production
   deletes), or a one-off migration with the backup in hand.

---

## 5. Admin-panel impact (what I just built)
- `/admin/metrics/messages` (message-mix) → **remove** or switch to pure
  metadata counts (total messages by type) *if the type is still sent in
  cleartext metadata*; otherwise remove.
- Activity feed → drop message-content events; keep signups/orders/disputes.
- Everything else (users, orders, disputes, payouts, reports, RBAC, audit,
  broadcast, system metrics) is **unaffected** — it never read message bodies.

---

## 6. Risks
- **Crypto correctness** is the whole product now; bugs = silent data loss or
  false privacy. Needs careful review and test vectors.
- **History loss on reinstall** if deletion ships before backup/transfer.
- **Support burden**: lost messages are unrecoverable by design — no server
  copy to help users restore.
- **Performance**: per-device fan-out encryption increases client work and
  message count (one ciphertext per recipient device).

---

## 7. Open decisions for you
1. Multi-device/reinstall: **encrypted backup (A), device transfer (B), or both?**
   (Blocks Phase 4.)
2. Groups: include group E2EE now (Phase 3) or ship 1:1 first?
3. Undelivered retention TTL: 30 days? other?
4. Start at **Phase 1** (E2EE-on for new 1:1 chats, non-destructive) once you've
   answered #1?

Nothing proceeds to code until you pick on #1–#4.
