import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import '../../core/auth/auth_notifier.dart';
import '../../core/theme.dart';
import '../../shared/widgets/adaptive_nav_shell.dart';
import 'pos_providers.dart';
import 'pos_session_providers.dart';

const _destinations = [
  AdaptiveNavDestination(
    label: 'Sale',
    icon: Icons.shopping_cart_outlined,
    selectedIcon: Icons.shopping_cart,
  ),
  AdaptiveNavDestination(
    label: 'Tender',
    icon: Icons.payments_outlined,
    selectedIcon: Icons.payments,
  ),
  AdaptiveNavDestination(
    label: 'Cash',
    icon: Icons.account_balance_wallet_outlined,
    selectedIcon: Icons.account_balance_wallet,
  ),
];

const _routes = ['/pos/cart', '/pos/tender', '/pos/cash'];

class PosShell extends ConsumerStatefulWidget {
  final String currentLocation;
  final Widget child;

  const PosShell({
    super.key,
    required this.currentLocation,
    required this.child,
  });

  @override
  ConsumerState<PosShell> createState() => _PosShellState();
}

class _PosShellState extends ConsumerState<PosShell> {
  Timer? _heartbeat;

  @override
  void initState() {
    super.initState();
    // Keep the open session off the server's idle sweep while the terminal is up.
    _heartbeat = Timer.periodic(const Duration(minutes: 4), (_) {
      if (ref.read(posSessionProvider) != null) {
        ref.read(posSessionProvider.notifier).touch();
      }
    });
  }

  @override
  void dispose() {
    _heartbeat?.cancel();
    super.dispose();
  }

  int get _selectedIndex => widget.currentLocation.startsWith('/pos/cash')
      ? 2
      : widget.currentLocation.startsWith('/pos/tender')
          ? 1
          : 0;

  @override
  Widget build(BuildContext context) {
    final session = ref.watch(posSessionProvider);

    return AdaptiveNavShell(
      title: 'POS Terminal',
      leadingIcon: Icons.point_of_sale,
      appBarBackgroundColor: AppTheme.posAccent,
      appBarForegroundColor: Colors.white,
      destinations: _destinations,
      selectedIndex: _selectedIndex,
      onDestinationSelected: (i) => context.go(_routes[i]),
      actions: [
        if (session != null)
          TextButton.icon(
            onPressed: () async {
              final ok = await showDialog<bool>(
                context: context,
                builder: (ctx) => AlertDialog(
                  title: const Text('Clock out?'),
                  content: const Text(
                      'This ends your POS session. Any sale in progress is kept.'),
                  actions: [
                    TextButton(
                        onPressed: () => Navigator.pop(ctx, false),
                        child: const Text('Cancel')),
                    FilledButton(
                        onPressed: () => Navigator.pop(ctx, true),
                        child: const Text('Clock out')),
                  ],
                ),
              );
              if (ok == true) {
                await ref.read(posSessionProvider.notifier).clockOut();
              }
            },
            icon: const Icon(Icons.logout, color: Colors.white, size: 18),
            label: const Text('Clock out',
                style: TextStyle(color: Colors.white)),
          ),
        IconButton(
          icon: const Icon(Icons.exit_to_app),
          tooltip: 'Sign out',
          onPressed: () => ref.read(authNotifierProvider.notifier).logout(),
        ),
      ],
      // Gate the whole terminal: no selling until a cashier clocks in.
      child: session == null ? const _ClockInView() : widget.child,
    );
  }
}

/// Clock-in screen — a cashier picks a store and opens a POS session before any
/// sale can be rung up.
class _ClockInView extends ConsumerStatefulWidget {
  const _ClockInView();

  @override
  ConsumerState<_ClockInView> createState() => _ClockInViewState();
}

class _ClockInViewState extends ConsumerState<_ClockInView> {
  String? _storeId;
  bool _busy = false;
  String? _error;

  Future<void> _clockIn() async {
    if (_storeId == null) return;
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      await ref.read(posSessionProvider.notifier).clockIn(_storeId!);
    } catch (e) {
      setState(() => _error = 'Could not clock in: $e');
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final storesAsync = ref.watch(posStoresProvider);

    return Center(
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 420),
        child: Card(
          margin: const EdgeInsets.all(24),
          child: Padding(
            padding: const EdgeInsets.all(28),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                const Icon(Icons.point_of_sale,
                    size: 48, color: AppTheme.posAccent),
                const SizedBox(height: 12),
                Text('Clock in',
                    textAlign: TextAlign.center,
                    style: Theme.of(context).textTheme.headlineSmall),
                const SizedBox(height: 4),
                Text('Open a POS session to start selling.',
                    textAlign: TextAlign.center,
                    style: TextStyle(color: cs.outline)),
                const SizedBox(height: 24),
                storesAsync.when(
                  loading: () => const Center(
                      child: Padding(
                          padding: EdgeInsets.all(8),
                          child: CircularProgressIndicator())),
                  error: (e, _) => Text('Could not load stores: $e',
                      style: TextStyle(color: cs.error)),
                  data: (stores) {
                    if (stores.isEmpty) {
                      return Text('No stores configured.',
                          style: TextStyle(color: cs.error));
                    }
                    _storeId ??= stores.first.id;
                    return DropdownButtonFormField<String>(
                      initialValue: _storeId,
                      decoration: const InputDecoration(
                        labelText: 'Store / terminal',
                        prefixIcon: Icon(Icons.store_outlined),
                      ),
                      items: [
                        for (final s in stores)
                          DropdownMenuItem(value: s.id, child: Text(s.name)),
                      ],
                      onChanged:
                          _busy ? null : (v) => setState(() => _storeId = v),
                    );
                  },
                ),
                if (_error != null) ...[
                  const SizedBox(height: 12),
                  Text(_error!, style: TextStyle(color: cs.error)),
                ],
                const SizedBox(height: 24),
                FilledButton.icon(
                  style: FilledButton.styleFrom(
                    backgroundColor: AppTheme.posAccent,
                    padding: const EdgeInsets.symmetric(vertical: 14),
                  ),
                  onPressed: _busy ? null : _clockIn,
                  icon: _busy
                      ? const SizedBox(
                          height: 18,
                          width: 18,
                          child: CircularProgressIndicator(
                              strokeWidth: 2, color: Colors.white))
                      : const Icon(Icons.login),
                  label: Text(_busy ? 'Opening…' : 'Clock in',
                      style: const TextStyle(fontSize: 16)),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
