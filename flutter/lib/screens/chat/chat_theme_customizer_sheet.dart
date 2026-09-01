import 'package:flutter/material.dart';
import '../../core/models/chat_theme.dart';
import '../../core/services/chat_theme_service.dart';
import '../../core/theme/app_theme.dart';
import '../../widgets/chat_doodle_painter.dart';

class ChatThemeCustomizerSheet extends StatefulWidget {
  final String conversationId;
  final String conversationName;
  final ChatTheme currentTheme;
  final ValueChanged<ChatTheme> onThemeChanged;

  const ChatThemeCustomizerSheet({
    super.key,
    required this.conversationId,
    required this.conversationName,
    required this.currentTheme,
    required this.onThemeChanged,
  });

  @override
  State<ChatThemeCustomizerSheet> createState() => _ChatThemeCustomizerSheetState();
}

class _ChatThemeCustomizerSheetState extends State<ChatThemeCustomizerSheet> {
  late ChatTheme _selectedTheme;
  final _imageUrlCtrl = TextEditingController();
  bool _applyToAllChats = false;

  final List<List<Color>> _customSenderGradients = [
    [const Color(0xFF005C4B), const Color(0xFF008069)], // Emerald
    [const Color(0xFF8B5CF6), const Color(0xFFEC4899)], // Cyberpunk
    [const Color(0xFF059669), const Color(0xFF10B981)], // Mint
    [const Color(0xFFF97316), const Color(0xFFEF4444)], // Sunset
    [const Color(0xFF0284C7), const Color(0xFF38BDF8)], // Sky
    [const Color(0xFF7C3AED), const Color(0xFFA78BFA)], // Royal
    [const Color(0xFF15803D), const Color(0xFF22C55E)], // Matrix
    [const Color(0xFFE11D48), const Color(0xFFFB7185)], // Rose
    [const Color(0xFF475569), const Color(0xFF64748B)], // Slate
  ];

  @override
  void initState() {
    super.initState();
    _selectedTheme = widget.currentTheme;
    if (_selectedTheme.wallpaperImageUrl != null) {
      _imageUrlCtrl.text = _selectedTheme.wallpaperImageUrl!;
    }
  }

  @override
  void dispose() {
    _imageUrlCtrl.dispose();
    super.dispose();
  }

  void _updateTheme(ChatTheme newTheme) {
    setState(() => _selectedTheme = newTheme);
    widget.onThemeChanged(newTheme);
  }

