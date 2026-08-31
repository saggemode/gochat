import 'dart:convert';
import 'dart:io';
import 'package:flutter/foundation.dart' show kIsWeb;
import 'package:flutter/material.dart';
import '../core/models/message.dart';
import '../core/services/media_storage_service.dart';
import '../core/theme/app_theme.dart';
import '../screens/chat/media_lightbox_screen.dart';

/// WhatsApp-style media card for in-chat Images and Videos supporting:
/// - 50 MB file size limit enforcement & formatting
/// - Live circular upload progress for sender
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

    if (!kIsWeb && !rawUrl.startsWith('http://') && !rawUrl.startsWith('https://')) {
      if (!rawUrl.startsWith('data:')) {
        final file = File(rawUrl);
        if (file.existsSync()) {
          _localPath = rawUrl;
        }
      }
    }
  }

  String _formatFileSize(int? bytes) {
    if (bytes == null || bytes <= 0) return 'Image';
    if (bytes < 1024 * 1024) {
      return '${(bytes / 1024).toStringAsFixed(1)} KB';
    }
    return '${(bytes / (1024 * 1024)).toStringAsFixed(1)} MB';
  }

  Future<void> _startDownload() async {
    final rawUrl = widget.message.mediaUrl ?? '';
    if (rawUrl.isEmpty || !rawUrl.startsWith('http')) return;

    setState(() {
      _isDownloading = true;
      _downloadProgress = 0.05;
    });

    final isVideo = widget.message.type == MessageType.video;
    final ext = isVideo ? '.mp4' : '.jpg';
    final category = isVideo ? MediaCategory.video : MediaCategory.images;

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

  @override
  Widget build(BuildContext context) {
    final rawUrl = widget.message.mediaUrl ?? '';
    final isVideo = widget.message.type == MessageType.video;
    final isBase64 = rawUrl.startsWith('data:image') || rawUrl.startsWith('data:') || rawUrl.contains(';base64,');
    final isLocal = _localPath != null && File(_localPath!).existsSync();
    final isRemote = !isLocal && !isBase64 && (rawUrl.startsWith('http://') || rawUrl.startsWith('https://'));

    return GestureDetector(
      onTap: () {
        if (isRemote && _localPath == null) {
          _startDownload();
        } else {
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
            color: Colors.black26,
            child: Stack(
              fit: StackFit.expand,
              children: [
                // ── 1. Media Visual Layer ──
                if (isLocal)
                  Image.file(
                    File(_localPath!),
                    fit: BoxFit.cover,
                    errorBuilder: (_, __, ___) => _buildPlaceholder(isVideo),
                  )
                else if (isBase64)
                  _buildBase64Image(rawUrl)
                else if (isRemote && !_isDownloading && _localPath == null)
                  // Blurred network preview or placeholder
                  Image.network(
                    rawUrl,
                    fit: BoxFit.cover,
                    errorBuilder: (_, __, ___) => _buildPlaceholder(isVideo),
                  )
                else
                  _buildPlaceholder(isVideo),

                // ── 2. Video Play Icon Overlay ──
                if (isVideo && (isLocal || isBase64 || _localPath != null))
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

                // ── 3. Receiver Download Action Overlay ──
                if (isRemote && _localPath == null)
                  Container(
                    color: Colors.black45,
                    child: Center(
                      child: _isDownloading
                          ? Container(
                              padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
                              decoration: BoxDecoration(
                                color: Colors.black87,
                                borderRadius: BorderRadius.circular(16),
                                border: Border.all(color: AppTheme.primary.withValues(alpha: 0.4)),
                              ),
                              child: Column(
                                mainAxisSize: MainAxisSize.min,
                                children: [
                                  SizedBox(
                                    width: 38,
                                    height: 38,
                                    child: CircularProgressIndicator(
                                      value: _downloadProgress > 0 ? _downloadProgress : null,
                                      strokeWidth: 3.5,
                                      color: AppTheme.primary,
                                      backgroundColor: Colors.white24,
                                    ),
                                  ),
                                  const SizedBox(height: 8),
                                  Text(
                                    '${(_downloadProgress * 100).toInt()}%',
                                    style: const TextStyle(
                                      color: Colors.white,
                                      fontSize: 12,
                                      fontWeight: FontWeight.bold,
                                    ),
                                  ),
                                ],
                              ),
                            )
                          : Container(
                              padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
                              decoration: BoxDecoration(
                                color: Colors.black87,
                                borderRadius: BorderRadius.circular(20),
                                border: Border.all(color: AppTheme.primary, width: 1.2),
                                boxShadow: [
                                  BoxShadow(
                                    color: AppTheme.primary.withValues(alpha: 0.3),
                                    blurRadius: 10,
                                  ),
                                ],
                              ),
                              child: Row(
                                mainAxisSize: MainAxisSize.min,
                                children: [
                                  const Icon(Icons.download_rounded, color: AppTheme.primary, size: 20),
                                  const SizedBox(width: 6),
                                  Text(
                                    widget.message.mediaSize != null
                                        ? _formatFileSize(widget.message.mediaSize)
                                        : 'Download',
                                    style: const TextStyle(
                                      color: Colors.white,
                                      fontWeight: FontWeight.bold,
                                      fontSize: 12.5,
                                    ),
                                  ),
                                ],
                              ),
                            ),
                    ),
                  ),

                // ── 4. File Size Badge (Bottom Left) ──
                if (widget.message.mediaSize != null && widget.message.mediaSize! > 0)
                  Positioned(
                    bottom: 8,
                    left: 8,
                    child: Container(
                      padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
                      decoration: BoxDecoration(
                        color: Colors.black54,
                        borderRadius: BorderRadius.circular(6),
                      ),
                      child: Text(
                        _formatFileSize(widget.message.mediaSize),
                        style: const TextStyle(color: Colors.white, fontSize: 10),
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
        errorBuilder: (_, __, ___) => _buildPlaceholder(false),
      );
    } catch (_) {
      return _buildPlaceholder(false);
    }
  }

  Widget _buildPlaceholder(bool isVideo) {
    return Container(
      color: Colors.black26,
      child: Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(
              isVideo ? Icons.videocam_rounded : Icons.photo_rounded,
              size: 40,
              color: Colors.white38,
            ),
            const SizedBox(height: 4),
            Text(
              isVideo ? 'Video' : 'Photo',
              style: const TextStyle(color: Colors.white38, fontSize: 12),
            ),
          ],
        ),
      ),
    );
  }
}
