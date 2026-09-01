import 'dart:math';

/// Game logic and AI engine for in-chat mini-games (Tic-Tac-Toe & Connect 4).
/// Provides turn validation, win detection, and multi-difficulty AI opponents.
class AIGameEngine {
  static final Random _random = Random();

  // ═══════════════════════════════════════════════════════════════════════════
  //  TIC-TAC-TOE ENGINE
  // ═══════════════════════════════════════════════════════════════════════════

  static const List<List<int>> _tttWinningCombinations = [
    [0, 1, 2], [3, 4, 5], [6, 7, 8], // Rows
    [0, 3, 6], [1, 4, 7], [2, 5, 8], // Columns
    [0, 4, 8], [2, 4, 6],             // Diagonals
  ];

  /// Checks if a player has won Tic-Tac-Toe
  static ({bool hasWon, List<int> line}) checkTicTacToeWin(List<String> board, String symbol) {
    for (final comb in _tttWinningCombinations) {
      if (board[comb[0]] == symbol &&
          board[comb[1]] == symbol &&
          board[comb[2]] == symbol) {
        return (hasWon: true, line: comb);
      }
    }
    return (hasWon: false, line: <int>[]);
  }

  /// Calculates the next AI move for Tic-Tac-Toe based on difficulty
  static int getTicTacToeAiMove(List<String> board, String aiSymbol, String playerSymbol, String difficulty) {
    final available = <int>[];
    for (int i = 0; i < 9; i++) {
      if (board[i].isEmpty) available.add(i);
    }
    if (available.isEmpty) return -1;

    if (difficulty == 'easy') {
      return available[_random.nextInt(available.length)];
    }

    if (difficulty == 'medium') {
      // 1. Can AI win immediately?
      for (final idx in available) {
        final testBoard = List<String>.from(board);
        testBoard[idx] = aiSymbol;
        if (checkTicTacToeWin(testBoard, aiSymbol).hasWon) return idx;
      }
      // 2. Must AI block opponent from winning?
      for (final idx in available) {
        final testBoard = List<String>.from(board);
        testBoard[idx] = playerSymbol;
        if (checkTicTacToeWin(testBoard, playerSymbol).hasWon) return idx;
      }
      // 3. Take center if free
      if (board[4].isEmpty) return 4;
      // 4. Otherwise random corner or side
      final corners = [0, 2, 6, 8].where((i) => board[i].isEmpty).toList();
      if (corners.isNotEmpty) return corners[_random.nextInt(corners.length)];
      return available[_random.nextInt(available.length)];
    }

    // Hard / Master: Unbeatable Minimax
    int bestScore = -1000;
    int bestMove = available.first;

    for (final idx in available) {
      final testBoard = List<String>.from(board);
      testBoard[idx] = aiSymbol;
      final score = _tttMinimax(testBoard, 0, false, aiSymbol, playerSymbol);
      if (score > bestScore) {
        bestScore = score;
        bestMove = idx;
      }
    }
    return bestMove;
  }

