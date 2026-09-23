import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/auth/auth_notifier.dart';
import '../../core/auth/auth_state.dart';
import '../../core/format.dart';
import '../../core/network/api_error.dart';
import '../../shared/widgets/error_view.dart';
import '../../shared/widgets/loading_view.dart';
import 'api_keys_api.dart';
import 'providers/admin_providers.dart';

// ---------------------------------------------------------------------------
// Integrations (22.7): the keys a business's own systems present instead of a
// person's sign-in. An owner mints one — a name, a staff tier, the stores it
// may work in, a day it stops — and is shown the key once, with a copy button
// and a warning that it will not be shown again; the list says what each key
// is, when it was last used and whether it still works; an owner revokes one
// and it stops within seconds. A manager reads the list and changes nothing.
// ---------------------------------------------------------------------------

class IntegrationsScreen extends ConsumerWidget {
  const IntegrationsScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final auth = ref.watch(authNotifierProvider).value;
    final owner = auth is AuthAuthenticated && auth.roles.contains('OWNER');
    final keys = ref.watch(apiKeysProvider);
    final theme = Theme.of(context);

    return Scaffold(
      body: ListView(
        padding: const EdgeInsets.all(24),
        children: [
          Text('Integrations', style: theme.textTheme.headlineSmall?.copyWith(fontWeight: FontWeight.bold)),
          const SizedBox(height: 4),
          Text(
            'API keys let your own systems — an ERP, an accounting package, an integrator — '
            'use the platform without a person signing in. A key acts as staff, never as an owner.',
            style: theme.textTheme.bodyMedium,
          ),
          const SizedBox(height: 24),
          Row(
            children: [
              Text('API keys', style: theme.textTheme.titleLarge?.copyWith(fontWeight: FontWeight.bold)),
              const Spacer(),
              if (owner)
                FilledButton.icon(
                  key: const Key('mint-key'),
                  onPressed: () => _mint(context, ref),
                  icon: const Icon(Icons.vpn_key_outlined),
                  label: const Text('Mint a key'),
                ),
            ],
          ),
          const SizedBox(height: 12),
          keys.when(
            loading: () => const LoadingView(label: 'Loading keys…'),
            error: (e, _) => ErrorView(
              message: friendlyError(e, fallback: 'Could not load the keys.'),
              onRetry: () => ref.invalidate(apiKeysProvider),
            ),
            data: (list) => list.isEmpty
                ? const Padding(
                    key: Key('keys-none'),
                    padding: EdgeInsets.symmetric(vertical: 24),
                    child: Text('No keys yet. Mint one for each system that needs its own way in.'),
                  )
                : Column(
                    children: [
                      for (final k in list) _KeyTile(k: k, owner: owner, onRevoke: () => _revoke(context, ref, k)),
                    ],
                  ),
          ),
        ],
      ),
    );
  }

  Future<void> _mint(BuildContext context, WidgetRef ref) async {
    final minted = await showDialog<MintedApiKey>(
      context: context,
      builder: (_) => const MintKeyDialog(),
    );
    if (minted == null || !context.mounted) return;
    ref.invalidate(apiKeysProvider);
    await showDialog<void>(
      context: context,
      barrierDismissible: false,
      builder: (_) => _KeyShownOnceDialog(minted: minted),
    );
  }

  Future<void> _revoke(BuildContext context, WidgetRef ref, ApiKey k) async {
    final sure = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text('Revoke ${k.name}?'),
        content: const Text('The system using this key stops within seconds. This cannot be undone; mint a new key if it should carry on.'),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Keep it')),
          FilledButton(key: const Key('revoke-confirm'), onPressed: () => Navigator.pop(ctx, true), child: const Text('Revoke')),
        ],
      ),
    );
    if (sure != true || !context.mounted) return;
    try {
      await ref.read(apiKeysApiProvider).revoke(k.id);
      ref.invalidate(apiKeysProvider);
      if (context.mounted) {
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('${k.name} revoked')));
      }
    } catch (e) {
      if (context.mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text(friendlyError(e, fallback: 'The key could not be revoked.'))),
        );
      }
    }
  }
}

