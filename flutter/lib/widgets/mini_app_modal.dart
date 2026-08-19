import 'package:flutter/material.dart';
import '../core/theme/app_theme.dart';

class MiniAppModal extends StatefulWidget {
  final Function(String summary)? onShareToChat;

  const MiniAppModal({super.key, this.onShareToChat});

  @override
  State<MiniAppModal> createState() => _MiniAppModalState();
}

class _MiniAppModalState extends State<MiniAppModal>
    with SingleTickerProviderStateMixin {
  late TabController _tabController;

  // Whiteboard Canvas State
  final List<List<Offset>> _lines = [];
  Color _selectedColor = AppTheme.accent;
  final double _strokeWidth = 3.0;

  // Tic-Tac-Toe Mini-Game State
  List<String> _board = List.filled(9, '');
  String _currentPlayer = 'X';
  String _gameStatus = 'Player X Turn';

  @override
  void initState() {
    super.initState();
    _tabController = TabController(length: 2, vsync: this);
  }

  void _resetGame() {
    setState(() {
      _board = List.filled(9, '');
      _currentPlayer = 'X';
      _gameStatus = 'Player X Turn';
    });
  }

  void _handleGameTap(int index) {
    if (_board[index] != '' || _gameStatus.contains('Won') || _gameStatus.contains('Draw')) {
      return;
    }

    setState(() {
      _board[index] = _currentPlayer;
      if (_checkWinner(_currentPlayer)) {
        _gameStatus = '🎉 Player $_currentPlayer Won!';
      } else if (!_board.contains('')) {
        _gameStatus = '🤝 It is a Draw!';
      } else {
        _currentPlayer = _currentPlayer == 'X' ? 'O' : 'X';
        _gameStatus = 'Player $_currentPlayer Turn';
      }
    });
  }

  bool _checkWinner(String player) {
    const winningCombos = [
      [0, 1, 2], [3, 4, 5], [6, 7, 8], // Rows
      [0, 3, 6], [1, 4, 7], [2, 5, 8], // Columns
      [0, 4, 8], [2, 4, 6],             // Diagonals
    ];
    for (final combo in winningCombos) {
      if (_board[combo[0]] == player &&
          _board[combo[1]] == player &&
          _board[combo[2]] == player) {
        return true;
      }
    }
    return false;
  }

  @override
  Widget build(BuildContext context) {
    return Container(
      height: MediaQuery.of(context).size.height * 0.78,
      decoration: const BoxDecoration(
        color: AppTheme.darkSurface,
        borderRadius: BorderRadius.vertical(top: Radius.circular(24)),
      ),
      child: Column(
        children: [
          // Drag Handle
          const SizedBox(height: 10),
          Container(
            width: 38,
            height: 4,
            decoration: BoxDecoration(
              color: Colors.white24,
              borderRadius: BorderRadius.circular(2),
            ),
          ),
          const SizedBox(height: 10),

          // Header & Tabs
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 16),
            child: Row(
              children: [
                Expanded(
                  child: TabBar(
                    controller: _tabController,
                    indicatorColor: AppTheme.primary,
                    labelColor: AppTheme.primary,
                    unselectedLabelColor: AppTheme.textMuted,
                    dividerColor: Colors.transparent,
                    tabs: const [
                      Tab(icon: Icon(Icons.brush_rounded), text: 'Live Canvas'),
                      Tab(icon: Icon(Icons.videogame_asset_rounded), text: 'Mini-Game'),
                    ],
                  ),
                ),
                IconButton(
                  onPressed: () => Navigator.pop(context),
                  icon: const Icon(Icons.close, color: AppTheme.iconColor),
                ),
              ],
            ),
          ),
          const Divider(),

          // Tab Views
          Expanded(
            child: TabBarView(
              controller: _tabController,
              children: [
                // 1. Live Canvas View
                Column(
                  children: [
                    // Canvas Tools
                    Padding(
                      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
                      child: Row(
                        children: [
                          ...[
                            AppTheme.accent,
                            Colors.lightBlueAccent,
                            Colors.amber,
                            Colors.pinkAccent,
                            Colors.white,
                          ].map((color) {
                            final isSelected = _selectedColor == color;
                            return GestureDetector(
                              onTap: () => setState(() => _selectedColor = color),
                              child: Container(
                                margin: const EdgeInsets.only(right: 10),
                                width: 24,
                                height: 24,
                                decoration: BoxDecoration(
                                  color: color,
                                  shape: BoxShape.circle,
                                  border: Border.all(
                                    color: isSelected ? Colors.white : Colors.transparent,
                                    width: 2,
                                  ),
                                ),
                              ),
                            );
                          }),
                          const Spacer(),
                          IconButton(
                            icon: const Icon(Icons.delete_outline, size: 20, color: AppTheme.dangerRed),
                            onPressed: () => setState(() => _lines.clear()),
                          ),
                          ElevatedButton.icon(
                            style: ElevatedButton.styleFrom(
                              backgroundColor: AppTheme.primary,
                              foregroundColor: Colors.white,
                              padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
                            ),
                            icon: const Icon(Icons.send_rounded, size: 16),
                            label: const Text('Share to Chat', style: TextStyle(fontSize: 12)),
                            onPressed: () {
                              Navigator.pop(context);
                              widget.onShareToChat?.call('Collaborative Canvas Drawing');
                            },
                          ),
                        ],
                      ),
                    ),

                    // Drawing Area
                    Expanded(
                      child: Container(
                        margin: const EdgeInsets.all(12),
                        decoration: BoxDecoration(
                          color: AppTheme.darkBg,
                          borderRadius: BorderRadius.circular(16),
                          border: Border.all(color: AppTheme.darkBorder),
                        ),
                        child: GestureDetector(
                          onPanStart: (details) {
                            setState(() {
                              _lines.add([details.localPosition]);
                            });
                          },
                          onPanUpdate: (details) {
                            setState(() {
                              if (_lines.isNotEmpty) {
                                _lines.last.add(details.localPosition);
                              }
                            });
                          },
                          child: CustomPaint(
                            painter: _CanvasPainter(lines: _lines, color: _selectedColor, strokeWidth: _strokeWidth),
                            size: Size.infinite,
                          ),
                        ),
                      ),
                    ),
                  ],
                ),

                // 2. Mini-Game View (Tic-Tac-Toe Multiplayer)
                Padding(
                  padding: const EdgeInsets.all(20),
                  child: Column(
                    children: [
                      Text(
                        _gameStatus,
                        style: const TextStyle(
                          fontSize: 18,
                          fontWeight: FontWeight.bold,
                          color: AppTheme.textLight,
                        ),
                      ),
                      const SizedBox(height: 20),
                      // 3x3 Grid
                      AspectRatio(
                        aspectRatio: 1.0,
                        child: Container(
                          padding: const EdgeInsets.all(8),
                          decoration: BoxDecoration(
                            color: AppTheme.darkCard,
                            borderRadius: BorderRadius.circular(16),
                          ),
                          child: GridView.builder(
                            gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(
                              crossAxisCount: 3,
                              mainAxisSpacing: 8,
                              crossAxisSpacing: 8,
                            ),
                            itemCount: 9,
                            itemBuilder: (ctx, i) {
                              final val = _board[i];
                              return GestureDetector(
                                onTap: () => _handleGameTap(i),
                                child: Container(
                                  decoration: BoxDecoration(
                                    color: AppTheme.darkSurface,
                                    borderRadius: BorderRadius.circular(12),
                                    border: Border.all(color: Colors.white12),
                                  ),
                                  child: Center(
                                    child: Text(
                                      val,
                                      style: TextStyle(
                                        fontSize: 36,
                                        fontWeight: FontWeight.bold,
                                        color: val == 'X' ? AppTheme.accent : Colors.amber,
                                      ),
                                    ),
                                  ),
                                ),
                              );
                            },
                          ),
                        ),
                      ),
                      const Spacer(),
                      Row(
                        mainAxisAlignment: MainAxisAlignment.center,
                        children: [
                          OutlinedButton.icon(
                            style: OutlinedButton.styleFrom(
                              foregroundColor: AppTheme.textLight,
                              side: const BorderSide(color: AppTheme.darkBorder),
                            ),
                            icon: const Icon(Icons.refresh),
                            label: const Text('Reset Board'),
                            onPressed: _resetGame,
                          ),
                          const SizedBox(width: 12),
                          ElevatedButton.icon(
                            style: ElevatedButton.styleFrom(
                              backgroundColor: AppTheme.primary,
                              foregroundColor: Colors.white,
                            ),
                            icon: const Icon(Icons.send_rounded),
                            label: const Text('Share Turn to Chat'),
                            onPressed: () {
                              Navigator.pop(context);
                              widget.onShareToChat?.call('Tic-Tac-Toe Game Move ($_gameStatus)');
                            },
                          ),
                        ],
                      ),
                    ],
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class _CanvasPainter extends CustomPainter {
  final List<List<Offset>> lines;
  final Color color;
  final double strokeWidth;

  _CanvasPainter({required this.lines, required this.color, required this.strokeWidth});

  @override
  void paint(Canvas canvas, Size size) {
    final paint = Paint()
      ..color = color
      ..strokeCap = StrokeCap.round
      ..strokeWidth = strokeWidth
      ..isAntiAlias = true;

    for (final line in lines) {
      for (int i = 0; i < line.length - 1; i++) {
        canvas.drawLine(line[i], line[i + 1], paint);
      }
    }
  }

  @override
  bool shouldRepaint(covariant _CanvasPainter oldDelegate) => true;
}
