import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart';
import '../../core/auth/auth_notifier.dart';
import '../../core/auth/auth_state.dart';
import '../../core/network/api_client.dart';
import '../../core/network/api_error.dart';
import '../../core/spacing.dart';
import '../../shared/util/file_download.dart';
import '../../shared/widgets/error_view.dart';
import '../../shared/widgets/loading_view.dart';
import 'food_safety_providers.dart';
import 'providers/admin_providers.dart';

/// Temperature monitoring and HACCP checks for one store: what is due today,
/// the diary an inspector reads, and — for managers — the points and the
/// periodic sign-off.
class FoodSafetyScreen extends ConsumerWidget {
  const FoodSafetyScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final auth = ref.watch(authNotifierProvider).value;
    final isManager = auth is AuthAuthenticated && auth.isManager;
    final allowedStores =
        auth is AuthAuthenticated ? auth.storeIds : const <String>[];
    final storesAsync = ref.watch(storesProvider);

    final header = Padding(
      padding:
          const EdgeInsets.fromLTRB(AppSpacing.xl, AppSpacing.xl, AppSpacing.xl, 0),
      child: Text('Food safety',
          style: Theme.of(context).textTheme.headlineMedium),
    );

    if (storesAsync.hasError) {
      return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        header,
        Expanded(
          child: ErrorView(
            message: friendlyError(storesAsync.error!),
            onRetry: () => ref.invalidate(storesProvider),
          ),
        ),
      ]);
    }
    if (!storesAsync.hasValue) {
      return const LoadingView(label: 'Loading stores…');
    }
    // A store-bound member of staff sees only the stores they work in, which
    // is also all the server will let them record at.
    final stores = storesAsync.value!
        .where((s) => allowedStores.isEmpty || allowedStores.contains(s.id))
        .toList();
    if (stores.isEmpty) {
      return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        header,
        const Expanded(
          child: Center(child: Text('Add a store before setting up checks.')),
        ),
      ]);
    }
    final chosen = ref.watch(foodSafetyStoreProvider);
    final storeId = stores.any((s) => s.id == chosen) ? chosen! : stores.first.id;

    final tabs = [
      const Tab(text: 'Today'),
      const Tab(text: 'Diary'),
      if (isManager) const Tab(text: 'Setup'),
      if (isManager) const Tab(text: 'Reviews'),
    ];

    return DefaultTabController(
      key: ValueKey(isManager),
      length: tabs.length,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(
                AppSpacing.xl, AppSpacing.xl, AppSpacing.xl, 0),
            child: Wrap(
              spacing: AppSpacing.lg,
              runSpacing: AppSpacing.sm,
              crossAxisAlignment: WrapCrossAlignment.center,
              children: [
                Text('Food safety',
                    style: Theme.of(context).textTheme.headlineMedium),
                if (stores.length > 1)
                  DropdownButton<String>(
                    key: const Key('fs-store'),
                    value: storeId,
                    items: [
                      for (final s in stores)
                        DropdownMenuItem(value: s.id, child: Text(s.name)),
                    ],
                    onChanged: (v) =>
                        ref.read(foodSafetyStoreProvider.notifier).state = v,
                  )
                else
                  Text(stores.first.name,
                      style: Theme.of(context).textTheme.titleMedium),
              ],
            ),
          ),
          TabBar(isScrollable: true, tabs: tabs),
          Expanded(
            child: TabBarView(children: [
              _TodayTab(storeId: storeId),
              _DiaryTab(storeId: storeId),
              if (isManager) _SetupTab(storeId: storeId),
              if (isManager) _ReviewsTab(storeId: storeId),
            ]),
          ),
        ],
      ),
    );
  }
}

void _refreshChecks(WidgetRef ref) {
  ref.invalidate(foodSafetyPointsProvider);
  ref.invalidate(foodSafetyDiaryProvider);
  ref.invalidate(foodSafetyReviewsProvider);
}

String _every(int hours) => switch (hours) {
      1 => 'every hour',
      12 => 'twice a day',
      24 => 'daily',
      168 => 'weekly',
      _ when hours % 24 == 0 => 'every ${hours ~/ 24} days',
      _ => 'every $hours hours',
    };

