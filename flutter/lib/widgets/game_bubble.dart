import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../core/models/game_data.dart';
import '../core/models/message.dart';
import '../core/services/ai_game_engine.dart';
import '../core/theme/app_theme.dart';

/// Interactive in-chat game bubble supporting live Tic-Tac-Toe and Connect 4
/// with peer-to-peer multiplayer and multi-level AI bots.
class GameBubble extends StatefulWidget {
  final Message message;
  final String currentUserId;
  final bool isMe;
  final Function(GameData updatedGame)? onUpdateGame;
  final VoidCallback? onRematch;

  const GameBubble({
    super.key,
    required this.message,
    required this.currentUserId,
    required this.isMe,
    this.onUpdateGame,
    this.onRematch,
  });

  @override
  State<GameBubble> createState() => _GameBubbleState();
}

class _GameBubbleState extends State<GameBubble> with SingleTickerProviderStateMixin {
  late GameData _game;
  bool _isAiThinking = false;

  @override
  void initState() {
    super.initState();
    _game = widget.message.gameData ??
        GameData.newTicTacToe(
          player1Id: widget.currentUserId,
          player1Name: 'Player',
          player2Id: 'ai',
          player2Name: 'Computer',
        );
  }

  @override
  void didUpdateWidget(covariant GameBubble oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (widget.message.gameData != null) {
      _game = widget.message.gameData!;
    }
  }

  bool get _isMyTurn => _game.isActive && _game.currentTurnId == widget.currentUserId;

  void _handleCellTap(int index) {
    if (!_isMyTurn || _isAiThinking) return;
    if (_game.gameType == 'tictactoe') {
      _playTicTacToeMove(index);
    } else if (_game.gameType == 'connect4') {
      final col = index % 7;
      _playConnect4Move(col);
    }
  }

  void _playTicTacToeMove(int index) {
    if (_game.board[index].isNotEmpty) return;

    HapticFeedback.mediumImpact();
    final isPlayer1 = widget.currentUserId == _game.player1Id;
    final mySymbol = isPlayer1 ? _game.player1Symbol : _game.player2Symbol;
    final opponentId = isPlayer1 ? _game.player2Id : _game.player1Id;

    final newBoard = List<String>.from(_game.board);
    newBoard[index] = mySymbol;

    // Check Win
    final winCheck = AIGameEngine.checkTicTacToeWin(newBoard, mySymbol);
    if (winCheck.hasWon) {
      final updated = _game.copyWith(
        board: newBoard,
        status: 'won',
        winnerId: widget.currentUserId,
        winnerName: isPlayer1 ? _game.player1Name : _game.player2Name,
        winningLine: winCheck.line,
      );
      _publishUpdate(updated);
      return;
    }

    // Check Draw
    if (!newBoard.contains('')) {
      final updated = _game.copyWith(
        board: newBoard,
        status: 'draw',
      );
      _publishUpdate(updated);
      return;
    }

    // Pass turn
    final updated = _game.copyWith(
      board: newBoard,
      currentTurnId: opponentId,
    );
    _publishUpdate(updated);

    // If opponent is AI, trigger AI response after brief delay
    if (_game.isAiGame && opponentId == 'ai') {
      _triggerAiTicTacToeMove(updated);
    }
  }

  void _triggerAiTicTacToeMove(GameData state) {
    setState(() => _isAiThinking = true);

    Timer(const Duration(milliseconds: 550), () {
      if (!mounted) return;
      final aiSymbol = state.player2Symbol;
      final playerSymbol = state.player1Symbol;

      final move = AIGameEngine.getTicTacToeAiMove(
        state.board,
        aiSymbol,
        playerSymbol,
        state.aiDifficulty,
      );

      if (move != -1) {
        final newBoard = List<String>.from(state.board);
        newBoard[move] = aiSymbol;

        final winCheck = AIGameEngine.checkTicTacToeWin(newBoard, aiSymbol);
        if (winCheck.hasWon) {
          final finished = state.copyWith(
            board: newBoard,
            status: 'won',
            winnerId: 'ai',
            winnerName: state.player2Name,
            winningLine: winCheck.line,
          );
          _publishUpdate(finished);
        } else if (!newBoard.contains('')) {
          final finished = state.copyWith(
            board: newBoard,
            status: 'draw',
          );
          _publishUpdate(finished);
        } else {
          final nextTurn = state.copyWith(
            board: newBoard,
            currentTurnId: state.player1Id,
          );
          _publishUpdate(nextTurn);
        }
      }
      setState(() => _isAiThinking = false);
    });
  }

