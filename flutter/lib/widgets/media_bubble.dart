import 'dart:convert';
import 'dart:io';
import 'dart:ui' as ui;
import 'package:flutter/foundation.dart' show kIsWeb;
import 'package:flutter/material.dart';
import '../core/models/message.dart';
import '../core/services/media_storage_service.dart';
import '../core/theme/app_theme.dart';
import '../screens/chat/media_lightbox_screen.dart';

/// WhatsApp-style media card for in-chat Images and Videos supporting:
/// - Blurred thumbnail preview before download with center download action
/// - Live circular download progress with file size badge for receiver
/// - Instant local file playback and Lightbox viewer
class MediaBubble extends StatefulWidget {
  final Message message;
  final bool isMe;
  final String heroTag;

  const MediaBubble({
    super.key,
    required this.message,
    required this.isMe,
    required this.heroTag,
  });

  @override
  State<MediaBubble> createState() => _MediaBubbleState();
}

class _MediaBubbleState extends State<MediaBubble> {
  bool _isDownloading = false;
  double _downloadProgress = 0.0;
  String? _localPath;

  @override
  void initState() {
    super.initState();
    _checkLocalFile();
  }

  void _checkLocalFile() {
    final rawUrl = widget.message.mediaUrl ?? '';
    if (rawUrl.isEmpty) return;

    if (!kIsWeb && !rawUrl.startsWith('http://') && !rawUrl.startsWith('https://') && !rawUrl.startsWith('data:')) {
      final file = File(rawUrl);
      if (file.existsSync()) {
        _localPath = rawUrl;
      }
    }
  }

  String _formatFileSize(int? bytes) {
    if (bytes == null || bytes <= 0) return '1.4 MB';
    if (bytes < 1024 * 1024) {
      return '${(bytes / 1024).toStringAsFixed(1)} KB';
    }
    return '${(bytes / (1024 * 1024)).toStringAsFixed(1)} MB';
  }

