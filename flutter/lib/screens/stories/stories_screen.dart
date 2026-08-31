import 'dart:io';
import 'dart:math';
import 'package:flutter/material.dart';
import 'package:image_picker/image_picker.dart';
import '../../core/models/story.dart';
import '../../core/services/media_storage_service.dart';
import '../../core/state/app_state.dart';
import '../../core/theme/app_theme.dart';
import '../../widgets/widgets.dart';
import 'story_viewer_screen.dart';

class StoriesScreen extends StatelessWidget {
  final AppState appState;

  const StoriesScreen({super.key, required this.appState});

  @override
  Widget build(BuildContext context) {
    final stories = appState.stories;
    final myStory = stories.where((s) => s.isMe).firstOrNull ??
        (stories.isNotEmpty
            ? stories.first
            : UserStories(
                userId: appState.currentUser?.id ?? 'me',
                userName: appState.currentUser?.displayName ?? 'My Status',
                userAvatar: appState.currentUser?.avatarUrl ?? '',
                isMe: true,
                stories: [],
              ));
    final otherStories = stories.where((s) => !s.isMe).toList();
    final isDark = Theme.of(context).brightness == Brightness.dark;

    return Scaffold(
      appBar: AppBar(
        title: Text(
          'Updates',
          style: TextStyle(
            fontWeight: FontWeight.bold,
            color: isDark ? AppTheme.textLight : AppTheme.textDark,
          ),
        ),
        actions: [
          IconButton(icon: const Icon(Icons.search), onPressed: () {}),
          IconButton(icon: const Icon(Icons.more_vert), onPressed: () {}),
        ],
      ),
      body: ListView(
        children: [
          // My Status Tile
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
            child: Row(
              children: [
                Stack(
                  children: [
                    StoryAvatar(
                      avatarUrl: (myStory.stories.isNotEmpty && myStory.stories.first.mediaUrl.isNotEmpty)
                          ? myStory.stories.first.mediaUrl
                          : myStory.userAvatar,
                      radius: 28,
                      hasUnseenStory: myStory.hasUnseenStories,
                      storyCount: myStory.stories.length,
                      onTap: () {
                        if (myStory.stories.isNotEmpty) {
                          Navigator.push(
                            context,
                            MaterialPageRoute(
                              builder: (_) => StoryViewerScreen(userStories: myStory),
                            ),
                          );
                        } else {
                          _showStatusTypeChooser(context);
                        }
                      },
                    ),
                    Positioned(
                      bottom: 0,
                      right: 0,
                      child: GestureDetector(
                        onTap: () => _showStatusTypeChooser(context),
                        child: Container(
                          padding: const EdgeInsets.all(3),
                          decoration: const BoxDecoration(
                            color: AppTheme.primary,
                            shape: BoxShape.circle,
                          ),
                          child: const Icon(Icons.add, size: 16, color: Colors.black),
                        ),
                      ),
                    ),
                  ],
                ),
                const SizedBox(width: 16),
                Expanded(
                  child: GestureDetector(
                    onTap: () {
                      if (myStory.stories.isNotEmpty) {
                        Navigator.push(
                          context,
                          MaterialPageRoute(
                            builder: (_) => StoryViewerScreen(userStories: myStory),
                          ),
                        );
                      } else {
                        _showStatusTypeChooser(context);
                      }
                    },
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          'My status',
                          style: TextStyle(
                            fontSize: 16,
                            fontWeight: FontWeight.bold,
                            color: isDark ? AppTheme.textLight : AppTheme.textDark,
                          ),
                        ),
                        const SizedBox(height: 2),
                        Text(
                          myStory.stories.isNotEmpty
                              ? '${myStory.stories.length} ${myStory.stories.length == 1 ? 'update' : 'updates'} • Tap to view'
                              : 'Tap to add status update',
                          style: TextStyle(
                            fontSize: 13,
                            color: myStory.stories.isNotEmpty ? AppTheme.primary : (isDark ? AppTheme.textMuted : AppTheme.textMutedLight),
                            fontWeight: myStory.stories.isNotEmpty ? FontWeight.w600 : FontWeight.normal,
                          ),
                        ),
                      ],
                    ),
                  ),
                ),
                // ── Separate action buttons for quick post ──
                IconButton(
                  icon: const Icon(Icons.edit_rounded, color: AppTheme.primary, size: 22),
                  tooltip: 'Text Status',
                  onPressed: () => _openTextStatusEditor(context),
                ),
                IconButton(
                  icon: const Icon(Icons.camera_alt_rounded, color: AppTheme.primary, size: 22),
                  tooltip: 'Photo/Video Status',
                  onPressed: () => _showStatusTypeChooser(context),
                ),
              ],
            ),
          ),

