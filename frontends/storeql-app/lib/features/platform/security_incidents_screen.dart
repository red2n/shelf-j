import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/constants.dart';
import '../../core/format.dart';
import '../../core/network/api_client.dart';
import '../../core/network/api_error.dart';
import '../../core/spacing.dart';
import '../../shared/widgets/error_view.dart';
import '../../shared/widgets/loading_view.dart';
import '../admin/providers/admin_providers.dart';

// ---------------------------------------------------------------------------
// The platform's security incident register (21.15).
//
// tenant-svc holds the stages the law sets for each kind of incident, with
// their clocks and citations, and works out on every read what is due, done or
// overdue. This screen records what was reported, when and under which
// reference, and tells the businesses an incident affects. The reports
// themselves are made on the authorities' own platforms.
// ---------------------------------------------------------------------------

const incidentKindLabels = {
  'EXPLOITED_VULNERABILITY': 'Actively exploited vulnerability',
  'SEVERE_INCIDENT': 'Severe incident',
  'PERSONAL_DATA_BREACH': 'Personal data breach',
};

const _stageLabels = {
  'EARLY_WARNING': 'Early warning',
  'NOTIFICATION': 'Notification',
  'FINAL_REPORT': 'Final report',
  'TENANT_NOTICE': 'Businesses told',
};

const _eventLabels = {
  'EARLY_WARNING_SENT': 'Early warning sent',
  'NOTIFICATION_SENT': 'Notification sent',
  'MITIGATION_AVAILABLE': 'Corrective measure available',
  'FINAL_REPORT_SENT': 'Final report sent',
  'TENANTS_NOTIFIED': 'Businesses told',
  'NOTE': 'Note',
  'CLOSED': 'Closed',
};

/// The stage each report completes; the rest are not stages.
const _stageOf = {
  'EARLY_WARNING_SENT': 'EARLY_WARNING',
  'NOTIFICATION_SENT': 'NOTIFICATION',
  'FINAL_REPORT_SENT': 'FINAL_REPORT',
};

String _incidents([String suffix = '']) =>
    '/${ApiConstants.tenant}/platform/security-incidents$suffix';

class IncidentStage {
  final String stage;
  final String summary;
  final String citation;
  final String? dueAt;
  final String? doneAt;
  final String state;

  const IncidentStage({
    required this.stage,
    required this.summary,
    required this.citation,
    this.dueAt,
    this.doneAt,
    required this.state,
  });

  factory IncidentStage.fromJson(Map<String, dynamic> j) => IncidentStage(
        stage: j['stage'] as String? ?? '',
        summary: j['summary'] as String? ?? '',
        citation: j['citation'] as String? ?? '',
        dueAt: j['dueAt'] as String?,
        doneAt: j['doneAt'] as String?,
        state: j['state'] as String? ?? '',
      );
}

class IncidentEntry {
  final String kind;
  final String occurredAt;
  final String? reference;
  final String? note;

  const IncidentEntry(
      {required this.kind, required this.occurredAt, this.reference, this.note});

  factory IncidentEntry.fromJson(Map<String, dynamic> j) => IncidentEntry(
        kind: j['kind'] as String? ?? '',
        occurredAt: j['occurredAt'] as String? ?? '',
        reference: j['reference'] as String?,
        note: j['note'] as String?,
      );
}

class SecurityIncident {
  final String id;
  final String kind;
  final String title;
  final String summary;
  final String awareAt;
  final bool affectsAllTenants;
  final List<String> tenantIds;
  final String status;
  final List<IncidentStage> stages;
  final List<IncidentEntry> events;
  final int noticesIssued;
  final int noticesAcknowledged;

  const SecurityIncident({
    required this.id,
    required this.kind,
    required this.title,
    required this.summary,
    required this.awareAt,
    required this.affectsAllTenants,
    required this.tenantIds,
    required this.status,
    required this.stages,
    required this.events,
    required this.noticesIssued,
    required this.noticesAcknowledged,
  });

