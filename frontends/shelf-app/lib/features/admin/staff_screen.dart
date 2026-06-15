import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../core/constants.dart';
import '../../core/network/api_client.dart';
import '../../shared/widgets/error_view.dart';
import '../../shared/widgets/loading_view.dart';
import 'providers/admin_providers.dart';

const _roles = ['OWNER', 'MANAGER', 'STOREKEEPER', 'CASHIER'];

class StaffScreen extends ConsumerWidget {
  const StaffScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final staffAsync = ref.watch(staffProvider);
    final cs = Theme.of(context).colorScheme;

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(24, 24, 24, 0),
          child: Row(
            children: [
              Text('Staff', style: Theme.of(context).textTheme.headlineMedium),
              const Spacer(),
              FilledButton.icon(
                onPressed: () => _showAssignDialog(context, ref),
                icon: const Icon(Icons.person_add),
                label: const Text('Assign Staff'),
              ),
              const SizedBox(width: 8),
              IconButton(
                icon: const Icon(Icons.refresh),
                onPressed: () => ref.invalidate(staffProvider),
              ),
            ],
          ),
        ),
        const SizedBox(height: 16),
        Expanded(
          child: staffAsync.when(
            loading: () => const LoadingView(label: 'Loading staff…'),
            error: (e, _) => ErrorView(
              message: 'Could not load staff.\n$e',
              onRetry: () => ref.invalidate(staffProvider),
            ),
            data: (staff) {
              if (staff.isEmpty) {
                return Center(
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Icon(Icons.people_outline, size: 64, color: cs.outlineVariant),
                      const SizedBox(height: 16),
                      Text('No staff assigned yet',
                          style: Theme.of(context).textTheme.titleMedium),
                      const SizedBox(height: 8),
                      Text(
                        'Assign a user to a store with a role to grant them access.',
                        style: Theme.of(context)
                            .textTheme
                            .bodyMedium
                            ?.copyWith(color: cs.outline),
                        textAlign: TextAlign.center,
                      ),
                      const SizedBox(height: 24),
                      OutlinedButton.icon(
                        onPressed: () => _showAssignDialog(context, ref),
                        icon: const Icon(Icons.person_add),
                        label: const Text('Assign Staff'),
                      ),
                    ],
                  ),
                );
              }
              return ListView.separated(
                padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
                itemCount: staff.length,
                separatorBuilder: (_, __) => const SizedBox(height: 4),
                itemBuilder: (context, i) {
                  final m = staff[i];
                  return Card(
                    child: ListTile(
                      leading: CircleAvatar(
                        backgroundColor: cs.secondaryContainer,
                        child: Icon(Icons.person_outline,
                            color: cs.onSecondaryContainer),
                      ),
                      title: Row(
                        children: [
                          _RoleBadge(role: m.role),
                          const SizedBox(width: 8),
                          Expanded(
                            child: Text(
                              m.userId,
                              style: const TextStyle(
                                  fontFamily: 'monospace', fontSize: 12),
                              overflow: TextOverflow.ellipsis,
                            ),
                          ),
                        ],
                      ),
                      subtitle: Text(
                        'Store: ${m.storeId.length > 8 ? m.storeId.substring(0, 8) : m.storeId}',
                        style:
                            const TextStyle(fontFamily: 'monospace', fontSize: 11),
                      ),
                      trailing: IconButton(
                        icon: Icon(Icons.delete_outline, color: cs.error),
                        tooltip: 'Remove',
                        onPressed: () => _removeStaff(context, ref, m),
                      ),
                    ),
                  );
                },
              );
            },
          ),
        ),
      ],
    );
  }

  void _showAssignDialog(BuildContext context, WidgetRef ref) {
    showDialog(
      context: context,
      builder: (_) => _AssignStaffDialog(
        onAssigned: () => ref.invalidate(staffProvider),
      ),
    );
  }

  Future<void> _removeStaff(
      BuildContext context, WidgetRef ref, StaffMember m) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Remove staff assignment?'),
        content: Text(
            'Remove ${m.role} access for this user at this store. They lose access to it immediately.'),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(ctx, false),
              child: const Text('Cancel')),
          FilledButton(
            style: FilledButton.styleFrom(
              backgroundColor: Theme.of(ctx).colorScheme.error,
              foregroundColor: Theme.of(ctx).colorScheme.onError,
            ),
            onPressed: () => Navigator.pop(ctx, true),
            child: const Text('Remove'),
          ),
        ],
      ),
    );
    if (confirmed != true || !context.mounted) return;
    try {
      await ref.read(apiClientProvider).dio.delete(
        '/${ApiConstants.tenant}/admin/staff/${m.userId}',
        queryParameters: {'store': m.storeId},
      );
      ref.invalidate(staffProvider);
    } catch (e) {
      if (context.mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text('Failed to remove: $e'),
            backgroundColor: Theme.of(context).colorScheme.error,
          ),
        );
      }
    }
  }
}

