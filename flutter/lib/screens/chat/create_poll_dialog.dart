import 'package:flutter/material.dart';
import '../../core/models/message.dart';
import '../../core/theme/app_theme.dart';

class CreatePollDialog extends StatelessWidget {
  final Function(PollData poll) onCreatePoll;

  const CreatePollDialog({super.key, required this.onCreatePoll});

  static Future<void> show(BuildContext context, {required Function(PollData poll) onCreatePoll}) {
    return showDialog(
      context: context,
      builder: (_) => CreatePollDialog(onCreatePoll: onCreatePoll),
    );
  }

  @override
  Widget build(BuildContext context) {
    final questionController = TextEditingController();
    final opt1Controller = TextEditingController();
    final opt2Controller = TextEditingController();
    final opt3Controller = TextEditingController();

    return AlertDialog(
      backgroundColor: AppTheme.darkSurface,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
      title: const Row(
        children: [
          Icon(Icons.poll_rounded, color: AppTheme.primary),
          SizedBox(width: 8),
          Text('Create Live Poll', style: TextStyle(color: AppTheme.textLight)),
        ],
      ),
      content: SingleChildScrollView(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            TextField(
              controller: questionController,
              style: const TextStyle(color: AppTheme.textLight),
              decoration: const InputDecoration(hintText: 'Ask a question...'),
            ),
            const SizedBox(height: 12),
            TextField(
              controller: opt1Controller,
              style: const TextStyle(color: AppTheme.textLight),
              decoration: const InputDecoration(hintText: 'Option 1'),
            ),
            const SizedBox(height: 8),
            TextField(
              controller: opt2Controller,
              style: const TextStyle(color: AppTheme.textLight),
              decoration: const InputDecoration(hintText: 'Option 2'),
            ),
            const SizedBox(height: 8),
            TextField(
              controller: opt3Controller,
              style: const TextStyle(color: AppTheme.textLight),
              decoration: const InputDecoration(hintText: 'Option 3 (Optional)'),
            ),
          ],
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(context),
          child: const Text('Cancel', style: TextStyle(color: AppTheme.textMuted)),
        ),
        ElevatedButton(
          style: ElevatedButton.styleFrom(backgroundColor: AppTheme.primary),
          onPressed: () {
            final q = questionController.text.trim();
            final o1 = opt1Controller.text.trim();
            final o2 = opt2Controller.text.trim();
            final o3 = opt3Controller.text.trim();

            if (q.isNotEmpty && o1.isNotEmpty && o2.isNotEmpty) {
              final poll = PollData(
                id: 'poll_${DateTime.now().millisecondsSinceEpoch}',
                question: q,
                options: [
                  PollOption(id: 'o1', text: o1),
                  PollOption(id: 'o2', text: o2),
                  if (o3.isNotEmpty) PollOption(id: 'o3', text: o3),
                ],
              );
              onCreatePoll(poll);
              Navigator.pop(context);
            }
          },
          child: const Text('Create Poll'),
        ),
      ],
    );
  }
}