final _whenFormat = DateFormat('d MMM HH:mm');

// ── Today ────────────────────────────────────────────────────────────────────

class _TodayTab extends ConsumerWidget {
  final String storeId;
  const _TodayTab({required this.storeId});

  static const _order = {'OVERDUE': 0, 'DUE': 1, 'OK': 2};

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final async = ref.watch(foodSafetyPointsProvider(FsPointsQuery(storeId)));
    // The error first: Riverpod retries a failed provider and reads as loading
    // while it does, and a day of checks that shows nothing reads as a day
    // with nothing due.
    if (async.hasError) {
      return ErrorView(
        message: friendlyError(async.error!,
            fallback: 'The checks could not be loaded, so nothing here is known to be done.'),
        onRetry: () => ref.invalidate(foodSafetyPointsProvider),
      );
    }
    if (!async.hasValue) return const LoadingView(label: 'Loading checks…');

    final points = [...async.value!]..sort((a, b) {
        final byStatus =
            (_order[a.dueStatus] ?? 3).compareTo(_order[b.dueStatus] ?? 3);
        return byStatus != 0 ? byStatus : a.name.compareTo(b.name);
      });
    if (points.isEmpty) {
      return const Center(
        child: Padding(
          padding: EdgeInsets.all(AppSpacing.xl),
          child: Text(
              'No checks are set up for this store. A manager adds chillers, '
              'freezers and daily checklists on the Setup tab.',
              textAlign: TextAlign.center),
        ),
      );
    }
    final overdue = points.where((p) => p.dueStatus == 'OVERDUE').length;
    final due = points.where((p) => p.dueStatus == 'DUE').length;
    final open = points.fold<int>(0, (n, p) => n + p.openFailures);

    return ListView(
      padding: const EdgeInsets.all(AppSpacing.xl),
      children: [
        Wrap(spacing: AppSpacing.sm, runSpacing: AppSpacing.sm, children: [
          _CountChip(label: '$overdue overdue', alert: overdue > 0),
          _CountChip(label: '$due due soon', alert: false),
          _CountChip(label: '$open open failures', alert: open > 0),
        ]),
        const SizedBox(height: AppSpacing.lg),
        for (final p in points) _PointTile(point: p),
      ],
    );
  }
}

class _CountChip extends StatelessWidget {
  final String label;
  final bool alert;
  const _CountChip({required this.label, required this.alert});

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Chip(
      label: Text(label),
      backgroundColor: alert ? cs.errorContainer : null,
      labelStyle: alert ? TextStyle(color: cs.onErrorContainer) : null,
    );
  }
}

class _PointTile extends ConsumerWidget {
  final FsPoint point;
  const _PointTile({required this.point});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final cs = Theme.of(context).colorScheme;
    final last = point.lastRecordedAt == null
        ? 'Not checked yet'
        : 'Last: ${point.lastValue != null ? '${point.lastValue!.toStringAsFixed(2)} °C ' : ''}'
            '${point.lastResult == 'FAIL' ? 'failed' : 'passed'} · ${_whenFormat.format(point.lastRecordedAt!)}';
    final limit = point.limitLabel;
    final (statusText, statusColor) = switch (point.dueStatus) {
      'OVERDUE' => ('Overdue', cs.error),
      'DUE' => ('Due', cs.tertiary),
      _ => ('Done', cs.primary),
    };
    return Card(
      child: ListTile(
        leading: Icon(point.checkType.isTemperature
            ? Icons.thermostat_outlined
            : Icons.checklist_outlined),
        title: Text(point.name),
        subtitle: Text([
          point.checkType.name,
          ?limit,
          _every(point.frequencyHours),
          last,
          if (point.openFailures > 0)
            '${point.openFailures} failure${point.openFailures == 1 ? '' : 's'} without a corrective action',
        ].join(' · ')),
        isThreeLine: true,
        trailing: Wrap(
          spacing: AppSpacing.sm,
          crossAxisAlignment: WrapCrossAlignment.center,
          children: [
            Text(statusText,
                style: TextStyle(color: statusColor, fontWeight: FontWeight.w600)),
            FilledButton.tonal(
              onPressed: () async {
                await showDialog<void>(
                  context: context,
                  barrierDismissible: false,
                  builder: (_) => RecordCheckDialog(point: point),
                );
                _refreshChecks(ref);
              },
              child: const Text('Record'),
            ),
          ],
        ),
      ),
    );
  }
}