  bool get open => status != 'CLOSED';

  factory SecurityIncident.fromJson(Map<String, dynamic> j) => SecurityIncident(
        id: j['id'] as String? ?? '',
        kind: j['kind'] as String? ?? '',
        title: j['title'] as String? ?? '',
        summary: j['summary'] as String? ?? '',
        awareAt: j['awareAt'] as String? ?? '',
        affectsAllTenants: j['affectsAllTenants'] as bool? ?? false,
        tenantIds: [for (final t in (j['tenantIds'] as List?) ?? const []) t as String],
        status: j['status'] as String? ?? '',
        stages: [
          for (final s in (j['stages'] as List?) ?? const [])
            IncidentStage.fromJson(s as Map<String, dynamic>)
        ],
        events: [
          for (final e in (j['events'] as List?) ?? const [])
            IncidentEntry.fromJson(e as Map<String, dynamic>)
        ],
        noticesIssued: (j['noticesIssued'] as num?)?.toInt() ?? 0,
        noticesAcknowledged: (j['noticesAcknowledged'] as num?)?.toInt() ?? 0,
      );
}

class IncidentSummary {
  final String id;
  final String kind;
  final String title;
  final String awareAt;
  final String status;
  final String? nextStage;
  final String? nextDueAt;
  final bool overdue;

  const IncidentSummary({
    required this.id,
    required this.kind,
    required this.title,
    required this.awareAt,
    required this.status,
    this.nextStage,
    this.nextDueAt,
    required this.overdue,
  });

  factory IncidentSummary.fromJson(Map<String, dynamic> j) => IncidentSummary(
        id: j['id'] as String? ?? '',
        kind: j['kind'] as String? ?? '',
        title: j['title'] as String? ?? '',
        awareAt: j['awareAt'] as String? ?? '',
        status: j['status'] as String? ?? '',
        nextStage: j['nextStage'] as String?,
        nextDueAt: j['nextDueAt'] as String?,
        overdue: j['overdue'] as bool? ?? false,
      );
}

/// Incidents by status: `OPEN`, `CLOSED`, or empty for all.
final securityIncidentsProvider = FutureProvider.autoDispose
    .family<List<IncidentSummary>, String>((ref, status) async {
  final resp = await ref.read(apiClientProvider).dio.get(_incidents(),
      queryParameters: {if (status.isNotEmpty) 'status': status});
  return [
    for (final j in (resp.data['data'] as List?) ?? const [])
      IncidentSummary.fromJson(j as Map<String, dynamic>)
  ];
});

final securityIncidentProvider = FutureProvider.autoDispose
    .family<SecurityIncident, String>((ref, id) async {
  final resp = await ref.read(apiClientProvider).dio.get(_incidents('/$id'));
  return SecurityIncident.fromJson(resp.data['data'] as Map<String, dynamic>);
});

/// What can still be recorded against an incident, in the order it happens.
/// The server decides; this only keeps impossible choices off the list.
List<String> recordableEvents(SecurityIncident i) {
  final done = {for (final e in i.events) e.kind};
  final stages = {for (final s in i.stages) s.stage};
  return [
    for (final k in const [
      'EARLY_WARNING_SENT',
      'NOTIFICATION_SENT',
      'MITIGATION_AVAILABLE',
      'FINAL_REPORT_SENT'
    ])
      if (!done.contains(k) &&
          (k == 'MITIGATION_AVAILABLE'
              ? i.kind == 'EXPLOITED_VULNERABILITY'
              : stages.contains(_stageOf[k])))
        k,
    'NOTE',
    'CLOSED',
  ];
}

class SecurityIncidentsScreen extends ConsumerStatefulWidget {
  const SecurityIncidentsScreen({super.key});

  @override
  ConsumerState<SecurityIncidentsScreen> createState() =>
      _SecurityIncidentsScreenState();
}

