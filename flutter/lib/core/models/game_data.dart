/// Represents the state of an interactive in-chat mini-game (Tic-Tac-Toe or Connect 4).
class GameData {
  final String gameId;
  final String gameType; // 'tictactoe' or 'connect4'
  final String gameMode; // 'pvp' (multiplayer) or 'ai' (vs computer)
  final String aiDifficulty; // 'easy', 'medium', 'hard'
  final List<String> board; // Cell values ('', 'X', 'O' or '', 'R', 'Y')
  final String player1Id;
  final String player1Name;
  final String player1Symbol;
  final String player2Id; // 'ai' or user_id
  final String player2Name;
  final String player2Symbol;
  final String currentTurnId;
  final String status; // 'active', 'won', 'draw'
  final String? winnerId;
  final String? winnerName;
  final List<int> winningLine;

  GameData({
    required this.gameId,
    required this.gameType,
    this.gameMode = 'pvp',
    this.aiDifficulty = 'medium',
    required this.board,
    required this.player1Id,
    required this.player1Name,
    this.player1Symbol = 'X',
    required this.player2Id,
    required this.player2Name,
    this.player2Symbol = 'O',
    required this.currentTurnId,
    this.status = 'active',
    this.winnerId,
    this.winnerName,
    List<int>? winningLine,
  }) : winningLine = winningLine ?? [];

  /// Helper factory to initialize a fresh Tic-Tac-Toe game
  factory GameData.newTicTacToe({
    required String player1Id,
    required String player1Name,
    required String player2Id,
    required String player2Name,
    String gameMode = 'pvp',
    String aiDifficulty = 'medium',
  }) {
    return GameData(
      gameId: 'game_${DateTime.now().millisecondsSinceEpoch}',
      gameType: 'tictactoe',
      gameMode: gameMode,
      aiDifficulty: aiDifficulty,
      board: List.filled(9, ''),
      player1Id: player1Id,
      player1Name: player1Name,
      player1Symbol: 'X',
      player2Id: player2Id,
      player2Name: player2Name,
      player2Symbol: 'O',
      currentTurnId: player1Id,
      status: 'active',
    );
  }

  /// Helper factory to initialize a fresh Connect 4 game (7 cols x 6 rows = 42 cells)
  factory GameData.newConnect4({
    required String player1Id,
    required String player1Name,
    required String player2Id,
    required String player2Name,
    String gameMode = 'pvp',
    String aiDifficulty = 'medium',
  }) {
    return GameData(
      gameId: 'game_${DateTime.now().millisecondsSinceEpoch}',
      gameType: 'connect4',
      gameMode: gameMode,
      aiDifficulty: aiDifficulty,
      board: List.filled(42, ''),
      player1Id: player1Id,
      player1Name: player1Name,
      player1Symbol: '🔴',
      player2Id: player2Id,
      player2Name: player2Name,
      player2Symbol: '🟡',
      currentTurnId: player1Id,
      status: 'active',
    );
  }

  bool get isAiGame => gameMode == 'ai' || player2Id == 'ai';
  bool get isActive => status == 'active';
  bool get isGameOver => status == 'won' || status == 'draw';

  GameData copyWith({
    String? gameId,
    String? gameType,
    String? gameMode,
    String? aiDifficulty,
    List<String>? board,
    String? player1Id,
    String? player1Name,
    String? player1Symbol,
    String? player2Id,
    String? player2Name,
    String? player2Symbol,
    String? currentTurnId,
    String? status,
    String? winnerId,
    String? winnerName,
    List<int>? winningLine,
  }) {
    return GameData(
      gameId: gameId ?? this.gameId,
      gameType: gameType ?? this.gameType,
      gameMode: gameMode ?? this.gameMode,
      aiDifficulty: aiDifficulty ?? this.aiDifficulty,
      board: board ?? List.from(this.board),
      player1Id: player1Id ?? this.player1Id,
      player1Name: player1Name ?? this.player1Name,
      player1Symbol: player1Symbol ?? this.player1Symbol,
      player2Id: player2Id ?? this.player2Id,
      player2Name: player2Name ?? this.player2Name,
      player2Symbol: player2Symbol ?? this.player2Symbol,
      currentTurnId: currentTurnId ?? this.currentTurnId,
      status: status ?? this.status,
      winnerId: winnerId ?? this.winnerId,
      winnerName: winnerName ?? this.winnerName,
      winningLine: winningLine ?? List.from(this.winningLine),
    );
  }

  factory GameData.fromJson(Map<String, dynamic> json) {
    return GameData(
      gameId: json['game_id']?.toString() ?? '',
      gameType: json['game_type']?.toString() ?? 'tictactoe',
      gameMode: json['game_mode']?.toString() ?? 'pvp',
      aiDifficulty: json['ai_difficulty']?.toString() ?? 'medium',
      board: (json['board'] as List?)?.map((e) => e.toString()).toList() ??
          (json['game_type'] == 'connect4' ? List.filled(42, '') : List.filled(9, '')),
      player1Id: json['player1_id']?.toString() ?? '',
      player1Name: json['player1_name']?.toString() ?? 'Player 1',
      player1Symbol: json['player1_symbol']?.toString() ?? (json['game_type'] == 'connect4' ? '🔴' : 'X'),
      player2Id: json['player2_id']?.toString() ?? 'ai',
      player2Name: json['player2_name']?.toString() ?? 'Opponent',
      player2Symbol: json['player2_symbol']?.toString() ?? (json['game_type'] == 'connect4' ? '🟡' : 'O'),
      currentTurnId: json['current_turn_id']?.toString() ?? '',
      status: json['status']?.toString() ?? 'active',
      winnerId: json['winner_id']?.toString(),
      winnerName: json['winner_name']?.toString(),
      winningLine: (json['winning_line'] as List?)?.map((e) => (e as num).toInt()).toList() ?? [],
    );
  }

  Map<String, dynamic> toJson() {
    return {
      'game_id': gameId,
      'game_type': gameType,
      'game_mode': gameMode,
      'ai_difficulty': aiDifficulty,
      'board': board,
      'player1_id': player1Id,
      'player1_name': player1Name,
      'player1_symbol': player1Symbol,
      'player2_id': player2Id,
      'player2_name': player2Name,
      'player2_symbol': player2Symbol,
      'current_turn_id': currentTurnId,
      'status': status,
      'winner_id': winnerId,
      'winner_name': winnerName,
      'winning_line': winningLine,
    };
  }
}
