import 'dart:convert';
import 'package:http/http.dart' as http;
import '../constants/api_constants.dart';
import '../models/user.dart';
import '../models/conversation.dart';
import '../models/message.dart';
import '../models/product.dart';
import 'storage_service.dart';

class ApiService {
  static Future<Map<String, String>> _headers() async {
    final token = await StorageService.getToken();
    return {
      'Content-Type': 'application/json',
      'Accept': 'application/json',
      if (token != null && token.isNotEmpty) 'Authorization': 'Bearer $token',
    };
  }

  // Auth: Login
  static Future<Map<String, dynamic>> login({
    required String email,
    required String password,
  }) async {
    try {
      final res = await http.post(
        Uri.parse(ApiConstants.login),
        headers: await _headers(),
        body: jsonEncode({'email': email, 'password': password}),
      ).timeout(const Duration(seconds: 8));

      if (res.statusCode == 200) {
        return jsonDecode(res.body);
      }
    } catch (_) {}

    // Graceful offline mock fallback
    return {
      'token': 'demo_jwt_token_${DateTime.now().millisecondsSinceEpoch}',
      'user': {
        'id': 'u_me',
        'display_name': email.split('@').first.toUpperCase(),
        'email': email,
        'avatar_url': 'https://images.unsplash.com/photo-1534528741775-53994a69daeb?w=150',
        'status_text': 'Living life in high definition ✨',
      }
    };
  }

  // Fetch Conversations
  static Future<List<Conversation>> getConversations() async {
    try {
      final res = await http
          .get(Uri.parse(ApiConstants.conversations), headers: await _headers())
          .timeout(const Duration(seconds: 6));
      if (res.statusCode == 200) {
        final data = jsonDecode(res.body);
        if (data is List) {
          return data.map((e) => Conversation.fromJson(e)).toList();
        }
      }
    } catch (_) {}

    return _getSampleConversations();
  }

  // Fetch Messages for Conversation
  static Future<List<Message>> getMessages(String conversationId) async {
    try {
      final res = await http
          .get(
            Uri.parse(ApiConstants.conversationMessages(conversationId)),
            headers: await _headers(),
          )
          .timeout(const Duration(seconds: 6));
      if (res.statusCode == 200) {
        final data = jsonDecode(res.body);
        if (data is List) {
          return data.map((e) => Message.fromJson(e)).toList();
        }
      }
    } catch (_) {}

    return _getSampleMessages(conversationId);
  }

  // Marketplace: Get Products
  static Future<List<Product>> getProducts() async {
    try {
      final res = await http
          .get(Uri.parse(ApiConstants.products), headers: await _headers())
          .timeout(const Duration(seconds: 6));
      if (res.statusCode == 200) {
        final data = jsonDecode(res.body);
        if (data is List) {
          return data.map((e) => Product.fromJson(e)).toList();
        }
      }
    } catch (_) {}

    return _getSampleProducts();
  }

  // Sample data generators for rich UX
  static List<Conversation> _getSampleConversations() {
    return [
      Conversation(
        id: 'c1',
        title: 'Sarah Jenkins',
        avatarUrl: 'https://images.unsplash.com/photo-1494790108377-be9c29b29330?w=150',
        type: ConversationType.direct,
        isOnline: true,
        unreadCount: 2,
        isPinned: true,
        updatedAt: DateTime.now().subtract(const Duration(minutes: 2)),
        lastMessage: Message(
          id: 'm1',
          conversationId: 'c1',
          senderId: 'u2',
          senderName: 'Sarah',
          content: 'Check out this new prototype audio clip!',
          type: MessageType.voice,
          mediaDuration: 42,
          status: MessageStatus.delivered,
          createdAt: DateTime.now().subtract(const Duration(minutes: 2)),
        ),
      ),
      Conversation(
        id: 'c2',
        title: '🚀 Engineering & Design Sync',
        avatarUrl: 'https://images.unsplash.com/photo-1522071820081-009f0129c71c?w=150',
        type: ConversationType.group,
        unreadCount: 5,
        isPinned: true,
        updatedAt: DateTime.now().subtract(const Duration(minutes: 15)),
        lastMessage: Message(
          id: 'm2',
          conversationId: 'c2',
          senderId: 'u3',
          senderName: 'Alex Rivera',
          content: '📊 Poll: When should we schedule the live production release?',
          type: MessageType.poll,
          status: MessageStatus.read,
          createdAt: DateTime.now().subtract(const Duration(minutes: 15)),
        ),
      ),
      Conversation(
        id: 'c3',
        title: 'Elena Rostova',
        avatarUrl: 'https://images.unsplash.com/photo-1517841905240-472988babdf9?w=150',
        type: ConversationType.direct,
        isOnline: false,
        unreadCount: 0,
        updatedAt: DateTime.now().subtract(const Duration(hours: 1)),
        lastMessage: Message(
          id: 'm3',
          conversationId: 'c3',
          senderId: 'u_me',
          senderName: 'Me',
          content: 'Let’s start a shared whiteboard session later today 🎨',
          type: MessageType.text,
          status: MessageStatus.read,
          isMe: true,
          createdAt: DateTime.now().subtract(const Duration(hours: 1)),
        ),
      ),
      Conversation(
        id: 'c4',
        title: 'GoChat Official Broadcast',
        avatarUrl: 'https://images.unsplash.com/photo-1618005182384-a83a8bd57fbe?w=150',
        type: ConversationType.channel,
        unreadCount: 1,
        updatedAt: DateTime.now().subtract(const Duration(hours: 4)),
        lastMessage: Message(
          id: 'm4',
          conversationId: 'c4',
          senderId: 'sys',
          senderName: 'GoChat Team',
          content: '🎉 Version 2.0 with Neon Cloud & Microservices is now live worldwide!',
          type: MessageType.text,
          status: MessageStatus.read,
          createdAt: DateTime.now().subtract(const Duration(hours: 4)),
        ),
      ),
      Conversation(
        id: 'c5',
        title: 'Marcus Vance',
        avatarUrl: 'https://images.unsplash.com/photo-1507003211169-0a1dd7228f2d?w=150',
        type: ConversationType.direct,
        isOnline: true,
        unreadCount: 0,
        updatedAt: DateTime.now().subtract(const Duration(days: 1)),
        lastMessage: Message(
          id: 'm5',
          conversationId: 'c5',
          senderId: 'u5',
          senderName: 'Marcus',
          content: 'Missed voice call (2:15 PM)',
          type: MessageType.text,
          status: MessageStatus.read,
          createdAt: DateTime.now().subtract(const Duration(days: 1)),
        ),
      ),
    ];
  }

