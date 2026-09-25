import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../core/auth/auth_notifier.dart';
import '../../core/auth/sso.dart';
import '../../core/network/api_error.dart';
import '../../core/spacing.dart';
import '../../core/theme.dart';
import '../../l10n/gen/app_localizations.dart';

/// iam-svc's `PasswordPolicy` (NIST SP 800-63B-4): a new password is fifteen
/// to a hundred and twenty-eight characters, counted as a person sees them.
/// Only a new password is held to it — signing in asks for the password the
/// login already has, whatever its length.
const passwordMinLength = 15;
const passwordMaxLength = 128;

/// Why [password] would not do as a new password, in the policy's words, or
/// null when its length is within the policy. Identity and breach checks stay
/// with the server, which says so by code.
///
/// [min] and [max] are the policy's bounds: the ones this app mirrors, until
/// the server names its own in a refusal.
String? newPasswordProblem(
  AppLocalizations l,
  String? password, {
  int min = passwordMinLength,
  int max = passwordMaxLength,
}) {
  final length = (password ?? '').runes.length;
  if (length < min) return l.fieldPasswordTooShort(min);
  if (length > max) return l.fieldPasswordTooLong(max);
  return null;
}

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

  /// The password policy's bounds: the ones this app mirrors, until iam-svc
  /// names its own in a refusal (its minimum is configured). Then the helper,
  /// the check before sending and the refusal all say the server's number.
  int _minLength = passwordMinLength;
  int _maxLength = passwordMaxLength;

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
    if (authAsync.hasError) _learnPolicy(authAsync.error!);
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
          padding: context.pagePadding,
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 420),
            child: Card(
              child: Padding(
                padding: const EdgeInsets.all(AppSpacing.xxl),
                child: Form(
                  key: _formKey,
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      Icon(Icons.storefront_rounded, size: 52, color: cs.primary),
                      const SizedBox(height: AppSpacing.sm),
                      Text(
                        'storeql.com',
                        style: Theme.of(context)
                            .textTheme
                            .headlineMedium
                            ?.copyWith(fontWeight: FontWeight.bold, color: cs.primary),
                        textAlign: TextAlign.center,
                      ),
                      const SizedBox(height: AppSpacing.xs),
                      Text(
                        _isRegister ? l.createYourAccount : l.signInToContinue,
                        style: Theme.of(context)
                            .textTheme
                            .bodyMedium
                            ?.copyWith(color: cs.outline),
                        textAlign: TextAlign.center,
                      ),
                      const SizedBox(height: AppSpacing.xl),
                      if (error != null) ...[
                        Container(
                          padding: const EdgeInsets.symmetric(horizontal: AppSpacing.md, vertical: AppSpacing.sm),
                          decoration: BoxDecoration(
                            color: cs.errorContainer,
                            borderRadius: AppRadius.chip,
                          ),
                          child: Text(error, style: TextStyle(color: cs.onErrorContainer)),
                        ),
                        if (requiredSlug != null) ...[
                          const SizedBox(height: AppSpacing.sm),
                          FilledButton.tonalIcon(
                            key: const Key('sso-continue'),
                            onPressed: isLoading ? null : () => _signInWithBusiness(slug: requiredSlug),
                            icon: const Icon(Icons.business_outlined),
                            label: Text(l.continueWithBusiness(requiredSlug)),
                          ),
                        ],
                        const SizedBox(height: AppSpacing.lg),
                      ],
                      TextFormField(
                        controller: _emailCtrl,
                        keyboardType: TextInputType.emailAddress,
                        textInputAction: TextInputAction.next,
                        decoration: InputDecoration(
                          labelText: l.fieldEmail,
                          prefixIcon: const Icon(Icons.email_outlined),
                        ),
                        validator: (v) =>
                            v == null || !v.contains('@') ? l.fieldEmailInvalid : null,
                      ),
                      if (_isRegister) ...[
                        const SizedBox(height: AppSpacing.lg),
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
                      const SizedBox(height: AppSpacing.lg),
                      TextFormField(
                        // A fresh field per mode, so the other mode's refusal
                        // does not linger; the controller keeps what was typed.
                        key: ValueKey('password-$_isRegister'),
                        controller: _passwordCtrl,
                        obscureText: _obscure,
                        textInputAction: TextInputAction.done,
                        onFieldSubmitted: (_) => _submit(),
                        decoration: InputDecoration(
                          labelText: l.fieldPassword,
                          prefixIcon: const Icon(Icons.lock_outline),
                          // A new password is told the rule before it is refused by it.
                          helperText: _isRegister ? l.fieldPasswordTooShort(_minLength) : null,
                          helperMaxLines: 3,
                          errorMaxLines: 3,
                          suffixIcon: IconButton(
                            icon: Icon(_obscure ? Icons.visibility_off : Icons.visibility),
                            tooltip: _obscure ? l.showPassword : l.hidePassword,
                            onPressed: () => setState(() => _obscure = !_obscure),
                          ),
                        ),
                        // Signing in asks only for a password: the policy is for new ones.
                        validator: (v) => _isRegister
                            ? newPasswordProblem(l, v, min: _minLength, max: _maxLength)
                            : (v == null || v.isEmpty ? l.fieldPasswordRequired : null),
                      ),
                      const SizedBox(height: AppSpacing.xl),
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
                        const SizedBox(height: AppSpacing.md),
                        OutlinedButton.icon(
                          key: const Key('sso-start'),
                          onPressed: isLoading ? null : _signInWithBusiness,
                          icon: const Icon(Icons.business_outlined),
                          label: Text(l.signInWithBusiness),
                        ),
                      ],
                      const SizedBox(height: AppSpacing.sm),
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
    if (e is SsoError) return ssoMessage(e.code, l);
    final code = apiErrorCode(e);
    if (code != null && (code.startsWith('SSO_') || code == 'TENANT_INACTIVE')) {
      return ssoMessage(code, l);
    }
    // iam-svc's PasswordPolicy refusals, each in its own words. A length is the
    // server's own when its message names one (its configured rule), else the
    // one this app mirrors.
    switch (code) {
      case 'PASSWORD_TOO_SHORT':
        return l.fieldPasswordTooShort(_ruleLength(e, 'at least') ?? _minLength);
      case 'PASSWORD_TOO_LONG':
        return l.fieldPasswordTooLong(_ruleLength(e, 'at most') ?? _maxLength);
      case 'PASSWORD_IS_IDENTITY':
        return l.errPasswordIsIdentity;
      case 'PASSWORD_BREACHED':
        return l.errPasswordBreached;
      case 'INVALID_CREDENTIALS':
        return l.errInvalidCredentials;
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

  /// Takes the policy's bounds from a refusal that names them, so the card
  /// never shows two rules.
  void _learnPolicy(Object e) {
    switch (apiErrorCode(e)) {
      case 'PASSWORD_TOO_SHORT':
        _minLength = _ruleLength(e, 'at least') ?? _minLength;
      case 'PASSWORD_TOO_LONG':
        _maxLength = _ruleLength(e, 'at most') ?? _maxLength;
    }
  }

  /// The number after [bound] ("at least", "at most") in the server's message.
  static int? _ruleLength(Object e, String bound) {
    final message = apiErrorOf(e)?.message ?? '';
    final match = RegExp('$bound (\\d+)').firstMatch(message);
    return match == null ? null : int.tryParse(match.group(1)!);
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
  Widget build(BuildContext context) {
    final l = AppLocalizations.of(context);
    return AlertDialog(
      title: Text(l.signInWithBusiness),
      content: SizedBox(
        width: 380,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(l.businessSignInNameHelp),
            const SizedBox(height: AppSpacing.md),
            TextField(
              key: const Key('sso-slug'),
              controller: _ctrl,
              autofocus: true,
              autocorrect: false,
              textInputAction: TextInputAction.go,
              onSubmitted: (_) => _go(),
              decoration: InputDecoration(
                labelText: l.fieldBusinessSignInName,
                hintText: l.fieldBusinessSignInNameHint,
                prefixIcon: const Icon(Icons.business_outlined),
              ),
            ),
          ],
        ),
      ),
      actions: [
        TextButton(onPressed: () => Navigator.of(context).pop(), child: Text(l.actionCancel)),
        FilledButton(key: const Key('sso-go'), onPressed: _go, child: Text(l.actionContinue)),
      ],
    );
  }
}