class _KeyTile extends StatelessWidget {
  final ApiKey k;
  final bool owner;
  final VoidCallback onRevoke;
  const _KeyTile({required this.k, required this.owner, required this.onRevoke});

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final active = k.status == 'Active';
    final where = k.storeIds.isEmpty ? 'every store' : '${k.storeIds.length} ${k.storeIds.length == 1 ? 'store' : 'stores'}';
    final used = k.lastUsedAt == null ? 'never used' : 'last used ${AppFormat.date(k.lastUsedAt)}';
    final until = k.expiresAt == null ? '' : ' · until ${AppFormat.date(k.expiresAt)}';
    return Card(
      key: Key('key-${k.id}'),
      margin: const EdgeInsets.only(bottom: 8),
      child: ListTile(
        leading: Icon(Icons.vpn_key_outlined, color: active ? cs.primary : cs.outline),
        title: Text(k.name),
        subtitle: Text('${k.prefix}… · ${k.role} · $where · $used$until'),
        trailing: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            Chip(
              label: Text(k.status),
              backgroundColor: active ? cs.primaryContainer : cs.surfaceContainerHighest,
            ),
            if (owner && active) ...[
              const SizedBox(width: 8),
              IconButton(
                key: Key('revoke-${k.id}'),
                tooltip: 'Revoke',
                icon: const Icon(Icons.delete_outline),
                onPressed: onRevoke,
              ),
            ],
          ],
        ),
      ),
    );
  }
}

/// The form that mints a key: a name, a tier, the stores, an optional last day.
class MintKeyDialog extends ConsumerStatefulWidget {
  const MintKeyDialog({super.key});

  @override
  ConsumerState<MintKeyDialog> createState() => _MintKeyDialogState();
}

class _MintKeyDialogState extends ConsumerState<MintKeyDialog> {
  final _formKey = GlobalKey<FormState>();
  final _name = TextEditingController();
  String _role = 'STOREKEEPER';
  final Set<String> _stores = {};
  DateTime? _expiresAt;
  bool _busy = false;
  String? _error;

