import 'dart:io';
import 'package:flutter/foundation.dart';
import 'package:intl/intl.dart';
import 'package:path/path.dart' as p;
import 'package:path_provider/path_provider.dart';

enum MediaCategory {
  voiceNotes,
  images,
  video,
  documents,
}

/// WhatsApp-style local file manager that structures GoChat media files on the device filesystem.
/// 
/// Directory Structure:
/// GoChat/
/// └── Media/
///     ├── GoChat Voice Notes/
///     │   └── AUD-20260831-WA0001.m4a
///     ├── GoChat Images/
///     │   └── IMG-20260831-WA0001.jpg
///     ├── GoChat Video/
///     │   └── VID-20260831-WA0001.mp4
///     └── GoChat Documents/
///         └── DOC-20260831-WA0001.pdf
class MediaStorageService {
  static final MediaStorageService _instance = MediaStorageService._();
  factory MediaStorageService() => _instance;
  MediaStorageService._();

  static int _fileCounter = 1;
  String? _baseMediaPath;

  /// Ensure all media subdirectories exist on disk
  Future<void> init() async {
    if (kIsWeb) return;
    try {
      Directory baseDir;
      if (Platform.isAndroid) {
        // Try external storage directory, fallback to app docs
        final extDir = await getExternalStorageDirectory();
        baseDir = extDir ?? await getApplicationDocumentsDirectory();
      } else {
        baseDir = await getApplicationDocumentsDirectory();
      }

      _baseMediaPath = p.join(baseDir.path, 'GoChat', 'Media');

      // Create all category subdirectories
      for (final category in MediaCategory.values) {
        final dirPath = _getCategoryPath(category);
        final dir = Directory(dirPath);
        if (!await dir.exists()) {
          await dir.create(recursive: true);
        }
      }

      debugPrint('[MediaStorageService] Media folders initialized at: $_baseMediaPath');
    } catch (e) {
      debugPrint('[MediaStorageService] Error initializing media directories: $e');
    }
  }

  /// Get absolute path for a media category folder
  String _getCategoryPath(MediaCategory category) {
    final root = _baseMediaPath ?? '';
    switch (category) {
      case MediaCategory.voiceNotes:
        return p.join(root, 'GoChat Voice Notes');
      case MediaCategory.images:
        return p.join(root, 'GoChat Images');
      case MediaCategory.video:
        return p.join(root, 'GoChat Video');
      case MediaCategory.documents:
        return p.join(root, 'GoChat Documents');
    }
  }

  /// Generate a WhatsApp-standard filename (e.g. `AUD-20260831-WA0001.m4a`)
  String _generateFileName(MediaCategory category, String ext) {
    final dateStr = DateFormat('yyyyMMdd').format(DateTime.now());
    final countStr = (_fileCounter++ % 9999).toString().padLeft(4, '0');
    final cleanExt = ext.startsWith('.') ? ext : '.$ext';

    switch (category) {
      case MediaCategory.voiceNotes:
        return 'AUD-$dateStr-WA$countStr$cleanExt';
      case MediaCategory.images:
        return 'IMG-$dateStr-WA$countStr$cleanExt';
      case MediaCategory.video:
        return 'VID-$dateStr-WA$countStr$cleanExt';
      case MediaCategory.documents:
        return 'DOC-$dateStr-WA$countStr$cleanExt';
    }
  }

  /// Save a newly recorded voice note to the permanent `GoChat Voice Notes` folder
  Future<String> saveVoiceNote(String sourceTempPath) async {
    if (kIsWeb) return sourceTempPath;
    await init();

    try {
      final sourceFile = File(sourceTempPath);
      if (!await sourceFile.exists()) return sourceTempPath;

      final targetFolder = _getCategoryPath(MediaCategory.voiceNotes);
      final newFileName = _generateFileName(MediaCategory.voiceNotes, '.m4a');
      final targetPath = p.join(targetFolder, newFileName);

      final savedFile = await sourceFile.copy(targetPath);
      debugPrint('[MediaStorageService] Saved voice note to permanent storage: $targetPath');
      return savedFile.path;
    } catch (e) {
      debugPrint('[MediaStorageService] Error saving voice note: $e');
      return sourceTempPath;
    }
  }

  /// Save an image to the permanent `GoChat Images` folder
  Future<String> saveImage(String sourceTempPath, {String ext = '.jpg'}) async {
    if (kIsWeb) return sourceTempPath;
    await init();

    try {
      final sourceFile = File(sourceTempPath);
      if (!await sourceFile.exists()) return sourceTempPath;

      final targetFolder = _getCategoryPath(MediaCategory.images);
      final newFileName = _generateFileName(MediaCategory.images, ext);
      final targetPath = p.join(targetFolder, newFileName);

      final savedFile = await sourceFile.copy(targetPath);
      debugPrint('[MediaStorageService] Saved image to permanent storage: $targetPath');
      return savedFile.path;
    } catch (e) {
      debugPrint('[MediaStorageService] Error saving image: $e');
      return sourceTempPath;
    }
  }

  /// Save a video to the permanent `GoChat Video` folder
  Future<String> saveVideo(String sourceTempPath, {String ext = '.mp4'}) async {
    if (kIsWeb) return sourceTempPath;
    await init();

    try {
      final sourceFile = File(sourceTempPath);
      if (!await sourceFile.exists()) return sourceTempPath;

      final targetFolder = _getCategoryPath(MediaCategory.video);
      final newFileName = _generateFileName(MediaCategory.video, ext);
      final targetPath = p.join(targetFolder, newFileName);

      final savedFile = await sourceFile.copy(targetPath);
      debugPrint('[MediaStorageService] Saved video to permanent storage: $targetPath');
      return savedFile.path;
    } catch (e) {
      debugPrint('[MediaStorageService] Error saving video: $e');
      return sourceTempPath;
    }
  }

  /// Check if a local file exists for the given path
  bool existsLocally(String? localPath) {
    if (localPath == null || localPath.isEmpty || kIsWeb) return false;
    try {
      final file = File(localPath);
      return file.existsSync();
    } catch (_) {
      return false;
    }
  }

  /// Calculate total storage used by all GoChat media folders
  Future<int> getTotalMediaSizeBytes() async {
    if (kIsWeb || _baseMediaPath == null) return 0;
    int total = 0;
    try {
      final dir = Directory(_baseMediaPath!);
      if (await dir.exists()) {
        await for (final entity in dir.list(recursive: true, followLinks: false)) {
          if (entity is File) {
            total += await entity.length();
          }
        }
      }
    } catch (_) {}
    return total;
  }
}
