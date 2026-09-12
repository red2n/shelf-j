import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import '../../core/network/api_error.dart';
import '../../shared/widgets/adaptive_nav_shell.dart';
import 'storefront_providers.dart';
import 'survey_widgets.dart';

const _destinations = [
  AdaptiveNavDestination(
    label: 'Shop',
    icon: Icons.store_outlined,
    selectedIcon: Icons.store,
  ),
  AdaptiveNavDestination(
    label: 'Cart',
    icon: Icons.shopping_bag_outlined,
    selectedIcon: Icons.shopping_bag,
  ),
];

const _routes = ['/store/products', '/store/cart'];

class StorefrontShell extends ConsumerWidget {
  final String currentLocation;
  final Widget child;

  const StorefrontShell({
    super.key,
    required this.currentLocation,
    required this.child,
  });

  int get _selectedIndex => currentLocation.startsWith('/store/cart') ? 1 : 0;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final count = ref.watch(cartProvider).fold<int>(0, (s, l) => s + l.qty);
    // A deactivated tenant's shop is closed — show a friendly notice instead of
    // letting every product/price call fail with a raw 403.
    final suspended = ref.watch(storefrontSuspendedProvider).value ?? false;

    // Show the preferences sheet once after a customer first signs in or registers.
    ref.listen<bool>(storefrontJustAuthenticatedProvider, (_, justAuth) {
      if (!justAuth) return;
      ref.read(storefrontJustAuthenticatedProvider.notifier).state = false;
      final asked = ref.read(customerPrefsProvider).prefsAsked;
      if (!asked) {
        WidgetsBinding.instance.addPostFrameCallback(
            (_) => showPreferencesSheet(context));
      }
    });

    return AdaptiveNavShell(
      title: 'Shop',
      destinations: _destinations,
      selectedIndex: _selectedIndex,
      onDestinationSelected: (i) => context.go(_routes[i]),
      // Only 2 destinations — a bottom bar beats a hamburger-triggered drawer
      // for the phone-first shopping flow (Material's compact-width guidance).
      compactStyle: CompactNavStyle.bottomBar,
      actions: suspended
          ? const []
          : [
              const _AccountAction(),
              Padding(
                padding: const EdgeInsets.only(right: 8),
                child: Badge(
                  isLabelVisible: count > 0,
                  label: Text('$count'),
                  child: IconButton(
                    icon: const Icon(Icons.shopping_cart_outlined),
                    tooltip: 'Cart',
                    onPressed: () => context.go('/store/cart'),
                  ),
                ),
              ),
            ],
      child: suspended
          ? const _StoreUnavailable()
          : Column(
              children: [
                Expanded(child: child),
                // Sticky cart bar — a constant, low-friction path to checkout
                // while browsing. Hidden on the cart screen (it has its own CTA).
                if (!currentLocation.startsWith('/store/cart')) const _CartBar(),
              ],
            ),
    );
  }
}

/// Shown when the tenant is deactivated (gateway 403). The shop is closed.
class _StoreUnavailable extends StatelessWidget {
  const _StoreUnavailable();

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(32),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(Icons.storefront_outlined, size: 72, color: cs.outlineVariant),
            const SizedBox(height: 20),
            Text('This store is currently unavailable',
                textAlign: TextAlign.center,
                style: Theme.of(context).textTheme.titleLarge),
            const SizedBox(height: 8),
            Text('Please check back later or contact the store directly.',
                textAlign: TextAlign.center,
                style: TextStyle(color: cs.outline)),
          ],
        ),
      ),
    );
  }
}

class _CartBar extends ConsumerWidget {
  const _CartBar();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final cart = ref.watch(cartProvider);
    if (cart.isEmpty) return const SizedBox.shrink();

    final cs = Theme.of(context).colorScheme;
    final showPrices = ref.watch(storefrontShowPricesProvider);
    final count = cart.fold<int>(0, (s, l) => s + l.qty);
    final total = cart.fold<double>(0, (s, l) => s + l.lineTotal);
    final currency = cart.first.currency;