          const SectionHeader(title: 'RECENT UPDATES'),

          if (otherStories.isEmpty)
            Padding(
              padding: const EdgeInsets.all(32.0),
              child: EmptyStateView(
                icon: Icons.history_toggle_off_rounded,
                title: 'No Recent Updates',
                description: 'Status updates from your contacts will appear here and disappear after 24 hours.',
              ),
            )
          else
            ...otherStories.map((item) {
              return ListTile(
                leading: StoryAvatar(
                  avatarUrl: (item.stories.isNotEmpty && item.stories.first.mediaUrl.isNotEmpty)
                      ? item.stories.first.mediaUrl
                      : item.userAvatar,
                  radius: 26,
                  hasUnseenStory: item.hasUnseenStories,
                  storyCount: item.stories.length,
                ),
                title: Text(
                  item.userName,
                  style: TextStyle(
                    fontWeight: FontWeight.bold,
                    color: isDark ? AppTheme.textLight : AppTheme.textDark,
                  ),
                ),
                subtitle: Text(
                  item.stories.isNotEmpty ? 'Today, 2:30 PM' : 'No updates',
                  style: TextStyle(
                    fontSize: 12,
                    color: isDark ? AppTheme.textMuted : AppTheme.textMutedLight,
                  ),
                ),
                onTap: () {
                  if (item.stories.isNotEmpty) {
                    Navigator.push(
                      context,
                      MaterialPageRoute(
                        builder: (_) => StoryViewerScreen(userStories: item),
                      ),
                    );
                  }
                },
              );
            }),
        ],
      ),
      // ── Stacked FABs: Text + Camera ────────────────────────────────────────
      floatingActionButton: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          // Small pencil FAB for text status
          FloatingActionButton.small(
            heroTag: 'stories_fab_text',
            backgroundColor: isDark ? AppTheme.darkCard : Colors.white,
            onPressed: () => _openTextStatusEditor(context),
            child: Icon(Icons.edit_rounded, color: isDark ? AppTheme.textLight : AppTheme.textDark, size: 20),
          ),
          const SizedBox(height: 12),
          // Main camera FAB for photo/video
          FloatingActionButton(
            heroTag: 'stories_fab_camera',
            onPressed: () => _showStatusTypeChooser(context),
            child: const Icon(Icons.camera_alt_rounded),
          ),
        ],
      ),
    );
  }

  // ═══════════════════════════════════════════════════════════════════════════
  //  STATUS TYPE CHOOSER (Bottom Sheet)
  // ═══════════════════════════════════════════════════════════════════════════

  void _showStatusTypeChooser(BuildContext context) {
    final isDark = Theme.of(context).brightness == Brightness.dark;

    showModalBottomSheet(
      context: context,
      backgroundColor: isDark ? AppTheme.darkSurface : Colors.white,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(24)),
      ),
      builder: (ctx) {
        return Padding(
          padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 24),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              // Drag Handle
              Center(
                child: Container(
                  width: 40,
                  height: 4,
                  decoration: BoxDecoration(
                    color: isDark ? Colors.white24 : Colors.black12,
                    borderRadius: BorderRadius.circular(2),
                  ),
                ),
              ),
              const SizedBox(height: 16),

              const Text(
                'Create Status Update',
                style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold),
              ),
              const SizedBox(height: 4),
              const Text(
                'Choose what type of status to share',
                style: TextStyle(fontSize: 12.5, color: AppTheme.textMuted),
              ),
              const SizedBox(height: 20),

              // ── 3 Status Type Cards ────────────────────────────────────
              Row(
                children: [
                  // Text Status
                  Expanded(
                    child: _StatusTypeCard(
                      icon: Icons.edit_rounded,
                      label: 'Text',
                      subtitle: 'Share a thought',
                      color: Colors.deepPurpleAccent,
                      onTap: () {
                        Navigator.pop(ctx);
                        _openTextStatusEditor(context);
                      },
                    ),
                  ),
                  const SizedBox(width: 12),
                  // Photo Status
                  Expanded(
                    child: _StatusTypeCard(
                      icon: Icons.photo_rounded,
                      label: 'Photo',
                      subtitle: 'Camera or gallery',
                      color: Colors.teal,
                      onTap: () {
                        Navigator.pop(ctx);
                        _showImageSourcePicker(context);
                      },
                    ),
                  ),
                  const SizedBox(width: 12),
                  // Video Status
                  Expanded(
                    child: _StatusTypeCard(
                      icon: Icons.videocam_rounded,
                      label: 'Video',
                      subtitle: 'Record or pick',
                      color: Colors.redAccent,
                      onTap: () {
                        Navigator.pop(ctx);
                        _pickVideoStatus(context);
                      },
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 16),
            ],
          ),
        );
      },
    );
  }

  // ═══════════════════════════════════════════════════════════════════════════
  //  TEXT STATUS EDITOR (Full-Screen)
  // ═══════════════════════════════════════════════════════════════════════════

  static const List<List<Color>> _textBgGradients = [
    [Color(0xFF6C63FF), Color(0xFF3F3D99)],
    [Color(0xFFFF6B6B), Color(0xFFEE5A24)],
    [Color(0xFF00B894), Color(0xFF00965C)],
    [Color(0xFFFDCB6E), Color(0xFFE17055)],
    [Color(0xFFA29BFE), Color(0xFF6C5CE7)],
    [Color(0xFF55E6C1), Color(0xFF25CCF7)],
    [Color(0xFFFF9FF3), Color(0xFFF368E0)],
    [Color(0xFF2C3E50), Color(0xFF1A1A2E)],
    [Color(0xFF0ABDE3), Color(0xFF48DBFB)],
    [Color(0xFFFF9F43), Color(0xFFEE5A24)],
  ];

  void _openTextStatusEditor(BuildContext context) {
    Navigator.of(context).push(
      MaterialPageRoute(
        fullscreenDialog: true,
        builder: (_) => _TextStatusEditorPage(
          onPost: (text, bgIndex) {
            final gradient = _textBgGradients[bgIndex % _textBgGradients.length];
            final bgHex = '#${gradient[0].value.toRadixString(16).padLeft(8, '0')}';
            appState.addStory(
              '',
              text,
              mediaType: 'text',
              backgroundColor: bgHex,
            );
            ScaffoldMessenger.of(context).showSnackBar(
              const SnackBar(content: Text('🎉 Text status published!')),
            );
          },
        ),
      ),
    );
  }

  // ═══════════════════════════════════════════════════════════════════════════
  //  PHOTO STATUS (Camera or Gallery via image_picker)
  // ═══════════════════════════════════════════════════════════════════════════

  void _showImageSourcePicker(BuildContext context) {
    final isDark = Theme.of(context).brightness == Brightness.dark;

    showModalBottomSheet(
      context: context,
      backgroundColor: isDark ? AppTheme.darkSurface : Colors.white,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(20)),
      ),
      builder: (ctx) => Padding(
        padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 20),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            ListTile(
              leading: Container(
                padding: const EdgeInsets.all(10),
                decoration: BoxDecoration(
                  color: AppTheme.primary.withValues(alpha: 0.15),
                  borderRadius: BorderRadius.circular(12),
                ),
                child: const Icon(Icons.camera_alt_rounded, color: AppTheme.primary),
              ),
              title: const Text('Take Photo', style: TextStyle(fontWeight: FontWeight.bold)),
              subtitle: const Text('Use your camera', style: TextStyle(fontSize: 12, color: AppTheme.textMuted)),
              onTap: () {
                Navigator.pop(ctx);
                _pickImageStatus(context, ImageSource.camera);
              },
            ),
            const Divider(height: 1),
            ListTile(
              leading: Container(
                padding: const EdgeInsets.all(10),
                decoration: BoxDecoration(
                  color: Colors.teal.withValues(alpha: 0.15),
                  borderRadius: BorderRadius.circular(12),
                ),
                child: const Icon(Icons.photo_library_rounded, color: Colors.teal),
              ),
              title: const Text('Choose from Gallery', style: TextStyle(fontWeight: FontWeight.bold)),
              subtitle: const Text('Pick an existing photo', style: TextStyle(fontSize: 12, color: AppTheme.textMuted)),
              onTap: () {
                Navigator.pop(ctx);
                _pickImageStatus(context, ImageSource.gallery);
              },
            ),
          ],
        ),
      ),
    );
  }

  Future<void> _pickImageStatus(BuildContext context, ImageSource source) async {
    final picker = ImagePicker();
    final XFile? image = await picker.pickImage(
      source: source,
      maxWidth: 1920,
      maxHeight: 1920,
      imageQuality: 85,
    );
    if (image == null) return;
    if (!context.mounted) return;

    // Save permanently
    final savedPath = await MediaStorageService().saveImage(image.path);

    // Optional caption dialog
    final caption = await _showCaptionDialog(context, savedPath, isVideo: false);
    if (!context.mounted) return;

    appState.addStory(
      savedPath,
      caption ?? '',
      mediaType: 'image',
    );
    ScaffoldMessenger.of(context).showSnackBar(
      const SnackBar(content: Text('📸 Photo status published!')),
    );
  }

  // ═══════════════════════════════════════════════════════════════════════════
  //  VIDEO STATUS (Camera or Gallery)
  // ═══════════════════════════════════════════════════════════════════════════

  Future<void> _pickVideoStatus(BuildContext context) async {
    final picker = ImagePicker();
    final XFile? video = await picker.pickVideo(
      source: ImageSource.gallery,
      maxDuration: const Duration(seconds: 30),
    );
    if (video == null) return;
    if (!context.mounted) return;

    // Save permanently
    final savedPath = await MediaStorageService().saveVideo(video.path);

    // Optional caption dialog
    final caption = await _showCaptionDialog(context, savedPath, isVideo: true);
    if (!context.mounted) return;

    appState.addStory(
      savedPath,
      caption ?? '',
      mediaType: 'video',
    );
    ScaffoldMessenger.of(context).showSnackBar(
      const SnackBar(content: Text('🎬 Video status published!')),
    );
  }

  // ═══════════════════════════════════════════════════════════════════════════
  //  CAPTION INPUT DIALOG (after picking image/video)
  // ═══════════════════════════════════════════════════════════════════════════

  Future<String?> _showCaptionDialog(BuildContext context, String mediaPath, {bool isVideo = false}) async {
    final controller = TextEditingController();

    return showDialog<String>(
      context: context,
      builder: (ctx) => AlertDialog(
        backgroundColor: AppTheme.darkSurface,
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(20)),
        title: Row(
          children: [
            Icon(isVideo ? Icons.videocam_rounded : Icons.photo_rounded, color: AppTheme.primary, size: 22),
            const SizedBox(width: 8),
            Text(
              isVideo ? 'Video Status' : 'Photo Status',
              style: const TextStyle(color: Colors.white, fontWeight: FontWeight.bold, fontSize: 16),
            ),
          ],
        ),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            // Preview thumbnail
            ClipRRect(
              borderRadius: BorderRadius.circular(12),
              child: Container(
                height: 140,
                width: double.infinity,
                decoration: BoxDecoration(
                  color: Colors.black26,
                  borderRadius: BorderRadius.circular(12),
                ),
                child: isVideo
                    ? const Center(child: Icon(Icons.play_circle_fill_rounded, color: Colors.white54, size: 48))
                    : Image.file(File(mediaPath), fit: BoxFit.cover, errorBuilder: (_, __, ___) =>
                        const Center(child: Icon(Icons.image, color: Colors.white24, size: 48))),
              ),
            ),
            const SizedBox(height: 14),
            TextField(
              controller: controller,
              autofocus: true,
              style: const TextStyle(color: Colors.white),
              decoration: const InputDecoration(
                hintText: 'Add a caption... (optional)',
                hintStyle: TextStyle(color: Colors.white30),
                border: OutlineInputBorder(),
                contentPadding: EdgeInsets.symmetric(horizontal: 12, vertical: 10),
              ),
              maxLines: 2,
            ),
          ],
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx, ''),
            child: const Text('Skip', style: TextStyle(color: Colors.white60)),
          ),
          ElevatedButton(
            style: ElevatedButton.styleFrom(backgroundColor: AppTheme.primary),
            onPressed: () => Navigator.pop(ctx, controller.text.trim()),
            child: const Text('Post', style: TextStyle(color: Colors.black, fontWeight: FontWeight.bold)),
          ),
        ],
      ),
    );
  }
}