class _SecurityIncidentsScreenState
    extends ConsumerState<SecurityIncidentsScreen> {
  String _status = 'OPEN';
  String? _selected;

  @override
  Widget build(BuildContext context) {
    final selected = _selected;
    if (selected != null) {
      return _IncidentDetail(
          id: selected, onBack: () => setState(() => _selected = null));
    }
    final theme = Theme.of(context);
    final incidents = ref.watch(securityIncidentsProvider(_status));
    return ListView(
      padding: const EdgeInsets.all(AppSpacing.xl),
      children: [
        Wrap(
          spacing: AppSpacing.lg,
          runSpacing: AppSpacing.sm,
          crossAxisAlignment: WrapCrossAlignment.center,
          alignment: WrapAlignment.spaceBetween,
          children: [
            Text('Security incidents', style: theme.textTheme.headlineMedium),
            FilledButton.icon(
              key: const Key('open-incident'),
              onPressed: _open,
              icon: const Icon(Icons.add_moderator_outlined),
              label: const Text('Open incident'),
            ),
          ],
        ),
        const SizedBox(height: 4),
        Text(
          'What the Cyber Resilience Act and GDPR ask the platform to report, '
          'and by when: an early warning within 24 hours of becoming aware of '
          'an actively exploited vulnerability or a severe incident, a '
          'notification within 72, a final report after; and word to every '
          'business a breach of its customers’ data affects. The reports '
          'are made on the authorities’ platforms and recorded here with '
          'their references.',
          style: theme.textTheme.bodyMedium
              ?.copyWith(color: theme.colorScheme.onSurfaceVariant),
        ),
        const SizedBox(height: AppSpacing.lg),
        SegmentedButton<String>(
          segments: const [
            ButtonSegment(value: 'OPEN', label: Text('Open')),
            ButtonSegment(value: 'CLOSED', label: Text('Closed')),
            ButtonSegment(value: '', label: Text('All')),
          ],
          selected: {_status},
          onSelectionChanged: (s) => setState(() => _status = s.first),
        ),
        const SizedBox(height: AppSpacing.lg),
        incidents.when(
          loading: () => const LoadingView(label: 'Loading incidents…'),
          error: (e, _) => ErrorView(
            message: friendlyError(e,
                fallback: 'Could not load the security incidents.'),
            onRetry: () => ref.invalidate(securityIncidentsProvider(_status)),
          ),
          data: (list) => list.isEmpty
              ? Text(_status == 'OPEN'
                  ? 'No open incidents.'
                  : 'No incidents on the register.')
              : Card(
                  child: Column(children: [
                    for (final i in list)
                      _SummaryTile(
                          incident: i,
                          onTap: () => setState(() => _selected = i.id)),
                  ]),
                ),
        ),
      ],
    );
  }

  Future<void> _open() async {
    final id = await showDialog<String>(
        context: context, builder: (_) => const _OpenIncidentDialog());
    if (id == null || !mounted) return;
    ref.invalidate(securityIncidentsProvider);
    setState(() => _selected = id);
  }
}

class _SummaryTile extends StatelessWidget {
  final IncidentSummary incident;
  final VoidCallback onTap;

  const _SummaryTile({required this.incident, required this.onTap});

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final next = incident.nextStage;
    return ListTile(
      key: Key('incident-${incident.id}'),
      onTap: onTap,
      title: Text(incident.title),
      subtitle: Text([
        incidentKindLabels[incident.kind] ?? incident.kind,
        'aware ${AppFormat.dateTime(incident.awareAt)}',
        if (next != null)
          'next: ${_stageLabels[next] ?? next} by ${AppFormat.dateTime(incident.nextDueAt)}',
      ].join(' · ')),
      trailing: incident.overdue
          ? Chip(
              label: const Text('Overdue'),
              backgroundColor: cs.errorContainer,
              labelStyle: TextStyle(color: cs.onErrorContainer))
          : Chip(
              label: Text(incident.status == 'CLOSED' ? 'Closed' : 'Open')),
    );
  }
}