  @override
  void dispose() {
    _name.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    if (!_formKey.currentState!.validate()) return;
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final minted = await ref.read(apiKeysApiProvider).mint(
            name: _name.text.trim(),
            role: _role,
            storeIds: _stores.toList(),
            expiresAt: _expiresAt,
          );
      if (mounted) Navigator.pop(context, minted);
    } catch (e) {
      setState(() {
        _busy = false;
        _error = friendlyError(e, fallback: 'The key could not be minted.');
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final stores = ref.watch(storesProvider);
    return AlertDialog(
      title: const Text('Mint an API key'),
      content: SizedBox(
        width: 460,
        child: Form(
          key: _formKey,
          child: SingleChildScrollView(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                TextFormField(
                  key: const Key('key-name'),
                  controller: _name,
                  decoration: const InputDecoration(labelText: 'Name *', hintText: 'e.g. Warehouse ERP'),
                  maxLength: 80,
                  validator: (v) => v == null || v.trim().isEmpty ? 'Required' : null,
                ),
                const SizedBox(height: 8),
                DropdownButtonFormField<String>(
                  key: const Key('key-role'),
                  initialValue: _role,
                  decoration: const InputDecoration(labelText: 'Acts as *', helperText: 'A key is never an owner.'),
                  items: const [
                    DropdownMenuItem(value: 'MANAGER', child: Text('Manager')),
                    DropdownMenuItem(value: 'STOREKEEPER', child: Text('Storekeeper')),
                    DropdownMenuItem(value: 'CASHIER', child: Text('Cashier')),
                  ],
                  onChanged: (v) => setState(() => _role = v ?? _role),
                ),
                const SizedBox(height: 16),
                Text('Stores', style: Theme.of(context).textTheme.labelLarge),
                const SizedBox(height: 4),
                stores.when(
                  loading: () => const Text('Loading stores…'),
                  error: (_, _) => const Text('Stores could not be loaded; the key works in every store.'),
                  data: (list) => Wrap(
                    spacing: 8,
                    runSpacing: 4,
                    children: [
                      FilterChip(
                        key: const Key('store-all'),
                        label: const Text('Every store'),
                        selected: _stores.isEmpty,
                        onSelected: (_) => setState(_stores.clear),
                      ),
                      for (final s in list)
                        FilterChip(
                          key: Key('store-${s.id}'),
                          label: Text(s.name),
                          selected: _stores.contains(s.id),
                          onSelected: (on) => setState(() => on ? _stores.add(s.id) : _stores.remove(s.id)),
                        ),
                    ],
                  ),
                ),
                const SizedBox(height: 16),
                Row(
                  children: [
                    Expanded(
                      child: Text(
                        _expiresAt == null ? 'Works until revoked' : 'Stops on ${AppFormat.date(_expiresAt!.toIso8601String())}',
                      ),
                    ),
                    TextButton(
                      key: const Key('key-expiry'),
                      onPressed: () async {
                        final now = DateTime.now();
                        final picked = await showDatePicker(
                          context: context,
                          initialDate: now.add(const Duration(days: 90)),
                          firstDate: now.add(const Duration(days: 1)),
                          lastDate: now.add(const Duration(days: 365 * 5)),
                        );
                        if (picked != null) setState(() => _expiresAt = DateTime(picked.year, picked.month, picked.day, 23, 59, 59));
                      },
                      child: Text(_expiresAt == null ? 'Set a last day' : 'Change'),
                    ),
                    if (_expiresAt != null)
                      IconButton(
                        tooltip: 'No last day',
                        icon: const Icon(Icons.close),
                        onPressed: () => setState(() => _expiresAt = null),
                      ),
                  ],
                ),
                if (_error != null) ...[
                  const SizedBox(height: 8),
                  Text(_error!, style: TextStyle(color: Theme.of(context).colorScheme.error)),
                ],
              ],
            ),
          ),
        ),
      ),
      actions: [
        TextButton(onPressed: _busy ? null : () => Navigator.pop(context), child: const Text('Cancel')),
        FilledButton(
          key: const Key('mint-submit'),
          onPressed: _busy ? null : _submit,
          child: _busy
              ? const SizedBox(height: 18, width: 18, child: CircularProgressIndicator(strokeWidth: 2))
              : const Text('Mint'),
        ),
      ],
    );
  }
}

/// The key, the one time it is seen.
class _KeyShownOnceDialog extends StatelessWidget {
  final MintedApiKey minted;
  const _KeyShownOnceDialog({required this.minted});

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return AlertDialog(
      title: Text('${minted.key.name} is ready'),
      content: SizedBox(
        width: 460,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            const Text('Copy the key into the system that will use it. It is shown once and cannot be shown again; if it is lost, revoke it and mint another.'),
            const SizedBox(height: 12),
            Container(
              key: const Key('key-secret'),
              padding: const EdgeInsets.all(12),
              decoration: BoxDecoration(color: cs.surfaceContainerHighest, borderRadius: BorderRadius.circular(8)),
              child: SelectableText(minted.secret, style: const TextStyle(fontFamily: 'monospace')),
            ),
            const SizedBox(height: 8),
            Text('Send it as  Authorization: Bearer ${minted.key.prefix}…', style: Theme.of(context).textTheme.bodySmall),
          ],
        ),
      ),
      actions: [
        TextButton.icon(
          key: const Key('key-copy'),
          onPressed: () async {
            await Clipboard.setData(ClipboardData(text: minted.secret));
            if (context.mounted) {
              ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('Key copied')));
            }
          },
          icon: const Icon(Icons.copy),
          label: const Text('Copy'),
        ),
        FilledButton(key: const Key('key-done'), onPressed: () => Navigator.pop(context), child: const Text('I have copied it')),
      ],
    );
  }
}