// ═════════════════════════════════════════════════════════════════════════════
//  STATUS TYPE CARD WIDGET
// ═════════════════════════════════════════════════════════════════════════════

class _StatusTypeCard extends StatelessWidget {
  final IconData icon;
  final String label;
  final String subtitle;
  final Color color;
  final VoidCallback onTap;

  const _StatusTypeCard({
    required this.icon,
    required this.label,
    required this.subtitle,
    required this.color,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    final isDark = Theme.of(context).brightness == Brightness.dark;

    return InkWell(
      onTap: onTap,
      borderRadius: BorderRadius.circular(16),
      child: Container(
        padding: const EdgeInsets.symmetric(vertical: 18, horizontal: 8),
        decoration: BoxDecoration(
          color: color.withValues(alpha: 0.12),
          borderRadius: BorderRadius.circular(16),
          border: Border.all(color: color.withValues(alpha: 0.3)),
        ),
        child: Column(
          children: [
            Container(
              padding: const EdgeInsets.all(12),
              decoration: BoxDecoration(
                color: color.withValues(alpha: 0.2),
                shape: BoxShape.circle,
              ),
              child: Icon(icon, color: color, size: 24),
            ),
            const SizedBox(height: 8),
            Text(
              label,
              style: TextStyle(
                fontWeight: FontWeight.bold,
                fontSize: 13,
                color: isDark ? AppTheme.textLight : AppTheme.textDark,
              ),
            ),
            const SizedBox(height: 2),
            Text(
              subtitle,
              style: const TextStyle(fontSize: 10.5, color: AppTheme.textMuted),
              textAlign: TextAlign.center,
            ),
          ],
        ),
      ),
    );
  }
}

// ═════════════════════════════════════════════════════════════════════════════
//  FULL-SCREEN TEXT STATUS EDITOR
// ═════════════════════════════════════════════════════════════════════════════

class _TextStatusEditorPage extends StatefulWidget {
  final Function(String text, int bgIndex) onPost;