class _IncidentDetail extends ConsumerWidget {
  final String id;
  final VoidCallback onBack;

  const _IncidentDetail({required this.id, required this.onBack});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final theme = Theme.of(context);
    final sheet = ref.watch(securityIncidentProvider(id));
    return ListView(
      padding: const EdgeInsets.all(AppSpacing.xl),
      children: [
        Align(
          alignment: Alignment.centerLeft,
          child: TextButton.icon(
              onPressed: onBack,
              icon: const Icon(Icons.arrow_back),
              label: const Text('All incidents')),
        ),
        sheet.when(
          loading: () => const LoadingView(label: 'Loading the incident…'),
          error: (e, _) => ErrorView(
            message: friendlyError(e, fallback: 'Could not load the incident.'),
            onRetry: () => ref.invalidate(securityIncidentProvider(id)),
          ),
          data: (i) {
            final affected = i.affectsAllTenants
                ? 'every business'
                : '${i.tenantIds.length} ${i.tenantIds.length == 1 ? 'business' : 'businesses'}';
            return Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Text(i.title, style: theme.textTheme.headlineMedium),
                const SizedBox(height: 4),
                Text(
                    '${incidentKindLabels[i.kind] ?? i.kind} · aware ${AppFormat.dateTime(i.awareAt)} · $affected · ${i.open ? 'open' : 'closed'}'),
                const SizedBox(height: AppSpacing.sm),
                Text(i.summary),
                if (i.open) ...[
                  const SizedBox(height: AppSpacing.lg),
                  Wrap(
                    spacing: AppSpacing.sm,
                    runSpacing: AppSpacing.sm,
                    children: [
                      FilledButton.icon(
                        key: const Key('record-event'),
                        onPressed: () => _changed(context, ref,
                            _RecordDialog(incident: i), 'Recorded.'),
                        icon: const Icon(Icons.edit_note),
                        label: const Text('Record'),
                      ),
                      OutlinedButton.icon(
                        key: const Key('tell-businesses'),
                        onPressed: () => _changed(
                            context, ref, _TellDialog(incident: i), null),
                        icon: const Icon(Icons.campaign_outlined),
                        label: const Text('Tell businesses'),
                      ),
                    ],
                  ),
                ],
                const SizedBox(height: AppSpacing.lg),
                Text('What the law asks', style: theme.textTheme.titleMedium),
                const SizedBox(height: AppSpacing.sm),
                Card(
                    child: Column(
                        children: [for (final s in i.stages) _StageTile(stage: s)])),
                const SizedBox(height: AppSpacing.lg),
                Text(
                    'Notices: ${i.noticesIssued} issued, ${i.noticesAcknowledged} acknowledged',
                    key: const Key('notice-counts')),
                const SizedBox(height: AppSpacing.lg),
                Text('Timeline', style: theme.textTheme.titleMedium),
                const SizedBox(height: AppSpacing.sm),
                if (i.events.isEmpty)
                  const Text('Nothing recorded yet.')
                else
                  Card(
                    child: Column(children: [
                      for (final e in i.events)
                        ListTile(
                          title: Text(_eventLabels[e.kind] ?? e.kind),
                          subtitle: Text([
                            AppFormat.dateTime(e.occurredAt),
                            if (e.reference != null) 'ref ${e.reference}',
                            if (e.note != null) e.note!,
                          ].join(' · ')),
                        ),
                    ]),
                  ),
              ],
            );
          },
        ),
      ],
    );
  }

  /// Opens a dialog that writes; when it did, reloads the incident and the
  /// register and says what happened.
  Future<void> _changed(
      BuildContext context, WidgetRef ref, Widget dialog, String? done) async {
    final result = await showDialog<String>(context: context, builder: (_) => dialog);
    if (result == null) return;
    ref.invalidate(securityIncidentProvider(id));
    ref.invalidate(securityIncidentsProvider);
    if (context.mounted) {
      ScaffoldMessenger.of(context)
          .showSnackBar(SnackBar(content: Text(done ?? result)));
    }
  }
}