class _RoleBadge extends StatelessWidget {
  final String role;
  const _RoleBadge({required this.role});

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
      decoration: BoxDecoration(
        color: cs.primaryContainer,
        borderRadius: BorderRadius.circular(6),
      ),
      child: Text(
        role,
        style: TextStyle(
            fontSize: 11,
            fontWeight: FontWeight.bold,
            color: cs.onPrimaryContainer),
      ),
    );
  }
}

class _AssignStaffDialog extends ConsumerStatefulWidget {
  final VoidCallback onAssigned;
  const _AssignStaffDialog({required this.onAssigned});

  @override
  ConsumerState<_AssignStaffDialog> createState() => _AssignStaffDialogState();
}

class _AssignStaffDialogState extends ConsumerState<_AssignStaffDialog> {
  final _formKey = GlobalKey<FormState>();
  final _emailCtrl = TextEditingController();
  final _passwordCtrl = TextEditingController();
  String _role = 'CASHIER';
  String? _storeId;
  bool _loading = false;
  String? _error;

  @override
  void dispose() {
    _emailCtrl.dispose();
    _passwordCtrl.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    if (!_formKey.currentState!.validate()) return;
    if (_storeId == null) {
      setState(() => _error = 'Select a store.');
      return;
    }
    setState(() {
      _loading = true;
      _error = null;
    });
    final dio = ref.read(apiClientProvider).dio;
    final pwd = _passwordCtrl.text.trim();
    try {
      // 1. Find-or-create the account by email — backend owns the user UUID.
      final provision = await dio.post(
        '/${ApiConstants.iam}/auth/admin/staff-users',
        data: {
          'email': _emailCtrl.text.trim(),
          if (pwd.isNotEmpty) 'password': pwd,
        },
      );
      final pdata = provision.data['data'] as Map<String, dynamic>;
      final userId = pdata['userId'] as String;
      final created = pdata['created'] as bool? ?? false;
      final tempPassword = pdata['tempPassword'] as String?;

      // 2. Assign that user a role at the chosen store.
      await dio.post(
        '/${ApiConstants.tenant}/admin/staff',
        data: {'userId': userId, 'storeId': _storeId, 'role': _role},
      );
      if (!mounted) return;
      widget.onAssigned();
      Navigator.pop(context);
      _showResult(created, tempPassword);
    } catch (e) {
      setState(() {
        _loading = false;
        _error = _friendly(e);
      });
    }
  }

