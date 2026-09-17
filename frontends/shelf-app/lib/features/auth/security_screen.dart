import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/auth/passkeys.dart';
import '../../core/network/api_error.dart';
import 'mfa_api.dart';
import 'mfa_widgets.dart';

/// A login's own second factors (20.12): an authenticator app, passkeys, and the
/// recovery codes that go with them. Taking one away asks for the password, and
/// is refused when the business — or the platform — requires a factor and it is
/// the last.
class SecurityScreen extends ConsumerWidget {
  const SecurityScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final status = ref.watch(mfaStatusProvider);
    return Scaffold(
      appBar: AppBar(title: const Text('Sign-in security')),
      body: Center(
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 640),
          child: status.when(
            loading: () => const Center(child: CircularProgressIndicator()),
            error: (e, _) => Center(child: Text(friendlyError(e))),
            data: (s) => _Factors(status: s),
          ),
        ),
      ),
    );
  }
}

class _Factors extends ConsumerWidget {
  final MfaStatus status;

  const _Factors({required this.status});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final api = ref.watch(mfaApiProvider);
    final text = Theme.of(context).textTheme;
    final cs = Theme.of(context).colorScheme;
    void refresh() => ref.invalidate(mfaStatusProvider);

    return ListView(
      padding: const EdgeInsets.all(24),
      children: [
        Text('Second step at sign-in', style: text.titleLarge),
        const SizedBox(height: 4),
        Text(
          status.enrolled
              ? 'You are asked for a second step every time you sign in.'
              : 'Your password is all that protects this login. Add a second step: a code from an app on '
                  'your phone, or a passkey on this device.',
          style: text.bodyMedium?.copyWith(color: cs.outline),
        ),
        if (status.required) ...[
          const SizedBox(height: 12),
          Container(
            key: const Key('mfa-required'),
            padding: const EdgeInsets.all(12),
            decoration: BoxDecoration(color: cs.secondaryContainer, borderRadius: BorderRadius.circular(8)),
            child: Text(
              'A second step is required of this login, so the last one cannot be removed.',
              style: TextStyle(color: cs.onSecondaryContainer),
            ),
          ),
        ],
        const SizedBox(height: 24),
        Card(
          child: ListTile(
            leading: const Icon(Icons.phonelink_lock_outlined),
            title: const Text('Authenticator app'),
            subtitle: Text(status.totp ? 'Set up' : 'Not set up'),
            trailing: status.totp
                ? TextButton(
                    key: const Key('totp-remove'),
                    onPressed: () => _withPassword(context, 'Remove the authenticator app', api.removeTotp, refresh),
                    child: const Text('Remove'),
                  )
                : FilledButton.tonal(
                    key: const Key('totp-setup'),
                    onPressed: () => _setUpTotp(context, api, refresh),
                    child: const Text('Set up'),
                  ),
          ),
        ),
        if (passkeys.supported || status.passkeys.isNotEmpty)
          Card(
            child: Column(
              children: [
                ListTile(
                  leading: const Icon(Icons.fingerprint),
                  title: const Text('Passkeys'),
                  subtitle: const Text('Cannot be phished: a passkey only answers the real site.'),
                  trailing: passkeys.supported
                      ? FilledButton.tonal(
                          key: const Key('passkey-add'),
                          onPressed: () => _addPasskey(context, api, refresh),
                          child: const Text('Add'),
                        )
                      : null,
                ),
                for (final p in status.passkeys)
                  ListTile(
                    dense: true,
                    contentPadding: const EdgeInsets.only(left: 72, right: 16),
                    title: Text(p.name),
                    subtitle: Text(p.lastUsedAt == null ? 'Never used' : 'Last used ${p.lastUsedAt!.substring(0, 10)}'),
                    trailing: IconButton(
                      tooltip: 'Remove this passkey',
                      icon: const Icon(Icons.delete_outline),
                      onPressed: () => _withPassword(
                        context,
                        'Remove "${p.name}"',
                        (password) => api.removePasskey(p.id, password),
                        refresh,
                      ),
                    ),
                  ),
              ],
            ),
          ),
        if (status.enrolled)
          Card(
            child: ListTile(
              leading: const Icon(Icons.key_outlined),
              title: const Text('Recovery codes'),
              subtitle: Text('${status.recoveryCodesLeft} left. Each signs you in once if you lose your phone.'),
              trailing: TextButton(
                key: const Key('recovery-new'),
                onPressed: () => _newCodes(context, api, refresh),
                child: const Text('New codes'),
              ),
            ),
          ),
      ],
    );
  }

  Future<void> _setUpTotp(BuildContext context, MfaApi api, VoidCallback refresh) async {
    await showDialog<void>(
      context: context,
      barrierDismissible: false,
      builder: (context) => _FactorDialog(
        title: 'Authenticator app',
        body: (onEnrolled) => TotpSetup(api: api, onEnrolled: onEnrolled),
      ),
    );
    refresh();
  }

  Future<void> _addPasskey(BuildContext context, MfaApi api, VoidCallback refresh) async {
    final name = await _ask(context, 'Name this passkey', 'For example: Office laptop', obscure: false);
    if (name == null || name.trim().isEmpty || !context.mounted) return;
    try {
      final enrolled = await api.addPasskey(name);
      if (context.mounted && enrolled.recoveryCodes.isNotEmpty) {
        await _showCodes(context, enrolled.recoveryCodes);
      }
    } on PasskeyCancelled {
      return;
    } catch (e) {
      if (context.mounted) _say(context, friendlyError(e));
    }
    refresh();
  }

  Future<void> _newCodes(BuildContext context, MfaApi api, VoidCallback refresh) async {
    final password = await _ask(context, 'New recovery codes', 'The old ones stop working. Your password:');
    if (password == null || password.isEmpty || !context.mounted) return;
    try {
      final codes = await api.newRecoveryCodes(password);
      if (context.mounted) await _showCodes(context, codes);
    } catch (e) {
      if (context.mounted) _say(context, _refusal(e));
    }
    refresh();
  }

  Future<void> _withPassword(
    BuildContext context,
    String title,
    Future<void> Function(String password) action,
    VoidCallback refresh,
  ) async {
    final password = await _ask(context, title, 'Your password, to confirm it is you:');
    if (password == null || password.isEmpty || !context.mounted) return;
    try {
      await action(password);
    } catch (e) {
      if (context.mounted) _say(context, _refusal(e));
    }
    refresh();
  }

  static String _refusal(Object e) => switch (apiErrorCode(e)) {
        'INVALID_CREDENTIALS' => 'That password did not match.',
        'MFA_REQUIRED_BY_POLICY' => 'A second step is required of this login: add another before removing the last.',
        _ => friendlyError(e),
      };

  static void _say(BuildContext context, String message) =>
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(message)));

  static Future<void> _showCodes(BuildContext context, List<String> codes) => showDialog<void>(
        context: context,
        barrierDismissible: false,
        builder: (context) => AlertDialog(
          content: SizedBox(
            width: 420,
            child: RecoveryCodesPanel(codes: codes, doneLabel: 'Done', onDone: () => Navigator.of(context).pop()),
          ),
        ),
      );

  static Future<String?> _ask(BuildContext context, String title, String label, {bool obscure = true}) {
    final controller = TextEditingController();
    return showDialog<String>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(title),
        content: TextField(
          key: const Key('security-prompt'),
          controller: controller,
          autofocus: true,
          obscureText: obscure,
          decoration: InputDecoration(labelText: label),
          onSubmitted: (v) => Navigator.of(context).pop(v),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.of(context).pop(), child: const Text('Cancel')),
          FilledButton(
            key: const Key('security-prompt-ok'),
            onPressed: () => Navigator.of(context).pop(controller.text),
            child: const Text('Continue'),
          ),
        ],
      ),
    );
  }
}