class _StageTile extends StatelessWidget {
  final IncidentStage stage;

  const _StageTile({required this.stage});

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final (label, overdue) = switch (stage.state) {
      'DONE' => ('Done ${AppFormat.dateTime(stage.doneAt)}', false),
      'DUE' => ('Due ${AppFormat.dateTime(stage.dueAt)}', false),
      'OVERDUE' => ('Overdue since ${AppFormat.dateTime(stage.dueAt)}', true),
      'WAITING' => ('Waiting', false),
      _ => ('No fixed time', false),
    };
    return ListTile(
      key: Key('stage-${stage.stage}'),
      title: Text(stage.summary),
      subtitle:
          Text('${_stageLabels[stage.stage] ?? stage.stage} · ${stage.citation}'),
      trailing: Chip(
        label: Text(label),
        backgroundColor: overdue ? cs.errorContainer : null,
        labelStyle: overdue ? TextStyle(color: cs.onErrorContainer) : null,
      ),
    );
  }
}

/// A dialog that posts once, shows the server's refusal in place, and closes
/// with a message when it succeeds.
abstract class _PostingDialogState<T extends ConsumerStatefulWidget>
    extends ConsumerState<T> {
  bool busy = false;
  String? error;

  Future<void> post(String path, Map<String, dynamic> body,
      {required String fallback,
      required String Function(Map<String, dynamic> data) done}) async {
    setState(() {
      busy = true;
      error = null;
    });
    try {
      final resp =
          await ref.read(apiClientProvider).dio.post(path, data: body);
      if (!mounted) return;
      final data = resp.data is Map ? resp.data['data'] : null;
      Navigator.of(context)
          .pop(done(data is Map<String, dynamic> ? data : const {}));
    } catch (e) {
      if (!mounted) return;
      setState(() {
        busy = false;
        error = friendlyError(e, fallback: fallback);
      });
    }
  }

  Widget errorLine(BuildContext context) => error == null
      ? const SizedBox.shrink()
      : Padding(
          padding: const EdgeInsets.only(top: AppSpacing.sm),
          child: Text(error!,
              key: const Key('dialog-error'),
              style: TextStyle(color: Theme.of(context).colorScheme.error)),
        );
}

class _RecordDialog extends ConsumerStatefulWidget {
  final SecurityIncident incident;

  const _RecordDialog({required this.incident});

  @override
  ConsumerState<_RecordDialog> createState() => _RecordDialogState();
}

class _RecordDialogState extends _PostingDialogState<_RecordDialog> {
  late final List<String> _options = recordableEvents(widget.incident);
  late String _kind = _options.first;
  final _occurredAt = TextEditingController();
  final _reference = TextEditingController();
  final _note = TextEditingController();

