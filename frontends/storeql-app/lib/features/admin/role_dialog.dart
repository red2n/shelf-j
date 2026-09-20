import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../core/constants.dart';
import '../../core/network/api_client.dart';
import '../../core/network/api_error.dart';
import 'providers/admin_providers.dart';

/// Define a role of the tenant's own, or — with [existing] — redefine one
/// (20.10). A role stands on a tier and holds a subset of that tier's
/// permissions: the checkboxes offered are exactly the tier's defaults, because
/// the server refuses anything outside them and a box that cannot be ticked
/// should not be on the screen. The tier is fixed once the role exists.
class RoleDialog extends ConsumerStatefulWidget {
  const RoleDialog({super.key, this.existing});

  final TenantRole? existing;

  @override
  ConsumerState<RoleDialog> createState() => _RoleDialogState();
}

class _RoleDialogState extends ConsumerState<RoleDialog> {
  static const tiers = ['MANAGER', 'STOREKEEPER', 'CASHIER'];
  final _code = TextEditingController();
  final _name = TextEditingController();
  final _description = TextEditingController();
  String _tier = 'CASHIER';
  final Set<String> _granted = {};
  bool _busy = false;
  String? _error;

  bool get _editing => widget.existing != null;

  @override
  void initState() {
    super.initState();
    final e = widget.existing;
    if (e != null) {
      _code.text = e.code;
      _name.text = e.name;
      _description.text = e.description ?? '';
      _tier = e.baseTier;
      _granted.addAll(e.permissions);
    }
    _code.addListener(_refresh);
    _name.addListener(_refresh);
  }

  void _refresh() => setState(() {});

  @override
  void dispose() {
    _code.dispose();
    _name.dispose();
    _description.dispose();
    super.dispose();
  }

  static final _codeShape = RegExp(r'^[A-Z][A-Z0-9_]{1,31}$');

  String? get _blocker {
    final code = _code.text.trim().toUpperCase();
    if (!_codeShape.hasMatch(code)) {
      return 'Code: 2–32 upper-case letters, digits or underscores.';
    }
    if (const ['OWNER', 'MANAGER', 'STOREKEEPER', 'CASHIER', 'PLATFORM_ADMIN', 'CUSTOMER']
        .contains(code)) {
      return '$code is a built-in role.';
    }
    if (_name.text.trim().isEmpty) return 'Give the role a name.';
    return null;
  }