/// Setting a factor up in a dialog: the set-up itself, then — when it was the
/// first — the recovery codes, which must be kept before the dialog will close.
class _FactorDialog extends StatefulWidget {
  final String title;
  final Widget Function(ValueChanged<FactorEnrolled> onEnrolled) body;

  const _FactorDialog({required this.title, required this.body});

  @override
  State<_FactorDialog> createState() => _FactorDialogState();
}

class _FactorDialogState extends State<_FactorDialog> {
  List<String>? _codes;

  @override
  Widget build(BuildContext context) {
    final codes = _codes;
    return AlertDialog(
      title: codes == null ? Text(widget.title) : null,
      content: SizedBox(
        width: 420,
        child: SingleChildScrollView(
          child: codes != null
              ? RecoveryCodesPanel(codes: codes, doneLabel: 'Done', onDone: () => Navigator.of(context).pop())
              : widget.body((enrolled) {
                  if (enrolled.recoveryCodes.isEmpty) {
                    Navigator.of(context).pop();
                  } else {
                    setState(() => _codes = enrolled.recoveryCodes);
                  }
                }),
        ),
      ),
      actions: codes == null
          ? [TextButton(onPressed: () => Navigator.of(context).pop(), child: const Text('Cancel'))]
          : null,
    );
  }
}
