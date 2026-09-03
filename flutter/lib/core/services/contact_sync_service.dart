import 'dart:convert';
import 'package:flutter/foundation.dart';
import 'package:flutter_contacts/flutter_contacts.dart';
import 'package:shared_preferences/shared_preferences.dart';
import '../models/synced_contact.dart';
import 'api_service.dart';

class ContactSyncService {
  static final ContactSyncService _instance = ContactSyncService._internal();
  factory ContactSyncService() => _instance;
  ContactSyncService._internal();

  static const String _cacheKey = 'gochat_cached_synced_contacts';

  /// Check whether Contacts permission has already been granted
  Future<bool> hasPermission() async {
    try {
      return await FlutterContacts.permissions.has(PermissionType.read);
    } catch (e) {
      debugPrint('[ContactSyncService] Error checking permission: $e');
      return false;
    }
  }

  /// Request runtime Contacts permission from the user
  Future<bool> requestPermission() async {
    try {
      final status = await FlutterContacts.permissions.request(PermissionType.read);
      return status == PermissionStatus.granted || status == PermissionStatus.limited;
    } catch (e) {
      debugPrint('[ContactSyncService] Error requesting permission: $e');
      return false;
    }
  }

  /// Load cached synced contacts from disk for instant display
  Future<List<SyncedContact>> getCachedContacts() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      final raw = prefs.getString(_cacheKey);
      if (raw != null && raw.isNotEmpty) {
        final List list = jsonDecode(raw);
        return list.map((e) => SyncedContact.fromJson(Map<String, dynamic>.from(e))).toList();
      }
    } catch (e) {
      debugPrint('[ContactSyncService] Error loading cached contacts: $e');
    }
    return [];
  }

  /// Scan device contacts, match phone numbers against registered GoChat users, and cache result
  Future<List<SyncedContact>> scanAndSyncContacts({String currentUserId = ''}) async {
    final granted = await requestPermission();
    if (!granted) {
      debugPrint('[ContactSyncService] Contacts permission not granted');
      return await getCachedContacts();
    }

    try {
      // 1. Fetch raw device contacts
      final deviceContacts = await FlutterContacts.getAll(
        properties: {ContactProperty.name, ContactProperty.phone},
      );

      if (deviceContacts.isEmpty) {
        return [];
      }

      // Map: normalized 10-digit phone suffix -> list of (rawPhone, phonebookName)
      final Map<String, List<Map<String, String>>> phoneToContact = {};
      final Set<String> queryIdentifiers = {};

      for (final contact in deviceContacts) {
        final name = (contact.displayName ?? '').trim().isNotEmpty
            ? (contact.displayName ?? '').trim()
            : '${contact.name?.first ?? ''} ${contact.name?.last ?? ''}'.trim();

        for (final p in contact.phones) {
          final rawNumber = p.number.trim();
          final digitsOnly = rawNumber.replaceAll(RegExp(r'\D'), '');
          if (digitsOnly.length >= 7) {
            queryIdentifiers.add(rawNumber);
            queryIdentifiers.add(digitsOnly);

            // Use the last 10 digits as a robust matching key across country code variations
            final suffixKey = digitsOnly.length >= 10
                ? digitsOnly.substring(digitsOnly.length - 10)
                : digitsOnly;

            phoneToContact.putIfAbsent(suffixKey, () => []).add({
              'raw': rawNumber,
              'name': name.isNotEmpty ? name : rawNumber,
            });
          }
        }
      }

      if (queryIdentifiers.isEmpty) {
        return [];
      }

      // 2. Query backend for registered GoChat accounts with these identifiers
      final registeredUsers = await ApiService.syncContacts(queryIdentifiers.toList());

      // 3. Match backend results to device contacts
      final List<SyncedContact> registeredContacts = [];
      final Set<String> matchedSuffixes = {};
      final Set<String> seenUserIds = {};

      for (final u in registeredUsers) {
        final uid = (u['id'] ?? '').toString();
        if (uid.isEmpty || uid == currentUserId || seenUserIds.contains(uid)) {
          continue;
        }
        seenUserIds.add(uid);

        final uPhone = (u['phone'] ?? '').toString();
        final uDigits = uPhone.replaceAll(RegExp(r'\D'), '');
        final uSuffix = uDigits.length >= 10
            ? uDigits.substring(uDigits.length - 10)
            : uDigits;

        String phonebookName = (u['name'] ?? 'GoChat User').toString();
        String primaryPhone = uPhone;

        if (phoneToContact.containsKey(uSuffix)) {
          final matchedList = phoneToContact[uSuffix]!;
          if (matchedList.isNotEmpty) {
            phonebookName = matchedList.first['name'] ?? phonebookName;
            primaryPhone = matchedList.first['raw'] ?? primaryPhone;
          }
          matchedSuffixes.add(uSuffix);
        }

        DateTime? parsedLastSeen;
        final ls = u['last_seen'];
        if (ls is int && ls > 0) {
          parsedLastSeen = DateTime.fromMillisecondsSinceEpoch(ls * 1000);
        } else if (ls is String && ls.isNotEmpty) {
          parsedLastSeen = DateTime.tryParse(ls);
        }

        registeredContacts.add(SyncedContact(
          id: uid,
          phonebookName: phonebookName,
          gochatName: (u['name'] ?? '').toString(),
          phone: primaryPhone,
          email: u['email']?.toString(),
          avatarUrl: (u['avatar'] ?? u['avatar_url'] ?? '').toString(),
          statusText: (u['status_text'] ?? '').toString(),
          isRegistered: true,
          isOnline: u['is_online'] == true,
          lastSeen: parsedLastSeen,
          pin: u['pin']?.toString(),
        ));
      }

      // Sort registered contacts: online first, then alphabetically
      registeredContacts.sort((a, b) {
        if (a.isOnline && !b.isOnline) return -1;
        if (!a.isOnline && b.isOnline) return 1;
        return a.displayName.toLowerCase().compareTo(b.displayName.toLowerCase());
      });

      // 4. Collect non-registered contacts for "Invite to GoChat"
      final List<SyncedContact> inviteContacts = [];
      final Set<String> seenInvitePhones = {};

      for (final entry in phoneToContact.entries) {
        if (matchedSuffixes.contains(entry.key)) continue;

        for (final item in entry.value) {
          final rawPhone = item['raw']!;
          final cleanDigits = rawPhone.replaceAll(RegExp(r'\D'), '');
          if (seenInvitePhones.contains(cleanDigits)) continue;
          seenInvitePhones.add(cleanDigits);

          inviteContacts.add(SyncedContact(
            phonebookName: item['name']!,
            phone: rawPhone,
            isRegistered: false,
            isOnline: false,
          ));
        }
      }

      inviteContacts.sort((a, b) => a.displayName.toLowerCase().compareTo(b.displayName.toLowerCase()));

      final combined = [...registeredContacts, ...inviteContacts];

      // 5. Cache result locally for offline/instant access
      final prefs = await SharedPreferences.getInstance();
      await prefs.setString(_cacheKey, jsonEncode(combined.map((c) => c.toJson()).toList()));

      return combined;
    } catch (e) {
      debugPrint('[ContactSyncService] Scan & Sync error: $e');
      return await getCachedContacts();
    }
  }
}
