import 'dart:async';
import 'dart:convert';
import 'package:web_socket_channel/web_socket_channel.dart';
import '../constants/api_constants.dart';
import 'storage_service.dart';

typedef WebSocketMessageCallback = void Function(Map<String, dynamic> data);

class WebSocketService {
  WebSocketChannel? _channel;
  bool _isConnected = false;
  final List<WebSocketMessageCallback> _listeners = [];
  Timer? _reconnectTimer;

  bool get isConnected => _isConnected;

  void addListener(WebSocketMessageCallback callback) {
    _listeners.add(callback);
  }

  void removeListener(WebSocketMessageCallback callback) {
    _listeners.remove(callback);
  }

  Future<void> connect() async {
    if (_isConnected) return;

    final token = await StorageService.getToken();
    final uri = Uri.parse(ApiConstants.wsUrl).replace(
      queryParameters: {
        if (token != null && token.isNotEmpty) 'token': token,
      },
    );

    try {
      _channel = WebSocketChannel.connect(uri);
      _isConnected = true;

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
        onError: (err) {
          _handleDisconnect();
        },
        onDone: () {
          _handleDisconnect();
        },
      );
    } catch (_) {
      _handleDisconnect();
    }
  }

  void send(Map<String, dynamic> payload) {
    if (_isConnected && _channel != null) {
      try {
        _channel?.sink.add(jsonEncode(payload));
      } catch (_) {}
    }
  }

  void _handleDisconnect() {
    _isConnected = false;
    _channel = null;
    _reconnectTimer?.cancel();
    _reconnectTimer = Timer(const Duration(seconds: 5), () {
      connect();
    });
  }

  void disconnect() {
    _reconnectTimer?.cancel();
    _channel?.sink.close();
    _isConnected = false;
    _channel = null;
  }
}