  void _playConnect4Move(int col) {
    final row = AIGameEngine.getConnect4AvailableRow(_game.board, col);
    if (row == -1) return; // Column full

    HapticFeedback.mediumImpact();
    final isPlayer1 = widget.currentUserId == _game.player1Id;
    final mySymbol = isPlayer1 ? _game.player1Symbol : _game.player2Symbol;
    final opponentId = isPlayer1 ? _game.player2Id : _game.player1Id;

    final cellIdx = row * 7 + col;
    final newBoard = List<String>.from(_game.board);
    newBoard[cellIdx] = mySymbol;

    // Check Win
    final winCheck = AIGameEngine.checkConnect4Win(newBoard, mySymbol);
    if (winCheck.hasWon) {
      final updated = _game.copyWith(
        board: newBoard,
        status: 'won',
        winnerId: widget.currentUserId,
        winnerName: isPlayer1 ? _game.player1Name : _game.player2Name,
        winningLine: winCheck.line,
      );
      _publishUpdate(updated);
      return;
    }

    // Check Draw
    if (!newBoard.contains('')) {
      final updated = _game.copyWith(
        board: newBoard,
        status: 'draw',
      );
      _publishUpdate(updated);
      return;
    }

    // Pass turn
    final updated = _game.copyWith(
      board: newBoard,
      currentTurnId: opponentId,
    );
    _publishUpdate(updated);

    // If opponent is AI, trigger AI response
    if (_game.isAiGame && opponentId == 'ai') {
      _triggerAiConnect4Move(updated);
    }
  }

  void _triggerAiConnect4Move(GameData state) {
    setState(() => _isAiThinking = true);

    Timer(const Duration(milliseconds: 650), () {
      if (!mounted) return;
      final aiSymbol = state.player2Symbol;
      final playerSymbol = state.player1Symbol;

      final col = AIGameEngine.getConnect4AiMove(
        state.board,
        aiSymbol,
        playerSymbol,
        state.aiDifficulty,
      );

      if (col != -1) {
        final row = AIGameEngine.getConnect4AvailableRow(state.board, col);
        if (row != -1) {
          final cellIdx = row * 7 + col;
          final newBoard = List<String>.from(state.board);
          newBoard[cellIdx] = aiSymbol;

          final winCheck = AIGameEngine.checkConnect4Win(newBoard, aiSymbol);
          if (winCheck.hasWon) {
            final finished = state.copyWith(
              board: newBoard,
              status: 'won',
              winnerId: 'ai',
              winnerName: state.player2Name,
              winningLine: winCheck.line,
            );
            _publishUpdate(finished);
          } else if (!newBoard.contains('')) {
            final finished = state.copyWith(
              board: newBoard,
              status: 'draw',
            );
            _publishUpdate(finished);
          } else {
            final nextTurn = state.copyWith(
              board: newBoard,
              currentTurnId: state.player1Id,
            );
            _publishUpdate(nextTurn);
          }
        }
      }
      setState(() => _isAiThinking = false);
    });
  }

  void _publishUpdate(GameData updated) {
    setState(() => _game = updated);
    widget.onUpdateGame?.call(updated);
  }