/// Takes one check. A failure is saved as a failure at once — a failed
/// reading must be on record even if nothing is done about it yet — and the
/// dialog then asks what was done, which can be left for later but not
/// skipped silently.
class RecordCheckDialog extends ConsumerStatefulWidget {
  final FsPoint point;
  const RecordCheckDialog({super.key, required this.point});

  @override
  ConsumerState<RecordCheckDialog> createState() => _RecordCheckDialogState();
}

class _RecordCheckDialogState extends ConsumerState<RecordCheckDialog> {
  // One key for this attempt at this check, reused however many times Save is
  // pressed after a network failure, so the check is recorded once.
  final _key = newFoodSafetyKey();
  final _value = TextEditingController();
  final _notes = TextEditingController();
  bool? _passed;
  bool _saving = false;
  String? _error;
  FsRecord? _failed;

  @override
  void dispose() {
    _value.dispose();
    _notes.dispose();
    super.dispose();
  }

  /// The reading as typed, or null. A comma is a decimal point on many
  /// keyboards; more than two places is refused, as the server refuses it.
  double? get _reading {
    final text = _value.text.trim().replaceAll(',', '.');
    if (!RegExp(r'^-?\d{1,4}(\.\d{1,2})?$').hasMatch(text)) return null;
    return double.tryParse(text);
  }

  bool get _ready =>
      widget.point.checkType.isTemperature ? _reading != null : _passed != null;

  Future<void> _save() async {
    setState(() {
      _saving = true;
      _error = null;
    });
    try {
      final record = await recordFoodSafetyCheck(
        ref.read(apiClientProvider).dio,
        pointId: widget.point.id,
        value: widget.point.checkType.isTemperature ? _reading : null,
        passed: widget.point.checkType.isTemperature ? null : _passed,
        notes: _notes.text,
        idempotencyKey: _key,
      );
      if (!mounted) return;
      if (record.failed && record.openFailure) {
        setState(() {
          _failed = record;
          _saving = false;
        });
      } else {
        Navigator.of(context).pop();
      }
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _saving = false;
        _error = friendlyError(e, fallback: 'The check was not saved. Try again.');
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    if (_failed != null) {
      return CorrectiveActionDialog(record: _failed!, fromRecording: true);
    }
    final point = widget.point;
    final cs = Theme.of(context).colorScheme;
    final reading = _reading;
    final preview = reading == null ? null : point.preview(reading);
    return AlertDialog(
      title: Text(point.name),
      content: SizedBox(
        width: 380,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(point.checkType.name,
                style: Theme.of(context).textTheme.titleSmall),
            if (point.limitLabel != null)
              Text(
                'Limit ${point.limitLabel}'
                '${point.checkType.statutory ? ' — a legal limit' : ''}',
              ),
            const SizedBox(height: AppSpacing.lg),
            if (point.checkType.isTemperature) ...[
              TextField(
                key: const Key('fs-reading'),
                controller: _value,
                autofocus: true,
                keyboardType: const TextInputType.numberWithOptions(
                    signed: true, decimal: true),
                inputFormatters: [
                  FilteringTextInputFormatter.allow(RegExp(r'[-0-9.,]')),
                ],
                decoration: const InputDecoration(
                  labelText: 'Reading',
                  suffixText: '°C',
                  helperText: 'The probe or display reading, to two decimal places at most',
                ),
                onChanged: (_) => setState(() {}),
              ),
              if (preview != null)
                Padding(
                  padding: const EdgeInsets.only(top: AppSpacing.sm),
                  child: Text(
                    preview == 'PASS' ? 'Within the limit' : 'Outside the limit — this will be recorded as a failure',
                    style: TextStyle(
                      color: preview == 'PASS' ? cs.primary : cs.error,
                      fontWeight: FontWeight.w600,
                    ),
                  ),
                ),
            ] else
              SegmentedButton<bool>(
                emptySelectionAllowed: true,
                segments: const [
                  ButtonSegment(value: true, label: Text('Passed')),
                  ButtonSegment(value: false, label: Text('Failed')),
                ],
                selected: {?_passed},
                onSelectionChanged: (s) =>
                    setState(() => _passed = s.isEmpty ? null : s.first),
              ),
            const SizedBox(height: AppSpacing.md),
            TextField(
              controller: _notes,
              maxLength: 2000,
              decoration: const InputDecoration(labelText: 'Notes (optional)'),
            ),
            if (_error != null)
              Text(_error!, style: TextStyle(color: cs.error)),
          ],
        ),
      ),
      actions: [
        TextButton(
          onPressed: _saving ? null : () => Navigator.of(context).pop(),
          child: const Text('Cancel'),
        ),
        FilledButton(
          onPressed: _saving || !_ready ? null : _save,
          child: Text(_saving ? 'Saving…' : 'Save'),
        ),
      ],
    );
  }
}

