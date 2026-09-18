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
// Statutory returns (07.14): what this business owes each authority, when each
// one falls due, and the evidence that it went.
//
// The obligations screen next door says what the law asks. This says what is
// outstanding *now* — which is the question somebody has to act on — and keeps
// the receipt for each one that was filed.
//
// Two things the screen deliberately does not do. It never files on the
// business's behalf: the platform is not authorised to speak to a tax
// authority, so "Record filing" records that a person did. And it does not
// serve the export — the SAF-T belongs to order-svc and the VAT return to
// pricing-svc, so the row links to where the export lives.
// ---------------------------------------------------------------------------

class StatutoryFiling {
  final String id;
  final String returnCode;
  final String periodStart;
  final String? reference;
  final String provider;
  final String? filedAt;
  final String? payloadDigest;
  final String? supersedes;
  final bool stands;
  final String? note;

  const StatutoryFiling({
    required this.id,
    required this.returnCode,
    required this.periodStart,
    required this.provider,
    required this.stands,
    this.reference,
    this.filedAt,
    this.payloadDigest,
    this.supersedes,
    this.note,
  });

  factory StatutoryFiling.fromJson(Map<String, dynamic> j) => StatutoryFiling(
        id: j['id'] as String? ?? '',
        returnCode: j['returnCode'] as String? ?? '',
        periodStart: j['periodStart'] as String? ?? '',
        provider: j['provider'] as String? ?? '',
        stands: j['stands'] as bool? ?? true,
        reference: j['reference'] as String?,
        filedAt: j['filedAt'] as String?,
        payloadDigest: j['payloadDigest'] as String?,
        supersedes: j['supersedes'] as String?,
        note: j['note'] as String?,
      );
}

/// One period of one return, with its date and state worked out by tenant-svc.
/// Nothing here is stored there either: a stored deadline goes stale.
class StatutoryObligation {
  final String returnCode;
  final String name;
  final String scopeKind;
  final String scope;
  final String frequency;
  final String periodStart;
  final String periodEnd;
  final String dueOn;
  final String state;
  final String citation;
  final String? exportService;
  final String? exportPath;
  final StatutoryFiling? filing;

  const StatutoryObligation({
    required this.returnCode,
    required this.name,
    required this.scopeKind,
    required this.scope,
    required this.frequency,
    required this.periodStart,
    required this.periodEnd,
    required this.dueOn,
    required this.state,
    required this.citation,
    this.exportService,
    this.exportPath,
    this.filing,
  });

  bool get filed => state == 'FILED';
  bool get overdue => state == 'OVERDUE';
  bool get actionable => state == 'DUE' || state == 'OVERDUE';

  /// The month or quarter, as somebody says it out loud.
  String get periodLabel {
    final parts = periodStart.split('-');
    if (parts.length < 2) return periodStart;
    final month = int.tryParse(parts[1]) ?? 1;
    if (frequency == 'QUARTERLY') return 'Q${((month - 1) ~/ 3) + 1} ${parts[0]}';
    if (frequency == 'ANNUAL') return parts[0];
    return '${_months[month - 1]} ${parts[0]}';
  }

  static const _months = [
    'January', 'February', 'March', 'April', 'May', 'June',
    'July', 'August', 'September', 'October', 'November', 'December',
  ];

  factory StatutoryObligation.fromJson(Map<String, dynamic> j) =>
      StatutoryObligation(
        returnCode: j['returnCode'] as String? ?? '',
        name: j['name'] as String? ?? '',
        scopeKind: j['scopeKind'] as String? ?? '',
        scope: j['scope'] as String? ?? '',
        frequency: j['frequency'] as String? ?? '',
        periodStart: j['periodStart'] as String? ?? '',
        periodEnd: j['periodEnd'] as String? ?? '',
        dueOn: j['dueOn'] as String? ?? '',
        state: j['state'] as String? ?? '',
        citation: j['citation'] as String? ?? '',
        exportService: j['exportService'] as String?,
        exportPath: j['exportPath'] as String?,
        filing: j['filing'] == null
            ? null
            : StatutoryFiling.fromJson(j['filing'] as Map<String, dynamic>),
      );
}

class StatutoryCalendar {
  final String asOf;
  final List<StatutoryObligation> obligations;
  final List<StatutoryObligation> outstanding;

  const StatutoryCalendar({
    required this.asOf,
    required this.obligations,
    required this.outstanding,
  });

