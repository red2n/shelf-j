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
// The card machines a business has (07.16).
//
// A terminal belongs to a store: it is a physical object on a counter, and a
// payment taken on it is taken there. So this screen is the register — which
// devices exist, where, and which have been retired.
//
// A retired terminal is never deleted, because payments point at it. Its label
// is freed for the device that replaces it, which is what happens when a pinpad
// is swapped after a fault; a shop should not have to invent "Till 2 (new)".
//
// Only vendors this deployment can actually reach are offered. Being told at the
// counter, with a customer waiting, that the platform cannot talk to the device
// you paired last week is the failure this avoids.
// ---------------------------------------------------------------------------

class CardTerminalRow {
  final String id;
  final String storeId;
  final String label;
  final String vendor;
  final String? serial;
  final String status;
  final String? retiredReason;
  final String? createdAt;

  const CardTerminalRow({
    required this.id,
    required this.storeId,
    required this.label,
    required this.vendor,
    required this.status,
    this.serial,
    this.retiredReason,
    this.createdAt,
  });

  bool get active => status == 'ACTIVE';

  /// True for the built-in simulator, which is said plainly on the row: a shop
  /// must never believe a real card was taken when nothing left the building.
  bool get simulated => vendor == 'SIMULATED';

  factory CardTerminalRow.fromJson(Map<String, dynamic> j) => CardTerminalRow(
        id: j['id'] as String? ?? '',
        storeId: j['storeId'] as String? ?? '',
        label: j['label'] as String? ?? '',
        vendor: j['vendor'] as String? ?? '',
        status: j['status'] as String? ?? 'ACTIVE',
        serial: j['serial'] as String?,
        retiredReason: j['retiredReason'] as String?,
        createdAt: j['createdAt'] as String?,
      );
}

const _terminalsPath = '/${ApiConstants.payment}/admin/payments/terminals';

final terminalsProvider =
    FutureProvider.autoDispose<List<CardTerminalRow>>((ref) async {
  final resp = await ref.read(apiClientProvider).dio.get(_terminalsPath);
  final rows = (resp.data['data'] as List?) ?? const [];
  return [
    for (final e in rows)
      if (e is Map<String, dynamic>) CardTerminalRow.fromJson(e),
  ];
});

/// The vendors this deployment is configured for. A vendor whose credentials are
/// missing is not offered at all.
final terminalVendorsProvider =
    FutureProvider.autoDispose<List<String>>((ref) async {
  final resp =
      await ref.read(apiClientProvider).dio.get('$_terminalsPath/vendors');
  return [for (final v in (resp.data['data'] as List?) ?? const []) '$v'];
});