/// What was done about a failed check, and what happened to the food.
class CorrectiveActionDialog extends ConsumerStatefulWidget {
  final FsRecord record;

  /// True straight after the failing check was saved, when the dialog says so.
  final bool fromRecording;
  const CorrectiveActionDialog(
      {super.key, required this.record, this.fromRecording = false});

  @override
  ConsumerState<CorrectiveActionDialog> createState() =>
      _CorrectiveActionDialogState();
}

class _CorrectiveActionDialogState extends ConsumerState<CorrectiveActionDialog> {
  final _action = TextEditingController();
  String _disposition = 'NONE';
  bool _saving = false;
  String? _error;

  @override
  void dispose() {
    _action.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    setState(() {
      _saving = true;
      _error = null;
    });
    try {
      await addFoodSafetyCorrectiveAction(
        ref.read(apiClientProvider).dio,
        recordId: widget.record.id,
        action: _action.text,
        foodDisposition: _disposition,
      );
      if (mounted) Navigator.of(context).pop();
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _saving = false;
        _error = friendlyError(e, fallback: 'The action was not saved. Try again.');
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final r = widget.record;
    final cs = Theme.of(context).colorScheme;
    final limits = formatLimits(r.minValue, r.maxValue);
    return AlertDialog(
      title: const Text('Record what was done'),
      content: SizedBox(
        width: 380,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              '${widget.fromRecording ? 'Saved as a failure. ' : ''}'
              '${r.pointName}: ${r.reading}${limits != null ? ' against $limits' : ''}.',
              style: TextStyle(color: cs.error),
            ),
            const SizedBox(height: AppSpacing.md),
            TextField(
              key: const Key('fs-action'),
              controller: _action,
              autofocus: true,
              maxLength: 2000,
              minLines: 2,
              maxLines: 4,
              decoration: const InputDecoration(
                labelText: 'Action taken',
                hintText: 'e.g. Moved stock to the walk-in and called the engineer',
              ),
              onChanged: (_) => setState(() {}),
            ),
            DropdownButtonFormField<String>(
              key: const Key('fs-disposition'),
              initialValue: _disposition,
              decoration: const InputDecoration(labelText: 'The food'),
              items: [
                for (final e in foodDispositions.entries)
                  DropdownMenuItem(value: e.key, child: Text(e.value)),
              ],
              onChanged: (v) => setState(() => _disposition = v ?? 'NONE'),
            ),
            if (_error != null)
              Padding(
                padding: const EdgeInsets.only(top: AppSpacing.sm),
                child: Text(_error!, style: TextStyle(color: cs.error)),
              ),
          ],
        ),
      ),
      actions: [
        TextButton(
          onPressed: _saving ? null : () => Navigator.of(context).pop(),
          child: const Text('Record later'),
        ),
        FilledButton(
          onPressed: _saving || _action.text.trim().isEmpty ? null : _save,
          child: Text(_saving ? 'Saving…' : 'Save action'),
        ),
      ],
    );
  }
}

// ── Diary ────────────────────────────────────────────────────────────────────

class _DiaryTab extends ConsumerStatefulWidget {
  final String storeId;
  const _DiaryTab({required this.storeId});

  @override
  ConsumerState<_DiaryTab> createState() => _DiaryTabState();
}