  Future<void> _submit() async {
    if (_blocker != null || _busy) return;
    setState(() {
      _busy = true;
      _error = null;
    });
    final dio = ref.read(apiClientProvider).dio;
    final code = _code.text.trim().toUpperCase();
    try {
      if (_editing) {
        await dio.put(
          '/${ApiConstants.tenant}/admin/roles/$code',
          data: {
            'name': _name.text.trim(),
            'permissions': _granted.toList()..sort(),
            'description': _description.text.trim(),
          },
        );
      } else {
        await dio.post(
          '/${ApiConstants.tenant}/admin/roles',
          data: {
            'code': code,
            'name': _name.text.trim(),
            'baseTier': _tier,
            'permissions': _granted.toList()..sort(),
            'description': _description.text.trim(),
          },
        );
      }
      if (!mounted) return;
      ref.invalidate(rolesProvider);
      Navigator.pop(context, true);
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(
          content: Text(_editing
              ? '$code redefined; holders carry it from their next sign-in.'
              : '$code defined.')));
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _busy = false;
        _error = friendlyError(e,
            fallback: _editing ? 'Could not redefine the role.' : 'Could not define the role.');
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final catalogue = ref.watch(permissionCatalogueProvider);
    final blocker = _blocker;
    return AlertDialog(
      title: Text(_editing ? 'Redefine ${widget.existing!.code}' : 'Define a role'),
      content: SizedBox(
        width: 520,
        child: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Text(
                'A role stands on a built-in tier and holds some of what that tier holds — '
                'never more. Untick what this role must not do.',
                style: TextStyle(color: cs.outline, fontSize: 13),
              ),
              const SizedBox(height: 12),
              Row(children: [
                Expanded(
                  child: TextField(
                    key: const Key('role-code'),
                    controller: _code,
                    enabled: !_editing,
                    maxLength: 32,
                    textCapitalization: TextCapitalization.characters,
                    decoration: const InputDecoration(
                        labelText: 'Code', hintText: 'SHIFT_LEAD', counterText: ''),
                  ),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: TextField(
                    key: const Key('role-name'),
                    controller: _name,
                    maxLength: 60,
                    decoration:
                        const InputDecoration(labelText: 'Name', counterText: ''),
                  ),
                ),
              ]),
              const SizedBox(height: 8),
              DropdownButtonFormField<String>(
                key: const Key('role-tier'),
                initialValue: _tier,
                decoration: const InputDecoration(labelText: 'Stands on'),
                items: tiers
                    .map((t) => DropdownMenuItem(value: t, child: Text(t)))
                    .toList(),
                onChanged: _editing
                    ? null
                    : (v) => setState(() {
                          _tier = v!;
                          // What the new tier does not hold cannot stay ticked.
                          _granted.removeWhere((p) => !_tierHolds(catalogue, _tier, p));
                        }),
              ),
              const SizedBox(height: 8),
              TextField(
                key: const Key('role-description'),
                controller: _description,
                maxLength: 200,
                decoration:
                    const InputDecoration(labelText: 'Description (optional)', counterText: ''),
              ),
              const SizedBox(height: 8),
              catalogue.when(
                loading: () => const LinearProgressIndicator(),
                error: (e, _) => Text(
                    friendlyError(e, fallback: 'Could not load the permissions.'),
                    style: TextStyle(color: cs.error)),
                data: (perms) {
                  final offered = perms.where((p) => p.defaultFor.contains(_tier)).toList();
                  if (offered.isEmpty) {
                    return Text('A $_tier holds no permission that can be taken away.',
                        style: TextStyle(color: cs.outline, fontSize: 13));
                  }
                  return Column(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      for (final p in offered)
                        CheckboxListTile(
                          key: Key('role-perm-${p.code}'),
                          dense: true,
                          contentPadding: EdgeInsets.zero,
                          controlAffinity: ListTileControlAffinity.leading,
                          value: _granted.contains(p.code),
                          onChanged: (v) => setState(() {
                            if (v == true) {
                              _granted.add(p.code);
                            } else {
                              _granted.remove(p.code);
                            }
                          }),
                          title: Text(p.description, style: const TextStyle(fontSize: 13)),
                          subtitle: Text(p.code,
                              style: const TextStyle(fontFamily: 'monospace', fontSize: 11)),
                        ),
                    ],
                  );
                },
              ),
              if (blocker != null && _error == null)
                Padding(
                  padding: const EdgeInsets.only(top: 4),
                  child: Text(blocker, style: TextStyle(color: cs.outline, fontSize: 12)),
                ),
              if (_error != null)
                Padding(
                  padding: const EdgeInsets.only(top: 8),
                  child: Text(_error!, style: TextStyle(color: cs.error)),
                ),
            ],
          ),
        ),
      ),
      actions: [
        TextButton(
          onPressed: _busy ? null : () => Navigator.pop(context, false),
          child: const Text('Cancel'),
        ),
        FilledButton(
          key: const Key('role-save'),
          onPressed: blocker == null && !_busy ? _submit : null,
          child: Text(_editing ? 'Save' : 'Define'),
        ),
      ],
    );
  }

  static bool _tierHolds(AsyncValue<List<PermissionInfo>> catalogue, String tier, String code) {
    final perms = catalogue.value;
    if (perms == null) return true;
    final p = perms.where((x) => x.code == code);
    return p.isEmpty || p.first.defaultFor.contains(tier);
  }
}