  @override
  void dispose() {
    _occurredAt.dispose();
    _reference.dispose();
    _note.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: const Text('Record'),
      content: SizedBox(
        width: 480,
        child: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              DropdownButtonFormField<String>(
                key: const Key('record-kind'),
                isExpanded: true,
                initialValue: _kind,
                decoration: const InputDecoration(labelText: 'What happened'),
                items: [
                  for (final k in _options)
                    DropdownMenuItem(value: k, child: Text(_eventLabels[k] ?? k)),
                ],
                onChanged: (v) => setState(() => _kind = v ?? _kind),
              ),
              TextField(
                key: const Key('record-occurred-at'),
                controller: _occurredAt,
                decoration: const InputDecoration(
                    labelText: 'When (UTC, ISO-8601)', hintText: 'Blank for now'),
              ),
              TextField(
                key: const Key('record-reference'),
                controller: _reference,
                maxLength: 120,
                decoration: const InputDecoration(
                    labelText: 'Authority reference',
                    hintText: 'The case number the report was filed under'),
              ),
              TextField(
                key: const Key('record-note'),
                controller: _note,
                maxLength: 2000,
                maxLines: 3,
                decoration: InputDecoration(
                    labelText: _kind == 'NOTE' ? 'Note (required)' : 'Note'),
              ),
              errorLine(context),
            ],
          ),
        ),
      ),
      actions: [
        TextButton(
            onPressed: busy ? null : () => Navigator.of(context).pop(),
            child: const Text('Cancel')),
        FilledButton(
          key: const Key('record-submit'),
          onPressed: busy
              ? null
              : () => post(
                    _incidents('/${widget.incident.id}/events'),
                    {
                      'kind': _kind,
                      if (_occurredAt.text.trim().isNotEmpty)
                        'occurredAt': _occurredAt.text.trim(),
                      if (_reference.text.trim().isNotEmpty)
                        'reference': _reference.text.trim(),
                      if (_note.text.trim().isNotEmpty) 'note': _note.text.trim(),
                    },
                    fallback: 'Could not record that.',
                    done: (_) => 'Recorded.',
                  ),
          child: const Text('Record'),
        ),
      ],
    );
  }
}

class _TellDialog extends ConsumerStatefulWidget {
  final SecurityIncident incident;

  const _TellDialog({required this.incident});

  @override
  ConsumerState<_TellDialog> createState() => _TellDialogState();
}

class _TellDialogState extends _PostingDialogState<_TellDialog> {
  final _message = TextEditingController();

  @override
  void dispose() {
    _message.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final i = widget.incident;
    return AlertDialog(
      title: const Text('Tell businesses'),
      content: SizedBox(
        width: 480,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Text(i.affectsAllTenants
                ? 'Every business on the platform gets this notice, once.'
                : 'Each of the ${i.tenantIds.length} businesses affected gets this notice, once.'),
            TextField(
              key: const Key('tell-message'),
              controller: _message,
              maxLength: 4000,
              maxLines: 5,
              onChanged: (_) => setState(() {}),
              decoration: const InputDecoration(
                  labelText: 'What happened, and what they should do'),
            ),
            errorLine(context),
          ],
        ),
      ),
      actions: [
        TextButton(
            onPressed: busy ? null : () => Navigator.of(context).pop(),
            child: const Text('Cancel')),
        FilledButton(
          key: const Key('tell-submit'),
          onPressed: busy || _message.text.trim().isEmpty
              ? null
              : () => post(
                    _incidents('/${i.id}/notices'),
                    {'message': _message.text.trim()},
                    fallback: 'Could not send the notices.',
                    done: (d) =>
                        '${d['issued'] ?? 0} new notices; ${d['total'] ?? 0} in all, ${d['acknowledged'] ?? 0} acknowledged.',
                  ),
          child: const Text('Send'),
        ),
      ],
    );
  }
}

class _OpenIncidentDialog extends ConsumerStatefulWidget {
  const _OpenIncidentDialog();

  @override
  ConsumerState<_OpenIncidentDialog> createState() => _OpenIncidentDialogState();
}

class _OpenIncidentDialogState extends _PostingDialogState<_OpenIncidentDialog> {
  final _form = GlobalKey<FormState>();
  String _kind = 'EXPLOITED_VULNERABILITY';
  final _title = TextEditingController();
  final _summary = TextEditingController();
  final _awareAt = TextEditingController(
      text: DateTime.now()
          .toUtc()
          .copyWith(millisecond: 0, microsecond: 0)
          .toIso8601String());
  final Set<String> _tenants = {};

  @override
  void dispose() {
    _title.dispose();
    _summary.dispose();
    _awareAt.dispose();
    super.dispose();
  }