  factory StatutoryCalendar.fromJson(Map<String, dynamic> j) => StatutoryCalendar(
        asOf: j['asOf'] as String? ?? '',
        obligations: [
          for (final o in (j['obligations'] as List?) ?? const [])
            StatutoryObligation.fromJson(o as Map<String, dynamic>)
        ],
        outstanding: [
          for (final o in (j['outstanding'] as List?) ?? const [])
            StatutoryObligation.fromJson(o as Map<String, dynamic>)
        ],
      );

  /// The returns this business owes, each with its periods newest first.
  Map<String, List<StatutoryObligation>> get byReturn {
    final grouped = <String, List<StatutoryObligation>>{};
    for (final o in obligations) {
      grouped.putIfAbsent(o.returnCode, () => []).add(o);
    }
    for (final periods in grouped.values) {
      periods.sort((a, b) => b.periodStart.compareTo(a.periodStart));
    }
    return grouped;
  }
}

const _statutoryPath = '/${ApiConstants.tenant}/admin/tenant/statutory-returns';

final statutoryCalendarProvider =
    FutureProvider.autoDispose<StatutoryCalendar>((ref) async {
  final resp = await ref.read(apiClientProvider).dio.get(_statutoryPath);
  return StatutoryCalendar.fromJson(resp.data['data'] as Map<String, dynamic>);
});

class StatutoryReturnsScreen extends ConsumerWidget {
  const StatutoryReturnsScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final theme = Theme.of(context);
    final calendar = ref.watch(statutoryCalendarProvider);
    return ListView(
      padding: const EdgeInsets.all(AppSpacing.xl),
      children: [
        Text('Statutory returns', style: theme.textTheme.headlineMedium),
        const SizedBox(height: 4),
        Text(
          'What this business owes each authority, when each falls due, and the '
          'evidence that it went. Dates are worked out from the instrument every '
          'time this is opened, so a rule change moves them. The platform never '
          'files for you — recording a filing records that you did.',
          style: theme.textTheme.bodyMedium
              ?.copyWith(color: theme.colorScheme.onSurfaceVariant),
        ),
        const SizedBox(height: AppSpacing.lg),
        calendar.when(
          loading: () => const LoadingView(label: 'Working out what is due…'),
          error: (e, _) => ErrorView(
            message: friendlyError(e,
                fallback: 'Could not work out the statutory returns.'),
            onRetry: () => ref.invalidate(statutoryCalendarProvider),
          ),
          data: (c) {
            if (c.obligations.isEmpty) {
              return const Card(
                child: Padding(
                  padding: EdgeInsets.all(AppSpacing.lg),
                  child: Text(
                    'No statutory returns are tracked for this country yet. That '
                    'means the platform tracks none, not that none apply — ask an '
                    'accountant what this business owes.',
                  ),
                ),
              );
            }
            final grouped = c.byReturn;
            return Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                _Outstanding(outstanding: c.outstanding, asOf: c.asOf),
                for (final code in grouped.keys) ...[
                  const SizedBox(height: AppSpacing.lg),
                  _ReturnCard(periods: grouped[code]!),
                ],
              ],
            );
          },
        ),
      ],
    );
  }
}

/// What needs acting on, oldest first. The only part of the page anybody has to
/// read on a normal day.
class _Outstanding extends StatelessWidget {
  const _Outstanding({required this.outstanding, required this.asOf});
  final List<StatutoryObligation> outstanding;
  final String asOf;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    if (outstanding.isEmpty) {
      return Card(
        key: const Key('statutory-nothing-outstanding'),
        child: ListTile(
          leading: Icon(Icons.check_circle_outline,
              color: theme.colorScheme.primary),
          title: const Text('Nothing is outstanding'),
          subtitle: Text('Every return up to ${AppFormat.date(asOf)} is filed.'),
        ),
      );
    }
    final overdue = outstanding.where((o) => o.overdue).length;
    return Card(
      key: const Key('statutory-outstanding'),
      color: theme.colorScheme.errorContainer,
      child: Padding(
        padding: const EdgeInsets.all(AppSpacing.lg),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              overdue == 0
                  ? '${outstanding.length} to file'
                  : '$overdue overdue, ${outstanding.length} to file',
              style: theme.textTheme.titleMedium
                  ?.copyWith(color: theme.colorScheme.onErrorContainer),
            ),
            const SizedBox(height: AppSpacing.xs),
            Text(
              'Oldest first, which is the order to deal with them in.',
              style: theme.textTheme.bodySmall
                  ?.copyWith(color: theme.colorScheme.onErrorContainer),
            ),
            for (final o in outstanding.take(8))
              ListTile(
                key: Key('statutory-outstanding-${o.returnCode}-${o.periodStart}'),
                dense: true,
                contentPadding: EdgeInsets.zero,
                title: Text('${o.name} · ${o.periodLabel}'),
                subtitle: Text(o.overdue
                    ? 'Was due ${AppFormat.date(o.dueOn)}'
                    : 'Due ${AppFormat.date(o.dueOn)}'),
                trailing: _StateChip(state: o.state),
              ),
            if (outstanding.length > 8)
              Padding(
                padding: const EdgeInsets.only(top: AppSpacing.xs),
                child: Text('and ${outstanding.length - 8} more below.',
                    style: theme.textTheme.bodySmall),
              ),
          ],
        ),
      ),
    );
  }
}

