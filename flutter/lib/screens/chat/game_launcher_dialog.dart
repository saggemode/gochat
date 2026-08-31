import 'package:flutter/material.dart';
import '../../core/models/game_data.dart';
import '../../core/theme/app_theme.dart';

/// Modal bottom sheet to customize and launch an in-chat multiplayer or AI mini-game.
class GameLauncherDialog extends StatefulWidget {
  final String currentUserId;
  final String currentUserName;
  final String partnerId;
  final String partnerName;
  final Function(GameData game) onLaunchGame;

  const GameLauncherDialog({
    super.key,
    required this.currentUserId,
    required this.currentUserName,
    required this.partnerId,
    required this.partnerName,
    required this.onLaunchGame,
  });

  static Future<void> show(
    BuildContext context, {
    required String currentUserId,
    required String currentUserName,
    required String partnerId,
    required String partnerName,
    required Function(GameData game) onLaunchGame,
  }) {
    final isDark = Theme.of(context).brightness == Brightness.dark;

    return showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      backgroundColor: isDark ? AppTheme.darkSurface : Colors.white,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(24)),
      ),
      builder: (_) => GameLauncherDialog(
        currentUserId: currentUserId,
        currentUserName: currentUserName,
        partnerId: partnerId,
        partnerName: partnerName,
        onLaunchGame: onLaunchGame,
      ),
    );
  }

  @override
  State<GameLauncherDialog> createState() => _GameLauncherDialogState();
}

class _GameLauncherDialogState extends State<GameLauncherDialog> {
  String _selectedGame = 'tictactoe'; // 'tictactoe' or 'connect4'
  String _selectedMode = 'ai'; // 'pvp' or 'ai'
  String _selectedDifficulty = 'medium'; // 'easy', 'medium', 'hard'

  void _handleStart() {
    Navigator.pop(context);

    final isTtt = _selectedGame == 'tictactoe';
    final isAi = _selectedMode == 'ai';

    final GameData game = isTtt
        ? GameData.newTicTacToe(
            player1Id: widget.currentUserId,
            player1Name: widget.currentUserName,
            player2Id: isAi ? 'ai' : widget.partnerId,
            player2Name: isAi ? 'Computer 🤖' : widget.partnerName,
            gameMode: _selectedMode,
            aiDifficulty: _selectedDifficulty,
          )
        : GameData.newConnect4(
            player1Id: widget.currentUserId,
            player1Name: widget.currentUserName,
            player2Id: isAi ? 'ai' : widget.partnerId,
            player2Name: isAi ? 'Computer 🤖' : widget.partnerName,
            gameMode: _selectedMode,
            aiDifficulty: _selectedDifficulty,
          );

    widget.onLaunchGame(game);
  }