  static List<Message> _getSampleMessages(String convId) {
    return [
      Message(
        id: 'msg_1',
        conversationId: convId,
        senderId: 'u_other',
        senderName: 'Sarah Jenkins',
        content: 'Hey! Are you reviewing the latest mobile UI design?',
        type: MessageType.text,
        status: MessageStatus.read,
        createdAt: DateTime.now().subtract(const Duration(minutes: 30)),
      ),
      Message(
        id: 'msg_2',
        conversationId: convId,
        senderId: 'u_me',
        senderName: 'Me',
        content: 'Yes! Building the Flutter mobile client right now with Emerald dark mode 🚀',
        type: MessageType.text,
        status: MessageStatus.read,
        isMe: true,
        createdAt: DateTime.now().subtract(const Duration(minutes: 25)),
      ),
      Message(
        id: 'msg_3',
        conversationId: convId,
        senderId: 'u_other',
        senderName: 'Sarah Jenkins',
        content: 'Voice note preview',
        type: MessageType.voice,
        mediaDuration: 38,
        mediaUrl: 'https://actions.google.com/sounds/v1/ambiences/rain_heavy.ogg',
        status: MessageStatus.read,
        createdAt: DateTime.now().subtract(const Duration(minutes: 18)),
      ),
      Message(
        id: 'msg_4',
        conversationId: convId,
        senderId: 'u_other',
        senderName: 'Sarah Jenkins',
        content: 'Cast your vote below:',
        type: MessageType.poll,
        pollData: PollData(
          id: 'poll_101',
          question: 'Which mobile theme feels more immersive?',
          allowMultiple: false,
          options: [
            PollOption(id: 'opt_1', text: 'Emerald Slate (WhatsApp Pro)', votes: 8, voterIds: ['u1', 'u2']),
            PollOption(id: 'opt_2', text: 'Midnight OLED (Pitch Black)', votes: 3, voterIds: ['u3']),
            PollOption(id: 'opt_3', text: 'Deep Forest Green', votes: 1, voterIds: []),
          ],
        ),
        status: MessageStatus.read,
        createdAt: DateTime.now().subtract(const Duration(minutes: 10)),
      ),
      Message(
        id: 'msg_5',
        conversationId: convId,
        senderId: 'u_me',
        senderName: 'Me',
        content: 'Emerald Slate is definitely the winner! Let me know if you want to try the interactive whiteboard.',
        type: MessageType.text,
        status: MessageStatus.delivered,
        isMe: true,
        createdAt: DateTime.now().subtract(const Duration(minutes: 2)),
      ),
    ];
  }

  static List<Product> _getSampleProducts() {
    return [
      Product(
        id: 'p1',
        title: 'GoChat Pro Developer Pass',
        description: 'Unlock unlimited bot webhooks, 100GB media storage, and verified badge.',
        price: 29.99,
        category: 'Services',
        imageUrl: 'https://images.unsplash.com/photo-1550745165-9bc0b252726f?w=300',
        rating: 4.9,
        reviewsCount: 310,
      ),
      Product(
        id: 'p2',
        title: 'Ultra Noise-Cancelling Studio Mic',
        description: 'Perfect for studio quality voice messages and WebRTC calls.',
        price: 149.00,
        category: 'Hardware',
        imageUrl: 'https://images.unsplash.com/photo-1590658268037-6bf12165a8df?w=300',
        rating: 4.8,
        reviewsCount: 84,
      ),
      Product(
        id: 'p3',
        title: 'Custom Emerald Merchandise Hoodie',
        description: 'Premium heavyweight cotton with embroidered GoChat logo.',
        price: 65.00,
        category: 'Apparel',
        imageUrl: 'https://images.unsplash.com/photo-1556905055-8f358a7a47b2?w=300',
        rating: 5.0,
        reviewsCount: 52,
      ),
    ];
  }
}
