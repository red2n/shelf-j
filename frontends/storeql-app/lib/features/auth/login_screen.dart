import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../core/auth/auth_notifier.dart';
import '../../core/auth/sso.dart';
import '../../core/network/api_error.dart';
import '../../l10n/gen/app_localizations.dart';
import '../../core/theme.dart';

class LoginScreen extends ConsumerStatefulWidget {
  const LoginScreen({super.key});

  @override
  ConsumerState<LoginScreen> createState() => _LoginScreenState();
}

class _LoginScreenState extends ConsumerState<LoginScreen> {
  final _formKey = GlobalKey<FormState>();
  final _emailCtrl = TextEditingController();
  final _passwordCtrl = TextEditingController();
  final _phoneCtrl = TextEditingController();
  bool _obscure = true;
  bool _isRegister = false;

  @override
  void dispose() {
    _emailCtrl.dispose();
    _passwordCtrl.dispose();
    _phoneCtrl.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    if (!_formKey.currentState!.validate()) return;
    final notifier = ref.read(authNotifierProvider.notifier);
    if (_isRegister) {
      final phone = _phoneCtrl.text.trim();
      await notifier.register(
        _emailCtrl.text.trim(),
        _passwordCtrl.text,
        phone.isEmpty ? null : phone,
      );
    } else {
      await notifier.login(_emailCtrl.text.trim(), _passwordCtrl.text);
    }
    // routing handled by go_router redirect on auth state change
  }

  /// Signing in through the business's own identity provider (20.x): asks for
  /// the business's sign-in name, unless the server has just named it.
  Future<void> _signInWithBusiness({String? slug}) async {
    final name = slug ?? await showDialog<String>(context: context, builder: (_) => const _BusinessNameDialog());
    if (name == null || name.trim().isEmpty) return;
    await ref.read(authNotifierProvider.notifier).startSso(name);
  }

