class ApiConstants {
  // Live Render Gateway and local development fallback
  static const String prodBaseUrl = 'https://gochat-kvpj.onrender.com';
  static const String localBaseUrl = 'http://localhost:8080';
  static const String androidEmulatorBaseUrl = 'http://10.0.2.2:8080';

  // Active default API base URL
  static const String baseUrl = prodBaseUrl;
  static const String apiV1 = '$baseUrl/api/v1';

  // Auth endpoints
  static const String register = '$apiV1/auth/register';
  static const String login = '$apiV1/auth/login';
  static const String refresh = '$apiV1/auth/refresh';
  static const String requestOtp = '$apiV1/auth/otp/request';
  static const String verifyOtp = '$apiV1/auth/otp/verify';

  // Chat & Messaging endpoints
  static const String conversations = '$apiV1/chat/conversations';
  static String conversationMessages(String convId) => '$apiV1/chat/conversations/$convId/messages';
  static String pollVote(String pollId) => '$apiV1/chat/polls/$pollId/vote';

  // Stories & Status endpoints
  static const String stories = '$apiV1/stories';
  static const String myStories = '$apiV1/stories/my';

  // Channels endpoints
  static const String channels = '$apiV1/channels';
  static String channelFeed(String channelId) => '$apiV1/channels/$channelId/feed';

  // Business & Merchant endpoints
  static const String businessProfile = '$apiV1/business/profile';
  static const String businessProducts = '$apiV1/business/products';
  static const String businessOrders = '$apiV1/business/orders';
  static const String businessCoupons = '$apiV1/business/coupons';

  // Marketplace & Store endpoints
  static const String products = '$apiV1/marketplace/products';
  static const String categories = '$apiV1/marketplace/categories';
  static const String cart = '$apiV1/marketplace/cart';
  static const String orders = '$apiV1/marketplace/orders';
  static const String wishlist = '$apiV1/marketplace/wishlist';
  static const String validateCoupon = '$apiV1/marketplace/coupons/validate';

  // WebSocket URL
  static String get wsUrl {
    final wsProtocol = baseUrl.startsWith('https') ? 'wss' : 'ws';
    final host = baseUrl.replaceFirst('https://', '').replaceFirst('http://', '');
    return '$wsProtocol://$host/ws';
  }
}