  String? _required(String? v, int max, String what) {
    final t = v?.trim() ?? '';
    if (t.isEmpty) return '$what is required';
    if (t.length > max) return '$what is at most $max characters';
    return null;
  }

  @override
  Widget build(BuildContext context) {
    final tenants = ref.watch(allTenantsProvider);
    return AlertDialog(
      title: const Text('Open incident'),
      content: SizedBox(
        width: 520,
        child: SingleChildScrollView(
          child: Form(
            key: _form,
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                DropdownButtonFormField<String>(
                  key: const Key('incident-kind'),
                isExpanded: true,
                  initialValue: _kind,
                  decoration: const InputDecoration(labelText: 'Kind'),
                  items: [
                    for (final e in incidentKindLabels.entries)
                      DropdownMenuItem(value: e.key, child: Text(e.value)),
                  ],
                  onChanged: (v) => setState(() => _kind = v ?? _kind),
                ),
                TextFormField(
                  key: const Key('incident-title'),
                  controller: _title,
                  decoration: const InputDecoration(labelText: 'Title'),
                  validator: (v) => _required(v, 200, 'A title'),
                ),
                TextFormField(
                  key: const Key('incident-summary'),
                  controller: _summary,
                  maxLines: 4,
                  decoration: const InputDecoration(labelText: 'What happened'),
                  validator: (v) => _required(v, 4000, 'What happened'),
                ),
                TextFormField(
                  key: const Key('incident-aware-at'),
                  controller: _awareAt,
                  decoration: const InputDecoration(
                      labelText: 'Aware since (UTC, ISO-8601)',
                      helperText: 'Every deadline runs from this moment'),
                  validator: (v) => DateTime.tryParse(v?.trim() ?? '') == null
                      ? 'A date and time such as 2026-09-14T08:00:00Z'
                      : null,
                ),
                const SizedBox(height: AppSpacing.lg),
                Text('Businesses affected — none chosen means every business',
                    style: Theme.of(context).textTheme.labelLarge),
                const SizedBox(height: AppSpacing.sm),
                tenants.when(
                  loading: () => const LinearProgressIndicator(),
                  error: (e, _) => Text(
                    'The businesses could not be loaded, so this incident '
                    'cannot be scoped yet. Close and try again.',
                    key: const Key('tenants-unavailable'),
                    style: TextStyle(color: Theme.of(context).colorScheme.error),
                  ),
                  data: (list) => Wrap(
                    spacing: AppSpacing.sm,
                    runSpacing: AppSpacing.sm,
                    children: [
                      for (final t in list)
                        FilterChip(
                          key: Key('tenant-${t.id}'),
                          label: Text(t.name),
                          selected: _tenants.contains(t.id),
                          onSelected: (on) => setState(() =>
                              on ? _tenants.add(t.id) : _tenants.remove(t.id)),
                        ),
                    ],
                  ),
                ),
                errorLine(context),
              ],
            ),
          ),
        ),
      ),
      actions: [
        TextButton(
            onPressed: busy ? null : () => Navigator.of(context).pop(),
            child: const Text('Cancel')),
        FilledButton(
          key: const Key('incident-submit'),
          // Never while the business list is unknown: a failed read must not
          // quietly widen an incident to every business.
          onPressed: busy || !tenants.hasValue
              ? null
              : () {
                  if (!(_form.currentState?.validate() ?? false)) return;
                  post(
                    _incidents(),
                    {
                      'kind': _kind,
                      'title': _title.text.trim(),
                      'summary': _summary.text.trim(),
                      'awareAt': _awareAt.text.trim(),
                      'tenantIds': _tenants.toList(),
                    },
                    fallback: 'Could not open the incident.',
                    done: (d) => d['id'] as String? ?? '',
                  );
                },
          child: const Text('Open'),
        ),
      ],
    );
  }
}
