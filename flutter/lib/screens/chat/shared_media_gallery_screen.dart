import 'package:flutter/material.dart';
import '../../core/models/models.dart';
import '../../core/theme/app_theme.dart';
import '../../core/utils/media_image_helper.dart';
import '../../widgets/widgets.dart';
import 'media_lightbox_screen.dart';

class SharedMediaGalleryScreen extends StatelessWidget {
  final String title;
  final List<Message> messages;

  const SharedMediaGalleryScreen({
    super.key,
    required this.title,
    required this.messages,
  });

  List<Message> get _mediaMessages =>
      messages.where((m) => m.type == MessageType.image || m.type == MessageType.video).toList();

  List<Message> get _docMessages =>
      messages.where((m) => m.type == MessageType.file).toList();

  List<Message> get _linkMessages =>
      messages.where((m) => m.content.contains('http://') || m.content.contains('https://')).toList();

  @override
  Widget build(BuildContext context) {
    final isDark = Theme.of(context).brightness == Brightness.dark;

    return DefaultTabController(
      length: 3,
      child: Scaffold(
        appBar: AppBar(
          title: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                title,
                style: const TextStyle(fontSize: 16, fontWeight: FontWeight.bold),
              ),
              const Text(
                'Media, Links, and Docs',
                style: TextStyle(fontSize: 11, color: AppTheme.textMuted),
              ),
            ],
          ),
          bottom: TabBar(
            indicatorColor: AppTheme.primary,
            labelColor: AppTheme.primary,
            unselectedLabelColor: isDark ? AppTheme.textMuted : AppTheme.textMutedLight,
            tabs: [
              Tab(text: 'Media (${_mediaMessages.length})'),
              Tab(text: 'Docs (${_docMessages.length})'),
              Tab(text: 'Links (${_linkMessages.length})'),
            ],
          ),
        ),
        body: TabBarView(
          children: [
            // ── Tab 1: Media Grid ─────────────────────────────────────────────
            _mediaMessages.isEmpty
                ? const EmptyStateView(
                    icon: Icons.photo_library_outlined,
                    title: 'No Media Shared',
                    description: 'Photos and videos shared in this conversation will appear here.',
                  )
                : GridView.builder(
                    padding: const EdgeInsets.all(4),
                    gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(
                      crossAxisCount: 3,
                      crossAxisSpacing: 4,
                      mainAxisSpacing: 4,
                    ),
                    itemCount: _mediaMessages.length,
                    itemBuilder: (ctx, idx) {
                      final m = _mediaMessages[idx];
                      return GestureDetector(
                        onTap: () {
                          if (m.mediaUrl != null) {
                            MediaLightboxScreen.show(
                              context,
                              mediaUrl: m.mediaUrl!,
                              title: m.senderName,
                              caption: m.content,
                              timestamp: m.createdAt,
                              heroTag: 'gallery_${m.id}',
                            );
                          }
                        },
                        child: Hero(
                          tag: 'gallery_${m.id}',
                          child: MediaImageHelper.buildSafeImage(
                            m.mediaUrl,
                            fit: BoxFit.cover,
                            errorWidget: Container(
                              color: isDark ? AppTheme.darkCard : const Color(0xFFE9EDEF),
                              child: const Icon(Icons.broken_image, color: AppTheme.iconColor),
                            ),
                          ),
                        ),
                      );
                    },
                  ),

            // ── Tab 2: Documents List ─────────────────────────────────────────
            _docMessages.isEmpty
                ? const EmptyStateView(
                    icon: Icons.description_outlined,
                    title: 'No Documents Shared',
                    description: 'Files and PDFs shared in this conversation will appear here.',
                  )
                : ListView.separated(
                    padding: const EdgeInsets.all(12),
                    itemCount: _docMessages.length,
                    separatorBuilder: (_, _) => const Divider(height: 1),
                    itemBuilder: (ctx, idx) {
                      final m = _docMessages[idx];
                      return ListTile(
                        leading: Container(
                          padding: const EdgeInsets.all(10),
                          decoration: BoxDecoration(
                            color: Colors.blueAccent.withValues(alpha: 0.15),
                            borderRadius: BorderRadius.circular(10),
                          ),
                          child: const Icon(Icons.insert_drive_file_rounded, color: Colors.blueAccent),
                        ),
                        title: Text(
                          m.content,
                          style: TextStyle(
                            fontSize: 14,
                            fontWeight: FontWeight.bold,
                            color: isDark ? AppTheme.textLight : AppTheme.textDark,
                          ),
                        ),
                        subtitle: Text(
                          '${m.senderName} · ${m.mediaSize != null ? '${(m.mediaSize! / 1024).round()} KB' : 'PDF'}',
                          style: const TextStyle(fontSize: 11, color: AppTheme.textMuted),
                        ),
                        trailing: const Icon(Icons.download_rounded, color: AppTheme.primary),
                        onTap: () {
                          ScaffoldMessenger.of(context).showSnackBar(
                            SnackBar(content: Text('Downloading ${m.content}...')),
                          );
                        },
                      );
                    },
                  ),

            // ── Tab 3: Shared Links List ──────────────────────────────────────
            _linkMessages.isEmpty
                ? const EmptyStateView(
                    icon: Icons.link_rounded,
                    title: 'No Links Shared',
                    description: 'Web links and URLs shared in this chat will appear here.',
                  )
                : ListView.separated(
                    padding: const EdgeInsets.all(12),
                    itemCount: _linkMessages.length,
                    separatorBuilder: (_, _) => const Divider(height: 1),
                    itemBuilder: (ctx, idx) {
                      final m = _linkMessages[idx];
                      return ListTile(
                        leading: Container(
                          padding: const EdgeInsets.all(10),
                          decoration: BoxDecoration(
                            color: AppTheme.primary.withValues(alpha: 0.15),
                            borderRadius: BorderRadius.circular(10),
                          ),
                          child: const Icon(Icons.language_rounded, color: AppTheme.primary),
                        ),
                        title: Text(
                          m.content,
                          style: const TextStyle(
                            fontSize: 13.5,
                            color: AppTheme.readBlue,
                            decoration: TextDecoration.underline,
                          ),
                        ),
                        subtitle: Text(
                          'Shared by ${m.senderName}',
                          style: const TextStyle(fontSize: 11, color: AppTheme.textMuted),
                        ),
                        onTap: () {
                          ScaffoldMessenger.of(context).showSnackBar(
                            const SnackBar(content: Text('Opening link in browser...')),
                          );
                        },
                      );
                    },
                  ),
          ],
        ),
      ),
    );
  }
}
