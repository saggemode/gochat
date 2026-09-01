import 'dart:convert';
import 'package:flutter/foundation.dart';
import 'package:http/http.dart' as http;

/// Model for a GIF item from Giphy/Tenor or curated CDN
class GifItem {
  final String id;
  final String title;
  final String previewUrl;
  final String fullUrl;
  final double? width;
  final double? height;

  const GifItem({
    required this.id,
    required this.title,
    required this.previewUrl,
    required this.fullUrl,
    this.width,
    this.height,
  });

  factory GifItem.fromTenor(Map<String, dynamic> json) {
    final mediaFormats = json['media_formats'] as Map<String, dynamic>? ?? {};
    final tinygif = mediaFormats['tinygif'] as Map<String, dynamic>? ?? {};
    final gif = mediaFormats['gif'] as Map<String, dynamic>? ?? {};
    final nanogif = mediaFormats['nanogif'] as Map<String, dynamic>? ?? {};

    return GifItem(
      id: json['id']?.toString() ?? '',
      title: json['content_description']?.toString() ?? 'GIF',
      previewUrl: nanogif['url']?.toString() ?? tinygif['url']?.toString() ?? gif['url']?.toString() ?? '',
      fullUrl: gif['url']?.toString() ?? tinygif['url']?.toString() ?? '',
      width: (gif['dims'] is List && (gif['dims'] as List).isNotEmpty)
          ? double.tryParse((gif['dims'] as List)[0].toString())
          : null,
      height: (gif['dims'] is List && (gif['dims'] as List).length > 1)
          ? double.tryParse((gif['dims'] as List)[1].toString())
          : null,
    );
  }

  factory GifItem.fromGiphy(Map<String, dynamic> json) {
    final images = json['images'] as Map<String, dynamic>? ?? {};
    final fixedHeightSmall = images['fixed_height_small'] as Map<String, dynamic>? ?? {};
    final original = images['original'] as Map<String, dynamic>? ?? {};

    return GifItem(
      id: json['id']?.toString() ?? '',
      title: json['title']?.toString() ?? 'GIF',
      previewUrl: fixedHeightSmall['url']?.toString() ?? original['url']?.toString() ?? '',
      fullUrl: original['url']?.toString() ?? fixedHeightSmall['url']?.toString() ?? '',
      width: double.tryParse(original['width']?.toString() ?? ''),
      height: double.tryParse(original['height']?.toString() ?? ''),
    );
  }
}

/// Model for an animated sticker pack
class StickerPack {
  final String id;
  final String name;
  final String iconEmoji;
  final List<StickerItem> stickers;

  const StickerPack({
    required this.id,
    required this.name,
    required this.iconEmoji,
    required this.stickers,
  });
}

/// Individual sticker inside a pack
class StickerItem {
  final String id;
  final String name;
  final String url;

  const StickerItem({
    required this.id,
    required this.name,
    required this.url,
  });
}

class GiphyTenorService {
  static final GiphyTenorService _instance = GiphyTenorService._internal();
  factory GiphyTenorService() => _instance;
  GiphyTenorService._internal();

