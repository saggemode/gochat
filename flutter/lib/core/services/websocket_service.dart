import 'dart:async';
import 'dart:convert';
import 'package:web_socket_channel/web_socket_channel.dart';
import '../constants/api_constants.dart';
import 'api_service.dart';
import 'storage_service.dart';

typedef WebSocketMessageCallback = void Function(Map<String, dynamic> data);

class WebSocketService {
  WebSocketChannel? _channel;
  bool _isConnected = false;
  final List<WebSocketMessageCallback> _listeners = [];
  Timer? _reconnectTimer;
  int _reconnectAttempts = 0;

  bool get isConnected => _isConnected;

  void addListener(WebSocketMessageCallback callback) {
    _listeners.add(callback);
  }

  void removeListener(WebSocketMessageCallback callback) {
    _listeners.remove(callback);
  }

  Future<void> connect() async {
    if (_isConnected) return;

    try {
      // 1. Ensure token is a valid JWT before attempting handshake
      var token = await StorageService.getToken();
      if (!ApiService.isValidJwt(token)) {
        token = await ApiService.ensureValidToken();
      }

      if (token == null || !ApiService.isValidJwt(token)) {
        // No valid auth credentials available yet; pause reconnecting until user logs in
        return;
      }

      final uri = Uri.parse(ApiConstants.wsUrl).replace(
        queryParameters: {
          'token': token,
        },
      );

      _channel = WebSocketChannel.connect(uri);
      await _channel!.ready;
      _isConnected = true;
      _reconnectAttempts = 0;

      _channel?.stream.listen(
        (message) {
          try {
            final data = jsonDecode(message.toString());
            if (data is Map<String, dynamic>) {
              for (final listener in _listeners) {
                listener(data);
              }
            }
          } catch (_) {}
        },
        onError: (_) {
          _handleDisconnect(needsReauth: true);
        },
        onDone: () {
          _handleDisconnect();
        },
        cancelOnError: true,
      );
    } catch (_) {
      _handleDisconnect(needsReauth: true);
    }
  }

  void send(Map<String, dynamic> payload) {
    if (_isConnected && _channel != null) {
      try {
        _channel?.sink.add(jsonEncode(payload));
      } catch (_) {}
    }
  }

  void _handleDisconnect({bool needsReauth = false}) {
    _isConnected = false;
    _channel = null;
    _reconnectTimer?.cancel();

    _reconnectAttempts++;
    final delaySeconds = (_reconnectAttempts > 5) ? 15 : 5;

    _reconnectTimer = Timer(Duration(seconds: delaySeconds), () async {
      if (needsReauth) {
        await ApiService.ensureValidToken();
      }
      connect();
    });
  }

  void disconnect() {
    _reconnectTimer?.cancel();
    _channel?.sink.close();
    _isConnected = false;
    _channel = null;
    _reconnectAttempts = 0;
  }
}