  void _showResult(bool created, String? tempPassword) {
    final email = _emailCtrl.text.trim();
    if (created && tempPassword != null) {
      // New account — surface the temp password so the admin can pass it on.
      showDialog<void>(
        context: context,
        builder: (ctx) => AlertDialog(
          icon: Icon(Icons.check_circle_outline,
              color: Theme.of(ctx).colorScheme.primary, size: 40),
          title: const Text('Staff account created'),
          content: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text('$email can now sign in with this temporary password '
                  '(ask them to change it after first login):'),
              const SizedBox(height: 12),
              Container(
                width: double.infinity,
                padding: const EdgeInsets.all(12),
                decoration: BoxDecoration(
                  color: Theme.of(ctx).colorScheme.surfaceContainerHighest,
                  borderRadius: BorderRadius.circular(8),
                ),
                child: SelectableText(tempPassword,
                    style: const TextStyle(
                        fontFamily: 'monospace',
                        fontSize: 16,
                        fontWeight: FontWeight.bold)),
              ),
            ],
          ),
          actions: [
            FilledButton(
                onPressed: () => Navigator.pop(ctx),
                child: const Text('Done')),
          ],
        ),
      );
    } else {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('$email assigned as $_role.')),
      );
    }
  }

  String _friendly(Object e) {
    final s = e.toString();
    if (s.contains('EMAIL_IN_OTHER_TENANT') || s.contains('409')) {
      return 'That email already belongs to another business, or the user '
          'already has that role at this store.';
    }
    if (s.contains('400')) return 'Check the email is valid.';
    return 'Could not assign staff: $s';
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final storesAsync = ref.watch(storesProvider);
    return AlertDialog(
      title: const Text('Assign Staff'),
      content: SizedBox(
        width: 420,
        child: Form(
          key: _formKey,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            mainAxisSize: MainAxisSize.min,
            children: [
              Text(
                "Enter the staff member's email. If they don't have an account "
                'yet, one is created automatically and a temporary password is '
                'shown for you to share.',
                style: Theme.of(context)
                    .textTheme
                    .bodySmall
                    ?.copyWith(color: cs.outline),
              ),
              const SizedBox(height: 16),
              if (_error != null) ...[
                Container(
                  padding: const EdgeInsets.all(12),
                  decoration: BoxDecoration(
                    color: cs.errorContainer,
                    borderRadius: BorderRadius.circular(8),
                  ),
                  child: Text(_error!, style: TextStyle(color: cs.onErrorContainer)),
                ),
                const SizedBox(height: 12),
              ],
              TextFormField(
                controller: _emailCtrl,
                keyboardType: TextInputType.emailAddress,
                autofillHints: const [AutofillHints.email],
                decoration: const InputDecoration(
                  labelText: 'Staff email *',
                  prefixIcon: Icon(Icons.alternate_email),
                ),
                validator: (v) {
                  final s = v?.trim() ?? '';
                  if (s.isEmpty) return 'Required';
                  if (!s.contains('@') || !s.contains('.')) {
                    return 'Enter a valid email';
                  }
                  return null;
                },
              ),
              const SizedBox(height: 12),
              TextFormField(
                controller: _passwordCtrl,
                decoration: const InputDecoration(
                  labelText: 'Temporary password (optional)',
                  helperText: 'Leave blank to auto-generate',
                  prefixIcon: Icon(Icons.password_outlined),
                ),
                validator: (v) {
                  final s = v?.trim() ?? '';
                  if (s.isNotEmpty && s.length < 8) {
                    return 'At least 8 characters';
                  }
                  return null;
                },
              ),
              const SizedBox(height: 12),
              storesAsync.when(
                loading: () => const LinearProgressIndicator(),
                error: (e, _) => Text('Could not load stores: $e',
                    style: TextStyle(color: cs.error)),
                data: (stores) => DropdownButtonFormField<String>(
                  value: _storeId,
                  isExpanded: true,
                  decoration: const InputDecoration(
                    labelText: 'Store *',
                    prefixIcon: Icon(Icons.store_outlined),
                  ),
                  items: stores
                      .map((s) => DropdownMenuItem(
                            value: s.id,
                            child: Text('${s.name} (${s.code})',
                                overflow: TextOverflow.ellipsis),
                          ))
                      .toList(),
                  onChanged: (v) => setState(() => _storeId = v),
                  validator: (v) => v == null ? 'Required' : null,
                ),
              ),
              const SizedBox(height: 12),
              DropdownButtonFormField<String>(
                value: _role,
                decoration: const InputDecoration(
                  labelText: 'Role *',
                  prefixIcon: Icon(Icons.shield_outlined),
                ),
                items: _roles
                    .map((r) => DropdownMenuItem(value: r, child: Text(r)))
                    .toList(),
                onChanged: (v) => setState(() => _role = v!),
              ),
            ],
          ),
        ),
      ),
      actions: [
        TextButton(
          onPressed: _loading ? null : () => Navigator.pop(context),
          child: const Text('Cancel'),
        ),
        FilledButton(
          onPressed: _loading ? null : _submit,
          child: _loading
              ? const SizedBox(
                  height: 18,
                  width: 18,
                  child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white))
              : const Text('Assign'),
        ),
      ],
    );
  }
}
