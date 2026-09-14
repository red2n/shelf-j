import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/constants.dart';
import '../../core/format.dart';
import '../../core/network/api_client.dart';
import '../../core/network/api_error.dart';
import '../../core/spacing.dart';
import '../../shared/widgets/error_view.dart';
import '../../shared/widgets/loading_view.dart';

// ---------------------------------------------------------------------------
// Security notices the platform has sent this business (21.15).
//
// When a security incident affects a business — a breach of its customers'
// data above all, where the business is the controller with 72 hours to tell
// its supervisory authority — the platform sends it a notice here. The owner
// or a manager acknowledges it, once.
// ---------------------------------------------------------------------------

String _notices([String suffix = '']) =>
    '/${ApiConstants.tenant}/admin/tenant/security-notices$suffix';

class SecurityNotice {
  final String id;
  final String incidentId;
  final String title;
  final String body;
  final String issuedAt;
  final String? acknowledgedAt;

  const SecurityNotice({
    required this.id,
    required this.incidentId,
    required this.title,
    required this.body,
    required this.issuedAt,
    this.acknowledgedAt,
  });

  bool get acknowledged => acknowledgedAt != null;

  factory SecurityNotice.fromJson(Map<String, dynamic> j) => SecurityNotice(
        id: j['id'] as String? ?? '',
        incidentId: j['incidentId'] as String? ?? '',
        title: j['title'] as String? ?? '',
        body: j['body'] as String? ?? '',
        issuedAt: j['issuedAt'] as String? ?? '',
        acknowledgedAt: j['acknowledgedAt'] as String?,
      );
}

final securityNoticesProvider =
    FutureProvider.autoDispose<List<SecurityNotice>>((ref) async {
  final resp = await ref.read(apiClientProvider).dio.get(_notices());
  return [
    for (final j in (resp.data['data'] as List?) ?? const [])
      SecurityNotice.fromJson(j as Map<String, dynamic>)
  ];
});

class SecurityNoticesScreen extends ConsumerStatefulWidget {
  const SecurityNoticesScreen({super.key});

  @override
  ConsumerState<SecurityNoticesScreen> createState() =>
      _SecurityNoticesScreenState();
}

class _SecurityNoticesScreenState extends ConsumerState<SecurityNoticesScreen> {
  final Set<String> _busy = {};

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final notices = ref.watch(securityNoticesProvider);
    return ListView(
      padding: const EdgeInsets.all(AppSpacing.xl),
      children: [
        Text('Security notices', style: theme.textTheme.headlineMedium),
        const SizedBox(height: 4),
        Text(
          'What the platform has told this business about a security incident '
          'that affects it, and what to do. Where customers’ personal data '
          'was involved, this business is the controller: it decides whether '
          'to tell its supervisory authority, within 72 hours of becoming '
          'aware. Acknowledge each notice once it has been read.',
          style: theme.textTheme.bodyMedium
              ?.copyWith(color: theme.colorScheme.onSurfaceVariant),
        ),
        const SizedBox(height: AppSpacing.lg),
        notices.when(
          loading: () => const LoadingView(label: 'Loading notices…'),
          error: (e, _) => ErrorView(
            message:
                friendlyError(e, fallback: 'Could not load the security notices.'),
            onRetry: () => ref.invalidate(securityNoticesProvider),
          ),
          data: (list) => list.isEmpty
              ? const Text(
                  'The platform has sent this business no security notices.')
              : Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [for (final n in list) _notice(context, n)],
                ),
        ),
      ],
    );
  }

  Widget _notice(BuildContext context, SecurityNotice n) {
    final theme = Theme.of(context);
    return Card(
      key: Key('notice-${n.id}'),
      child: Padding(
        padding: const EdgeInsets.all(AppSpacing.lg),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(n.title, style: theme.textTheme.titleMedium),
            Text('Sent ${AppFormat.dateTime(n.issuedAt)}',
                style: theme.textTheme.bodySmall),
            const SizedBox(height: AppSpacing.sm),
            Text(n.body),
            const SizedBox(height: AppSpacing.sm),
            n.acknowledged
                ? Chip(
                    label: Text(
                        'Acknowledged ${AppFormat.dateTime(n.acknowledgedAt)}'))
                : FilledButton.icon(
                    key: Key('acknowledge-${n.id}'),
                    onPressed: _busy.contains(n.id) ? null : () => _acknowledge(n),
                    icon: const Icon(Icons.task_alt),
                    label: const Text('Acknowledge'),
                  ),
          ],
        ),
      ),
    );
  }

  Future<void> _acknowledge(SecurityNotice n) async {
    setState(() => _busy.add(n.id));
    try {
      await ref.read(apiClientProvider).dio.post(_notices('/${n.id}/acknowledge'));
      ref.invalidate(securityNoticesProvider);
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(
            content: Text(friendlyError(e,
                fallback: 'Could not acknowledge the notice.'))));
      }
    } finally {
      if (mounted) setState(() => _busy.remove(n.id));
    }
  }
}
