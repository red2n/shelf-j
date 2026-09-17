import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/network/api_error.dart';
import '../auth/mfa_api.dart';

/// The business's rule about second factors (20.12): which tiers of its staff
/// must have one. A login in a required tier without one is made to set it up at
/// its next sign-in, and a session that was only a password's is not renewed.
class MfaPolicyDialog extends ConsumerStatefulWidget {
  const MfaPolicyDialog({super.key});

  @override
  ConsumerState<MfaPolicyDialog> createState() => _MfaPolicyDialogState();
}

class _MfaPolicyDialogState extends ConsumerState<MfaPolicyDialog> {
  static const _tiers = {
    'OWNER': 'Owners',
    'MANAGER': 'Managers',
    'STOREKEEPER': 'Storekeepers',
    'CASHIER': 'Cashiers',
  };

  Set<String>? _required;
  String? _error;
  bool _saving = false;

  Future<void> _save() async {
    setState(() {
      _saving = true;
      _error = null;
    });
    try {
      await ref.read(mfaApiProvider).setPolicy(_required!.toList()..sort());
      ref.invalidate(mfaPolicyProvider);
      if (mounted) Navigator.of(context).pop(true);
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _saving = false;
        _error = apiErrorCode(e) == 'FORBIDDEN' || apiErrorCode(e) == 'PERMISSION_DENIED'
            ? 'Only an owner changes this.'
            : friendlyError(e);
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final policy = ref.watch(mfaPolicyProvider);
    final cs = Theme.of(context).colorScheme;
    return AlertDialog(
      title: const Text('Second step at sign-in'),
      content: SizedBox(
        width: 420,
        child: policy.when(
          loading: () => const SizedBox(height: 120, child: Center(child: CircularProgressIndicator())),
          error: (e, _) => Text(friendlyError(e)),
          data: (current) {
            final required = _required ??= current.toSet();
            return Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const Text(
                  'Who must sign in with a second step — a code from an app on their phone, or a passkey — '
                  'as well as a password. Anyone may set one up; these must.',
                ),
                const SizedBox(height: 8),
                for (final tier in _tiers.entries)
                  CheckboxListTile(
                    key: Key('mfa-tier-${tier.key}'),
                    contentPadding: EdgeInsets.zero,
                    controlAffinity: ListTileControlAffinity.leading,
                    title: Text(tier.value),
                    value: required.contains(tier.key),
                    onChanged: _saving
                        ? null
                        : (v) => setState(() => v == true ? required.add(tier.key) : required.remove(tier.key)),
                  ),
                const SizedBox(height: 4),
                Text(
                  'Someone in a ticked group without a second step sets one up the next time they sign in. '
                  'If they lose their phone, reset theirs from the staff list.',
                  style: Theme.of(context).textTheme.bodySmall?.copyWith(color: cs.outline),
                ),
                if (_error != null) ...[
                  const SizedBox(height: 8),
                  Text(_error!, style: TextStyle(color: cs.error)),
                ],
              ],
            );
          },
        ),
      ),
      actions: [
        TextButton(onPressed: _saving ? null : () => Navigator.of(context).pop(), child: const Text('Cancel')),
        FilledButton(
          key: const Key('mfa-policy-save'),
          onPressed: _saving || _required == null ? null : _save,
          child: Text(_saving ? 'Saving…' : 'Save'),
        ),
      ],
    );
  }
}

/// The lost phone: clears a member of staff's second factors, after asking.
Future<void> resetSecondFactor(BuildContext context, WidgetRef ref, String userId, String who) async {
  final ok = await showDialog<bool>(
    context: context,
    builder: (context) => AlertDialog(
      title: const Text('Reset second step'),
      content: Text(
        'This removes the authenticator app, passkeys and recovery codes of $who and signs them out everywhere. '
        'Use it when they have lost their phone. They sign in with their password next, and set a second step '
        'up again if your business requires one.',
      ),
      actions: [
        TextButton(onPressed: () => Navigator.of(context).pop(false), child: const Text('Cancel')),
        FilledButton(
          key: const Key('mfa-reset-confirm'),
          onPressed: () => Navigator.of(context).pop(true),
          child: const Text('Reset'),
        ),
      ],
    ),
  );
  if (ok != true || !context.mounted) return;
  final messenger = ScaffoldMessenger.of(context);
  try {
    await ref.read(mfaApiProvider).resetStaff(userId);
    messenger.showSnackBar(SnackBar(content: Text('Second step reset for $who.')));
  } catch (e) {
    messenger.showSnackBar(SnackBar(
      content: Text(switch (apiErrorCode(e)) {
        'MFA_RESET_SELF' => 'Your own is changed under Account → Sign-in security.',
        'FORBIDDEN' || 'PERMISSION_DENIED' => 'Only an owner resets a second step.',
        _ => friendlyError(e),
      }),
    ));
  }
}