class _DiaryTabState extends ConsumerState<_DiaryTab> {
  int _days = 1;
  bool _openOnly = false;

  FsDiaryQuery get _query {
    final now = DateTime.now();
    final today = DateTime(now.year, now.month, now.day);
    return FsDiaryQuery(
      storeId: widget.storeId,
      openOnly: _openOnly,
      fromDay: today.subtract(Duration(days: _days - 1)),
      toDay: today,
    );
  }

  void _export(List<FsRecord> records, FsDiaryQuery q) {
    String cell(Object? v) {
      final s = v?.toString() ?? '';
      return s.contains(RegExp(r'[",\n]')) ? '"${s.replaceAll('"', '""')}"' : s;
    }

    final rows = [
      'recorded_at_utc,point,check,reading_c,min_c,max_c,result,corrective_actions,notes',
      for (final r in records)
        [
          r.recordedAt?.toUtc().toIso8601String(),
          r.pointName,
          r.checkTypeName,
          r.value?.toStringAsFixed(2),
          r.minValue?.toStringAsFixed(2),
          r.maxValue?.toStringAsFixed(2),
          r.result,
          r.correctiveActionCount,
          r.notes,
        ].map(cell).join(','),
    ];
    final day = DateFormat('yyyy-MM-dd');
    downloadTextFile(
      'food-safety-diary-${day.format(q.fromDay)}-to-${day.format(q.toDay)}.csv',
      '${rows.join('\n')}\n',
      mimeType: 'text/csv;charset=utf-8',
    );
  }

  @override
  Widget build(BuildContext context) {
    final q = _query;
    final async = ref.watch(foodSafetyDiaryProvider(q));
    final cs = Theme.of(context).colorScheme;

    final controls = Padding(
      padding: const EdgeInsets.fromLTRB(
          AppSpacing.xl, AppSpacing.lg, AppSpacing.xl, 0),
      child: Wrap(
        spacing: AppSpacing.sm,
        runSpacing: AppSpacing.sm,
        crossAxisAlignment: WrapCrossAlignment.center,
        children: [
          for (final (days, label) in [(1, 'Today'), (7, '7 days'), (28, '28 days')])
            ChoiceChip(
              label: Text(label),
              selected: _days == days,
              onSelected: (_) => setState(() => _days = days),
            ),
          FilterChip(
            label: const Text('Open failures only'),
            selected: _openOnly,
            onSelected: (v) => setState(() => _openOnly = v),
          ),
          if (async.hasValue && async.value!.isNotEmpty)
            OutlinedButton.icon(
              onPressed: () => _export(async.value!, q),
              icon: const Icon(Icons.download_outlined),
              label: const Text('Export CSV'),
            ),
        ],
      ),
    );

    final Widget body;
    if (async.hasError) {
      body = ErrorView(
        message: friendlyError(async.error!),
        onRetry: () => ref.invalidate(foodSafetyDiaryProvider),
      );
    } else if (!async.hasValue) {
      body = const LoadingView(label: 'Loading the diary…');
    } else if (async.value!.isEmpty) {
      body = Center(
        child: Text(_openOnly
            ? 'No failures are waiting for a corrective action.'
            : 'No checks recorded in this period.'),
      );
    } else {
      body = ListView.separated(
        padding: const EdgeInsets.all(AppSpacing.xl),
        itemCount: async.value!.length,
        separatorBuilder: (_, _) => const Divider(height: 1),
        itemBuilder: (context, i) {
          final r = async.value![i];
          return ListTile(
            leading: Icon(
              r.failed ? Icons.error_outline : Icons.check_circle_outline,
              color: r.failed ? cs.error : cs.primary,
            ),
            title: Text('${r.pointName} · ${r.reading}'),
            subtitle: Text([
              if (r.recordedAt != null) _whenFormat.format(r.recordedAt!),
              r.checkTypeName,
              if (r.failed && !r.openFailure)
                '${r.correctiveActionCount} corrective action${r.correctiveActionCount == 1 ? '' : 's'}',
              ?r.notes,
            ].join(' · ')),
            trailing: r.openFailure
                ? FilledButton.tonal(
                    onPressed: () async {
                      await showDialog<void>(
                        context: context,
                        builder: (_) => CorrectiveActionDialog(record: r),
                      );
                      _refreshChecks(ref);
                    },
                    child: const Text('Record action'),
                  )
                : null,
          );
        },
      );
    }
    return Column(children: [controls, Expanded(child: body)]);
  }
}

