import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/constants.dart';
import '../../core/network/api_client.dart';
import '../../core/network/api_error.dart';
import '../../shared/widgets/error_view.dart';
import '../../shared/widgets/loading_view.dart';

// ---------------------------------------------------------------------------
// The plan this business is on (21.8).
//
// What it is paying for, what that allows, and how much of each allowance it
// has used — because a limit is about to refuse somebody mid-shift, and an
// owner should see it coming rather than meet it as an error.
//
// A count the owning service could not give is shown as unknown rather than
// guessed at; a business on no plan is unrestricted, and says so.
// ---------------------------------------------------------------------------

class PlanAllowance {
  final String key;
  final String label;
  final int? limitValue;
  final int? used;
  final bool over;

  const PlanAllowance({
    required this.key,
    required this.label,
    required this.limitValue,
    required this.used,
    required this.over,
  });

  factory PlanAllowance.fromJson(Map<String, dynamic> j) => PlanAllowance(
        key: j['key'] as String? ?? '',
        label: j['label'] as String? ?? '',
        limitValue: (j['limitValue'] as num?)?.toInt(),
        used: (j['used'] as num?)?.toInt(),
        over: j['over'] == true,
      );

  bool get unlimited => limitValue == null;
  bool get known => used != null;

  /// How full the allowance is, for the bar; null when there is nothing to draw.
  double? get fraction {
    if (unlimited || !known || limitValue == 0) return null;
    final f = used! / limitValue!;
    return f > 1 ? 1 : f;
  }

  String get says {
    if (unlimited) return known ? '$used · no limit' : 'no limit';
    if (!known) return 'up to $limitValue';
    return '$used of $limitValue';
  }
}

class TenantPlan {
  final String? code;
  final String? name;
  final String? billingInterval;
  final List<String> includes;
  final List<PlanAllowance> allowances;
  final String? note;

  const TenantPlan({
    required this.code,
    required this.name,
    required this.billingInterval,
    required this.includes,
    required this.allowances,
    required this.note,
  });

  factory TenantPlan.fromJson(Map<String, dynamic> j) {
    final plan = j['plan'] == null ? null : Map<String, dynamic>.from(j['plan'] as Map);
    return TenantPlan(
      code: plan?['code'] as String?,
      name: plan?['name'] as String?,
      billingInterval: plan?['billingInterval'] as String?,
      includes: [
        for (final g in plan?['includes'] as List<dynamic>? ?? const [])
          '${(g as Map)['label'] ?? g['key']}: ${g['enabled'] == null ? (g['limitValue'] ?? 'unlimited') : (g['enabled'] == true ? 'included' : 'not included')}',
      ],
      allowances: [
        for (final u in j['usage'] as List<dynamic>? ?? const [])
          PlanAllowance.fromJson(Map<String, dynamic>.from(u as Map)),
      ],
      note: j['note'] as String?,
    );
  }

  bool get onAPlan => code != null;
}

final tenantPlanProvider = FutureProvider.autoDispose<TenantPlan>((ref) async {
  final resp = await ref.watch(apiClientProvider).dio.get('/${ApiConstants.tenant}/admin/tenant/plan');
  return TenantPlan.fromJson(Map<String, dynamic>.from(resp.data['data'] as Map));
});

class PlanScreen extends ConsumerWidget {
  const PlanScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final plan = ref.watch(tenantPlanProvider);
    final text = Theme.of(context).textTheme;
    final cs = Theme.of(context).colorScheme;
    void refresh() => ref.invalidate(tenantPlanProvider);

    return Padding(
      padding: const EdgeInsets.all(24),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Expanded(child: Text('Plan', style: text.headlineSmall)),
              IconButton(icon: const Icon(Icons.refresh), tooltip: 'Refresh', onPressed: refresh),
            ],
          ),
          const SizedBox(height: 4),
          Text(
            'What this business is on, and how much of it is in use.',
            style: text.bodyMedium?.copyWith(color: cs.outline),
          ),
          const SizedBox(height: 16),
          Expanded(
            child: plan.when(
              loading: () => const LoadingView(label: 'Loading the plan…'),
              error: (e, _) => ErrorView(message: friendlyError(e, fallback: 'Could not load the plan.'), onRetry: refresh),
              data: (p) => ListView(
                children: [
                  Card(
                    child: Padding(
                      padding: const EdgeInsets.all(16),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          if (!p.onAPlan) ...[
                            Text('No plan', key: const Key('plan-none'), style: text.titleMedium),
                            const SizedBox(height: 6),
                            Text(
                              p.note ?? 'This business is on no plan, so nothing is limited.',
                              style: text.bodyMedium,
                            ),
                          ] else ...[
                            Text('${p.name} · ${p.code}', key: const Key('plan-name'), style: text.titleMedium),
                            const SizedBox(height: 2),
                            Text(
                              'billed ${p.billingInterval == 'YEAR' ? 'every year' : 'every month'}',
                              style: text.bodySmall?.copyWith(color: cs.outline),
                            ),
                            if (p.includes.isNotEmpty) ...[
                              const SizedBox(height: 12),
                              Wrap(
                                spacing: 8,
                                runSpacing: 6,
                                children: [for (final i in p.includes) Chip(label: Text(i))],
                              ),
                            ],
                          ],
                        ],
                      ),
                    ),
                  ),
                  if (p.allowances.isNotEmpty) ...[
                    const SizedBox(height: 16),
                    Text('What it allows', style: text.titleSmall),
                    const SizedBox(height: 8),
                    for (final a in p.allowances) _AllowanceRow(allowance: a),
                  ],
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class _AllowanceRow extends StatelessWidget {
  final PlanAllowance allowance;
  const _AllowanceRow({required this.allowance});

  @override
  Widget build(BuildContext context) {
    final a = allowance;
    final cs = Theme.of(context).colorScheme;
    final text = Theme.of(context).textTheme;
    final full = a.fraction != null && a.fraction! >= 1;
    return Padding(
      key: Key('allowance-${a.key}'),
      padding: const EdgeInsets.only(bottom: 14),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Expanded(child: Text(a.label, style: text.bodyMedium)),
              Text(
                a.says,
                style: text.bodyMedium?.copyWith(
                  color: a.over || full ? cs.error : cs.outline,
                  fontFeatures: const [FontFeature.tabularFigures()],
                ),
              ),
            ],
          ),
          if (a.fraction != null) ...[
            const SizedBox(height: 6),
            LinearProgressIndicator(
              value: a.fraction,
              color: full ? cs.error : cs.primary,
              backgroundColor: cs.surfaceContainerHighest,
            ),
          ],
          if (!a.known && !a.unlimited)
            Text(
              'counted by the service that holds them',
              style: text.bodySmall?.copyWith(color: cs.outline),
            ),
        ],
      ),
    );
  }
}