    return Material(
      color: cs.primary,
      elevation: 8,
      child: SafeArea(
        top: false,
        child: InkWell(
          onTap: () => context.go('/store/cart'),
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
            child: Row(
              children: [
                Badge(
                  label: Text('$count'),
                  child: Icon(Icons.shopping_bag, color: cs.onPrimary),
                ),
                const SizedBox(width: 16),
                Text(
                  showPrices
                      ? '$currency ${total.toStringAsFixed(2)}'
                      : '$count item${count == 1 ? '' : 's'}',
                  style: TextStyle(
                      color: cs.onPrimary,
                      fontSize: 16,
                      fontWeight: FontWeight.bold),
                ),
                const Spacer(),
                Text('View cart',
                    style: TextStyle(
                        color: cs.onPrimary, fontWeight: FontWeight.w600)),
                Icon(Icons.chevron_right, color: cs.onPrimary),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

/// App-bar account button: shows the signed-in email (with sign-out) or a
/// "Sign in" entry point to the customer auth dialog.
class _AccountAction extends ConsumerWidget {
  const _AccountAction();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final auth = ref.watch(storefrontAuthProvider);
    if (!auth.isSignedIn) {
      return PopupMenuButton<String>(
        icon: const Icon(Icons.person_outline),
        tooltip: 'Account',
        onSelected: (v) {
          if (v == 'signin') {
            showDialog(
                context: context, builder: (_) => const StorefrontAuthDialog());
          } else if (v == 'feedback') {
            showFeedbackSheet(context);
          }
        },
        itemBuilder: (_) => const [
          PopupMenuItem(value: 'signin', child: Text('Sign in')),
          PopupMenuItem(value: 'feedback', child: Text('Send feedback')),
        ],
      );
    }
    return PopupMenuButton<String>(
      icon: const Icon(Icons.account_circle),
      tooltip: auth.email ?? 'Account',
      onSelected: (v) {
        if (v == 'orders') {
          context.go('/store/orders');
        } else if (v == 'preferences') {
          showPreferencesSheet(context);
        } else if (v == 'privacy') {
          context.go('/store/privacy');
        } else if (v == 'feedback') {
          showFeedbackSheet(context);
        } else if (v == 'logout') {
          ref.read(storefrontAuthProvider.notifier).logout();
        } else if (v == 'delete_account') {
          showDialog(context: context, builder: (_) => const _DeleteAccountDialog());
        }
      },
      itemBuilder: (_) => [
        PopupMenuItem(
          enabled: false,
          child: Text(auth.email ?? 'Signed in',
              style: const TextStyle(fontWeight: FontWeight.bold)),
        ),
        const PopupMenuItem(value: 'orders', child: Text('My orders')),
        const PopupMenuItem(value: 'preferences', child: Text('My preferences')),
        const PopupMenuItem(
            value: 'privacy', child: Text('Privacy & marketing')),
        const PopupMenuItem(value: 'feedback', child: Text('Send feedback')),
        const PopupMenuItem(value: 'logout', child: Text('Sign out')),
        const PopupMenuDivider(),
        const PopupMenuItem(
            value: 'delete_account', child: Text('Delete my account')),
      ],
    );
  }
}

/// SJ-D43: the storefront's self-service "delete my account" — separate from
/// the admin's "Anonymize" action on [_CustomerDetailDialog] in the admin
/// console, which is a shop erasing one of its own customers. This is the
/// person deleting their own login, platform-wide: it asks for the password
/// again (a session left open on a shared device must not be enough), then
/// signs the device out along with it. A shop's own records of this person —
/// their orders, loyalty, customer profile with that shop — are unaffected;
/// deleting them is a separate request to that shop.
class _DeleteAccountDialog extends ConsumerStatefulWidget {
  const _DeleteAccountDialog();

  @override
  ConsumerState<_DeleteAccountDialog> createState() =>
      _DeleteAccountDialogState();
}

class _DeleteAccountDialogState extends ConsumerState<_DeleteAccountDialog> {
  final _passwordCtrl = TextEditingController();
  bool _loading = false;
  String? _error;

  @override
  void dispose() {
    _passwordCtrl.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    if (_passwordCtrl.text.isEmpty) return;
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      await ref
          .read(storefrontAuthProvider.notifier)
          .deleteAccount(_passwordCtrl.text);
      if (!mounted) return;
      Navigator.pop(context);
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Your account has been deleted.')),
      );
    } catch (e) {
      setState(() {
        _loading = false;
        final status = e is DioException ? e.response?.statusCode : null;
        _error = status == 401
            ? 'Incorrect password.'
            : friendlyError(e, fallback: 'Could not delete the account.');
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return AlertDialog(
      title: const Text('Delete my account?'),
      content: SizedBox(
        width: 360,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            const Text(
                'This permanently deletes your login. It cannot be undone. '
                'Orders and loyalty you have with individual shops are not '
                'affected — ask each shop separately if you want those erased '
                'too.'),
            const SizedBox(height: 16),
            if (_error != null) ...[
              Container(
                padding: const EdgeInsets.all(12),
                decoration: BoxDecoration(
                    color: cs.errorContainer,
                    borderRadius: BorderRadius.circular(8)),
                child:
                    Text(_error!, style: TextStyle(color: cs.onErrorContainer)),
              ),
              const SizedBox(height: 12),
            ],
            TextField(
              controller: _passwordCtrl,
              obscureText: true,
              autofocus: true,
              decoration: const InputDecoration(
                  labelText: 'Confirm your password',
                  prefixIcon: Icon(Icons.lock_outline)),
              onSubmitted: (_) => _loading ? null : _submit(),
            ),
          ],
        ),
      ),
      actions: [
        TextButton(
          onPressed: _loading ? null : () => Navigator.pop(context),
          child: const Text('Cancel'),
        ),
        FilledButton(
          style: FilledButton.styleFrom(backgroundColor: cs.error),
          onPressed: _loading ? null : _submit,
          child: _loading
              ? SizedBox(
                  height: 18,
                  width: 18,
                  child: CircularProgressIndicator(
                      strokeWidth: 2, color: cs.onError))
              : const Text('Delete account'),
        ),
      ],
    );
  }
}

/// Customer sign-in / sign-up dialog (iam self-service).
class StorefrontAuthDialog extends ConsumerStatefulWidget {
  const StorefrontAuthDialog({super.key});

  @override
  ConsumerState<StorefrontAuthDialog> createState() =>
      _StorefrontAuthDialogState();
}

class _StorefrontAuthDialogState extends ConsumerState<StorefrontAuthDialog> {
  final _formKey = GlobalKey<FormState>();
  final _emailCtrl = TextEditingController();
  final _passwordCtrl = TextEditingController();
  final _phoneCtrl = TextEditingController();
  bool _register = false;
  bool _loading = false;
  String? _error;

  @override
  void dispose() {
    _emailCtrl.dispose();
    _passwordCtrl.dispose();
    _phoneCtrl.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    if (!_formKey.currentState!.validate()) return;
    setState(() {
      _loading = true;
      _error = null;
    });
    final notifier = ref.read(storefrontAuthProvider.notifier);
    try {
      if (_register) {
        await notifier.register(
            _emailCtrl.text.trim(), _passwordCtrl.text, _phoneCtrl.text.trim());
      } else {
        await notifier.login(_emailCtrl.text.trim(), _passwordCtrl.text);
      }
      if (!mounted) return;
      Navigator.pop(context);
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text(_register ? 'Account created.' : 'Signed in.')),
      );
      // Signal the shell to show the preferences sheet if not yet asked.
      ref.read(storefrontJustAuthenticatedProvider.notifier).state = true;
    } catch (e) {
      setState(() {
        _loading = false;
        final status = e is DioException ? e.response?.statusCode : null;
        _error = status == 401
            ? 'Incorrect email or password.'
            : status == 409
                ? 'An account with this email already exists.'
                : friendlyError(e,
                    fallback:
                        'Could not ${_register ? 'register' : 'sign in'}.');
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return AlertDialog(
      title: Text(_register ? 'Create account' : 'Sign in'),
      content: SizedBox(
        width: 360,
        child: Form(
          key: _formKey,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              if (_error != null) ...[
                Container(
                  padding: const EdgeInsets.all(12),
                  decoration: BoxDecoration(
                      color: cs.errorContainer,
                      borderRadius: BorderRadius.circular(8)),
                  child: Text(_error!,
                      style: TextStyle(color: cs.onErrorContainer)),
                ),
                const SizedBox(height: 12),
              ],
              TextFormField(
                controller: _emailCtrl,
                keyboardType: TextInputType.emailAddress,
                decoration: const InputDecoration(
                    labelText: 'Email', prefixIcon: Icon(Icons.email_outlined)),
                validator: (v) =>
                    v == null || !v.contains('@') ? 'Valid email required' : null,
              ),
              const SizedBox(height: 12),
              TextFormField(
                controller: _passwordCtrl,
                obscureText: true,
                decoration: const InputDecoration(
                    labelText: 'Password', prefixIcon: Icon(Icons.lock_outline)),
                validator: (v) =>
                    v == null || v.length < 8 ? 'At least 8 characters' : null,
              ),
              if (_register) ...[
                const SizedBox(height: 12),
                TextFormField(
                  controller: _phoneCtrl,
                  keyboardType: TextInputType.phone,
                  decoration: const InputDecoration(
                      labelText: 'Phone number',
                      prefixIcon: Icon(Icons.phone_outlined)),
                  validator: (v) =>
                      v == null || v.trim().isEmpty ? 'Phone number required' : null,
                ),
              ],
              const SizedBox(height: 8),
              Align(
                alignment: Alignment.centerLeft,
                child: TextButton(
                  onPressed: _loading
                      ? null
                      : () => setState(() {
                            _register = !_register;
                            _error = null;
                          }),
                  child: Text(_register
                      ? 'Have an account? Sign in'
                      : 'New here? Create an account'),
                ),
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
              ?  SizedBox(
                  height: 18,
                  width: 18,
                  child: CircularProgressIndicator(
                      strokeWidth: 2, color: Theme.of(context).colorScheme.onPrimary))
              : Text(_register ? 'Create account' : 'Sign in'),
        ),
      ],
    );
  }
}