  // Curated Fallback GIFs when offline or if API key is not configured
  final List<GifItem> _fallbackGifs = [
    const GifItem(
      id: 'fb1',
      title: 'Excited Doge',
      previewUrl: 'https://media.giphy.com/media/oF5oUYTOhvZOE/giphy.gif',
      fullUrl: 'https://media.giphy.com/media/oF5oUYTOhvZOE/giphy.gif',
    ),
    const GifItem(
      id: 'fb2',
      title: 'Mind Blown',
      previewUrl: 'https://media.giphy.com/media/26ufdipQqU2lhNA4g/giphy.gif',
      fullUrl: 'https://media.giphy.com/media/26ufdipQqU2lhNA4g/giphy.gif',
    ),
    const GifItem(
      id: 'fb3',
      title: 'Cat Vibing / Typing Fast',
      previewUrl: 'https://media.giphy.com/media/JIX9t2j0ZTN9S/giphy.gif',
      fullUrl: 'https://media.giphy.com/media/JIX9t2j0ZTN9S/giphy.gif',
    ),
    const GifItem(
      id: 'fb4',
      title: 'Popcorn Michael Jackson',
      previewUrl: 'https://media.giphy.com/media/GLbiGvv9RiNp6/giphy.gif',
      fullUrl: 'https://media.giphy.com/media/GLbiGvv9RiNp6/giphy.gif',
    ),
    const GifItem(
      id: 'fb5',
      title: 'Thumbs Up Chuck Norris',
      previewUrl: 'https://media.giphy.com/media/111ebonMs90YLu/giphy.gif',
      fullUrl: 'https://media.giphy.com/media/111ebonMs90YLu/giphy.gif',
    ),
    const GifItem(
      id: 'fb6',
      title: 'Homer Simpson Backing into Bushes',
      previewUrl: 'https://media.giphy.com/media/a93jwI0wkWTQs/giphy.gif',
      fullUrl: 'https://media.giphy.com/media/a93jwI0wkWTQs/giphy.gif',
    ),
    const GifItem(
      id: 'fb7',
      title: 'Success Kid',
      previewUrl: 'https://media.giphy.com/media/nXxOjZrbnbRxS/giphy.gif',
      fullUrl: 'https://media.giphy.com/media/nXxOjZrbnbRxS/giphy.gif',
    ),
    const GifItem(
      id: 'fb8',
      title: 'Party Parrot Dance',
      previewUrl: 'https://media.giphy.com/media/l3q2wJsC23ikJg9xe/giphy.gif',
      fullUrl: 'https://media.giphy.com/media/l3q2wJsC23ikJg9xe/giphy.gif',
    ),
    const GifItem(
      id: 'fb9',
      title: 'High Five',
      previewUrl: 'https://media.giphy.com/media/l0ErFafpUCQTQFMSk/giphy.gif',
      fullUrl: 'https://media.giphy.com/media/l0ErFafpUCQTQFMSk/giphy.gif',
    ),
    const GifItem(
      id: 'fb10',
      title: 'SpongeBob Laughing',
      previewUrl: 'https://media.giphy.com/media/3oKIPnAiaMCws8nOsE/giphy.gif',
      fullUrl: 'https://media.giphy.com/media/3oKIPnAiaMCws8nOsE/giphy.gif',
    ),
    const GifItem(
      id: 'fb11',
      title: 'Dancing Groots',
      previewUrl: 'https://media.giphy.com/media/14bhmZtBNhVnIk/giphy.gif',
      fullUrl: 'https://media.giphy.com/media/14bhmZtBNhVnIk/giphy.gif',
    ),
    const GifItem(
      id: 'fb12',
      title: 'This is Fine Dog in Fire',
      previewUrl: 'https://media.giphy.com/media/NTur7XlVDUdqM/giphy.gif',
      fullUrl: 'https://media.giphy.com/media/NTur7XlVDUdqM/giphy.gif',
    ),
  ];

  /// Animated Sticker Packs
  List<StickerPack> getStickerPacks() {
    return [
      StickerPack(
        id: 'pepe',
        name: 'Pepe & Memes',
        iconEmoji: '🐸',
        stickers: [
          const StickerItem(
            id: 'p1',
            name: 'Pepe Clapping',
            url: 'https://media.giphy.com/media/7rj2ZgttvgomY/giphy.gif',
          ),
          const StickerItem(
            id: 'p2',
            name: 'Pepe Sad',
            url: 'https://media.giphy.com/media/OPU6wzx8JrHna/giphy.gif',
          ),
          const StickerItem(
            id: 'p3',
            name: 'Pepe Happy Hype',
            url: 'https://media.giphy.com/media/tsX3YMWYzDPjAARfeg/giphy.gif',
          ),
          const StickerItem(
            id: 'p4',
            name: 'Pepe Smirk Coffee',
            url: 'https://media.giphy.com/media/8vUEXZA2me7vnuUvrs/giphy.gif',
          ),
          const StickerItem(
            id: 'p5',
            name: 'Pepe Laughing',
            url: 'https://media.giphy.com/media/W3a0zO282sCyINqh13/giphy.gif',
          ),
          const StickerItem(
            id: 'p6',
            name: 'Pepe Dance',
            url: 'https://media.giphy.com/media/bkcbX8SqTCXHG/giphy.gif',
          ),
        ],
      ),
      StickerPack(
        id: 'shiba',
        name: 'Doge & Pets',
        iconEmoji: '🐕',
        stickers: [
          const StickerItem(
            id: 's1',
            name: 'Doge Bonk',
            url: 'https://media.giphy.com/media/HxMhuDg7O4pKOhhcRC/giphy.gif',
          ),
          const StickerItem(
            id: 's2',
            name: 'Doge Dance',
            url: 'https://media.giphy.com/media/oF5oUYTOhvZOE/giphy.gif',
          ),
          const StickerItem(
            id: 's3',
            name: 'Cat Bongo Tap',
            url: 'https://media.giphy.com/media/JIX9t2j0ZTN9S/giphy.gif',
          ),
          const StickerItem(
            id: 's4',
            name: 'Pug Head Tilt',
            url: 'https://media.giphy.com/media/bbshzgyFQDqPHXBo4c/giphy.gif',
          ),
          const StickerItem(
            id: 's5',
            name: 'Husky Howl',
            url: 'https://media.giphy.com/media/4Zo41lhzKt6iZ8xff9/giphy.gif',
          ),
        ],
      ),
      StickerPack(
        id: 'cyberpunk',
        name: 'Cyberpunk & Pixel',
        iconEmoji: '⚡',
        stickers: [
          const StickerItem(
            id: 'c1',
            name: 'Neon Heart Pulse',
            url: 'https://media.giphy.com/media/26ufdipQqU2lhNA4g/giphy.gif',
          ),
          const StickerItem(
            id: 'c2',
            name: 'Pixel Fire Flame',
            url: 'https://media.giphy.com/media/3o72FfM5HJydzafgUE/giphy.gif',
          ),
          const StickerItem(
            id: 'c3',
            name: 'Cyberpunk Glitch Skull',
            url: 'https://media.giphy.com/media/13HgwGsXF0aiGY/giphy.gif',
          ),
          const StickerItem(
            id: 'c4',
            name: 'Matrix Code Rain',
            url: 'https://media.giphy.com/media/26tP41HChbPE6dCme/giphy.gif',
          ),
        ],
      ),
      StickerPack(
        id: 'anime',
        name: 'Anime & Chibi',
        iconEmoji: '✨',
        stickers: [
          const StickerItem(
            id: 'a1',
            name: 'Anime Wow Sparkle',
            url: 'https://media.giphy.com/media/111ebonMs90YLu/giphy.gif',
          ),
          const StickerItem(
            id: 'a2',
            name: 'Chibi Wave Hello',
            url: 'https://media.giphy.com/media/vFKqnCdLPNOKc/giphy.gif',
          ),
          const StickerItem(
            id: 'a3',
            name: 'Anime Ramen Slurp',
            url: 'https://media.giphy.com/media/eHpWHuKjWW50I/giphy.gif',
          ),
          const StickerItem(
            id: 'a4',
            name: 'Chibi Sleepy Zzz',
            url: 'https://media.giphy.com/media/mguPrVJAnEHIY/giphy.gif',
          ),
        ],
      ),
      StickerPack(
        id: 'crypto',
        name: 'Crypto Vibes',
        iconEmoji: '🚀',
        stickers: [
          const StickerItem(
            id: 'cr1',
            name: 'To The Moon Rocket',
            url: 'https://media.giphy.com/media/9C1nyePmmMLwnmWYQt/giphy.gif',
          ),
          const StickerItem(
            id: 'cr2',
            name: 'Diamond Hands',
            url: 'https://media.giphy.com/media/d0DdMCREQChi3jGymW/giphy.gif',
          ),
          const StickerItem(
            id: 'cr3',
            name: 'Bull Run Rampage',
            url: 'https://media.giphy.com/media/mb410b433LQ2RevLM1/giphy.gif',
          ),
          const StickerItem(
            id: 'cr4',
            name: 'HODL Stamp',
            url: 'https://media.giphy.com/media/mz7iww9tHREmM/giphy.gif',
          ),
        ],
      ),
    ];
  }

