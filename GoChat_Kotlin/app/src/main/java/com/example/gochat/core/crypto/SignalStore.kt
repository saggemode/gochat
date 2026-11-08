package com.example.gochat.core.crypto

import android.content.Context
import android.util.Base64
import org.whispersystems.libsignal.*
import org.whispersystems.libsignal.state.*
import org.whispersystems.libsignal.util.KeyHelper
import org.whispersystems.libsignal.groups.SenderKeyName
import org.whispersystems.libsignal.groups.state.SenderKeyRecord
import org.whispersystems.libsignal.groups.state.SenderKeyStore

class SignalStore(context: Context) : SignalProtocolStore, SenderKeyStore {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS = "signal_store"

        /**
         * Wipe ALL Signal key material (identity key, registration id, pre/signed
         * keys, 1:1 sessions, group sender keys). Call on logout / account deletion
         * so a subsequent re-register starts from a fresh cryptographic identity with
         * no stale sessions (which otherwise cause undecryptable messages).
         */
        fun clear(context: Context) {
            try {
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
            } catch (_: Exception) {}
        }
    }

    // ── SenderKeyStore (group messaging) ─────────────────────────
    private fun senderKeyId(name: SenderKeyName): String =
        "senderkey_${name.groupId}_${name.sender.name}_${name.sender.deviceId}"

    override fun storeSenderKey(senderKeyName: SenderKeyName, record: SenderKeyRecord) {
        prefs.edit().putString(senderKeyId(senderKeyName), Base64.encodeToString(record.serialize(), Base64.DEFAULT)).apply()
    }

    override fun loadSenderKey(senderKeyName: SenderKeyName): SenderKeyRecord {
        val encoded = prefs.getString(senderKeyId(senderKeyName), null) ?: return SenderKeyRecord()
        return SenderKeyRecord(Base64.decode(encoded, Base64.DEFAULT))
    }

    // ── IdentityKeyStore ─────────────────────────────────────────

    override fun getIdentityKeyPair(): IdentityKeyPair {
        val encoded = prefs.getString("identity_key_pair", null)
        return if (encoded != null) {
            IdentityKeyPair(Base64.decode(encoded, Base64.DEFAULT))
        } else {
            val keyPair = KeyHelper.generateIdentityKeyPair()
            prefs.edit().putString("identity_key_pair", Base64.encodeToString(keyPair.serialize(), Base64.DEFAULT)).apply()
            keyPair
        }
    }

    override fun getLocalRegistrationId(): Int {
        val id = prefs.getInt("registration_id", 0)
        return if (id != 0) id else {
            val newId = KeyHelper.generateRegistrationId(false)
            prefs.edit().putInt("registration_id", newId).apply()
            newId
        }
    }

    // Multi-device: sessions/identities are keyed per (user, device). The LEGACY
    // key (no device suffix) is read as a fallback for device 1 so existing
    // single-device sessions never orphan; new writes always use the device key.
    private fun sessionKey(a: SignalProtocolAddress) = "session_${a.name}_${a.deviceId}"
    private fun legacySessionKey(a: SignalProtocolAddress) = "session_${a.name}"
    private fun identityKey(a: SignalProtocolAddress) = "identity_${a.name}_${a.deviceId}"
    private fun legacyIdentityKey(a: SignalProtocolAddress) = "identity_${a.name}"

    override fun saveIdentity(address: SignalProtocolAddress, identityKey: IdentityKey?): Boolean {
        prefs.edit().putString(identityKey(address), Base64.encodeToString(identityKey?.serialize(), Base64.DEFAULT)).apply()
        return true
    }

    override fun isTrustedIdentity(address: SignalProtocolAddress, identityKey: IdentityKey?, direction: IdentityKeyStore.Direction?): Boolean = true

    override fun getIdentity(address: SignalProtocolAddress): IdentityKey? {
        val encoded = prefs.getString(identityKey(address), null)
            ?: (if (address.deviceId == 1) prefs.getString(legacyIdentityKey(address), null) else null)
            ?: return null
        return IdentityKey(Base64.decode(encoded, Base64.DEFAULT), 0)
    }

    // ── SessionStore ─────────────────────────────────────────────

    override fun loadSession(address: SignalProtocolAddress): SessionRecord {
        val encoded = prefs.getString(sessionKey(address), null)
            ?: (if (address.deviceId == 1) prefs.getString(legacySessionKey(address), null) else null)
        return if (encoded != null) SessionRecord(Base64.decode(encoded, Base64.DEFAULT)) else SessionRecord()
    }

    override fun getSubDeviceSessions(name: String): List<Int> = emptyList()

    override fun storeSession(address: SignalProtocolAddress, record: SessionRecord) {
        prefs.edit().putString(sessionKey(address), Base64.encodeToString(record.serialize(), Base64.DEFAULT)).apply()
    }

    override fun containsSession(address: SignalProtocolAddress): Boolean =
        prefs.contains(sessionKey(address)) || (address.deviceId == 1 && prefs.contains(legacySessionKey(address)))

    override fun deleteSession(address: SignalProtocolAddress) {
        prefs.edit().remove(sessionKey(address)).also { if (address.deviceId == 1) it.remove(legacySessionKey(address)) }.apply()
    }

    override fun deleteAllSessions(name: String) {}

    // ── PreKeyStore ──────────────────────────────────────────────

    override fun loadPreKey(preKeyId: Int): PreKeyRecord {
        val encoded = prefs.getString("prekey_$preKeyId", null) ?: throw InvalidKeyIdException("No such prekey")
        return PreKeyRecord(Base64.decode(encoded, Base64.DEFAULT))
    }

    override fun storePreKey(preKeyId: Int, record: PreKeyRecord) {
        prefs.edit().putString("prekey_$preKeyId", Base64.encodeToString(record.serialize(), Base64.DEFAULT)).apply()
    }

    override fun containsPreKey(preKeyId: Int): Boolean = prefs.contains("prekey_$preKeyId")

    override fun removePreKey(preKeyId: Int) {
        prefs.edit().remove("prekey_$preKeyId").apply()
    }

    // ── SignedPreKeyStore ────────────────────────────────────────

    override fun loadSignedPreKey(signedPreKeyId: Int): SignedPreKeyRecord {
        val encoded = prefs.getString("signed_prekey_$signedPreKeyId", null) ?: throw InvalidKeyIdException("No such signed prekey")
        return SignedPreKeyRecord(Base64.decode(encoded, Base64.DEFAULT))
    }

    override fun loadSignedPreKeys(): List<SignedPreKeyRecord> = emptyList()

    override fun storeSignedPreKey(signedPreKeyId: Int, record: SignedPreKeyRecord) {
        prefs.edit().putString("signed_prekey_$signedPreKeyId", Base64.encodeToString(record.serialize(), Base64.DEFAULT)).apply()
    }

    override fun containsSignedPreKey(signedPreKeyId: Int): Boolean = prefs.contains("signed_prekey_$signedPreKeyId")

    override fun removeSignedPreKey(signedPreKeyId: Int) {
        prefs.edit().remove("signed_prekey_$signedPreKeyId").apply()
    }
}