  const _TextStatusEditorPage({required this.onPost});

  @override
  State<_TextStatusEditorPage> createState() => _TextStatusEditorPageState();
}

class _TextStatusEditorPageState extends State<_TextStatusEditorPage> {
  final _controller = TextEditingController();
  int _bgIndex = 0;

  static const List<List<Color>> _gradients = [
    [Color(0xFF6C63FF), Color(0xFF3F3D99)],
    [Color(0xFFFF6B6B), Color(0xFFEE5A24)],
    [Color(0xFF00B894), Color(0xFF00965C)],
    [Color(0xFFFDCB6E), Color(0xFFE17055)],
    [Color(0xFFA29BFE), Color(0xFF6C5CE7)],
    [Color(0xFF55E6C1), Color(0xFF25CCF7)],
    [Color(0xFFFF9FF3), Color(0xFFF368E0)],
    [Color(0xFF2C3E50), Color(0xFF1A1A2E)],
    [Color(0xFF0ABDE3), Color(0xFF48DBFB)],
    [Color(0xFFFF9F43), Color(0xFFEE5A24)],
  ];

  static const List<String> _fonts = [
    'Default',
    'Serif',
    'Monospace',
  ];
  int _fontIndex = 0;

  String get _fontFamily {
    switch (_fontIndex) {
      case 1:
        return 'serif';
      case 2:
        return 'monospace';
      default:
        return '';
    }
  }

