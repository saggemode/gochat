import 'dart:async';
import 'package:flutter/material.dart';
import '../../core/services/giphy_tenor_service.dart';
import '../../core/theme/app_theme.dart';

class ChatMediaPickerSheet extends StatefulWidget {
  final ValueChanged<String> onEmojiSelected;
  final ValueChanged<StickerItem> onSendSticker;
  final ValueChanged<GifItem> onSendGif;
  final VoidCallback? onBackspace;

  const ChatMediaPickerSheet({
    super.key,
    required this.onEmojiSelected,
    required this.onSendSticker,
    required this.onSendGif,
    this.onBackspace,
  });

  static Future<void> show(
    BuildContext context, {
    required ValueChanged<String> onEmojiSelected,
    required ValueChanged<StickerItem> onSendSticker,
    required ValueChanged<GifItem> onSendGif,
    VoidCallback? onBackspace,
  }) {
    return showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      backgroundColor: Colors.transparent,
      builder: (ctx) => ChatMediaPickerSheet(
        onEmojiSelected: onEmojiSelected,
        onSendSticker: onSendSticker,
        onSendGif: onSendGif,
        onBackspace: onBackspace,
      ),
    );
  }

  @override
  State<ChatMediaPickerSheet> createState() => _ChatMediaPickerSheetState();
}