  @override
  Widget build(BuildContext context) {
    final isDark = Theme.of(context).brightness == Brightness.dark;

    return Padding(
      padding: EdgeInsets.fromLTRB(20, 20, 20, MediaQuery.of(context).viewInsets.bottom + 24),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          // Drag Handle
          Center(
            child: Container(
              width: 40,
              height: 4,
              decoration: BoxDecoration(
                color: isDark ? Colors.white24 : Colors.black12,
                borderRadius: BorderRadius.circular(2),
              ),
            ),
          ),
          const SizedBox(height: 16),

          // Title
          const Row(
            children: [
              Icon(Icons.sports_esports_rounded, color: AppTheme.primary, size: 24),
              SizedBox(width: 10),
              Text(
                'In-Chat Mini-Games',
                style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold),
              ),
            ],
          ),
          const SizedBox(height: 4),
          const Text(
            'Play live in chat with your friend or challenge the AI bot',
            style: TextStyle(fontSize: 12.5, color: AppTheme.textMuted),
          ),

          const SizedBox(height: 20),

          // ── 1. Select Game ────────────────────────────────────────────────
          const Text(
            '1. Choose Game',
            style: TextStyle(fontWeight: FontWeight.bold, fontSize: 13),
          ),
          const SizedBox(height: 10),
          Row(
            children: [
              Expanded(
                child: _buildSelectableCard(
                  icon: '⭕',
                  title: 'Tic-Tac-Toe',
                  subtitle: 'Classic 3x3 grid',
                  isSelected: _selectedGame == 'tictactoe',
                  onTap: () => setState(() => _selectedGame = 'tictactoe'),
                ),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: _buildSelectableCard(
                  icon: '🔴',
                  title: 'Connect 4',
                  subtitle: '7x6 strategy drop',
                  isSelected: _selectedGame == 'connect4',
                  onTap: () => setState(() => _selectedGame = 'connect4'),
                ),
              ),
            ],
          ),

          const SizedBox(height: 20),

          // ── 2. Select Opponent / Mode ─────────────────────────────────────
          const Text(
            '2. Choose Opponent',
            style: TextStyle(fontWeight: FontWeight.bold, fontSize: 13),
          ),
          const SizedBox(height: 10),
          Row(
            children: [
              Expanded(
                child: _buildSelectableCard(
                  icon: '🤖',
                  title: 'vs Computer',
                  subtitle: 'Play instantly solo',
                  isSelected: _selectedMode == 'ai',
                  onTap: () => setState(() => _selectedMode = 'ai'),
                ),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: _buildSelectableCard(
                  icon: '👥',
                  title: 'vs ${widget.partnerName}',
                  subtitle: 'Live turn-based',
                  isSelected: _selectedMode == 'pvp',
                  onTap: () => setState(() => _selectedMode = 'pvp'),
                ),
              ),
            ],
          ),

          // ── 3. AI Difficulty (if vs Computer) ─────────────────────────────
          if (_selectedMode == 'ai') ...[
            const SizedBox(height: 20),
            const Text(
              '3. Computer Level',
              style: TextStyle(fontWeight: FontWeight.bold, fontSize: 13),
            ),
            const SizedBox(height: 10),
            Row(
              children: [
                _buildDifficultyChip('easy', '🟢 Easy', Colors.green),
                const SizedBox(width: 8),
                _buildDifficultyChip('medium', '🟡 Medium', Colors.amber),
                const SizedBox(width: 8),
                _buildDifficultyChip('hard', '🔴 Master', Colors.redAccent),
              ],
            ),
          ],

          const SizedBox(height: 24),

          // ── Start Game Button ─────────────────────────────────────────────
          ElevatedButton.icon(
            style: ElevatedButton.styleFrom(
              backgroundColor: AppTheme.primary,
              foregroundColor: Colors.white,
              padding: const EdgeInsets.symmetric(vertical: 14),
              shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
              elevation: 4,
            ),
            icon: const Icon(Icons.play_arrow_rounded, size: 22),
            label: Text(
              'Start ${_selectedGame == 'tictactoe' ? 'Tic-Tac-Toe' : 'Connect 4'}',
              style: const TextStyle(fontSize: 15, fontWeight: FontWeight.bold),
            ),
            onPressed: _handleStart,
          ),
        ],
      ),
    );
  }

  Widget _buildSelectableCard({
    required String icon,
    required String title,
    required String subtitle,
    required bool isSelected,
    required VoidCallback onTap,
  }) {
    final isDark = Theme.of(context).brightness == Brightness.dark;

    return InkWell(
      onTap: onTap,
      borderRadius: BorderRadius.circular(14),
      child: Container(
        padding: const EdgeInsets.all(12),
        decoration: BoxDecoration(
          color: isSelected
              ? AppTheme.primary.withValues(alpha: 0.15)
              : (isDark ? Colors.white.withValues(alpha: 0.05) : Colors.black.withValues(alpha: 0.03)),
          borderRadius: BorderRadius.circular(14),
          border: Border.all(
            color: isSelected ? AppTheme.primary : (isDark ? Colors.white12 : Colors.black12),
            width: isSelected ? 2 : 1,
          ),
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(icon, style: const TextStyle(fontSize: 24)),
            const SizedBox(height: 6),
            Text(
              title,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: TextStyle(
                fontWeight: FontWeight.bold,
                fontSize: 13.5,
                color: isSelected ? AppTheme.primary : null,
              ),
            ),
            Text(
              subtitle,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: const TextStyle(fontSize: 11, color: AppTheme.textMuted),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildDifficultyChip(String key, String label, Color color) {
    final isSelected = _selectedDifficulty == key;

    return Expanded(
      child: ChoiceChip(
        label: Center(
          child: Text(
            label,
            style: TextStyle(
              fontSize: 12,
              fontWeight: isSelected ? FontWeight.bold : FontWeight.normal,
              color: isSelected ? Colors.white : null,
            ),
          ),
        ),
        selected: isSelected,
        selectedColor: color,
        showCheckmark: false,
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10)),
        onSelected: (val) {
          if (val) setState(() => _selectedDifficulty = key);
        },
      ),
    );
  }
}