  Future<void> _saveAndClose() async {
    final service = ChatThemeService();
    if (_applyToAllChats) {
      await service.setGlobalTheme(_selectedTheme);
    }
    await service.setThemeForConversation(widget.conversationId, _selectedTheme);
    if (!mounted) return;
    Navigator.pop(context);
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text('✨ Applied "${_selectedTheme.name}" theme${_applyToAllChats ? ' to all chats' : ''}!'),
        backgroundColor: AppTheme.primary,
        behavior: SnackBarBehavior.floating,
      ),
    );
  }

  Future<void> _resetToDefault() async {
    final service = ChatThemeService();
    await service.resetThemeForConversation(widget.conversationId);
    _updateTheme(ChatTheme.defaultEmerald);
    if (!mounted) return;
    Navigator.pop(context);
    ScaffoldMessenger.of(context).showSnackBar(
      const SnackBar(
        content: Text('🔄 Reset to default GoChat Emerald theme'),
        backgroundColor: AppTheme.primary,
        behavior: SnackBarBehavior.floating,
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final isDark = Theme.of(context).brightness == Brightness.dark;

    return DraggableScrollableSheet(
      initialChildSize: 0.9,
      minChildSize: 0.5,
      maxChildSize: 0.95,
      expand: false,
      builder: (_, scrollController) {
        return Container(
          decoration: BoxDecoration(
            color: isDark ? AppTheme.darkSurface : Colors.white,
            borderRadius: const BorderRadius.vertical(top: Radius.circular(28)),
          ),
          child: Column(
            children: [
              // Drag Handle
              Container(
                width: 40,
                height: 5,
                margin: const EdgeInsets.only(top: 12, bottom: 8),
                decoration: BoxDecoration(
                  color: isDark ? Colors.white24 : Colors.black12,
                  borderRadius: BorderRadius.circular(3),
                ),
              ),

              // Sheet Header
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 8),
                child: Row(
                  mainAxisAlignment: MainAxisAlignment.spaceBetween,
                  children: [
                    Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        const Text(
                          'Wallpaper & Theme',
                          style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold),
                        ),
                        Text(
                          'For ${widget.conversationName}',
                          style: TextStyle(
                            fontSize: 12,
                            color: isDark ? AppTheme.textMuted : AppTheme.textMutedLight,
                          ),
                        ),
                      ],
                    ),
                    TextButton(
                      onPressed: _resetToDefault,
                      child: const Text('Reset', style: TextStyle(color: AppTheme.dangerRed, fontSize: 13)),
                    ),
                  ],
                ),
              ),
              const Divider(height: 1),

              // Content
              Expanded(
                child: ListView(
                  controller: scrollController,
                  padding: const EdgeInsets.all(20),
                  children: [
                    // ── Live Interactive Preview Card ───────────────────────────
                    _buildLivePreviewCard(isDark),
                    const SizedBox(height: 24),

                    // ── Preset Themes ───────────────────────────────────────────
                    const Text(
                      'PRESET THEMES',
                      style: TextStyle(
                        fontSize: 12,
                        fontWeight: FontWeight.bold,
                        color: AppTheme.primary,
                        letterSpacing: 1,
                      ),
                    ),
                    const SizedBox(height: 12),
                    _buildPresetsGrid(),
                    const SizedBox(height: 24),

                    // ── Bubble Shape ────────────────────────────────────────────
                    const Text(
                      'BUBBLE AESTHETICS',
                      style: TextStyle(
                        fontSize: 12,
                        fontWeight: FontWeight.bold,
                        color: AppTheme.primary,
                        letterSpacing: 1,
                      ),
                    ),
                    const SizedBox(height: 12),
                    _buildBubbleShapeSelector(isDark),
                    const SizedBox(height: 24),

                    // ── Sent Bubble Color Override ──────────────────────────────
                    const Text(
                      'ACCENT BUBBLE GRADIENT',
                      style: TextStyle(
                        fontSize: 12,
                        fontWeight: FontWeight.bold,
                        color: AppTheme.primary,
                        letterSpacing: 1,
                      ),
                    ),
                    const SizedBox(height: 12),
                    _buildColorSwatches(),
                    const SizedBox(height: 24),

                    // ── Doodle Pattern Overlay ──────────────────────────────────
                    Container(
                      padding: const EdgeInsets.all(14),
                      decoration: BoxDecoration(
                        color: isDark ? AppTheme.darkCard : const Color(0xFFF0F2F5),
                        borderRadius: BorderRadius.circular(16),
                        border: Border.all(
                          color: isDark ? AppTheme.darkBorder : AppTheme.lightBorder,
                          width: 0.5,
                        ),
                      ),
                      child: Column(
                        children: [
                          Row(
                            mainAxisAlignment: MainAxisAlignment.spaceBetween,
                            children: [
                              Row(
                                children: [
                                  Icon(
                                    Icons.auto_awesome_rounded,
                                    color: _selectedTheme.showDoodlePattern ? AppTheme.primary : (isDark ? AppTheme.textMuted : AppTheme.textMutedLight),
                                    size: 20,
                                  ),
                                  const SizedBox(width: 10),
                                  const Text('Doodle Pattern Overlay', style: TextStyle(fontWeight: FontWeight.bold, fontSize: 14)),
                                ],
                              ),
                              Switch(
                                value: _selectedTheme.showDoodlePattern,
                                activeThumbColor: AppTheme.primary,
                                onChanged: (val) {
                                  _updateTheme(_selectedTheme.copyWith(showDoodlePattern: val));
                                },
                              ),
                            ],
                          ),
                          if (_selectedTheme.showDoodlePattern) ...[
                            const SizedBox(height: 8),
                            Row(
                              children: [
                                const Text('Opacity:', style: TextStyle(fontSize: 12)),
                                Expanded(
                                  child: Slider(
                                    value: _selectedTheme.doodleOpacity,
                                    min: 0.02,
                                    max: 0.25,
                                    activeColor: AppTheme.primary,
                                    onChanged: (val) {
                                      _updateTheme(_selectedTheme.copyWith(doodleOpacity: val));
                                    },
                                  ),
                                ),
                                Text('${(_selectedTheme.doodleOpacity * 100).round()}%', style: const TextStyle(fontSize: 12, fontWeight: FontWeight.bold)),
                              ],
                            ),
                          ],
                        ],
                      ),
                    ),
                    const SizedBox(height: 16),

                    // ── Custom Wallpaper URL ────────────────────────────────────
                    Container(
                      padding: const EdgeInsets.all(14),
                      decoration: BoxDecoration(
                        color: isDark ? AppTheme.darkCard : const Color(0xFFF0F2F5),
                        borderRadius: BorderRadius.circular(16),
                        border: Border.all(
                          color: isDark ? AppTheme.darkBorder : AppTheme.lightBorder,
                          width: 0.5,
                        ),
                      ),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          const Row(
                            children: [
                              Icon(Icons.image_outlined, color: AppTheme.primary, size: 20),
                              SizedBox(width: 8),
                              Text('Custom Image Wallpaper', style: TextStyle(fontWeight: FontWeight.bold, fontSize: 14)),
                            ],
                          ),
                          const SizedBox(height: 8),
                          Row(
                            children: [
                              Expanded(
                                child: TextField(
                                  controller: _imageUrlCtrl,
                                  style: const TextStyle(fontSize: 13),
                                  decoration: const InputDecoration(
                                    hintText: 'https://images.unsplash.com/...',
                                    contentPadding: EdgeInsets.symmetric(horizontal: 12, vertical: 8),
                                    isDense: true,
                                  ),
                                ),
                              ),
                              const SizedBox(width: 8),
                              ElevatedButton(
                                style: ElevatedButton.styleFrom(
                                  backgroundColor: AppTheme.primary,
                                  foregroundColor: Colors.black,
                                  padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
                                ),
                                onPressed: () {
                                  final url = _imageUrlCtrl.text.trim();
                                  _updateTheme(_selectedTheme.copyWith(
                                    wallpaperImageUrl: url.isNotEmpty ? url : null,
                                  ));
                                },
                                child: const Text('Apply', style: TextStyle(fontSize: 12, fontWeight: FontWeight.bold)),
                              ),
                            ],
                          ),
                        ],
                      ),
                    ),
                    const SizedBox(height: 16),

                    // Apply to All Chats Checkbox
                    CheckboxListTile(
                      contentPadding: EdgeInsets.zero,
                      title: const Text('Apply this theme as default for all chats', style: TextStyle(fontSize: 13)),
                      value: _applyToAllChats,
                      activeColor: AppTheme.primary,
                      onChanged: (val) => setState(() => _applyToAllChats = val ?? false),
                    ),
                    const SizedBox(height: 20),

                    // Save Button
                    SizedBox(
                      width: double.infinity,
                      child: ElevatedButton.icon(
                        style: ElevatedButton.styleFrom(
                          backgroundColor: AppTheme.primary,
                          foregroundColor: Colors.black,
                          padding: const EdgeInsets.symmetric(vertical: 16),
                          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
                        ),
                        icon: const Icon(Icons.check_rounded, size: 20),
                        label: const Text('Save Theme', style: TextStyle(fontSize: 16, fontWeight: FontWeight.bold)),
                        onPressed: _saveAndClose,
                      ),
                    ),
                    const SizedBox(height: 20),
                  ],
                ),
              ),
            ],
          ),
        );
      },
    );
  }

  // ── Live Interactive Preview Card ───────────────────────────────────────────
  Widget _buildLivePreviewCard(bool isDark) {
    return Container(
      height: 200,
      clipBehavior: Clip.antiAlias,
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(20),
        border: Border.all(
          color: _selectedTheme.neonGlowColor?.withValues(alpha: 0.5) ?? (isDark ? AppTheme.darkBorder : AppTheme.lightBorder),
          width: _selectedTheme.bubbleShape == BubbleShape.neonGlow ? 1.5 : 1,
        ),
        boxShadow: [
          if (_selectedTheme.neonGlowColor != null)
            BoxShadow(
              color: _selectedTheme.neonGlowColor!.withValues(alpha: 0.25),
              blurRadius: 16,
              spreadRadius: 2,
            ),
        ],
      ),
      child: Stack(
        children: [
          // Background Gradient or Solid or Image
          if (_selectedTheme.wallpaperImageUrl != null && _selectedTheme.wallpaperImageUrl!.isNotEmpty)
            Positioned.fill(
              child: Image.network(
                _selectedTheme.wallpaperImageUrl!,
                fit: BoxFit.cover,
                errorBuilder: (_, _, _) => Container(
                  decoration: BoxDecoration(
                    gradient: LinearGradient(
                      colors: _selectedTheme.bgGradient,
                      begin: Alignment.topLeft,
                      end: Alignment.bottomRight,
                    ),
                  ),
                ),
              ),
            )
          else
            Positioned.fill(
              child: Container(
                decoration: BoxDecoration(
                  gradient: LinearGradient(
                    colors: _selectedTheme.bgGradient,
                    begin: Alignment.topLeft,
                    end: Alignment.bottomRight,
                  ),
                ),
              ),
            ),

          // Doodle Pattern Overlay
          if (_selectedTheme.showDoodlePattern)
            Positioned.fill(
              child: CustomPaint(
                painter: ChatDoodlePainter(
                  color: Colors.white,
                  opacity: _selectedTheme.doodleOpacity,
                ),
              ),
            ),

          // Sample Bubbles
          Padding(
            padding: const EdgeInsets.all(16),
            child: Column(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                // Received Bubble
                Align(
                  alignment: Alignment.centerLeft,
                  child: Container(
                    padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 9),
                    constraints: const BoxConstraints(maxWidth: 220),
                    decoration: BoxDecoration(
                      color: _selectedTheme.receiverColor,
                      borderRadius: BorderRadius.circular(16),
                      border: _selectedTheme.bubbleShape == BubbleShape.glassmorphism
                          ? Border.all(color: Colors.white.withValues(alpha: 0.15))
                          : null,
                    ),
                    child: Text(
                      'Hey! How do you like this wallpaper? ✨',
                      style: TextStyle(color: _selectedTheme.receiverTextColor, fontSize: 13),
                    ),
                  ),
                ),
                const SizedBox(height: 10),

                // Sent Bubble
                Align(
                  alignment: Alignment.centerRight,
                  child: Container(
                    padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 9),
                    constraints: const BoxConstraints(maxWidth: 220),
                    decoration: BoxDecoration(
                      gradient: LinearGradient(
                        colors: _selectedTheme.senderGradient,
                        begin: Alignment.topLeft,
                        end: Alignment.bottomRight,
                      ),
                      borderRadius: BorderRadius.circular(16),
                      border: _selectedTheme.bubbleShape == BubbleShape.neonGlow
                          ? Border.all(color: _selectedTheme.neonGlowColor ?? Colors.white, width: 1.2)
                          : (_selectedTheme.bubbleShape == BubbleShape.glassmorphism
                              ? Border.all(color: Colors.white.withValues(alpha: 0.25))
                              : null),
                      boxShadow: [
                        if (_selectedTheme.bubbleShape == BubbleShape.neonGlow && _selectedTheme.neonGlowColor != null)
                          BoxShadow(
                            color: _selectedTheme.neonGlowColor!.withValues(alpha: 0.35),
                            blurRadius: 8,
                          ),
                      ],
                    ),
                    child: Text(
                      'It looks stunning! 🚀 Loving the glow.',
                      style: TextStyle(color: _selectedTheme.senderTextColor, fontSize: 13, fontWeight: FontWeight.w500),
                    ),
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  // ── Presets Grid ────────────────────────────────────────────────────────────
  Widget _buildPresetsGrid() {
    return SizedBox(
      height: 110,
      child: ListView.separated(
        scrollDirection: Axis.horizontal,
        itemCount: ChatTheme.presets.length,
        separatorBuilder: (_, _) => const SizedBox(width: 12),
        itemBuilder: (_, index) {
          final preset = ChatTheme.presets[index];
          final isSelected = _selectedTheme.id == preset.id;

          return GestureDetector(
            onTap: () => _updateTheme(preset),
            child: Container(
              width: 90,
              decoration: BoxDecoration(
                borderRadius: BorderRadius.circular(16),
                border: Border.all(
                  color: isSelected ? AppTheme.primary : Colors.transparent,
                  width: 2.5,
                ),
              ),
              child: ClipRRect(
                borderRadius: BorderRadius.circular(14),
                child: Stack(
                  children: [
                    // Background swatch
                    Positioned.fill(
                      child: Container(
                        decoration: BoxDecoration(
                          gradient: LinearGradient(
                            colors: preset.bgGradient,
                            begin: Alignment.topLeft,
                            end: Alignment.bottomRight,
                          ),
                        ),
                      ),
                    ),
                    // Sample mini bubble dot
                    Positioned(
                      bottom: 28,
                      right: 10,
                      child: Container(
                        width: 22,
                        height: 12,
                        decoration: BoxDecoration(
                          gradient: LinearGradient(colors: preset.senderGradient),
                          borderRadius: BorderRadius.circular(6),
                        ),
                      ),
                    ),
                    // Title Label
                    Positioned(
                      bottom: 0,
                      left: 0,
                      right: 0,
                      child: Container(
                        color: Colors.black54,
                        padding: const EdgeInsets.symmetric(vertical: 4, horizontal: 4),
                        child: Text(
                          preset.name,
                          style: const TextStyle(fontSize: 10, color: Colors.white, fontWeight: FontWeight.bold),
                          textAlign: TextAlign.center,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                        ),
                      ),
                    ),
                    // Checked Icon if active
                    if (isSelected)
                      const Positioned(
                        top: 6,
                        right: 6,
                        child: CircleAvatar(
                          radius: 9,
                          backgroundColor: AppTheme.primary,
                          child: Icon(Icons.check, size: 12, color: Colors.black),
                        ),
                      ),
                  ],
                ),
              ),
            ),
          );
        },
      ),
    );
  }

  // ── Bubble Shape Selector ───────────────────────────────────────────────────
  Widget _buildBubbleShapeSelector(bool isDark) {
    return Row(
      children: BubbleShape.values.map((shape) {
        final isSelected = _selectedTheme.bubbleShape == shape;
        String label = 'Classic';
        IconData icon = Icons.chat_bubble_outline_rounded;

        if (shape == BubbleShape.glassmorphism) {
          label = 'Glass';
          icon = Icons.blur_on_rounded;
        } else if (shape == BubbleShape.neonGlow) {
          label = 'Neon Glow';
          icon = Icons.flash_on_rounded;
        } else if (shape == BubbleShape.minimalFlat) {
          label = 'Minimal';
          icon = Icons.crop_square_rounded;
        }

        return Expanded(
          child: GestureDetector(
            onTap: () => _updateTheme(_selectedTheme.copyWith(bubbleShape: shape)),
            child: Container(
              margin: const EdgeInsets.symmetric(horizontal: 3),
              padding: const EdgeInsets.symmetric(vertical: 10),
              decoration: BoxDecoration(
                color: isSelected
                    ? AppTheme.primary.withValues(alpha: 0.15)
                    : (isDark ? AppTheme.darkCard : const Color(0xFFF0F2F5)),
                borderRadius: BorderRadius.circular(12),
                border: Border.all(
                  color: isSelected ? AppTheme.primary : (isDark ? AppTheme.darkBorder : AppTheme.lightBorder),
                  width: isSelected ? 1.5 : 0.5,
                ),
              ),
              child: Column(
                children: [
                  Icon(icon, size: 18, color: isSelected ? AppTheme.primary : (isDark ? AppTheme.textMuted : AppTheme.textMutedLight)),
                  const SizedBox(height: 4),
                  Text(
                    label,
                    style: TextStyle(
                      fontSize: 11,
                      fontWeight: isSelected ? FontWeight.bold : FontWeight.normal,
                      color: isSelected ? AppTheme.primary : (isDark ? Colors.white : Colors.black),
                    ),
                  ),
                ],
              ),
            ),
          ),
        );
      }).toList(),
    );
  }

  // ── Color Swatches for Sent Bubble ──────────────────────────────────────────
  Widget _buildColorSwatches() {
    return SizedBox(
      height: 44,
      child: ListView.separated(
        scrollDirection: Axis.horizontal,
        itemCount: _customSenderGradients.length,
        separatorBuilder: (_, _) => const SizedBox(width: 10),
        itemBuilder: (_, index) {
          final grad = _customSenderGradients[index];
          final isSelected = _selectedTheme.senderGradient.first == grad.first;

          return GestureDetector(
            onTap: () => _updateTheme(_selectedTheme.copyWith(
              senderGradient: grad,
              neonGlowColor: grad.last,
            )),
            child: Container(
              width: 44,
              height: 44,
              decoration: BoxDecoration(
                shape: BoxShape.circle,
                gradient: LinearGradient(colors: grad),
                border: Border.all(
                  color: isSelected ? Colors.white : Colors.transparent,
                  width: 2.5,
                ),
                boxShadow: [
                  if (isSelected)
                    BoxShadow(
                      color: grad.last.withValues(alpha: 0.5),
                      blurRadius: 8,
                    ),
                ],
              ),
              child: isSelected ? const Icon(Icons.check, size: 18, color: Colors.white) : null,
            ),
          );
        },
      ),
    );
  }
}