class _ChatMediaPickerSheetState extends State<ChatMediaPickerSheet>
    with SingleTickerProviderStateMixin {
  late TabController _tabController;
  final GiphyTenorService _mediaService = GiphyTenorService();

  // GIF Search State
  final TextEditingController _gifSearchController = TextEditingController();
  List<GifItem> _gifs = [];
  bool _isLoadingGifs = true;
  Timer? _debounceTimer;
  String _selectedGifCategory = 'Trending';

  // Sticker Packs
  late List<StickerPack> _stickerPacks;
  int _selectedStickerPackIndex = 0;

  // Emoji Categories
  final Map<String, List<String>> _emojiCategories = {
    'Smileys': [
      '😀', '😃', '😄', '😁', '😆', '😅', '😂', '🤣', '🥲', '🥹', '😊', '😇',
      '🙂', '🙃', '😉', '😌', '😍', '🥰', '😘', '😗', '😙', '😚', '😋', '😛',
      '😝', '😜', '🤪', '🤨', '🧐', '🤓', '😎', '🥸', '🤩', '🥳', '😏', '😒',
      '😞', '😔', '😟', '😕', '🙁', '☹️', '😣', '😖', '😫', '😩', '🥺', '😢',
      '😭', '😮‍💨', '😤', '😠', '😡', '🤬', '🤯', '😳', '🥵', '🥶', '😱', '😨',
      '😰', '😥', '😓', '🫣', '🤗', '🫡', '🤔', '🫣', '🤭', '🫢', '🤫', '🫠',
    ],
    'Gestures': [
      '👍', '👎', '👊', '✊', '🤛', '🤜', '👏', '🙌', '👐', '🤲', '🤝', '🙏',
      '✍️', '💅', '🤳', '💪', '🦾', '🦿', '🦵', '🦶', '👂', '🦻', '👃', '🫀',
      '🫁', '🧠', '🫀', '👀', '👁️', '👅', '👄', '🫦', '💋', '❤️', '🧡', '💛',
      '💚', '💙', '💜', '🖤', '🤍', '🤎', '💔', '❤️‍🔥', '❤️‍🩹', '❣️', '💕', '💞',
      '💓', '💗', '💖', '💘', '💝', '💟', '☮️', '✝️', '☪️', '🕉️', '☸️', '✡️',
    ],
    'Animals': [
      '🐶', '🐱', '🐭', '🐹', '🐰', '🦊', '🐻', '🐼', '🐻‍❄️', '🐨', '🐯', '🦁',
      '🐮', '🐷', '🐸', '🐵', '🙈', '🙉', '🙊', '🐒', '🐔', '🐧', '🐦', '🐤',
      '🐣', '🐥', '🦆', '🦅', '🦉', '🦇', '🐺', '🐗', '🐴', '🦄', '🐝', '🪱',
      '🐛', '🦋', '🐌', '🐞', '🐜', '🪰', '🪲', '🪳', '🦟', '🦗', '🕷️', '🦂',
    ],
    'Food': [
      '🍏', '🍎', '🍐', '🍊', '🍋', '🍌', '🍉', '🍇', '🍓', '🫐', '🍈', '🍒',
      '🍑', '🥭', '🍍', '🥥', '🥝', '🍅', '🍆', '🥑', '🥦', '🥬', '🥒', '🌶️',
      '🫑', '🌽', '🥕', '🫒', '🧄', '🧅', '🥔', '🍠', '🥐', '🥯', '🍞', '🥖',
      '🥨', '🧀', '🥚', '🍳', '🧈', '🥞', '🧇', '🥓', '🥩', '🍗', '🍖', '🦴',
      '🌭', '🍔', '🍟', '🍕', '🫓', '🥪', '🥙', '🧆', '🌮', '🌯', '🫔', '🥗',
    ],
    'Activities': [
      '⚽', '🏀', '🏈', '⚾', '🥎', '🎾', '🏐', '🏉', '🥏', '🎱', '🪀', '🏓',
      '🏸', '🏒', '🏑', '🥍', '🏏', '🪃', '🥅', '⛳', '🪁', '🏹', '🎣', '🤿',
      '🥊', '🥋', '🎽', '🛹', '🛼', '🛷', '⛸️', '🥌', '🎿', '⛷️', '🏂', '🪂',
      '🏋️', '🤼', '🤸', '⛹️', '🤺', '🤾', '🏌️', '🏇', '🧘', '🏄', '🏊', '🤽',
    ],
    'Objects': [
      '💻', '🖥️', '🖨️', '⌨️', '🖱️', '📱', '📲', '☎️', '📞', '📟', '📠', '🔋',
      '🔌', '💡', '🔦', '🕯️', '🪔', '🧯', '🛢️', '💸', '💵', '💴', '💶', '💷',
      '🪙', '💰', '💳', '💎', '⚖️', '🪜', '🧰', '🪛', '🔧', '🔨', '⚒️', '🛠️',
      '⛏️', '🪚', '🔩', '⚙️', '🪤', '🧱', '⛓️', '🧲', '🔫', '💣', '🧨', '🪓',
    ],
  };

  final List<String> _gifChips = [
    'Trending',
    '😂 Laugh',
    '❤️ Love',
    '🔥 Fire',
    '💥 Mind Blown',
    '👏 Clap',
    '😢 Sad',
    '🚀 Hype',
    '🎮 Gaming',
    '✨ Anime',
    '🍿 Popcorn',
    '👋 Bye',
  ];

  @override
  void initState() {
    super.initState();
    _tabController = TabController(length: 3, vsync: this);
    _stickerPacks = _mediaService.getStickerPacks();
    _loadTrendingGifs();
  }

  @override
  void dispose() {
    _tabController.dispose();
    _gifSearchController.dispose();
    _debounceTimer?.cancel();
    super.dispose();
  }

  Future<void> _loadTrendingGifs() async {
    setState(() => _isLoadingGifs = true);
    final results = await _mediaService.getTrendingGifs();
    if (mounted) {
      setState(() {
        _gifs = results;
        _isLoadingGifs = false;
      });
    }
  }

  void _onGifSearchChanged(String query) {
    _debounceTimer?.cancel();
    _debounceTimer = Timer(const Duration(milliseconds: 400), () async {
      setState(() => _isLoadingGifs = true);
      final results = await _mediaService.searchGifs(query);
      if (mounted) {
        setState(() {
          _gifs = results;
          _isLoadingGifs = false;
        });
      }
    });
  }

  void _onGifChipSelected(String chip) {
    setState(() => _selectedGifCategory = chip);
    final searchTerm = chip.replaceAll(RegExp(r'[^\w\s]'), '').trim();
    if (chip == 'Trending') {
      _gifSearchController.clear();
      _loadTrendingGifs();
    } else {
      _gifSearchController.text = searchTerm;
      _onGifSearchChanged(searchTerm);
    }
  }

  @override
  Widget build(BuildContext context) {
    final isDark = Theme.of(context).brightness == Brightness.dark;
    final sheetHeight = MediaQuery.of(context).size.height * 0.48;

    return Container(
      height: sheetHeight,
      decoration: BoxDecoration(
        color: isDark ? AppTheme.darkSurface : Colors.white,
        borderRadius: const BorderRadius.vertical(top: Radius.circular(24)),
        boxShadow: [
          BoxShadow(
            color: Colors.black.withValues(alpha: 0.3),
            blurRadius: 20,
            offset: const Offset(0, -4),
          ),
        ],
      ),
      child: Column(
        children: [
          // Drag Handle
          Container(
            margin: const EdgeInsets.only(top: 8, bottom: 4),
            width: 36,
            height: 4,
            decoration: BoxDecoration(
              color: isDark ? Colors.white24 : Colors.black12,
              borderRadius: BorderRadius.circular(2),
            ),
          ),

          // Tab Bar (Emojis | Stickers | GIFs)
          Container(
            decoration: BoxDecoration(
              border: Border(
                bottom: BorderSide(
                  color: isDark ? AppTheme.darkBorder : AppTheme.lightBorder,
                  width: 0.5,
                ),
              ),
            ),
            child: TabBar(
              controller: _tabController,
              indicatorColor: AppTheme.primary,
              indicatorWeight: 3,
              labelColor: AppTheme.primary,
              unselectedLabelColor: isDark ? AppTheme.textMuted : AppTheme.textMutedLight,
              labelStyle: const TextStyle(fontWeight: FontWeight.bold, fontSize: 14),
              tabs: const [
                Tab(
                  icon: Icon(Icons.emoji_emotions_rounded, size: 20),
                  text: 'Emoji',
                ),
                Tab(
                  icon: Icon(Icons.sticky_note_2_rounded, size: 20),
                  text: 'Stickers',
                ),
                Tab(
                  icon: Icon(Icons.gif_box_rounded, size: 22),
                  text: 'GIFs (Giphy/Tenor)',
                ),
              ],
            ),
          ),

          // Tab Views
          Expanded(
            child: TabBarView(
              controller: _tabController,
              children: [
                _buildEmojiView(isDark),
                _buildStickersView(isDark),
                _buildGifsView(isDark),
              ],
            ),
          ),
        ],
      ),
    );
  }

  // ── 1. EMOJI VIEW ──────────────────────────────────────────────────────────
  Widget _buildEmojiView(bool isDark) {
    return Column(
      children: [
        Expanded(
          child: ListView.builder(
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
            itemCount: _emojiCategories.length,
            itemBuilder: (context, catIdx) {
              final catName = _emojiCategories.keys.elementAt(catIdx);
              final emojis = _emojiCategories[catName]!;

              return Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Padding(
                    padding: const EdgeInsets.only(left: 6, top: 8, bottom: 6),
                    child: Text(
                      catName.toUpperCase(),
                      style: TextStyle(
                        fontSize: 11,
                        fontWeight: FontWeight.w700,
                        letterSpacing: 1.1,
                        color: isDark ? AppTheme.textMuted : AppTheme.textMutedLight,
                      ),
                    ),
                  ),
                  GridView.builder(
                    shrinkWrap: true,
                    physics: const NeverScrollableScrollPhysics(),
                    gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(
                      crossAxisCount: 8,
                      mainAxisSpacing: 6,
                      crossAxisSpacing: 6,
                    ),
                    itemCount: emojis.length,
                    itemBuilder: (context, idx) {
                      final emoji = emojis[idx];
                      return InkWell(
                        onTap: () => widget.onEmojiSelected(emoji),
                        borderRadius: BorderRadius.circular(8),
                        child: Center(
                          child: Text(
                            emoji,
                            style: const TextStyle(fontSize: 26),
                          ),
                        ),
                      );
                    },
                  ),
                ],
              );
            },
          ),
        ),
        // Bottom Action Bar for Emoji (Backspace)
        if (widget.onBackspace != null)
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 6),
            decoration: BoxDecoration(
              color: isDark ? AppTheme.darkCard : const Color(0xFFF7F8FA),
              border: Border(
                top: BorderSide(
                  color: isDark ? AppTheme.darkBorder : AppTheme.lightBorder,
                  width: 0.5,
                ),
              ),
            ),
            child: Row(
              mainAxisAlignment: MainAxisAlignment.end,
              children: [
                IconButton(
                  icon: const Icon(Icons.backspace_outlined, size: 20),
                  color: isDark ? AppTheme.iconColor : AppTheme.iconColorLight,
                  onPressed: widget.onBackspace,
                  tooltip: 'Delete',
                ),
              ],
            ),
          ),
      ],
    );
  }

  // ── 2. STICKERS VIEW ───────────────────────────────────────────────────────
  Widget _buildStickersView(bool isDark) {
    final activePack = _stickerPacks[_selectedStickerPackIndex];

    return Column(
      children: [
        // Pack Selector Chips
        Container(
          height: 44,
          padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
          child: ListView.builder(
            scrollDirection: Axis.horizontal,
            itemCount: _stickerPacks.length,
            itemBuilder: (context, idx) {
              final pack = _stickerPacks[idx];
              final isSelected = idx == _selectedStickerPackIndex;
              return Padding(
                padding: const EdgeInsets.symmetric(horizontal: 4),
                child: ChoiceChip(
                  label: Text('${pack.iconEmoji} ${pack.name}'),
                  selected: isSelected,
                  selectedColor: AppTheme.primary.withValues(alpha: 0.25),
                  labelStyle: TextStyle(
                    fontSize: 12,
                    fontWeight: isSelected ? FontWeight.bold : FontWeight.normal,
                    color: isSelected
                        ? AppTheme.primary
                        : (isDark ? AppTheme.textLight : AppTheme.textDark),
                  ),
                  onSelected: (_) {
                    setState(() => _selectedStickerPackIndex = idx);
                  },
                ),
              );
            },
          ),
        ),
        const Divider(height: 1),

        // Animated Sticker Grid
        Expanded(
          child: GridView.builder(
            padding: const EdgeInsets.all(12),
            gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(
              crossAxisCount: 3,
              crossAxisSpacing: 10,
              mainAxisSpacing: 10,
              childAspectRatio: 1.0,
            ),
            itemCount: activePack.stickers.length,
            itemBuilder: (context, idx) {
              final sticker = activePack.stickers[idx];
              return InkWell(
                onTap: () {
                  Navigator.pop(context);
                  widget.onSendSticker(sticker);
                },
                borderRadius: BorderRadius.circular(16),
                child: Container(
                  padding: const EdgeInsets.all(8),
                  decoration: BoxDecoration(
                    color: isDark ? AppTheme.darkCard : const Color(0xFFF3F4F6),
                    borderRadius: BorderRadius.circular(16),
                    border: Border.all(
                      color: isDark ? AppTheme.darkBorder : AppTheme.lightBorder,
                      width: 0.5,
                    ),
                  ),
                  child: Image.network(
                    sticker.url,
                    fit: BoxFit.contain,
                    errorBuilder: (_, _, _) => const Center(
                      child: Icon(Icons.broken_image_rounded, size: 28, color: AppTheme.textMuted),
                    ),
                    loadingBuilder: (_, child, progress) {
                      if (progress == null) return child;
                      return const Center(
                        child: SizedBox(
                          width: 20,
                          height: 20,
                          child: CircularProgressIndicator(strokeWidth: 2, color: AppTheme.primary),
                        ),
                      );
                    },
                  ),
                ),
              );
            },
          ),
        ),
      ],
    );
  }

  // ── 3. GIFS VIEW ───────────────────────────────────────────────────────────
  Widget _buildGifsView(bool isDark) {
    return Column(
      children: [
        // GIF Search Bar
        Padding(
          padding: const EdgeInsets.fromLTRB(12, 10, 12, 6),
          child: TextField(
            controller: _gifSearchController,
            style: TextStyle(
              fontSize: 14,
              color: isDark ? AppTheme.textLight : AppTheme.textDark,
            ),
            decoration: InputDecoration(
              hintText: 'Search Giphy / Tenor GIFs...',
              hintStyle: TextStyle(
                fontSize: 13,
                color: isDark ? AppTheme.textMuted : AppTheme.textMutedLight,
              ),
              prefixIcon: const Icon(Icons.search_rounded, size: 20),
              suffixIcon: _gifSearchController.text.isNotEmpty
                  ? IconButton(
                      icon: const Icon(Icons.clear_rounded, size: 18),
                      onPressed: () {
                        _gifSearchController.clear();
                        _loadTrendingGifs();
                      },
                    )
                  : null,
              contentPadding: const EdgeInsets.symmetric(vertical: 8, horizontal: 14),
              filled: true,
              fillColor: isDark ? AppTheme.darkCard : const Color(0xFFF0F2F5),
              border: OutlineInputBorder(
                borderRadius: BorderRadius.circular(20),
                borderSide: BorderSide.none,
              ),
            ),
            onChanged: _onGifSearchChanged,
          ),
        ),

        // Quick Category Chips
        SizedBox(
          height: 38,
          child: ListView.builder(
            scrollDirection: Axis.horizontal,
            padding: const EdgeInsets.symmetric(horizontal: 8),
            itemCount: _gifChips.length,
            itemBuilder: (context, idx) {
              final chip = _gifChips[idx];
              final isSelected = chip == _selectedGifCategory;
              return Padding(
                padding: const EdgeInsets.symmetric(horizontal: 3),
                child: ChoiceChip(
                  label: Text(chip),
                  selected: isSelected,
                  selectedColor: AppTheme.primary.withValues(alpha: 0.25),
                  labelStyle: TextStyle(
                    fontSize: 12,
                    fontWeight: isSelected ? FontWeight.bold : FontWeight.normal,
                    color: isSelected
                        ? AppTheme.primary
                        : (isDark ? AppTheme.textLight : AppTheme.textDark),
                  ),
                  onSelected: (_) => _onGifChipSelected(chip),
                ),
              );
            },
          ),
        ),
        const SizedBox(height: 6),

        // GIF Results Grid
        Expanded(
          child: _isLoadingGifs
              ? const Center(
                  child: CircularProgressIndicator(color: AppTheme.primary),
                )
              : _gifs.isEmpty
                  ? Center(
                      child: Column(
                        mainAxisAlignment: MainAxisAlignment.center,
                        children: [
                          const Icon(Icons.sentiment_dissatisfied_rounded, size: 40, color: AppTheme.textMuted),
                          const SizedBox(height: 8),
                          Text(
                            'No GIFs found',
                            style: TextStyle(
                              color: isDark ? AppTheme.textMuted : AppTheme.textMutedLight,
                            ),
                          ),
                        ],
                      ),
                    )
                  : GridView.builder(
                      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
                      gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(
                        crossAxisCount: 2,
                        crossAxisSpacing: 8,
                        mainAxisSpacing: 8,
                        childAspectRatio: 1.3,
                      ),
                      itemCount: _gifs.length,
                      itemBuilder: (context, idx) {
                        final gif = _gifs[idx];
                        return InkWell(
                          onTap: () {
                            Navigator.pop(context);
                            widget.onSendGif(gif);
                          },
                          borderRadius: BorderRadius.circular(12),
                          child: ClipRRect(
                            borderRadius: BorderRadius.circular(12),
                            child: Stack(
                              fit: StackFit.expand,
                              children: [
                                Image.network(
                                  gif.previewUrl,
                                  fit: BoxFit.cover,
                                  loadingBuilder: (_, child, progress) {
                                    if (progress == null) return child;
                                    return Container(
                                      color: isDark ? AppTheme.darkCard : const Color(0xFFE5E7EB),
                                      child: const Center(
                                        child: SizedBox(
                                          width: 20,
                                          height: 20,
                                          child: CircularProgressIndicator(
                                            strokeWidth: 2,
                                            color: AppTheme.primary,
                                          ),
                                        ),
                                      ),
                                    );
                                  },
                                  errorBuilder: (_, _, _) => Container(
                                    color: isDark ? AppTheme.darkCard : const Color(0xFFE5E7EB),
                                    child: const Center(
                                      child: Icon(Icons.broken_image_rounded, color: AppTheme.textMuted),
                                    ),
                                  ),
                                ),
                                Positioned(
                                  bottom: 4,
                                  left: 6,
                                  child: Container(
                                    padding: const EdgeInsets.symmetric(horizontal: 5, vertical: 2),
                                    decoration: BoxDecoration(
                                      color: Colors.black.withValues(alpha: 0.6),
                                      borderRadius: BorderRadius.circular(4),
                                    ),
                                    child: const Text(
                                      'GIF',
                                      style: TextStyle(
                                        color: Colors.white,
                                        fontSize: 9,
                                        fontWeight: FontWeight.bold,
                                      ),
                                    ),
                                  ),
                                ),
                              ],
                            ),
                          ),
                        );
                      },
                    ),
        ),
      ],
    );
  }
}