  Future<void> _startDownload() async {
    final rawUrl = widget.message.mediaUrl ?? '';
    if (rawUrl.isEmpty) return;

    setState(() {
      _isDownloading = true;
      _downloadProgress = 0.1;
    });

    final isVideo = widget.message.type == MessageType.video;
    final ext = isVideo ? '.mp4' : '.jpg';
    final category = isVideo ? MediaCategory.video : MediaCategory.images;

    // 1. Remote HTTP URL
    if (rawUrl.startsWith('http')) {
      final savedPath = await MediaStorageService().downloadMediaWithProgress(
        remoteUrl: rawUrl,
        category: category,
        ext: ext,
        onProgress: (prog) {
          if (mounted) {
            setState(() => _downloadProgress = prog);
          }
        },
      );

      if (mounted) {
        setState(() {
          _isDownloading = false;
          if (savedPath != null && savedPath.isNotEmpty) {
            _localPath = savedPath;
          }
        });
      }
    }
    // 2. Base64 Data URI from Phone Gallery
    else if (rawUrl.startsWith('data:image') || rawUrl.startsWith('data:') || rawUrl.contains(';base64,')) {
      for (int i = 1; i <= 8; i++) {
        await Future.delayed(const Duration(milliseconds: 50));
        if (mounted) {
          setState(() => _downloadProgress = i / 8.0);
        }
      }
      try {
        final b64Index = rawUrl.indexOf('base64,');
        final b64Data = b64Index != -1 ? rawUrl.substring(b64Index + 7) : rawUrl;
        final bytes = base64Decode(b64Data.trim());
        final savedPath = await MediaStorageService().saveImageBytes(bytes, ext: ext);
        if (mounted) {
          setState(() {
            _isDownloading = false;
            _localPath = savedPath.isNotEmpty ? savedPath : rawUrl;
          });
        }
      } catch (e) {
        debugPrint('[MediaBubble] Error decoding base64 download: $e');
        if (mounted) {
          setState(() {
            _isDownloading = false;
            _localPath = rawUrl;
          });
        }
      }
    }
    // 3. Fallback Mock / Local Media
    else {
      for (int i = 1; i <= 8; i++) {
        await Future.delayed(const Duration(milliseconds: 50));
        if (mounted) {
          setState(() => _downloadProgress = i / 8.0);
        }
      }
      if (mounted) {
        setState(() {
          _isDownloading = false;
          _localPath = rawUrl;
        });
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final rawUrl = widget.message.mediaUrl ?? '';
    final isVideo = widget.message.type == MessageType.video;
    final isBase64 = rawUrl.startsWith('data:image') || rawUrl.startsWith('data:') || rawUrl.contains(';base64,');
    final isLocalFile = _localPath != null && File(_localPath!).existsSync();
    
    // Downloaded status: Sender always sees downloaded; Receiver sees downloaded once saved to local path
    final isDownloaded = widget.isMe || isLocalFile;

    return GestureDetector(
      onTap: () {
        if (!isDownloaded && !_isDownloading) {
          _startDownload();
        } else if (isDownloaded) {
          MediaLightboxScreen.show(
            context,
            mediaUrl: _localPath ?? rawUrl,
            title: widget.message.senderName,
            caption: widget.message.content.isNotEmpty &&
                    widget.message.content != '📷 Photo' &&
                    widget.message.content != '🎥 Video' &&
                    widget.message.content != 'Shared an image' &&
                    widget.message.content != 'Shared a video'
                ? widget.message.content
                : null,
            timestamp: widget.message.createdAt,
            heroTag: widget.heroTag,
          );
        }
      },
      child: Hero(
        tag: widget.heroTag,
        child: ClipRRect(
          borderRadius: BorderRadius.circular(12),
          child: Container(
            width: 250,
            height: 190,
            color: Colors.black38,
            child: Stack(
              fit: StackFit.expand,
              children: [
                // ── 1. Media Visual Layer ──
                if (isDownloaded)
                  if (isLocalFile)
                    Image.file(
                      File(_localPath!),
                      fit: BoxFit.cover,
                      errorBuilder: (_, _, _) => _buildPlaceholder(isVideo),
                    )
                  else if (isBase64)
                    _buildBase64Image(rawUrl)
                  else if (rawUrl.startsWith('http'))
                    Image.network(
                      rawUrl,
                      fit: BoxFit.cover,
                      errorBuilder: (_, _, _) => _buildPlaceholder(isVideo),
                    )
                  else
                    _buildPlaceholder(isVideo)
                else
                  // Blurred Thumbnail Layer for Receiver before Download
                  ImageFiltered(
                    imageFilter: ui.ImageFilter.blur(sigmaX: 14, sigmaY: 14),
                    child: isBase64
                        ? _buildBase64Image(rawUrl)
                        : (rawUrl.startsWith('http')
                            ? Image.network(
                                rawUrl,
                                fit: BoxFit.cover,
                                errorBuilder: (_, _, _) => _buildPlaceholder(isVideo),
                              )
                            : (rawUrl.startsWith('assets/')
                                ? Image.asset(
                                    rawUrl,
                                    fit: BoxFit.cover,
                                    errorBuilder: (_, _, _) => _buildPlaceholder(isVideo),
                                  )
                                : _buildPlaceholder(isVideo))),
                  ),

                // Dark translucent tint over thumbnail for contrast
                if (!isDownloaded)
                  Container(
                    color: Colors.black.withValues(alpha: 0.35),
                  ),

                // ── 2. Video Play Icon Overlay (When Downloaded) ──
                if (isVideo && isDownloaded)
                  Center(
                    child: Container(
                      width: 48,
                      height: 48,
                      decoration: BoxDecoration(
                        color: Colors.black54,
                        shape: BoxShape.circle,
                        border: Border.all(color: Colors.white, width: 2),
                      ),
                      child: const Icon(Icons.play_arrow_rounded, color: Colors.white, size: 30),
                    ),
                  ),

                // ── 3. WhatsApp-Style Thumbnail Download Button Overlay ──
                if (!isDownloaded)
                  Center(
                    child: _isDownloading
                        ? Container(
                            width: 68,
                            height: 68,
                            decoration: BoxDecoration(
                              color: Colors.black87,
                              shape: BoxShape.circle,
                              border: Border.all(color: AppTheme.primary.withValues(alpha: 0.6), width: 1.5),
                              boxShadow: [
                                BoxShadow(
                                  color: AppTheme.primary.withValues(alpha: 0.3),
                                  blurRadius: 12,
                                ),
                              ],
                            ),
                            child: Stack(
                              alignment: Alignment.center,
                              children: [
                                SizedBox(
                                  width: 48,
                                  height: 48,
                                  child: CircularProgressIndicator(
                                    value: _downloadProgress > 0 ? _downloadProgress : null,
                                    strokeWidth: 3.5,
                                    color: AppTheme.primary,
                                    backgroundColor: Colors.white24,
                                  ),
                                ),
                                Text(
                                  '${(_downloadProgress * 100).toInt()}%',
                                  style: const TextStyle(
                                    color: Colors.white,
                                    fontSize: 11,
                                    fontWeight: FontWeight.bold,
                                  ),
                                ),
                              ],
                            ),
                          )
                        : Container(
                            padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 8),
                            decoration: BoxDecoration(
                              color: Colors.black87,
                              borderRadius: BorderRadius.circular(24),
                              border: Border.all(color: AppTheme.primary, width: 1.5),
                              boxShadow: [
                                BoxShadow(
                                  color: Colors.black.withValues(alpha: 0.5),
                                  blurRadius: 10,
                                ),
                                BoxShadow(
                                  color: AppTheme.primary.withValues(alpha: 0.35),
                                  blurRadius: 8,
                                ),
                              ],
                            ),
                            child: Row(
                              mainAxisSize: MainAxisSize.min,
                              children: [
                                Container(
                                  padding: const EdgeInsets.all(4),
                                  decoration: const BoxDecoration(
                                    color: AppTheme.primary,
                                    shape: BoxShape.circle,
                                  ),
                                  child: const Icon(
                                    Icons.arrow_downward_rounded,
                                    color: Colors.white,
                                    size: 16,
                                  ),
                                ),
                                const SizedBox(width: 8),
                                Column(
                                  mainAxisSize: MainAxisSize.min,
                                  crossAxisAlignment: CrossAxisAlignment.start,
                                  children: [
                                    const Text(
                                      'DOWNLOAD',
                                      style: TextStyle(
                                        color: Colors.white,
                                        fontWeight: FontWeight.w900,
                                        fontSize: 11,
                                        letterSpacing: 0.6,
                                      ),
                                    ),
                                    Text(
                                      _formatFileSize(widget.message.mediaSize),
                                      style: TextStyle(
                                        color: Colors.white.withValues(alpha: 0.75),
                                        fontSize: 10,
                                      ),
                                    ),
                                  ],
                                ),
                              ],
                            ),
                          ),
                  ),

                // ── 4. Media Type Badge (Top Left) ──
                Positioned(
                  top: 8,
                  left: 8,
                  child: Container(
                    padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 3),
                    decoration: BoxDecoration(
                      color: Colors.black54,
                      borderRadius: BorderRadius.circular(6),
                    ),
                    child: Row(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        Icon(
                          isVideo ? Icons.videocam_rounded : Icons.photo_rounded,
                          color: Colors.white,
                          size: 12,
                        ),
                        const SizedBox(width: 4),
                        Text(
                          isVideo ? 'VIDEO' : 'PHOTO',
                          style: const TextStyle(
                            color: Colors.white,
                            fontSize: 9,
                            fontWeight: FontWeight.bold,
                          ),
                        ),
                      ],
                    ),
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildBase64Image(String rawUrl) {
    try {
      final b64Index = rawUrl.indexOf('base64,');
      final b64Data = b64Index != -1 ? rawUrl.substring(b64Index + 7) : rawUrl;
      final bytes = base64Decode(b64Data.trim());
      return Image.memory(
        bytes,
        fit: BoxFit.cover,
        errorBuilder: (_, _, _) => _buildPlaceholder(false),
      );
    } catch (_) {
      return _buildPlaceholder(false);
    }
  }

  Widget _buildPlaceholder(bool isVideo) {
    return Container(
      color: const Color(0xFF1E2A30),
      child: Center(
        child: Icon(
          isVideo ? Icons.videocam_rounded : Icons.photo_rounded,
          size: 48,
          color: Colors.white24,
        ),
      ),
    );
  }
}
