import 'package:flutter/material.dart';

import '../../core/spacing.dart';

/// What a list or screen shows when there is nothing in it yet: an outlined
/// icon on a quiet disc, a title that states the situation, one helpful
/// sentence, and at most one action. Never a blank area or a bare "No data".
class EmptyState extends StatelessWidget {
  final String title;
  final String? detail;
  final IconData icon;
  final Widget? action;

  const EmptyState({
    super.key,
    required this.title,
    this.detail,
    this.icon = Icons.inbox_outlined,
    this.action,
  });

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final text = Theme.of(context).textTheme;
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(AppSpacing.xl),
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 420),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Container(
                width: 64,
                height: 64,
                decoration:
                    BoxDecoration(color: cs.surfaceContainerHigh, shape: BoxShape.circle),
                alignment: Alignment.center,
                child: Icon(icon, size: 32, color: cs.onSurfaceVariant),
              ),
              const SizedBox(height: AppSpacing.lg),
              Text(
                title,
                textAlign: TextAlign.center,
                style: text.titleMedium?.copyWith(color: cs.onSurface),
              ),
              if (detail != null) ...[
                const SizedBox(height: AppSpacing.sm),
                Text(
                  detail!,
                  textAlign: TextAlign.center,
                  style: text.bodyMedium?.copyWith(color: cs.onSurfaceVariant),
                ),
              ],
              if (action != null) ...[
                const SizedBox(height: AppSpacing.lg),
                action!,
              ],
            ],
          ),
        ),
      ),
    );
  }
}