// ── Setup (management) ───────────────────────────────────────────────────────

class _SetupTab extends ConsumerWidget {
  final String storeId;
  const _SetupTab({required this.storeId});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final async = ref.watch(
        foodSafetyPointsProvider(FsPointsQuery(storeId, includeInactive: true)));
    final Widget list;
    if (async.hasError) {
      list = ErrorView(
        message: friendlyError(async.error!),
        onRetry: () => ref.invalidate(foodSafetyPointsProvider),
      );
    } else if (!async.hasValue) {
      list = const LoadingView(label: 'Loading points…');
    } else {
      final points = [...async.value!]..sort((a, b) => a.name.compareTo(b.name));
      list = ListView(
        padding: const EdgeInsets.all(AppSpacing.xl),
        children: [
          for (final p in points)
            Card(
              child: ListTile(
                title: Text(p.active ? p.name : '${p.name} (switched off)'),
                subtitle: Text([
                  p.checkType.name,
                  ?p.limitLabel,
                  _every(p.frequencyHours),
                ].join(' · ')),
                trailing: Wrap(spacing: AppSpacing.sm, children: [
                  TextButton(
                    onPressed: () async {
                      await showDialog<void>(
                        context: context,
                        builder: (_) => PointDialog(storeId: storeId, point: p),
                      );
                      _refreshChecks(ref);
                    },
                    child: const Text('Edit'),
                  ),
                  TextButton(
                    onPressed: () => _switch(context, ref, p),
                    child: Text(p.active ? 'Switch off' : 'Switch on'),
                  ),
                ]),
              ),
            ),
        ],
      );
    }
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(
              AppSpacing.xl, AppSpacing.lg, AppSpacing.xl, 0),
          child: FilledButton.icon(
            onPressed: () async {
              await showDialog<void>(
                context: context,
                builder: (_) => PointDialog(storeId: storeId),
              );
              _refreshChecks(ref);
            },
            icon: const Icon(Icons.add),
            label: const Text('Add a check'),
          ),
        ),
        Expanded(child: list),
      ],
    );
  }

  Future<void> _switch(BuildContext context, WidgetRef ref, FsPoint p) async {
    final reason = await showDialog<String>(
      context: context,
      builder: (_) => _ReasonDialog(
        title: p.active ? 'Switch off ${p.name}?' : 'Switch on ${p.name}?',
        explanation: p.active
            ? 'It stops being due and stops alerting. Its records are kept.'
            : 'It becomes due again straight away.',
      ),
    );
    if (reason == null) return;
    try {
      await switchFoodSafetyPoint(ref.read(apiClientProvider).dio,
          pointId: p.id, active: !p.active, reason: reason);
      _refreshChecks(ref);
    } catch (e) {
      if (context.mounted) {
        ScaffoldMessenger.of(context)
            .showSnackBar(SnackBar(content: Text(friendlyError(e))));
      }
    }
  }
}

class _ReasonDialog extends StatefulWidget {
  final String title;
  final String explanation;
  final String label;
  const _ReasonDialog(
      {required this.title, required this.explanation, this.label = 'Reason'});

  @override
  State<_ReasonDialog> createState() => _ReasonDialogState();
}

class _ReasonDialogState extends State<_ReasonDialog> {
  final _reason = TextEditingController();

  @override
  void dispose() {
    _reason.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: Text(widget.title),
      content: Column(mainAxisSize: MainAxisSize.min, children: [
        Text(widget.explanation),
        TextField(
          key: const Key('fs-reason'),
          controller: _reason,
          autofocus: true,
          maxLength: 500,
          decoration: InputDecoration(labelText: widget.label),
          onChanged: (_) => setState(() {}),
        ),
      ]),
      actions: [
        TextButton(
            onPressed: () => Navigator.of(context).pop(),
            child: const Text('Cancel')),
        FilledButton(
          onPressed: _reason.text.trim().isEmpty
              ? null
              : () => Navigator.of(context).pop(_reason.text.trim()),
          child: const Text('Confirm'),
        ),
      ],
    );
  }
}