  static int _tttMinimax(List<String> board, int depth, bool isMaximizing, String aiSymbol, String playerSymbol) {
    if (checkTicTacToeWin(board, aiSymbol).hasWon) return 10 - depth;
    if (checkTicTacToeWin(board, playerSymbol).hasWon) return depth - 10;
    if (!board.contains('')) return 0; // Draw

    if (isMaximizing) {
      int maxEval = -1000;
      for (int i = 0; i < 9; i++) {
        if (board[i].isEmpty) {
          board[i] = aiSymbol;
          final eval = _tttMinimax(board, depth + 1, false, aiSymbol, playerSymbol);
          board[i] = '';
          maxEval = max(maxEval, eval);
        }
      }
      return maxEval;
    } else {
      int minEval = 1000;
      for (int i = 0; i < 9; i++) {
        if (board[i].isEmpty) {
          board[i] = playerSymbol;
          final eval = _tttMinimax(board, depth + 1, true, aiSymbol, playerSymbol);
          board[i] = '';
          minEval = min(minEval, eval);
        }
      }
      return minEval;
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  //  CONNECT 4 ENGINE (7 columns x 6 rows = 42 cells)
  // ═══════════════════════════════════════════════════════════════════════════

  static const int c4Cols = 7;
  static const int c4Rows = 6;

  /// Gets the lowest open row in a Connect 4 column (-1 if column is full)
  static int getConnect4AvailableRow(List<String> board, int col) {
    if (col < 0 || col >= c4Cols) return -1;
    for (int row = c4Rows - 1; row >= 0; row--) {
      final idx = row * c4Cols + col;
      if (board[idx].isEmpty) return row;
    }
    return -1;
  }

  /// Checks if a player has won Connect 4 (4 in a row)
  static ({bool hasWon, List<int> line}) checkConnect4Win(List<String> board, String symbol) {
    // 1. Horizontal
    for (int r = 0; r < c4Rows; r++) {
      for (int c = 0; c <= c4Cols - 4; c++) {
        final line = [r * c4Cols + c, r * c4Cols + c + 1, r * c4Cols + c + 2, r * c4Cols + c + 3];
        if (line.every((i) => board[i] == symbol)) {
          return (hasWon: true, line: line);
        }
      }
    }

    // 2. Vertical
    for (int r = 0; r <= c4Rows - 4; r++) {
      for (int c = 0; c < c4Cols; c++) {
        final line = [r * c4Cols + c, (r + 1) * c4Cols + c, (r + 2) * c4Cols + c, (r + 3) * c4Cols + c];
        if (line.every((i) => board[i] == symbol)) {
          return (hasWon: true, line: line);
        }
      }
    }

    // 3. Diagonal Down-Right (\)
    for (int r = 0; r <= c4Rows - 4; r++) {
      for (int c = 0; c <= c4Cols - 4; c++) {
        final line = [r * c4Cols + c, (r + 1) * c4Cols + c + 1, (r + 2) * c4Cols + c + 2, (r + 3) * c4Cols + c + 3];
        if (line.every((i) => board[i] == symbol)) {
          return (hasWon: true, line: line);
        }
      }
    }

    // 4. Diagonal Up-Right (/)
    for (int r = 3; r < c4Rows; r++) {
      for (int c = 0; c <= c4Cols - 4; c++) {
        final line = [r * c4Cols + c, (r - 1) * c4Cols + c + 1, (r - 2) * c4Cols + c + 2, (r - 3) * c4Cols + c + 3];
        if (line.every((i) => board[i] == symbol)) {
          return (hasWon: true, line: line);
        }
      }
    }

    return (hasWon: false, line: <int>[]);
  }

  /// Calculates the next AI move column for Connect 4 based on difficulty
  static int getConnect4AiMove(List<String> board, String aiSymbol, String playerSymbol, String difficulty) {
    final validCols = <int>[];
    for (int c = 0; c < c4Cols; c++) {
      if (getConnect4AvailableRow(board, c) != -1) {
        validCols.add(c);
      }
    }
    if (validCols.isEmpty) return -1;

    if (difficulty == 'easy') {
      return validCols[_random.nextInt(validCols.length)];
    }

    // Medium: Win or block immediate 1-move win, favor center
    // 1. Can AI win on this turn?
    for (final c in validCols) {
      final r = getConnect4AvailableRow(board, c);
      final idx = r * c4Cols + c;
      final testBoard = List<String>.from(board);
      testBoard[idx] = aiSymbol;
      if (checkConnect4Win(testBoard, aiSymbol).hasWon) return c;
    }

    // 2. Can player win on their next turn? Block them!
    for (final c in validCols) {
      final r = getConnect4AvailableRow(board, c);
      final idx = r * c4Cols + c;
      final testBoard = List<String>.from(board);
      testBoard[idx] = playerSymbol;
      if (checkConnect4Win(testBoard, playerSymbol).hasWon) return c;
    }

    if (difficulty == 'medium') {
      // Prioritize center columns (3, 2, 4, 1, 5, 0, 6)
      const colPreference = [3, 2, 4, 1, 5, 0, 6];
      for (final c in colPreference) {
        if (validCols.contains(c)) return c;
      }
      return validCols[_random.nextInt(validCols.length)];
    }

    // Hard / Master: Heuristic column scoring + lookahead
    int bestScore = -99999;
    int bestCol = validCols.first;

    for (final c in validCols) {
      final r = getConnect4AvailableRow(board, c);
      final idx = r * c4Cols + c;
      final testBoard = List<String>.from(board);
      testBoard[idx] = aiSymbol;

      // Heuristic score
      int score = _scoreConnect4Position(testBoard, aiSymbol, playerSymbol);
      // Extra bonus for center
      if (c == 3) score += 6;
      if (c == 2 || c == 4) score += 3;

      if (score > bestScore) {
        bestScore = score;
        bestCol = c;
      }
    }
    return bestCol;
  }

  static int _scoreConnect4Position(List<String> board, String aiSymbol, String playerSymbol) {
    int score = 0;
    // Score center column pieces
    for (int r = 0; r < c4Rows; r++) {
      if (board[r * c4Cols + 3] == aiSymbol) score += 3;
    }
    return score;
  }
}
