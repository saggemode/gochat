package com.example.gochat.core.contacts

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import com.example.gochat.data.api.TokenManager
import com.example.gochat.data.model.SyncedContact
import com.example.gochat.data.repository.AuthRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ContactSyncManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val authRepo: AuthRepository,
    private val tokenManager: TokenManager
) {

    private val prefs = context.getSharedPreferences("gochat_contacts_pref", Context.MODE_PRIVATE)

    companion object {
        private const val CACHE_KEY = "gochat_cached_synced_contacts"
        private val json = Json { ignoreUnknownKeys = true }
    }

    fun hasPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun getCachedContacts(): List<SyncedContact> {
        return try {
            val raw = prefs.getString(CACHE_KEY, null)
            if (!raw.isNullOrBlank()) {
                json.decodeFromString<List<SyncedContact>>(raw)
            } else {
                emptyList()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun scanAndSyncContacts(force: Boolean = false): List<SyncedContact> = withContext(Dispatchers.IO) {
        if (!hasPermission()) {
            return@withContext getCachedContacts()
        }

        val currentUserId = tokenManager.userId.orEmpty()

        try {
            // 1. Read device address book
            val phoneToContact = mutableMapOf<String, MutableList<Pair<String, String>>>() // suffixKey -> List of (rawPhone, phonebookName)
            val queryIdentifiers = mutableSetOf<String>()

            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            )

            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                null,
                null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
            )?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numberIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)

                while (cursor.moveToNext()) {
                    val rawName = if (nameIdx != -1) cursor.getString(nameIdx).orEmpty().trim() else ""
                    val rawNumber = if (numberIdx != -1) cursor.getString(numberIdx).orEmpty().trim() else ""

                    val digitsOnly = rawNumber.replace(Regex("\\D"), "")
                    if (digitsOnly.length >= 7) {
                        queryIdentifiers.add(rawNumber)
                        queryIdentifiers.add(digitsOnly)

                        val suffixKey = if (digitsOnly.length >= 10) digitsOnly.takeLast(10) else digitsOnly
                        val contactName = rawName.ifBlank { rawNumber }

                        phoneToContact.getOrPut(suffixKey) { mutableListOf() }.add(rawNumber to contactName)
                    }
                }
            }

            if (queryIdentifiers.isEmpty()) {
                return@withContext emptyList()
            }

            // 2. Query backend for registered users matching these identifiers
            val result = authRepo.syncContacts(queryIdentifiers.toList())
            val registeredUsers = result.getOrNull() ?: emptyList()

            // 3. Match backend results against local device contacts
            val registeredContacts = mutableListOf<SyncedContact>()
            val matchedSuffixes = mutableSetOf<String>()
            val seenUserIds = mutableSetOf<String>()

            for (userObj in registeredUsers) {
                val uid = (userObj["id"] ?: userObj["user_id"])?.jsonPrimitive?.contentOrNull.orEmpty()
                if (uid.isBlank() || uid == currentUserId || seenUserIds.contains(uid)) {
                    continue
                }
                seenUserIds.add(uid)

                val uPhone = (userObj["phone"] ?: userObj["phone_number"])?.jsonPrimitive?.contentOrNull.orEmpty()
                val uDigits = uPhone.replace(Regex("\\D"), "")
                val uSuffix = if (uDigits.length >= 10) uDigits.takeLast(10) else uDigits

                var phonebookName = (userObj["name"] ?: userObj["display_name"])?.jsonPrimitive?.contentOrNull ?: "GoChat User"
                var primaryPhone = uPhone

                if (phoneToContact.containsKey(uSuffix)) {
                    val matchedList = phoneToContact[uSuffix]
                    if (!matchedList.isNullOrEmpty()) {
                        phonebookName = matchedList.first().second.ifBlank { phonebookName }
                        primaryPhone = matchedList.first().first.ifBlank { primaryPhone }
                    }
                    matchedSuffixes.add(uSuffix)
                }

                val avatar = (userObj["avatar"] ?: userObj["avatar_url"])?.jsonPrimitive?.contentOrNull.orEmpty()
                val statusText = (userObj["status_text"] ?: userObj["bio"])?.jsonPrimitive?.contentOrNull.orEmpty()
                val isOnline = userObj["is_online"]?.jsonPrimitive?.booleanOrNull == true
                val lastSeen = userObj["last_seen"]?.jsonPrimitive?.longOrNull
                val pin = (userObj["pin"] ?: userObj["PIN"])?.jsonPrimitive?.contentOrNull.orEmpty()
                val gochatName = (userObj["name"] ?: userObj["display_name"])?.jsonPrimitive?.contentOrNull

                registeredContacts.add(
                    SyncedContact(
                        id = uid,
                        phonebookName = phonebookName,
                        gochatName = gochatName,
                        phone = primaryPhone,
                        avatarUrl = avatar,
                        statusText = statusText,
                        isRegistered = true,
                        isOnline = isOnline,
                        lastSeen = lastSeen,
                        pin = pin,
                        name = phonebookName,
                        userId = uid
                    )
                )
            }

            // Sort registered contacts: online first, then alphabetically by display name
            registeredContacts.sortWith { a, b ->
                if (a.isOnline && !b.isOnline) -1
                else if (!a.isOnline && b.isOnline) 1
                else a.displayName.compareTo(b.displayName, ignoreCase = true)
            }

            // 4. Partition non-registered phonebook contacts into "Invite to GoChat"
            val inviteContacts = mutableListOf<SyncedContact>()
            val seenInvitePhones = mutableSetOf<String>()

            for ((suffix, entries) in phoneToContact) {
                if (matchedSuffixes.contains(suffix)) continue

                for ((rawPhone, contactName) in entries) {
                    val cleanDigits = rawPhone.replace(Regex("\\D"), "")
                    if (seenInvitePhones.contains(cleanDigits)) continue
                    seenInvitePhones.add(cleanDigits)

                    inviteContacts.add(
                        SyncedContact(
                            phonebookName = contactName,
                            phone = rawPhone,
                            isRegistered = false,
                            isOnline = false,
                            name = contactName
                        )
                    )
                }
            }

            inviteContacts.sortBy { it.displayName.lowercase() }

            val combined = registeredContacts + inviteContacts

            // 5. Cache result locally for instant startup
            try {
                val encoded = json.encodeToString(combined)
                prefs.edit().putString(CACHE_KEY, encoded).apply()
            } catch (_: Exception) {}

            combined
        } catch (_: Exception) {
            getCachedContacts()
        }
    }
}
