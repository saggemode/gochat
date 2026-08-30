import 'package:flutter/material.dart';

enum BubbleShape {
  classic,
  glassmorphism,
  neonGlow,
  minimalFlat,
}

class ChatTheme {
  final String id;
  final String name;
  final List<Color> bgGradient;
  final Color? solidBgColor;
  final String? wallpaperImageUrl;
  final bool showDoodlePattern;
  final double doodleOpacity;
  final BubbleShape bubbleShape;
  final List<Color> senderGradient;
  final Color senderTextColor;
  final Color receiverColor;
  final Color receiverTextColor;
  final Color? neonGlowColor;

  const ChatTheme({
    required this.id,
    required this.name,
    required this.bgGradient,
    this.solidBgColor,
    this.wallpaperImageUrl,
    this.showDoodlePattern = true,
    this.doodleOpacity = 0.06,
    this.bubbleShape = BubbleShape.classic,
    required this.senderGradient,
    this.senderTextColor = Colors.white,
    required this.receiverColor,
    this.receiverTextColor = const Color(0xFFE9EDEF),
    this.neonGlowColor,
  });

  ChatTheme copyWith({
    String? id,
    String? name,
    List<Color>? bgGradient,
    Color? solidBgColor,
    String? wallpaperImageUrl,
    bool? showDoodlePattern,
    double? doodleOpacity,
    BubbleShape? bubbleShape,
    List<Color>? senderGradient,
    Color? senderTextColor,
    Color? receiverColor,
    Color? receiverTextColor,
    Color? neonGlowColor,
  }) {
    return ChatTheme(
      id: id ?? this.id,
      name: name ?? this.name,
      bgGradient: bgGradient ?? this.bgGradient,
      solidBgColor: solidBgColor ?? this.solidBgColor,
      wallpaperImageUrl: wallpaperImageUrl ?? this.wallpaperImageUrl,
      showDoodlePattern: showDoodlePattern ?? this.showDoodlePattern,
      doodleOpacity: doodleOpacity ?? this.doodleOpacity,
      bubbleShape: bubbleShape ?? this.bubbleShape,
      senderGradient: senderGradient ?? this.senderGradient,
      senderTextColor: senderTextColor ?? this.senderTextColor,
      receiverColor: receiverColor ?? this.receiverColor,
      receiverTextColor: receiverTextColor ?? this.receiverTextColor,
      neonGlowColor: neonGlowColor ?? this.neonGlowColor,
    );
  }

  // ── JSON Serialization ──────────────────────────────────────────────────────
  Map<String, dynamic> toJson() {
    return {
      'id': id,
      'name': name,
      'bgGradient': bgGradient.map((c) => c.toARGB32()).toList(),
      'solidBgColor': solidBgColor?.toARGB32(),
      'wallpaperImageUrl': wallpaperImageUrl,
      'showDoodlePattern': showDoodlePattern,
      'doodleOpacity': doodleOpacity,
      'bubbleShape': bubbleShape.name,
      'senderGradient': senderGradient.map((c) => c.toARGB32()).toList(),
      'senderTextColor': senderTextColor.toARGB32(),
      'receiverColor': receiverColor.toARGB32(),
      'receiverTextColor': receiverTextColor.toARGB32(),
      'neonGlowColor': neonGlowColor?.toARGB32(),
    };
  }

  factory ChatTheme.fromJson(Map<String, dynamic> json) {
    return ChatTheme(
      id: json['id'] ?? 'default',
      name: json['name'] ?? 'Default',
      bgGradient: (json['bgGradient'] as List?)
              ?.map((v) => Color(v as int))
              .toList() ??
          [const Color(0xFF111B21), const Color(0xFF0C1317)],
      solidBgColor: json['solidBgColor'] != null ? Color(json['solidBgColor']) : null,
      wallpaperImageUrl: json['wallpaperImageUrl'],
      showDoodlePattern: json['showDoodlePattern'] ?? true,
      doodleOpacity: (json['doodleOpacity'] as num?)?.toDouble() ?? 0.06,
      bubbleShape: BubbleShape.values.firstWhere(
        (e) => e.name == json['bubbleShape'],
        orElse: () => BubbleShape.classic,
      ),
      senderGradient: (json['senderGradient'] as List?)
              ?.map((v) => Color(v as int))
              .toList() ??
          [const Color(0xFF005C4B), const Color(0xFF008069)],
      senderTextColor: json['senderTextColor'] != null ? Color(json['senderTextColor']) : Colors.white,
      receiverColor: json['receiverColor'] != null ? Color(json['receiverColor']) : const Color(0xFF202C33),
      receiverTextColor: json['receiverTextColor'] != null ? Color(json['receiverTextColor']) : const Color(0xFFE9EDEF),
      neonGlowColor: json['neonGlowColor'] != null ? Color(json['neonGlowColor']) : null,
    );
  }

