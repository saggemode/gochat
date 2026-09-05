package com.example.gochat.core.crypto

import android.content.Context
import android.util.Base64
import org.whispersystems.libsignal.*
import org.whispersystems.libsignal.state.*
import org.whispersystems.libsignal.util.KeyHelper

class SignalStore(context: Context) : SignalProtocolStore {

    private val prefs = context.getSharedPreferences("signal_store", Context.MODE_PRIVATE)

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

    override fun saveIdentity(address: SignalProtocolAddress, identityKey: IdentityKey?): Boolean {
        prefs.edit().putString("identity_${address.name}", Base64.encodeToString(identityKey?.serialize(), Base64.DEFAULT)).apply()
        return true
    }

    override fun isTrustedIdentity(address: SignalProtocolAddress, identityKey: IdentityKey?, direction: IdentityKeyStore.Direction?): Boolean = true

    override fun getIdentity(address: SignalProtocolAddress): IdentityKey? {
        val encoded = prefs.getString("identity_${address.name}", null) ?: return null
        return IdentityKey(Base64.decode(encoded, Base64.DEFAULT), 0)
    }

    // ── SessionStore ─────────────────────────────────────────────

    override fun loadSession(address: SignalProtocolAddress): SessionRecord {
        val encoded = prefs.getString("session_${address.name}", null)
        return if (encoded != null) SessionRecord(Base64.decode(encoded, Base64.DEFAULT)) else SessionRecord()
    }

    override fun getSubDeviceSessions(name: String): List<Int> = emptyList()

    override fun storeSession(address: SignalProtocolAddress, record: SessionRecord) {
        prefs.edit().putString("session_${address.name}", Base64.encodeToString(record.serialize(), Base64.DEFAULT)).apply()
    }

    override fun containsSession(address: SignalProtocolAddress): Boolean = prefs.contains("session_${address.name}")

    override fun deleteSession(address: SignalProtocolAddress) {
        prefs.edit().remove("session_${address.name}").apply()
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