/// Adds or edits a monitoring point. Limits start from the check type's and
/// may only be stricter; the server refuses a laxer one and its message is
/// shown as it is.
class PointDialog extends ConsumerStatefulWidget {
  final String storeId;
  final FsPoint? point;
  const PointDialog({super.key, required this.storeId, this.point});

  @override
  ConsumerState<PointDialog> createState() => _PointDialogState();
}

class _PointDialogState extends ConsumerState<PointDialog> {
  late final _name = TextEditingController(text: widget.point?.name ?? '');
  late final _min = TextEditingController(
      text: widget.point?.minValue?.toStringAsFixed(2) ?? '');
  late final _max = TextEditingController(
      text: widget.point?.maxValue?.toStringAsFixed(2) ?? '');
  late String? _typeId = widget.point?.checkType.id;
  late int _hours = widget.point?.frequencyHours ?? 4;
  bool _saving = false;
  String? _error;

  static const _frequencies = [1, 2, 4, 6, 8, 12, 24, 48, 168];

  @override
  void dispose() {
    _name.dispose();
    _min.dispose();
    _max.dispose();
    super.dispose();
  }

  double? _parse(TextEditingController c) =>
      double.tryParse(c.text.trim().replaceAll(',', '.'));

  Future<void> _save(FsCheckType type) async {
    setState(() {
      _saving = true;
      _error = null;
    });
    try {
      await saveFoodSafetyPoint(
        ref.read(apiClientProvider).dio,
        pointId: widget.point?.id,
        storeId: widget.storeId,
        name: _name.text,
        checkTypeId: type.id,
        minValue: type.isTemperature ? _parse(_min) : null,
        maxValue: type.isTemperature ? _parse(_max) : null,
        frequencyHours: _hours,
      );
      if (mounted) Navigator.of(context).pop();
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _saving = false;
        _error = friendlyError(e, fallback: 'The check was not saved.');
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final typesAsync = ref.watch(foodSafetyCheckTypesProvider);
    final cs = Theme.of(context).colorScheme;
    final editing = widget.point != null;
    final types = typesAsync.value ?? const <FsCheckType>[];
    final type = editing
        ? widget.point!.checkType
        : types.where((t) => t.id == _typeId).firstOrNull;

    return AlertDialog(
      title: Text(editing ? 'Edit ${widget.point!.name}' : 'Add a check'),
      content: SizedBox(
        width: 400,
        child: typesAsync.hasError && !editing
            ? Text(friendlyError(typesAsync.error!),
                style: TextStyle(color: cs.error))
            : Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  TextField(
                    key: const Key('fs-point-name'),
                    controller: _name,
                    decoration: const InputDecoration(
                        labelText: 'Name', hintText: 'e.g. Dairy chiller 2'),
                    onChanged: (_) => setState(() {}),
                  ),
                  if (!editing)
                    DropdownButtonFormField<String>(
                      key: const Key('fs-point-type'),
                      initialValue: _typeId,
                      decoration: const InputDecoration(labelText: 'Check'),
                      items: [
                        for (final t in types)
                          DropdownMenuItem(value: t.id, child: Text(t.name)),
                      ],
                      onChanged: (v) => setState(() => _typeId = v),
                    ),
                  if (type != null && type.isTemperature) ...[
                    Padding(
                      padding: const EdgeInsets.only(top: AppSpacing.md),
                      child: Text(
                        '${type.statutory ? 'Legal limit' : 'Limit'} '
                        '${formatLimits(type.minValue, type.maxValue)}. '
                        'You can set a stricter limit here, not a laxer one.',
                      ),
                    ),
                    Row(children: [
                      Expanded(
                        child: TextField(
                          controller: _min,
                          keyboardType: const TextInputType.numberWithOptions(
                              signed: true, decimal: true),
                          decoration: const InputDecoration(
                              labelText: 'Lowest', suffixText: '°C'),
                        ),
                      ),
                      const SizedBox(width: AppSpacing.md),
                      Expanded(
                        child: TextField(
                          controller: _max,
                          keyboardType: const TextInputType.numberWithOptions(
                              signed: true, decimal: true),
                          decoration: const InputDecoration(
                              labelText: 'Highest', suffixText: '°C'),
                        ),
                      ),
                    ]),
                  ],
                  DropdownButtonFormField<int>(
                    initialValue: _hours,
                    decoration: const InputDecoration(labelText: 'How often'),
                    items: [
                      for (final h in _frequencies)
                        DropdownMenuItem(
                            value: h,
                            child: Text(_every(h).replaceFirstMapped(
                                RegExp(r'^.'), (m) => m[0]!.toUpperCase()))),
                    ],
                    onChanged: (v) => setState(() => _hours = v ?? _hours),
                  ),
                  if (_error != null)
                    Padding(
                      padding: const EdgeInsets.only(top: AppSpacing.sm),
                      child: Text(_error!, style: TextStyle(color: cs.error)),
                    ),
                ],
              ),
      ),
      actions: [
        TextButton(
            onPressed: _saving ? null : () => Navigator.of(context).pop(),
            child: const Text('Cancel')),
        FilledButton(
          onPressed: _saving || type == null || _name.text.trim().isEmpty
              ? null
              : () => _save(type),
          child: Text(_saving ? 'Saving…' : 'Save'),
        ),
      ],
    );
  }
}