/// One return, its instrument, where its export lives, and its periods.
class _ReturnCard extends ConsumerWidget {
  const _ReturnCard({required this.periods});
  final List<StatutoryObligation> periods;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final theme = Theme.of(context);
    final head = periods.first;
    return Card(
      key: Key('statutory-return-${head.returnCode}'),
      child: Padding(
        padding: const EdgeInsets.all(AppSpacing.lg),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Expanded(
                    child: Text(head.name, style: theme.textTheme.titleMedium)),
                Chip(
                  label: Text(head.scopeKind == 'REGIME'
                      ? '${head.scope} law'
                      : 'National law'),
                ),
              ],
            ),
            const SizedBox(height: AppSpacing.xs),
            Text(head.citation, style: theme.textTheme.bodySmall),
            if (head.exportService == null)
              Padding(
                padding: const EdgeInsets.only(top: AppSpacing.xs),
                child: Text(
                  'The platform cannot produce this one. It has to be prepared '
                  'outside Shelf-J — record it here once it has gone.',
                  style: theme.textTheme.bodySmall
                      ?.copyWith(color: theme.colorScheme.error),
                ),
              )
            else
              Padding(
                padding: const EdgeInsets.only(top: AppSpacing.xs),
                child: Text(
                  'Export: ${head.exportService} ${head.exportPath}',
                  style: theme.textTheme.bodySmall,
                ),
              ),
            const Divider(),
            for (final o in periods.take(13))
              ListTile(
                key: Key('statutory-period-${o.returnCode}-${o.periodStart}'),
                dense: true,
                contentPadding: EdgeInsets.zero,
                title: Text(o.periodLabel),
                subtitle: Text(_subtitle(o)),
                trailing: Row(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    _StateChip(state: o.state),
                    if (o.state != 'NOT_DUE')
                      IconButton(
                        key: Key('statutory-file-${o.returnCode}-${o.periodStart}'),
                        tooltip: o.filed ? 'Correct this filing' : 'Record filing',
                        icon: Icon(o.filed ? Icons.edit_note : Icons.upload_file),
                        onPressed: () => _record(context, ref, o),
                      ),
                  ],
                ),
              ),
          ],
        ),
      ),
    );
  }

  static String _subtitle(StatutoryObligation o) {
    final filing = o.filing;
    if (filing != null) {
      final receipt = filing.reference == null ? '' : ' · ${filing.reference}';
      return 'Filed ${AppFormat.date(filing.filedAt)} by ${filing.provider}$receipt';
    }
    return o.overdue
        ? 'Was due ${AppFormat.date(o.dueOn)}'
        : 'Due ${AppFormat.date(o.dueOn)}';
  }

  Future<void> _record(
      BuildContext context, WidgetRef ref, StatutoryObligation o) async {
    final recorded = await showDialog<bool>(
      context: context,
      builder: (_) => _RecordFilingDialog(obligation: o),
    );
    if (recorded == true) ref.invalidate(statutoryCalendarProvider);
  }
}

class _StateChip extends StatelessWidget {
  const _StateChip({required this.state});
  final String state;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final (label, colour) = switch (state) {
      'FILED' => ('Filed', scheme.primaryContainer),
      'OVERDUE' => ('Overdue', scheme.errorContainer),
      'DUE' => ('Due', scheme.tertiaryContainer),
      _ => ('Not due yet', scheme.surfaceContainerHighest),
    };
    return Chip(
      key: Key('statutory-state-$state'),
      label: Text(label),
      backgroundColor: colour,
      visualDensity: VisualDensity.compact,
    );
  }
}

/// Records that a return went — the authority's receipt, and the digest of what
/// was sent so the filing can be proved against an export produced later.
/// Correcting a filed period names the filing it replaces; both stay on the
/// record, which is why this dialog says so rather than offering to edit.
class _RecordFilingDialog extends ConsumerStatefulWidget {
  const _RecordFilingDialog({required this.obligation});
  final StatutoryObligation obligation;

