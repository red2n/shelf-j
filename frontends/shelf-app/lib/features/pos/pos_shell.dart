import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import '../../core/auth/auth_notifier.dart';
import '../../core/network/api_error.dart';
import '../../core/offline/offline_queue.dart';
import '../../core/theme.dart';
import '../../shared/widgets/adaptive_nav_shell.dart';
import 'pos_providers.dart';
import 'pos_session_providers.dart';

/// [pending] badges the Pending destination so unsynced sales are visible from
/// anywhere in the terminal, not only once the cashier goes looking.
List<AdaptiveNavDestination> _destinations(int pending) => [
      const AdaptiveNavDestination(
        label: 'Sale',
        icon: Icons.shopping_cart_outlined,
        selectedIcon: Icons.shopping_cart,
      ),
      const AdaptiveNavDestination(
        label: 'Tender',
        icon: Icons.payments_outlined,
        selectedIcon: Icons.payments,
      ),
      const AdaptiveNavDestination(
        label: 'Cash',
        icon: Icons.account_balance_wallet_outlined,
        selectedIcon: Icons.account_balance_wallet,
      ),
      AdaptiveNavDestination(
        label: 'Pending',
        icon: Icons.cloud_off_outlined,
        selectedIcon: Icons.cloud_off,
        badgeCount: pending,
      ),
    ];

const _routes = ['/pos/cart', '/pos/tender', '/pos/cash', '/pos/pending'];

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

  int get _selectedIndex => widget.currentLocation.startsWith('/pos/pending')
      ? 3
      : widget.currentLocation.startsWith('/pos/cash')
          ? 2
          : widget.currentLocation.startsWith('/pos/tender')
              ? 1
              : 0;

  @override
  Widget build(BuildContext context) {
    final session = ref.watch(posSessionProvider);
    final pending = ref.watch(offlineQueueCountProvider);

    // Scope the amber channel accent (app bar, primary actions, nav indicator)
    // to the whole POS subtree via the theme system, rather than threading the
    // raw AppTheme.posAccent constant into each widget by hand.
    return Theme(
      data: AppTheme.applyPosAccent(Theme.of(context)),
      child: AdaptiveNavShell(
        title: 'POS Terminal',
        leadingIcon: Icons.point_of_sale,
        // 3 flat destinations — a bottom bar, per Material's compact-width guidance.
        compactStyle: CompactNavStyle.bottomBar,
        destinations: _destinations(pending),
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
                    content: Text(pending == 0
                        ? 'This ends your POS session. Any sale in progress is kept.'
                        : 'This ends your POS session. $pending sale'
                            '${pending == 1 ? '' : 's'} still '
                            "haven't reached the server — they stay on this till "
                            'and are sent when the network is back.'),
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
              // No explicit colour: TextButton.icon already resolves to
              // colorScheme.primary, which reads correctly on the amber app bar.
              icon: const Icon(Icons.logout, size: 18),
              label: const Text('Clock out'),
            ),
          IconButton(
            icon: const Icon(Icons.exit_to_app),
            tooltip: 'Sign out',
            onPressed: () => ref.read(authNotifierProvider.notifier).logout(),
          ),
        ],
        // Gate the whole terminal: no selling until a cashier clocks in.
        child: session == null ? const _ClockInView() : widget.child,
      ),
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
      setState(
          () => _error = friendlyError(e, fallback: 'Could not clock in.'));
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
                Icon(Icons.point_of_sale,
                    size: 48, color: context.channelAccent.color),
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
                  error: (e, _) => Text(
                      friendlyError(e, fallback: 'Could not load stores.'),
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
                    backgroundColor: context.channelAccent.color,
                    foregroundColor: context.channelAccent.onColor,
                    padding: const EdgeInsets.symmetric(vertical: 14),
                  ),
                  onPressed: _busy ? null : _clockIn,
                  icon: _busy
                      ? SizedBox(
                          height: 18,
                          width: 18,
                          child: CircularProgressIndicator(
                              strokeWidth: 2,
                              color: context.channelAccent.onColor))
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