class TerminalsScreen extends ConsumerWidget {
  const TerminalsScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final theme = Theme.of(context);
    final terminals = ref.watch(terminalsProvider);
    return ListView(
      padding: const EdgeInsets.all(AppSpacing.xl),
      children: [
        Row(
          children: [
            Expanded(
              child: Text('Card machines', style: theme.textTheme.headlineMedium),
            ),
            FilledButton.icon(
              key: const Key('terminal-add'),
              onPressed: () => _add(context, ref),
              icon: const Icon(Icons.add),
              label: const Text('Add a machine'),
            ),
          ],
        ),
        const SizedBox(height: 4),
        Text(
          'The card machines on your counters, and which store each one is at. A '
          'card is approved by the machine itself — this platform never sees the '
          'card number, only the last four digits your receipt prints.',
          style: theme.textTheme.bodyMedium
              ?.copyWith(color: theme.colorScheme.onSurfaceVariant),
        ),
        const SizedBox(height: AppSpacing.lg),
        terminals.when(
          loading: () => const LoadingView(label: 'Loading card machines…'),
          error: (e, _) => ErrorView(
            message:
                friendlyError(e, fallback: 'Could not load the card machines.'),
            onRetry: () => ref.invalidate(terminalsProvider),
          ),
          data: (rows) {
            if (rows.isEmpty) {
              return const Card(
                key: Key('terminals-empty'),
                child: Padding(
                  padding: EdgeInsets.all(AppSpacing.lg),
                  child: Text(
                    'No card machines yet. Until one is added, a card tender is '
                    'recorded on the cashier\'s word — nothing checks with the '
                    'machine whether the card was actually approved.',
                  ),
                ),
              );
            }
            final active = rows.where((t) => t.active).toList();
            final retired = rows.where((t) => !t.active).toList();
            return Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                if (active.isNotEmpty)
                  _Group(
                    title: 'On the counter',
                    rows: active,
                    onRetire: (t) => _retire(context, ref, t),
                  ),
                if (retired.isNotEmpty) ...[
                  const SizedBox(height: AppSpacing.lg),
                  _Group(
                    title: 'Retired',
                    rows: retired,
                    onRetire: null,
                  ),
                  Padding(
                    padding: const EdgeInsets.only(top: AppSpacing.xs),
                    child: Text(
                      'Retired machines are kept because past payments point at '
                      'them. Their labels are free to reuse.',
                      style: theme.textTheme.bodySmall,
                    ),
                  ),
                ],
              ],
            );
          },
        ),
      ],
    );
  }

  Future<void> _add(BuildContext context, WidgetRef ref) async {
    final added = await showDialog<bool>(
      context: context,
      builder: (_) => const _AddTerminalDialog(),
    );
    if (added == true) ref.invalidate(terminalsProvider);
  }

  Future<void> _retire(
      BuildContext context, WidgetRef ref, CardTerminalRow t) async {
    final reason = await showDialog<String>(
      context: context,
      builder: (_) => _RetireDialog(label: t.label),
    );
    if (reason == null) return;
    try {
      await ref
          .read(apiClientProvider)
          .dio
          .post('$_terminalsPath/${t.id}/retire', data: {'reason': reason});
      ref.invalidate(terminalsProvider);
    } catch (e) {
      if (!context.mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(
              friendlyError(e, fallback: 'Could not retire that machine.')),
        ),
      );
    }
  }
}

class _Group extends StatelessWidget {
  const _Group({required this.title, required this.rows, this.onRetire});
  final String title;
  final List<CardTerminalRow> rows;
  final void Function(CardTerminalRow)? onRetire;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Text(title, style: theme.textTheme.titleMedium),
        const SizedBox(height: AppSpacing.sm),
        Card(
          child: Column(
            children: [
              for (final t in rows)
                ListTile(
                  key: Key('terminal-${t.id}'),
                  leading: Icon(t.active
                      ? Icons.point_of_sale_outlined
                      : Icons.power_off_outlined),
                  title: Text(t.label),
                  subtitle: Text(_subtitle(t)),
                  trailing: Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      if (t.simulated)
                        const Chip(
                          label: Text('Simulated'),
                          visualDensity: VisualDensity.compact,
                        ),
                      if (onRetire != null)
                        IconButton(
                          key: Key('terminal-retire-${t.id}'),
                          tooltip: 'Retire',
                          icon: const Icon(Icons.delete_outline),
                          onPressed: () => onRetire!(t),
                        ),
                    ],
                  ),
                ),
            ],
          ),
        ),
      ],
    );
  }

  static String _subtitle(CardTerminalRow t) {
    final parts = <String>[
      t.simulated ? 'Simulated — no card is really taken' : t.vendor,
      if (t.serial != null) 'serial ${t.serial}',
      if (t.createdAt != null) 'added ${AppFormat.date(t.createdAt)}',
      if (t.retiredReason != null) 'retired: ${t.retiredReason}',
    ];
    return parts.join(' · ');
  }
}

class _AddTerminalDialog extends ConsumerStatefulWidget {
  const _AddTerminalDialog();

  @override
  ConsumerState<_AddTerminalDialog> createState() => _AddTerminalDialogState();
}

class _AddTerminalDialogState extends ConsumerState<_AddTerminalDialog> {
  final _label = TextEditingController();
  final _serial = TextEditingController();
  final _storeId = TextEditingController();
  String? _vendor;
  bool _saving = false;
  String? _error;

