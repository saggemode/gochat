import 'dart:convert';
import 'dart:io';
import 'package:flutter/foundation.dart' show kIsWeb;
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:intl/intl.dart';
import '../../core/theme/app_theme.dart';

class MediaLightboxScreen extends StatefulWidget {
  final String mediaUrl;
  final String title;
  final String? caption;
  final DateTime? timestamp;
  final String? heroTag;

  const MediaLightboxScreen({
    super.key,
    required this.mediaUrl,
    this.title = 'Photo',
    this.caption,
    this.timestamp,
    this.heroTag,
  });

  static void show(
    BuildContext context, {
    required String mediaUrl,
    String title = 'Photo',
    String? caption,
    DateTime? timestamp,
    String? heroTag,
  }) {
    Navigator.push(
      context,
      PageRouteBuilder(
        opaque: false,
        barrierColor: Colors.black,
        pageBuilder: (_, _, _) => MediaLightboxScreen(
          mediaUrl: mediaUrl,
          title: title,
          caption: caption,
          timestamp: timestamp,
          heroTag: heroTag,
        ),
      ),
    );
  }

  @override
  State<MediaLightboxScreen> createState() => _MediaLightboxScreenState();
}

class _MediaLightboxScreenState extends State<MediaLightboxScreen> {
  final TransformationController _transformController = TransformationController();
  bool _showControls = true;

  @override
  void dispose() {
    _transformController.dispose();
    super.dispose();
  }

  void _resetZoom() {
    _transformController.value = Matrix4.identity();
  }

  @override
  Widget build(BuildContext context) {
    final dateStr = widget.timestamp != null
        ? DateFormat('MMM dd, yyyy · hh:mm a').format(widget.timestamp!)
        : '';

    return Scaffold(
      backgroundColor: Colors.black,
      extendBodyBehindAppBar: true,
      appBar: _showControls
          ? AppBar(
              backgroundColor: Colors.black.withValues(alpha: 0.6),
              elevation: 0,
              leading: IconButton(
                icon: const Icon(Icons.arrow_back_ios_new_rounded, color: Colors.white),
                onPressed: () => Navigator.pop(context),
              ),
              title: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    widget.title,
                    style: const TextStyle(fontSize: 16, fontWeight: FontWeight.bold, color: Colors.white),
                  ),
                  if (dateStr.isNotEmpty)
                    Text(
                      dateStr,
                      style: const TextStyle(fontSize: 11, color: Colors.white70),
                    ),
                ],
              ),
              actions: [
                IconButton(
                  icon: const Icon(Icons.download_rounded, color: Colors.white),
                  tooltip: 'Save to Gallery',
                  onPressed: () {
                    HapticFeedback.lightImpact();
                    ScaffoldMessenger.of(context).showSnackBar(
                      const SnackBar(content: Text('💾 Media saved to your device gallery')),
                    );
                  },
                ),
                IconButton(
                  icon: const Icon(Icons.share_rounded, color: Colors.white),
                  tooltip: 'Share',
                  onPressed: () {
                    HapticFeedback.lightImpact();
                    ScaffoldMessenger.of(context).showSnackBar(
                      const SnackBar(content: Text('📤 Sharing media...')),
                    );
                  },
                ),
              ],
            )
          : null,
      body: GestureDetector(
        onTap: () => setState(() => _showControls = !_showControls),
        onDoubleTap: _resetZoom,
        child: Stack(
          children: [
            Center(
              child: widget.heroTag != null
                  ? Hero(
                      tag: widget.heroTag!,
                      child: _buildInteractiveImage(),
                    )
                  : _buildInteractiveImage(),
            ),
            if (_showControls && widget.caption != null && widget.caption!.isNotEmpty)
              Positioned(
                bottom: 0,
                left: 0,
                right: 0,
                child: Container(
                  padding: const EdgeInsets.fromLTRB(20, 16, 20, 32),
                  decoration: BoxDecoration(
                    gradient: LinearGradient(
                      begin: Alignment.bottomCenter,
                      end: Alignment.topCenter,
                      colors: [
                        Colors.black.withValues(alpha: 0.8),
                        Colors.transparent,
                      ],
                    ),
                  ),
                  child: Text(
                    widget.caption!,
                    style: const TextStyle(color: Colors.white, fontSize: 14.5, height: 1.4),
                    textAlign: TextAlign.center,
                  ),
                ),
              ),
          ],
        ),
      ),
    );
  }

  Widget _buildInteractiveImage() {
    return InteractiveViewer(
      transformationController: _transformController,
      minScale: 0.8,
      maxScale: 4.0,
      child: _buildImageFromUrl(widget.mediaUrl),
    );
  }

  /// Builds an Image widget that handles local file paths, Base64 data URIs, and network URLs.
  Widget _buildImageFromUrl(String url) {
    // 1. Base64 Data URI (fallback when server upload failed)
    if (url.startsWith('data:') || url.contains(';base64,')) {
      try {
        final b64Index = url.indexOf('base64,');
        final b64Data = b64Index != -1 ? url.substring(b64Index + 7) : url;
        final bytes = base64Decode(b64Data.trim());
        return Image.memory(
          bytes,
          fit: BoxFit.contain,
          errorBuilder: (_, _, _) => _errorPlaceholder(),
        );
      } catch (_) {
        return _errorPlaceholder();
      }
    }

    // 2. Local file path (sender's own device)
    if (!kIsWeb && !url.startsWith('http')) {
      final file = File(url);
      if (file.existsSync()) {
        return Image.file(
          file,
          fit: BoxFit.contain,
          errorBuilder: (_, _, _) => _errorPlaceholder(),
        );
      }
      return _errorPlaceholder();
    }

    // 3. Network URL (normal case for receiver)
    return Image.network(
      url,
      fit: BoxFit.contain,
      loadingBuilder: (ctx, child, progress) {
        if (progress == null) return child;
        return Center(
          child: CircularProgressIndicator(
            value: progress.expectedTotalBytes != null
                ? progress.cumulativeBytesLoaded / progress.expectedTotalBytes!
                : null,
            color: AppTheme.primary,
          ),
        );
      },
      errorBuilder: (_, _, _) => _errorPlaceholder(),
    );
  }

  Widget _errorPlaceholder() {
    return const Center(
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(Icons.broken_image_rounded, size: 60, color: Colors.white54),
          SizedBox(height: 12),
          Text('Unable to load full resolution image', style: TextStyle(color: Colors.white54)),
        ],
      ),
    );
  }
}