  @override
  Widget build(BuildContext context) {
    final isDark = Theme.of(context).brightness == Brightness.dark;
    final isTtt = _game.gameType == 'tictactoe';

    return Container(
      width: isTtt ? 260 : 300,
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: isDark ? AppTheme.darkCard : Colors.white,
        borderRadius: BorderRadius.circular(16),
        border: Border.all(
          color: AppTheme.primary.withValues(alpha: 0.35),
          width: 1.2,
        ),
        boxShadow: [
          BoxShadow(
            color: AppTheme.primary.withValues(alpha: 0.08),
            blurRadius: 10,
            offset: const Offset(0, 2),
          ),
        ],
      ),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          // ── Header: Game Title & Mode Badge ───────────────────────────────
          Row(
            children: [
              Text(
                isTtt ? '⭕ Tic-Tac-Toe' : '🔴 Connect 4',
                style: const TextStyle(fontWeight: FontWeight.bold, fontSize: 14),
              ),
              const Spacer(),
              Container(
                padding: const EdgeInsets.symmetric(horizontal: 7, vertical: 3),
                decoration: BoxDecoration(
                  color: _game.isAiGame
                      ? (_game.aiDifficulty == 'hard'
                          ? Colors.redAccent.withValues(alpha: 0.2)
                          : (_game.aiDifficulty == 'medium'
                              ? Colors.amber.withValues(alpha: 0.2)
                              : Colors.green.withValues(alpha: 0.2)))
                      : AppTheme.primary.withValues(alpha: 0.15),
                  borderRadius: BorderRadius.circular(10),
                ),
                child: Text(
                  _game.isAiGame
                      ? '🤖 ${_game.aiDifficulty.toUpperCase()}'
                      : '👥 PVP',
                  style: TextStyle(
                    fontSize: 10,
                    fontWeight: FontWeight.bold,
                    color: _game.isAiGame
                        ? (_game.aiDifficulty == 'hard'
                            ? Colors.redAccent
                            : (_game.aiDifficulty == 'medium'
                                ? Colors.amber
                                : Colors.green))
                        : AppTheme.primary,
                  ),
                ),
              ),
            ],
          ),

          const SizedBox(height: 8),

          // ── Status Banner ─────────────────────────────────────────────────
          _buildStatusBanner(),

          const SizedBox(height: 10),

          // ── Interactive Game Board ────────────────────────────────────────
          if (isTtt) _buildTicTacToeBoard() else _buildConnect4Board(),

          // ── Rematch / Play Again Action ───────────────────────────────────
          if (_game.isGameOver) ...[
            const SizedBox(height: 10),
            ElevatedButton.icon(
              style: ElevatedButton.styleFrom(
                backgroundColor: AppTheme.primary,
                foregroundColor: Colors.white,
                shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10)),
                padding: const EdgeInsets.symmetric(vertical: 8),
              ),
              icon: const Icon(Icons.replay_rounded, size: 16),
              label: const Text('Play Again', style: TextStyle(fontWeight: FontWeight.bold, fontSize: 12)),
              onPressed: () {
                final fresh = isTtt
                    ? GameData.newTicTacToe(
                        player1Id: widget.currentUserId,
                        player1Name: _game.player1Name,
                        player2Id: _game.player2Id,
                        player2Name: _game.player2Name,
                        gameMode: _game.gameMode,
                        aiDifficulty: _game.aiDifficulty,
                      )
                    : GameData.newConnect4(
                        player1Id: widget.currentUserId,
                        player1Name: _game.player1Name,
                        player2Id: _game.player2Id,
                        player2Name: _game.player2Name,
                        gameMode: _game.gameMode,
                        aiDifficulty: _game.aiDifficulty,
                      );
                _publishUpdate(fresh);
                widget.onRematch?.call();
              },
            ),
          ],
        ],
      ),
    );
  }

  Widget _buildStatusBanner() {
    if (_game.status == 'won') {
      final isWinnerMe = _game.winnerId == widget.currentUserId;
      return Container(
        padding: const EdgeInsets.symmetric(vertical: 4, horizontal: 8),
        decoration: BoxDecoration(
          color: isWinnerMe ? Colors.green.withValues(alpha: 0.2) : Colors.redAccent.withValues(alpha: 0.2),
          borderRadius: BorderRadius.circular(8),
        ),
        child: Text(
          isWinnerMe ? '🎉 You Won! 🏆' : '👑 ${_game.winnerName} Won!',
          textAlign: TextAlign.center,
          style: TextStyle(
            fontWeight: FontWeight.bold,
            fontSize: 12,
            color: isWinnerMe ? Colors.green : Colors.redAccent,
          ),
        ),
      );
    }

    if (_game.status == 'draw') {
      return Container(
        padding: const EdgeInsets.symmetric(vertical: 4, horizontal: 8),
        decoration: BoxDecoration(
          color: Colors.amber.withValues(alpha: 0.2),
          borderRadius: BorderRadius.circular(8),
        ),
        child: const Text(
          '🤝 Game Tied / Draw!',
          textAlign: TextAlign.center,
          style: TextStyle(fontWeight: FontWeight.bold, fontSize: 12, color: Colors.amber),
        ),
      );
    }

    if (_isAiThinking) {
      return Container(
        padding: const EdgeInsets.symmetric(vertical: 4, horizontal: 8),
        child: const Row(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            SizedBox(
              width: 12,
              height: 12,
              child: CircularProgressIndicator(strokeWidth: 2, color: AppTheme.primary),
            ),
            SizedBox(width: 8),
            Text('Computer is thinking...', style: TextStyle(fontSize: 11.5, color: AppTheme.textMuted)),
          ],
        ),
      );
    }

    return Text(
      _isMyTurn
          ? '🟢 Your turn (${widget.currentUserId == _game.player1Id ? _game.player1Symbol : _game.player2Symbol})'
          : '⏳ Waiting for ${_game.currentTurnId == 'ai' ? 'Computer' : _game.player2Name}...',
      textAlign: TextAlign.center,
      style: TextStyle(
        fontSize: 11.5,
        fontWeight: _isMyTurn ? FontWeight.bold : FontWeight.normal,
        color: _isMyTurn ? AppTheme.primary : AppTheme.textMuted,
      ),
    );
  }

  Widget _buildTicTacToeBoard() {
    return AspectRatio(
      aspectRatio: 1.0,
      child: Container(
        decoration: BoxDecoration(
          color: Colors.black12,
          borderRadius: BorderRadius.circular(12),
        ),
        child: GridView.builder(
          physics: const NeverScrollableScrollPhysics(),
          padding: const EdgeInsets.all(6),
          gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(
            crossAxisCount: 3,
            crossAxisSpacing: 6,
            mainAxisSpacing: 6,
          ),
          itemCount: 9,
          itemBuilder: (ctx, idx) {
            final val = _game.board[idx];
            final isWinningCell = _game.winningLine.contains(idx);

            return InkWell(
              onTap: () => _handleCellTap(idx),
              borderRadius: BorderRadius.circular(8),
              child: Container(
                decoration: BoxDecoration(
                  color: isWinningCell
                      ? AppTheme.primary.withValues(alpha: 0.35)
                      : (val.isNotEmpty ? Colors.white10 : Colors.white.withValues(alpha: 0.05)),
                  borderRadius: BorderRadius.circular(8),
                  border: Border.all(
                    color: isWinningCell
                        ? AppTheme.primary
                        : Colors.white12,
                    width: isWinningCell ? 2 : 1,
                  ),
                ),
                child: Center(
                  child: Text(
                    val,
                    style: TextStyle(
                      fontSize: 28,
                      fontWeight: FontWeight.w900,
                      color: val == 'X' ? AppTheme.primary : Colors.pinkAccent,
                    ),
                  ),
                ),
              ),
            );
          },
        ),
      ),
    );
  }

  Widget _buildConnect4Board() {
    return AspectRatio(
      aspectRatio: 7 / 6,
      child: Container(
        padding: const EdgeInsets.all(6),
        decoration: BoxDecoration(
          color: Colors.blue.shade900.withValues(alpha: 0.4),
          borderRadius: BorderRadius.circular(12),
          border: Border.all(color: Colors.blueAccent.withValues(alpha: 0.4)),
        ),
        child: GridView.builder(
          physics: const NeverScrollableScrollPhysics(),
          gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(
            crossAxisCount: 7,
            crossAxisSpacing: 4,
            mainAxisSpacing: 4,
          ),
          itemCount: 42,
          itemBuilder: (ctx, idx) {
            final val = _game.board[idx];
            final isWinningCell = _game.winningLine.contains(idx);

            return InkWell(
              onTap: () => _handleCellTap(idx),
              borderRadius: BorderRadius.circular(20),
              child: Container(
                decoration: BoxDecoration(
                  shape: BoxShape.circle,
                  color: val.isEmpty
                      ? Colors.black26
                      : (val.contains('🔴') || val == 'R'
                          ? Colors.redAccent
                          : Colors.amber),
                  border: Border.all(
                    color: isWinningCell ? Colors.white : Colors.black12,
                    width: isWinningCell ? 2.5 : 1,
                  ),
                  boxShadow: isWinningCell
                      ? [BoxShadow(color: Colors.white.withValues(alpha: 0.8), blurRadius: 6)]
                      : null,
                ),
              ),
            );
          },
        ),
      ),
    );
  }
}