  @override
  void dispose() {
    _label.dispose();
    _serial.dispose();
    _storeId.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    setState(() {
      _saving = true;
      _error = null;
    });
    try {
      await ref.read(apiClientProvider).dio.post(_terminalsPath, data: {
        'storeId': _storeId.text.trim(),
        'label': _label.text.trim(),
        'vendor': _vendor,
        if (_serial.text.trim().isNotEmpty) 'serial': _serial.text.trim(),
      });
      if (mounted) Navigator.of(context).pop(true);
    } catch (e) {
      if (mounted) {
        setState(() {
          _saving = false;
          _error = friendlyError(e, fallback: 'Could not add that machine.');
        });
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final vendors = ref.watch(terminalVendorsProvider);
    return AlertDialog(
      key: const Key('terminal-add-dialog'),
      title: const Text('Add a card machine'),
      content: SizedBox(
        width: 420,
        child: SingleChildScrollView(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              TextField(
                key: const Key('terminal-label'),
                controller: _label,
                decoration: const InputDecoration(
                  labelText: 'What the cashier sees',
                  helperText: 'e.g. "Till 2". Unique among this store\'s machines.',
                ),
              ),
              const SizedBox(height: AppSpacing.sm),
              TextField(
                key: const Key('terminal-store'),
                controller: _storeId,
                decoration: const InputDecoration(labelText: 'Store'),
              ),
              const SizedBox(height: AppSpacing.sm),
              vendors.when(
                loading: () => const LinearProgressIndicator(),
                error: (e, _) => Text(
                  friendlyError(e, fallback: 'Could not read the vendors.'),
                  style: TextStyle(color: Theme.of(context).colorScheme.error),
                ),
                data: (names) => DropdownButtonFormField<String>(
                  key: const Key('terminal-vendor'),
                  initialValue: _vendor ?? (names.length == 1 ? names.first : null),
                  isExpanded: true,
                  decoration: const InputDecoration(
                    labelText: 'Make',
                    helperText:
                        'Only makes this platform is set up to talk to are listed.',
                  ),
                  items: [
                    for (final n in names)
                      DropdownMenuItem(
                        value: n,
                        child: Text(n == 'SIMULATED'
                            ? 'Simulated (nothing is really charged)'
                            : n),
                      ),
                  ],
                  onChanged: (v) => setState(() => _vendor = v),
                ),
              ),
              const SizedBox(height: AppSpacing.sm),
              TextField(
                key: const Key('terminal-serial'),
                controller: _serial,
                decoration: const InputDecoration(
                  labelText: 'Serial number',
                  helperText: 'As printed on the machine. Optional until paired.',
                ),
              ),
              if (_error != null)
                Padding(
                  padding: const EdgeInsets.only(top: AppSpacing.sm),
                  child: Text(_error!,
                      style:
                          TextStyle(color: Theme.of(context).colorScheme.error)),
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
          key: const Key('terminal-add-save'),
          onPressed: _saving ? null : _save,
          child: const Text('Add'),
        ),
      ],
    );
  }
}

/// Retiring asks why, so a machine withdrawn after a fault is distinguishable
/// from one replaced on an upgrade — the first is a question for the vendor.
class _RetireDialog extends StatefulWidget {
  const _RetireDialog({required this.label});
  final String label;

  @override
  State<_RetireDialog> createState() => _RetireDialogState();
}

class _RetireDialogState extends State<_RetireDialog> {
  final _reason = TextEditingController();

  @override
  void dispose() {
    _reason.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      key: const Key('terminal-retire-dialog'),
      title: Text('Retire ${widget.label}?'),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          const Text(
            'It stops taking cards. Past payments keep pointing at it, and its '
            'name is free for the machine that replaces it.',
          ),
          const SizedBox(height: AppSpacing.sm),
          TextField(
            key: const Key('terminal-retire-reason'),
            controller: _reason,
            decoration: const InputDecoration(
              labelText: 'Why',
              helperText: 'e.g. "screen cracked", "replaced on upgrade".',
            ),
          ),
        ],
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.of(context).pop(),
          child: const Text('Keep it'),
        ),
        FilledButton(
          key: const Key('terminal-retire-confirm'),
          onPressed: () => Navigator.of(context).pop(_reason.text.trim()),
          child: const Text('Retire'),
        ),
      ],
    );
  }
}