  @override
  ConsumerState<_RecordFilingDialog> createState() => _RecordFilingDialogState();
}

class _RecordFilingDialogState extends ConsumerState<_RecordFilingDialog> {
  final _reference = TextEditingController();
  final _digest = TextEditingController();
  final _note = TextEditingController();
  String _provider = 'MANUAL';
  bool _saving = false;
  String? _error;

  @override
  void dispose() {
    _reference.dispose();
    _digest.dispose();
    _note.dispose();
    super.dispose();
  }

  bool get _correcting => widget.obligation.filing != null;

  Future<void> _save() async {
    setState(() {
      _saving = true;
      _error = null;
    });
    try {
      await ref.read(apiClientProvider).dio.post(
            '$_statutoryPath/${widget.obligation.returnCode}/filings',
            data: {
              'periodStart': widget.obligation.periodStart,
              'provider': _provider,
              if (_reference.text.trim().isNotEmpty)
                'reference': _reference.text.trim(),
              if (_digest.text.trim().isNotEmpty)
                'payloadDigest': _digest.text.trim(),
              if (_note.text.trim().isNotEmpty) 'note': _note.text.trim(),
              if (_correcting) 'supersedes': widget.obligation.filing!.id,
            },
          );
      if (mounted) Navigator.of(context).pop(true);
    } catch (e) {
      if (mounted) {
        setState(() {
          _saving = false;
          _error = friendlyError(e, fallback: 'Could not record the filing.');
        });
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final o = widget.obligation;
    return AlertDialog(
      key: const Key('statutory-record-filing'),
      title: Text(_correcting ? 'Correct a filing' : 'Record a filing'),
      content: SizedBox(
        width: 420,
        child: SingleChildScrollView(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Text('${o.name} · ${o.periodLabel}',
                  style: Theme.of(context).textTheme.titleSmall),
              const SizedBox(height: AppSpacing.xs),
              Text(
                _correcting
                    ? 'This replaces the filing recorded on '
                        '${AppFormat.date(o.filing!.filedAt)}. Both stay on the '
                        'record — a filing is never edited.'
                    : 'Record this once the return has actually gone. Nothing '
                        'here is sent to an authority.',
                style: Theme.of(context).textTheme.bodySmall,
              ),
              const SizedBox(height: AppSpacing.md),
              DropdownButtonFormField<String>(
                key: const Key('statutory-provider'),
                initialValue: _provider,
                // Without this the button sizes to its widest item and overflows the dialog: the
                // longest option is a sentence, and the dialog is 420 wide.
                isExpanded: true,
                decoration: const InputDecoration(labelText: 'How it went'),
                items: const [
                  DropdownMenuItem(
                      value: 'MANUAL', child: Text('By hand, on the portal')),
                  DropdownMenuItem(
                      value: 'HMRC_MTD', child: Text('HMRC Making Tax Digital')),
                  DropdownMenuItem(
                      value: 'SIMULATED',
                      child: Text('Simulated — nothing left the building')),
                ],
                onChanged: (v) => setState(() => _provider = v ?? 'MANUAL'),
              ),
              const SizedBox(height: AppSpacing.sm),
              TextField(
                key: const Key('statutory-reference'),
                controller: _reference,
                decoration: const InputDecoration(
                  labelText: 'The authority\'s receipt',
                  helperText: 'Where it gives one. Some do not.',
                ),
              ),
              const SizedBox(height: AppSpacing.sm),
              TextField(
                key: const Key('statutory-digest'),
                controller: _digest,
                decoration: const InputDecoration(
                  labelText: 'SHA-256 of what was sent',
                  helperText:
                      'Optional, and worth having: it proves this filing '
                      'against an export produced later.',
                ),
              ),
              const SizedBox(height: AppSpacing.sm),
              TextField(
                key: const Key('statutory-note'),
                controller: _note,
                maxLines: 2,
                decoration: const InputDecoration(labelText: 'Note'),
              ),
              if (_error != null)
                Padding(
                  padding: const EdgeInsets.only(top: AppSpacing.sm),
                  child: Text(_error!,
                      style: TextStyle(
                          color: Theme.of(context).colorScheme.error)),
                ),
            ],
          ),
        ),
      ),
      actions: [
        TextButton(
          onPressed: _saving ? null : () => Navigator.of(context).pop(false),
          child: const Text('Cancel'),
        ),
        FilledButton(
          key: const Key('statutory-record-save'),
          onPressed: _saving ? null : _save,
          child: Text(_correcting ? 'Record correction' : 'Record filing'),
        ),
      ],
    );
  }
}
