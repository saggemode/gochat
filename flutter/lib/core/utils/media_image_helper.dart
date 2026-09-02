import 'dart:convert';
import 'dart:io';
import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import '../constants/api_constants.dart';

class MediaImageHelper {
  /// Check if a path looks like an absolute local device filesystem path
  static bool isLocalDevicePath(String? path) {
    if (path == null || path.isEmpty) return false;
    final p = path.trim().toLowerCase();
    return p.startsWith('file://') ||
        p.startsWith('/storage/') ||
        p.startsWith('/data/') ||
        p.startsWith('/sdcard/') ||
        p.startsWith('/users/') ||
        p.startsWith('/private/') ||
        p.startsWith('/var/') ||
        p.startsWith('/tmp/') ||
        RegExp(r'^[a-z]:[\\/]', caseSensitive: false).hasMatch(p);
  }

  /// Extract a valid local File from either a file:// URI or a direct filesystem path.
  static File? getLocalFile(String? pathOrUri) {
    if (pathOrUri == null || pathOrUri.isEmpty || kIsWeb) return null;
    try {
      final clean = pathOrUri.trim();
      if (clean.startsWith('file://')) {
        final uri = Uri.tryParse(clean);
        if (uri != null) {
          final file = File.fromUri(uri);
          if (file.existsSync()) return file;
          // Also try unencoded raw path fallback
          final directPath = clean.replaceFirst(RegExp(r'^file://+'), '/');
          final fallbackFile = File(directPath);
          if (fallbackFile.existsSync()) return fallbackFile;
        }
      } else {
        final file = File(clean);
        if (file.existsSync()) return file;
        // If it's a known device path pattern, return the file object anyway for lazy creation
        if (isLocalDevicePath(clean)) return file;
      }
    } catch (_) {}
    return null;
  }

  /// Get a safe ImageProvider for any URL (http, https, /api/..., data:..., file://..., or local path).
  static ImageProvider? safeImageProvider(String? url) {
    if (url == null || url.trim().isEmpty) return null;
    final clean = url.trim();

    // 1. Base64 Data URI
    if (clean.startsWith('data:image') || clean.startsWith('data:') || clean.contains(';base64,')) {
      try {
        final b64Index = clean.indexOf('base64,');
        final b64Data = b64Index != -1 ? clean.substring(b64Index + 7) : clean;
        final bytes = base64Decode(b64Data.trim());
        if (bytes.isNotEmpty) return MemoryImage(bytes);
      } catch (_) {}
      return null;
    }

    // 2. HTTP / HTTPS URL
    if (clean.startsWith('http://') || clean.startsWith('https://')) {
      final uri = Uri.tryParse(clean);
      if (uri != null && uri.host.isNotEmpty) {
        return NetworkImage(clean);
      }
      return null;
    }

    // 3. Local File (file:// URI or path) - checked BEFORE relative web endpoints!
    final localFile = getLocalFile(clean);
    if (localFile != null && !kIsWeb) {
      return FileImage(localFile);
    }

    // If it is a device path that couldn't be loaded, do NOT treat as a network endpoint
    if (isLocalDevicePath(clean)) {
      return null;
    }

    // 4. Asset
    if (clean.startsWith('assets/')) {
      return AssetImage(clean);
    }

    // 5. Relative API path (e.g. /api/v1/..., /media/..., /uploads/...)
    if (clean.startsWith('/api/') || clean.startsWith('/media/') || clean.startsWith('/uploads/') || clean.startsWith('/static/')) {
      return NetworkImage('${ApiConstants.baseUrl}$clean');
    }

    return null;
  }

  /// Build a safe, universal Image widget that handles all media formats without crashing.
  static Widget buildSafeImage(
    String? url, {
    double? width,
    double? height,
    BoxFit fit = BoxFit.cover,
    Widget? placeholder,
    Widget? errorWidget,
  }) {
    final defaultError = errorWidget ??
        Container(
          width: width,
          height: height,
          color: Colors.black26,
          child: const Center(child: Icon(Icons.broken_image_rounded, color: Colors.white38, size: 28)),
        );

    if (url == null || url.trim().isEmpty) {
      return placeholder ?? defaultError;
    }

    final clean = url.trim();

    // 1. Base64 Data URI
    if (clean.startsWith('data:image') || clean.startsWith('data:') || clean.contains(';base64,')) {
      try {
        final b64Index = clean.indexOf('base64,');
        final b64Data = b64Index != -1 ? clean.substring(b64Index + 7) : clean;
        final bytes = base64Decode(b64Data.trim());
        if (bytes.isNotEmpty) {
          return Image.memory(
            bytes,
            width: width,
            height: height,
            fit: fit,
            errorBuilder: (_, _, _) => defaultError,
          );
        }
      } catch (_) {}
      return defaultError;
    }

    // 2. HTTP / HTTPS Network URL
    if (clean.startsWith('http://') || clean.startsWith('https://')) {
      final uri = Uri.tryParse(clean);
      if (uri != null && uri.host.isNotEmpty) {
        return Image.network(
          clean,
          width: width,
          height: height,
          fit: fit,
          errorBuilder: (_, _, _) => defaultError,
        );
      }
      return defaultError;
    }

    // 3. Local File (file:// or path) - checked BEFORE relative web endpoints!
    final localFile = getLocalFile(clean);
    if (localFile != null && !kIsWeb) {
      return Image.file(
        localFile,
        width: width,
        height: height,
        fit: fit,
        errorBuilder: (_, _, _) => defaultError,
      );
    }

    // If it is a device path that couldn't be loaded, do NOT treat as a network endpoint
    if (isLocalDevicePath(clean)) {
      return defaultError;
    }

    // 4. Asset
    if (clean.startsWith('assets/')) {
      return Image.asset(
        clean,
        width: width,
        height: height,
        fit: fit,
        errorBuilder: (_, _, _) => defaultError,
      );
    }

    // 5. Relative API path (e.g. /api/v1/..., /media/..., /uploads/...)
    if (clean.startsWith('/api/') || clean.startsWith('/media/') || clean.startsWith('/uploads/') || clean.startsWith('/static/')) {
      return Image.network(
        '${ApiConstants.baseUrl}$clean',
        width: width,
        height: height,
        fit: fit,
        errorBuilder: (_, _, _) => defaultError,
      );
    }

    return defaultError;
  }
}
