import 'dart:convert';
import 'dart:io';
import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import '../constants/api_constants.dart';

class MediaImageHelper {
  /// Extract a valid local File from either a file:// URI or a direct filesystem path.
  static File? getLocalFile(String? pathOrUri) {
    if (pathOrUri == null || pathOrUri.isEmpty || kIsWeb) return null;
    try {
      if (pathOrUri.startsWith('file://')) {
        final uri = Uri.tryParse(pathOrUri);
        if (uri != null) {
          final file = File.fromUri(uri);
          if (file.existsSync()) return file;
          // Also try unencoded raw path fallback
          final directPath = pathOrUri.replaceFirst(RegExp(r'^file://+'), '/');
          final fallbackFile = File(directPath);
          if (fallbackFile.existsSync()) return fallbackFile;
        }
      } else {
        final file = File(pathOrUri);
        if (file.existsSync()) return file;
      }
    } catch (_) {}
    return null;
  }

  /// Get a safe ImageProvider for any URL (http, https, /api/..., data:..., file://..., or local path).
  static ImageProvider? safeImageProvider(String? url) {
    if (url == null || url.trim().isEmpty) return null;
    final clean = url.trim();

    // 1. HTTP / HTTPS URL
    if (clean.startsWith('http://') || clean.startsWith('https://')) {
      final uri = Uri.tryParse(clean);
      if (uri != null && uri.host.isNotEmpty) {
        return NetworkImage(clean);
      }
      return null;
    }

    // 2. Relative API path
    if (clean.startsWith('/api/') || clean.startsWith('/')) {
      return NetworkImage('${ApiConstants.baseUrl}$clean');
    }

    // 3. Base64 Data URI
    if (clean.startsWith('data:image') || clean.startsWith('data:') || clean.contains(';base64,')) {
      try {
        final b64Index = clean.indexOf('base64,');
        final b64Data = b64Index != -1 ? clean.substring(b64Index + 7) : clean;
        final bytes = base64Decode(b64Data.trim());
        if (bytes.isNotEmpty) return MemoryImage(bytes);
      } catch (_) {}
      return null;
    }

    // 4. Local File (file:// URI or path)
    final localFile = getLocalFile(clean);
    if (localFile != null) {
      return FileImage(localFile);
    }

    // 5. Asset
    if (clean.startsWith('assets/')) {
      return AssetImage(clean);
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

    // 2. Local File (file:// or path)
    final localFile = getLocalFile(clean);
    if (localFile != null) {
      return Image.file(
        localFile,
        width: width,
        height: height,
        fit: fit,
        errorBuilder: (_, _, _) => defaultError,
      );
    }

    // 3. Asset
    if (clean.startsWith('assets/')) {
      return Image.asset(
        clean,
        width: width,
        height: height,
        fit: fit,
        errorBuilder: (_, _, _) => defaultError,
      );
    }

    // 4. Relative API path
    if (clean.startsWith('/api/') || clean.startsWith('/')) {
      return Image.network(
        '${ApiConstants.baseUrl}$clean',
        width: width,
        height: height,
        fit: fit,
        errorBuilder: (_, _, _) => defaultError,
      );
    }

    // 5. Network URL
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
    }

    return defaultError;
  }
}