// ── Reviews (management) ─────────────────────────────────────────────────────

class _ReviewsTab extends ConsumerWidget {
  final String storeId;
  const _ReviewsTab({required this.storeId});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final async = ref.watch(foodSafetyReviewsProvider(storeId));
    final day = DateFormat('d MMM');
    final Widget list;
    if (async.hasError) {
      list = ErrorView(
        message: friendlyError(async.error!),
        onRetry: () => ref.invalidate(foodSafetyReviewsProvider),
      );
    } else if (!async.hasValue) {
      list = const LoadingView(label: 'Loading reviews…');
    } else if (async.value!.isEmpty) {
      list = const Center(child: Text('No reviews signed off for this store yet.'));
    } else {
      list = ListView(
        padding: const EdgeInsets.all(AppSpacing.xl),
        children: [
          for (final r in async.value!)
            Card(
              child: ListTile(
                title: Text(
                    '${r.periodFrom != null ? day.format(r.periodFrom!) : '?'} – '
                    '${r.periodTo != null ? day.format(r.periodTo!.subtract(const Duration(days: 1))) : '?'}'),
                subtitle: Text([
                  '${r.recordsCount} checks, ${r.failuresCount} failed, '
                      '${r.openFailuresCount} without a corrective action at sign-off',
                  ?r.notes,
                ].join(' · ')),
                trailing: r.reviewedAt == null
                    ? null
                    : Text('Signed ${_whenFormat.format(r.reviewedAt!)}'),
              ),
            ),
        ],
      );
    }
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(
              AppSpacing.xl, AppSpacing.lg, AppSpacing.xl, 0),
          child: FilledButton.icon(
            onPressed: () => _signOff(context, ref),
            icon: const Icon(Icons.verified_outlined),
            label: const Text('Sign off the last 28 days'),
          ),
        ),
        Expanded(child: list),
      ],
    );
  }

  Future<void> _signOff(BuildContext context, WidgetRef ref) async {
    final notes = await showDialog<String>(
      context: context,
      builder: (_) => const _ReasonDialog(
        title: 'Sign off the last 28 days?',
        explanation:
            'Records that you have reviewed this store\'s checks, with the '
            'counts as they stand now.',
        label: 'Notes',
      ),
    );
    if (notes == null) return;
    final now = DateTime.now();
    final today = DateTime(now.year, now.month, now.day);
    try {
      await signOffFoodSafetyReview(
        ref.read(apiClientProvider).dio,
        storeId: storeId,
        fromDay: today.subtract(const Duration(days: 27)),
        toDay: today,
        notes: notes,
      );
      ref.invalidate(foodSafetyReviewsProvider);
    } catch (e) {
      if (context.mounted) {
        ScaffoldMessenger.of(context)
            .showSnackBar(SnackBar(content: Text(friendlyError(e))));
      }
    }
  }
}