  // ── Curated Presets ─────────────────────────────────────────────────────────
  static const ChatTheme defaultEmerald = ChatTheme(
    id: 'default',
    name: 'GoChat Emerald',
    bgGradient: [Color(0xFF111B21), Color(0xFF0B141A)],
    showDoodlePattern: true,
    doodleOpacity: 0.06,
    bubbleShape: BubbleShape.classic,
    senderGradient: [Color(0xFF005C4B), Color(0xFF008069)],
    senderTextColor: Colors.white,
    receiverColor: Color(0xFF202C33),
    receiverTextColor: Color(0xFFE9EDEF),
  );

  static const ChatTheme cyberpunkNeon = ChatTheme(
    id: 'cyberpunk',
    name: 'Cyberpunk Neon',
    bgGradient: [Color(0xFF0D0221), Color(0xFF05010D)],
    showDoodlePattern: true,
    doodleOpacity: 0.08,
    bubbleShape: BubbleShape.neonGlow,
    senderGradient: [Color(0xFF8B5CF6), Color(0xFFEC4899)],
    senderTextColor: Colors.white,
    receiverColor: Color(0xFF19122E),
    receiverTextColor: Color(0xFFF3E8FF),
    neonGlowColor: Color(0xFFEC4899),
  );

  static const ChatTheme emeraldGlass = ChatTheme(
    id: 'emerald_glass',
    name: 'Emerald Glass',
    bgGradient: [Color(0xFF0A231C), Color(0xFF03140F)],
    showDoodlePattern: true,
    doodleOpacity: 0.07,
    bubbleShape: BubbleShape.glassmorphism,
    senderGradient: [Color(0xFF059669), Color(0xFF10B981)],
    senderTextColor: Colors.white,
    receiverColor: Color(0xFF132A24),
    receiverTextColor: Color(0xFFD1FAE5),
    neonGlowColor: Color(0xFF10B981),
  );

  static const ChatTheme sunsetAurora = ChatTheme(
    id: 'sunset_aurora',
    name: 'Sunset Aurora',
    bgGradient: [Color(0xFF200F21), Color(0xFF0F0612)],
    showDoodlePattern: true,
    doodleOpacity: 0.06,
    bubbleShape: BubbleShape.glassmorphism,
    senderGradient: [Color(0xFFF97316), Color(0xFFEF4444)],
    senderTextColor: Colors.white,
    receiverColor: Color(0xFF2C182B),
    receiverTextColor: Color(0xFFFFEDD5),
    neonGlowColor: Color(0xFFF97316),
  );

  static const ChatTheme midnightOled = ChatTheme(
    id: 'midnight_oled',
    name: 'Midnight OLED',
    bgGradient: [Color(0xFF000000), Color(0xFF000000)],
    showDoodlePattern: false,
    bubbleShape: BubbleShape.minimalFlat,
    senderGradient: [Color(0xFF1F2937), Color(0xFF374151)],
    senderTextColor: Color(0xFFF9FAFB),
    receiverColor: Color(0xFF111827),
    receiverTextColor: Color(0xFFE5E7EB),
  );

  static const ChatTheme nordicIce = ChatTheme(
    id: 'nordic_ice',
    name: 'Nordic Ice',
    bgGradient: [Color(0xFF0F172A), Color(0xFF020617)],
    showDoodlePattern: true,
    doodleOpacity: 0.05,
    bubbleShape: BubbleShape.glassmorphism,
    senderGradient: [Color(0xFF0284C7), Color(0xFF38BDF8)],
    senderTextColor: Colors.white,
    receiverColor: Color(0xFF1E293B),
    receiverTextColor: Color(0xFFE0F2FE),
    neonGlowColor: Color(0xFF38BDF8),
  );

  static const ChatTheme lavenderDream = ChatTheme(
    id: 'lavender_dream',
    name: 'Lavender Dream',
    bgGradient: [Color(0xFF1E1035), Color(0xFF0F071D)],
    showDoodlePattern: true,
    doodleOpacity: 0.06,
    bubbleShape: BubbleShape.glassmorphism,
    senderGradient: [Color(0xFF7C3AED), Color(0xFFA78BFA)],
    senderTextColor: Colors.white,
    receiverColor: Color(0xFF2A1C44),
    receiverTextColor: Color(0xFFEDE9FE),
    neonGlowColor: Color(0xFFA78BFA),
  );

  static const ChatTheme matrixLime = ChatTheme(
    id: 'matrix_lime',
    name: 'Cyber Matrix',
    bgGradient: [Color(0xFF041208), Color(0xFF000502)],
    showDoodlePattern: true,
    doodleOpacity: 0.1,
    bubbleShape: BubbleShape.neonGlow,
    senderGradient: [Color(0xFF15803D), Color(0xFF22C55E)],
    senderTextColor: Colors.black,
    receiverColor: Color(0xFF0D2814),
    receiverTextColor: Color(0xFFDCFCE7),
    neonGlowColor: Color(0xFF22C55E),
  );

  static const List<ChatTheme> presets = [
    defaultEmerald,
    cyberpunkNeon,
    emeraldGlass,
    sunsetAurora,
    midnightOled,
    nordicIce,
    lavenderDream,
    matrixLime,
  ];
}