  @override
  Widget build(BuildContext context) {
    final l = AppLocalizations.of(context);
    final authAsync = ref.watch(authNotifierProvider);
    final isLoading = authAsync.isLoading;
    final error = authAsync.hasError ? _friendlyError(context, authAsync.error!) : null;
    // A password refused because the business signs its staff in through its
    // provider: the server names the business, so one press continues there.
    final requiredSlug = authAsync.hasError && apiErrorCode(authAsync.error!) == 'SSO_REQUIRED'
        ? apiErrorOf(authAsync.error!)?.detail('slug')
        : null;
    final cs = Theme.of(context).colorScheme;

    return Scaffold(
      body: Center(
        child: SingleChildScrollView(
          padding: const EdgeInsets.all(24),
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 420),
            child: Card(
              child: Padding(
                padding: const EdgeInsets.all(32),
                child: Form(
                  key: _formKey,
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      Icon(Icons.storefront_rounded, size: 52, color: cs.primary),
                      const SizedBox(height: 8),
                      Text(
                        'storeql.com',
                        style: Theme.of(context)
                            .textTheme
                            .headlineMedium
                            ?.copyWith(fontWeight: FontWeight.bold, color: cs.primary),
                        textAlign: TextAlign.center,
                      ),
                      const SizedBox(height: 4),
                      Text(
                        _isRegister ? l.createYourAccount : l.signInToContinue,
                        style: Theme.of(context)
                            .textTheme
                            .bodyMedium
                            ?.copyWith(color: cs.outline),
                        textAlign: TextAlign.center,
                      ),
                      const SizedBox(height: 28),
                      if (error != null) ...[
                        Container(
                          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
                          decoration: BoxDecoration(
                            color: cs.errorContainer,
                            borderRadius: AppRadius.chip,
                          ),
                          child: Text(error, style: TextStyle(color: cs.onErrorContainer)),
                        ),
                        if (requiredSlug != null) ...[
                          const SizedBox(height: 8),
                          FilledButton.tonalIcon(
                            key: const Key('sso-continue'),
                            onPressed: isLoading ? null : () => _signInWithBusiness(slug: requiredSlug),
                            icon: const Icon(Icons.business_outlined),
                            label: Text('Continue with $requiredSlug'),
                          ),
                        ],
                        const SizedBox(height: 16),
                      ],
                      TextFormField(
                        controller: _emailCtrl,
                        keyboardType: TextInputType.emailAddress,
                        textInputAction: _isRegister ? TextInputAction.next : TextInputAction.next,
                        decoration: InputDecoration(
                          labelText: l.fieldEmail,
                          prefixIcon: const Icon(Icons.email_outlined),
                        ),
                        validator: (v) =>
                            v == null || !v.contains('@') ? l.fieldEmailInvalid : null,
                      ),
                      if (_isRegister) ...[
                        const SizedBox(height: 16),
                        TextFormField(
                          controller: _phoneCtrl,
                          keyboardType: TextInputType.phone,
                          textInputAction: TextInputAction.next,
                          decoration: InputDecoration(
                            labelText: l.fieldPhoneOptional,
                            prefixIcon: const Icon(Icons.phone_outlined),
                          ),
                        ),
                      ],
                      const SizedBox(height: 16),
                      TextFormField(
                        controller: _passwordCtrl,
                        obscureText: _obscure,
                        textInputAction: TextInputAction.done,
                        onFieldSubmitted: (_) => _submit(),
                        decoration: InputDecoration(
                          labelText: l.fieldPassword,
                          prefixIcon: const Icon(Icons.lock_outline),
                          suffixIcon: IconButton(
                            icon: Icon(_obscure ? Icons.visibility_off : Icons.visibility),
                            tooltip: _obscure ? 'Show password' : 'Hide password',
                            onPressed: () => setState(() => _obscure = !_obscure),
                          ),
                        ),
                        validator: (v) =>
                            v == null || v.length < 8 ? l.fieldPasswordTooShort : null,
                      ),
                      const SizedBox(height: 24),
                      FilledButton(
                        onPressed: isLoading ? null : _submit,
                        child: isLoading
                            ? const SizedBox(
                                height: 20,
                                width: 20,
                                child: CircularProgressIndicator(strokeWidth: 2),
                              )
                            : Text(_isRegister ? l.actionCreateAccount : l.actionSignIn),
                      ),
                      if (!_isRegister && ssoBrowser.supported) ...[
                        const SizedBox(height: 12),
                        OutlinedButton.icon(
                          key: const Key('sso-start'),
                          onPressed: isLoading ? null : _signInWithBusiness,
                          icon: const Icon(Icons.business_outlined),
                          label: const Text('Sign in with your business'),
                        ),
                      ],
                      const SizedBox(height: 8),
                      TextButton(
                        onPressed: isLoading
                            ? null
                            : () => setState(() => _isRegister = !_isRegister),
                        child: Text(
                            _isRegister ? l.toggleHaveAccount : l.toggleNewHere),
                      ),
                    ],
                  ),
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }

  String _friendlyError(BuildContext context, Object e) {
    final l = AppLocalizations.of(context);
    if (e is SsoError) return ssoMessage(e.code);
    final code = apiErrorCode(e);
    if (code != null && (code.startsWith('SSO_') || code == 'TENANT_INACTIVE')) {
      return ssoMessage(code);
    }
    final raw = e.toString();
    if (raw.contains('401') || raw.contains('INVALID_CREDENTIALS')) {
      return l.errInvalidCredentials;
    }
    if (raw.contains('409') || raw.contains('EMAIL_ALREADY_EXISTS')) {
      return l.errEmailExists;
    }
    if (raw.contains('SocketException') || raw.contains('Failed host lookup')) {
      return l.errNetwork;
    }
    return l.errGeneric;
  }
}

/// The business's sign-in name: what its owner chose when connecting its
/// identity provider, and told its staff.
class _BusinessNameDialog extends StatefulWidget {
  const _BusinessNameDialog();

  @override
  State<_BusinessNameDialog> createState() => _BusinessNameDialogState();
}

class _BusinessNameDialogState extends State<_BusinessNameDialog> {
  final _ctrl = TextEditingController();

  @override
  void dispose() {
    _ctrl.dispose();
    super.dispose();
  }

  void _go() => Navigator.of(context).pop(_ctrl.text.trim());

  @override
  Widget build(BuildContext context) => AlertDialog(
        title: const Text('Sign in with your business'),
        content: SizedBox(
          width: 380,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const Text("Your business's sign-in name. Your manager has it."),
              const SizedBox(height: 12),
              TextField(
                key: const Key('sso-slug'),
                controller: _ctrl,
                autofocus: true,
                autocorrect: false,
                textInputAction: TextInputAction.go,
                onSubmitted: (_) => _go(),
                decoration: const InputDecoration(
                  labelText: 'Sign-in name',
                  hintText: 'e.g. acme-foods',
                  prefixIcon: Icon(Icons.business_outlined),
                ),
              ),
            ],
          ),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.of(context).pop(), child: const Text('Cancel')),
          FilledButton(key: const Key('sso-go'), onPressed: _go, child: const Text('Continue')),
        ],
      );
}