  /// Search GIFs via Tenor / Giphy API with fallback
  Future<List<GifItem>> searchGifs(String query, {int limit = 24}) async {
    final cleanQuery = query.trim();
    if (cleanQuery.isEmpty) {
      return getTrendingGifs(limit: limit);
    }

    try {
      // Free Tenor v2 Public Search Endpoint
      final url = Uri.parse(
        'https://tenor.googleapis.com/v2/search?q=${Uri.encodeComponent(cleanQuery)}&key=LIVDSRZULELA&client_key=gochat_app&limit=$limit&media_filter=gif,tinygif,nanogif',
      );
      final response = await http.get(url).timeout(const Duration(seconds: 4));

      if (response.statusCode == 200) {
        final data = jsonDecode(response.body) as Map<String, dynamic>;
        final results = data['results'] as List<dynamic>? ?? [];
        if (results.isNotEmpty) {
          return results
              .map((item) => GifItem.fromTenor(item as Map<String, dynamic>))
              .where((g) => g.previewUrl.isNotEmpty)
              .toList();
        }
      }
    } catch (e) {
      debugPrint('Tenor GIF search failed: $e');
    }

    // Filter fallback list by query
    final lower = cleanQuery.toLowerCase();
    final matched = _fallbackGifs.where((g) => g.title.toLowerCase().contains(lower)).toList();
    return matched.isNotEmpty ? matched : _fallbackGifs;
  }

  /// Get Trending GIFs
  Future<List<GifItem>> getTrendingGifs({int limit = 24}) async {
    try {
      final url = Uri.parse(
        'https://tenor.googleapis.com/v2/featured?key=LIVDSRZULELA&client_key=gochat_app&limit=$limit&media_filter=gif,tinygif,nanogif',
      );
      final response = await http.get(url).timeout(const Duration(seconds: 4));

      if (response.statusCode == 200) {
        final data = jsonDecode(response.body) as Map<String, dynamic>;
        final results = data['results'] as List<dynamic>? ?? [];
        if (results.isNotEmpty) {
          return results
              .map((item) => GifItem.fromTenor(item as Map<String, dynamic>))
              .where((g) => g.previewUrl.isNotEmpty)
              .toList();
        }
      }
    } catch (e) {
      debugPrint('Tenor GIF trending failed: $e');
    }

    return _fallbackGifs;
  }
}