  @override
  void initState() {
    super.initState();
    _bgIndex = Random().nextInt(_gradients.length);
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final gradient = _gradients[_bgIndex % _gradients.length];

    return Scaffold(
      body: Container(
        decoration: BoxDecoration(
          gradient: LinearGradient(
            colors: gradient,
            begin: Alignment.topLeft,
            end: Alignment.bottomRight,
          ),
        ),
        child: SafeArea(
          child: Column(
            children: [
              // ── Top Bar ──
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 8),
                child: Row(
                  children: [
                    IconButton(
                      icon: const Icon(Icons.close_rounded, color: Colors.white, size: 28),
                      onPressed: () => Navigator.pop(context),
                    ),
                    const Spacer(),
                    // Font toggle
                    TextButton.icon(
                      onPressed: () {
                        setState(() => _fontIndex = (_fontIndex + 1) % _fonts.length);
                      },
                      icon: const Icon(Icons.font_download_rounded, color: Colors.white, size: 18),
                      label: Text(
                        _fonts[_fontIndex],
                        style: const TextStyle(color: Colors.white, fontSize: 12),
                      ),
                    ),
                  ],
                ),
              ),

              // ── Center text input ──
              Expanded(
                child: Center(
                  child: Padding(
                    padding: const EdgeInsets.symmetric(horizontal: 32),
                    child: TextField(
                      controller: _controller,
                      autofocus: true,
                      textAlign: TextAlign.center,
                      maxLines: null,
                      style: TextStyle(
                        color: Colors.white,
                        fontSize: 28,
                        fontWeight: FontWeight.bold,
                        fontFamily: _fontFamily.isNotEmpty ? _fontFamily : null,
                        height: 1.4,
                      ),
                      decoration: const InputDecoration(
                        hintText: 'Type a status...',
                        hintStyle: TextStyle(color: Colors.white38, fontSize: 28),
                        border: InputBorder.none,
                      ),
                      onChanged: (_) => setState(() {}),
                    ),
                  ),
                ),
              ),

              // ── Bottom bar: color picker + post button ──
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
                child: Row(
                  children: [
                    // Background color palette
                    Expanded(
                      child: SizedBox(
                        height: 36,
                        child: ListView.separated(
                          scrollDirection: Axis.horizontal,
                          itemCount: _gradients.length,
                          separatorBuilder: (_, __) => const SizedBox(width: 8),
                          itemBuilder: (_, idx) {
                            final g = _gradients[idx];
                            final isSelected = idx == _bgIndex;
                            return GestureDetector(
                              onTap: () => setState(() => _bgIndex = idx),
                              child: Container(
                                width: 36,
                                height: 36,
                                decoration: BoxDecoration(
                                  shape: BoxShape.circle,
                                  gradient: LinearGradient(colors: g),
                                  border: Border.all(
                                    color: isSelected ? Colors.white : Colors.white24,
                                    width: isSelected ? 3 : 1.5,
                                  ),
                                ),
                              ),
                            );
                          },
                        ),
                      ),
                    ),
                    const SizedBox(width: 16),
                    // Post FAB
                    FloatingActionButton(
                      heroTag: 'text_status_post',
                      mini: true,
                      backgroundColor: Colors.white,
                      onPressed: _controller.text.trim().isEmpty
                          ? null
                          : () {
                              widget.onPost(_controller.text.trim(), _bgIndex);
                              Navigator.pop(context);
                            },
                      child: Icon(
                        Icons.send_rounded,
                        color: _controller.text.trim().isEmpty ? Colors.black26 : gradient[0],
                      ),
                    ),
                  ],
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
