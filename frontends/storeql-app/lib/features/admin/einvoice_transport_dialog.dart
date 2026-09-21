import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../core/network/api_client.dart';
import '../../core/network/api_error.dart';
import 'sales_invoice_providers.dart';

/// Where the business's e-invoices leave: a network, and the provider behind
/// it. SIMULATED stands in for every network on a stack with no provider
/// contract, and nothing leaves the platform; a real provider can be chosen
/// once the deployment holds its credentials. The network the business's
/// country asks for today is marked.
class TransportSettingsDialog extends ConsumerStatefulWidget {
  final TransportSettings current;
  const TransportSettingsDialog({super.key, required this.current});

  @override
  ConsumerState<TransportSettingsDialog> createState() =>
      _TransportSettingsDialogState();
}

class _TransportSettingsDialogState
    extends ConsumerState<TransportSettingsDialog> {
  late String _network = widget.current.network;
  late String? _provider = widget.current.provider;
  late final _account =
      TextEditingController(text: widget.current.providerAccount ?? '');
  final _secret = TextEditingController();
  bool _busy = false;
  String? _error;

  @override
  void dispose() {
    _account.dispose();
    _secret.dispose();
    super.dispose();
  }

  List<String> get _deployed => widget.current.providers[_network] ?? const [];
  bool get _needsSecret => widget.current.needsSecret(_network, _provider);
  List<String> get _available => widget.current.available[_network] ?? const [];

  Future<void> _save() async {
    if (_busy) return;
    if (_network != 'NONE' && (_provider == null || _provider!.isEmpty)) {
      setState(
          () => _error = 'Choose a provider for ${networkLabel(_network)}.');
      return;
    }
    if (_needsSecret && _secret.text.isEmpty && !widget.current.hasSecret) {
      setState(() => _error = "This provider signs in with the business's own "
          'credential: enter it.');
      return;
    }
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      await saveTransportSettings(
        ref.read(apiClientProvider).dio,
        network: _network,
        provider: _network == 'NONE' ? null : _provider,
        providerAccount: _network == 'NONE' ? null : _account.text,
        providerSecret:
            _network == 'NONE' || !_needsSecret ? null : _secret.text,
      );
      ref.invalidate(transportSettingsProvider);
      if (mounted) Navigator.of(context).pop();
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _busy = false;
        _error = friendlyError(e, fallback: 'Could not save the network.');
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final c = widget.current;
    return AlertDialog(
      title: const Text('Where e-invoices leave'),
      content: SizedBox(
        width: 480,
        child: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              DropdownButtonFormField<String>(
                key: const Key('einvoice-transport-network'),
                isExpanded: true,
                initialValue: _network,
                decoration: const InputDecoration(labelText: 'Network'),
                items: [
                  for (final n in ['NONE', ...c.networks])
                    DropdownMenuItem(
                      value: n,
                      child: Text(
                        n == c.suggestedNetwork
                            ? '${networkLabel(n)} — what your country asks for'
                            : networkLabel(n),
                        overflow: TextOverflow.ellipsis,
                      ),
                    ),
                ],
                onChanged: _busy
                    ? null
                    : (v) => setState(() {
                          _network = v ?? 'NONE';
                          final options = _available;
                          _provider = options.contains(_provider)
                              ? _provider
                              : (options.isNotEmpty ? options.first : null);
                        }),
              ),
              if (_network != 'NONE') ...[
                const SizedBox(height: 12),
                DropdownButtonFormField<String>(
                  key: const Key('einvoice-transport-provider'),
                  isExpanded: true,
                  initialValue:
                      _available.contains(_provider) ? _provider : null,
                  decoration: const InputDecoration(labelText: 'Provider'),
                  items: [
                    for (final p in _available)
                      DropdownMenuItem(
                          value: p, child: Text(_providerLabel(p))),
                  ],
                  onChanged:
                      _busy ? null : (v) => setState(() => _provider = v),
                ),
                for (final p in _deployed.where((p) => !_available.contains(p)))
                  Padding(
                    padding: const EdgeInsets.only(top: 6),
                    child: Text(
                      '$p is deployed but has no credentials on this '
                      'deployment, so it cannot be chosen yet.',
                      style: TextStyle(color: cs.outline, fontSize: 12),
                    ),
                  ),
                if (_provider == 'SIMULATED')
                  Padding(
                    padding: const EdgeInsets.only(top: 6),
                    child: Text(
                      'Simulated: the platform stands in for the network and '
                      'nothing leaves it. Documents are marked delivered so the '
                      'flow can be tried; a contract with a certified provider '
                      'is needed to reach a real receiver.',
                      style: TextStyle(color: cs.outline, fontSize: 12),
                    ),
                  ),
                const SizedBox(height: 12),
                TextField(
                  key: const Key('einvoice-transport-account'),
                  controller: _account,
                  enabled: !_busy,
                  maxLength: 120,
                  decoration: const InputDecoration(
                    labelText: 'Account at the provider',
                    helperText:
                        'How the provider knows this business: a legal-entity '
                        'id, a NIP, a portal user. Optional.',
                    helperMaxLines: 2,
                    counterText: '',
                  ),
                ),
                if (_needsSecret) ...[
                  const SizedBox(height: 4),
                  TextField(
                    key: const Key('einvoice-transport-secret'),
                    controller: _secret,
                    enabled: !_busy,
                    obscureText: true,
                    maxLength: 200,
                    decoration: InputDecoration(
                      labelText: switch (_provider) {
                        'NIC' => 'Portal password',
                        'KSEF' => 'KSeF token',
                        _ => 'Credential at the provider',
                      },
                      helperText: c.hasSecret
                          ? 'One is kept, sealed. Leave blank to keep it.'
                          : 'Kept sealed on the server and never shown again.',
                      helperMaxLines: 2,
                      counterText: '',
                    ),
                  ),
                ],
                if (_network == 'PEPPOL' && c.senderAddress == null)
                  Padding(
                    padding: const EdgeInsets.only(top: 8),
                    child: Text(
                      'Peppol needs the business\'s own electronic address: '
                      'set it under "how e-invoices name the business" first.',
                      style: TextStyle(color: cs.error, fontSize: 12),
                    ),
                  ),
              ],
              if (_error != null) ...[
                const SizedBox(height: 12),
                Text(
                  _error!,
                  key: const Key('einvoice-transport-error'),
                  style: TextStyle(color: cs.error),
                ),
              ],
            ],
          ),
        ),
      ),
      actions: [
        TextButton(
          onPressed: _busy ? null : () => Navigator.of(context).pop(),
          child: const Text('Cancel'),
        ),
        FilledButton(
          key: const Key('einvoice-transport-save'),
          onPressed: _busy ? null : _save,
          child: const Text('Save'),
        ),
      ],
    );
  }

  static String _providerLabel(String p) => switch (p) {
        'SIMULATED' => 'Simulated — nothing leaves the platform',
        'ACCESS_POINT' => 'Peppol access point',
        'PDP' => 'Approved platform',
        'NIC' => 'Invoice Registration Portal (NIC)',
        'KSEF' => 'KSeF (Ministry of Finance)',
        _ => p,
      };
}


/// What stands between this business and its first e-invoice, asked now.
///
/// The network is tried with the credentials actually held, and nothing is
/// sent. Three answers are kept apart on purpose: ready, a refusal someone has
/// to act on, and a network that is simply down — "it did not work" is what a
/// shop already knows.
class TransportReadinessDialog extends ConsumerStatefulWidget {
  const TransportReadinessDialog({super.key});

  @override
  ConsumerState<TransportReadinessDialog> createState() =>
      _TransportReadinessDialogState();
}

class _TransportReadinessDialogState
    extends ConsumerState<TransportReadinessDialog> {
  TransportReadiness? _answer;
  String? _error;
  bool _busy = true;

  @override
  void initState() {
    super.initState();
    _check();
  }

  Future<void> _check() async {
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final r =
          await fetchTransportReadiness(ref.read(apiClientProvider).dio);
      if (!mounted) return;
      setState(() {
        _answer = r;
        _busy = false;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _busy = false;
        _error = friendlyError(e, fallback: 'Could not check.');
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final r = _answer;
    return AlertDialog(
      title: const Text('Can an e-invoice go?'),
      content: SizedBox(
        width: 480,
        child: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              if (_busy)
                const Padding(
                  padding: EdgeInsets.symmetric(vertical: 24),
                  child: Center(child: CircularProgressIndicator()),
                ),
              if (_error != null)
                Text(
                  _error!,
                  key: const Key('einvoice-readiness-error'),
                  style: TextStyle(color: cs.error),
                ),
              if (r != null) ...[
                Text(
                  r.ready
                      ? 'Yes — everything holds and '
                          '${networkLabel(r.network)} answered.'
                      : 'Not yet.',
                  key: const Key('einvoice-readiness-verdict'),
                  style: TextStyle(
                    color: r.ready ? cs.primary : cs.error,
                    fontWeight: FontWeight.w600,
                  ),
                ),
                const SizedBox(height: 8),
                for (final c in r.checks)
                  ListTile(
                    key: Key('einvoice-readiness-${c.code}'),
                    dense: true,
                    contentPadding: EdgeInsets.zero,
                    leading: Icon(
                      c.satisfied ? Icons.check_circle_outline : Icons.cancel_outlined,
                      color: c.satisfied ? cs.primary : cs.error,
                      size: 20,
                    ),
                    title: Text(c.title),
                    subtitle: Text(c.detail),
                    isThreeLine: c.detail.length > 60,
                  ),
                if (r.networkState != null) ...[
                  const Divider(),
                  ListTile(
                    key: const Key('einvoice-readiness-network'),
                    dense: true,
                    contentPadding: EdgeInsets.zero,
                    leading: Icon(
                      switch (r.networkState) {
                        'READY' => Icons.cloud_done_outlined,
                        'UNREACHABLE' => Icons.cloud_off_outlined,
                        _ => Icons.block_outlined,
                      },
                      color: r.networkState == 'READY' ? cs.primary : cs.error,
                      size: 20,
                    ),
                    title: Text(switch (r.networkState) {
                      'READY' => 'The network answered',
                      'UNREACHABLE' =>
                        'The network could not be reached — try again later',
                      _ => 'The network would not have us — someone must act',
                    }),
                    subtitle: Text(r.networkDetail ?? ''),
                    isThreeLine: (r.networkDetail ?? '').length > 60,
                  ),
                ],
              ],
            ],
          ),
        ),
      ),
      actions: [
        TextButton(
          key: const Key('einvoice-readiness-again'),
          onPressed: _busy ? null : _check,
          child: const Text('Check again'),
        ),
        FilledButton(
          onPressed: () => Navigator.of(context).pop(),
          child: const Text('Close'),
        ),
      ],
    );
  }
}

/// The line on the E-invoices tab that says where invoices leave, with the
/// edit for management.
class TransportSettingsTile extends ConsumerWidget {
  final bool canEdit;
  const TransportSettingsTile({super.key, required this.canEdit});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final cs = Theme.of(context).colorScheme;
    final async = ref.watch(transportSettingsProvider);
    final s = async.value;
    return ListTile(
      leading: Icon(Icons.send_outlined, color: cs.primary),
      title: Text(
        s == null
            ? 'Where invoices leave…'
            : s.sending
                ? 'Invoices leave over ${networkLabel(s.network)}'
                : 'Invoices are issued and downloaded, not sent',
        key: const Key('einvoice-transport-title'),
      ),
      subtitle: Text(
        s == null
            ? (async.hasError
                ? friendlyError(async.error!, fallback: 'Could not load.')
                : 'Loading…')
            : s.sending
                ? [
                    s.provider == 'SIMULATED'
                        ? 'simulated: nothing leaves the platform'
                        : 'provider ${s.provider}',
                    if ((s.providerAccount ?? '').isNotEmpty)
                      'account ${s.providerAccount}',
                  ].join(' · ')
                : s.suggestedNetwork == null
                    ? 'No mandate reaches this business today.'
                    : 'Your country asks for '
                        '${networkLabel(s.suggestedNetwork!)}.',
      ),
      trailing: canEdit && s != null
          ? Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                IconButton(
                  key: const Key('einvoice-readiness-check'),
                  tooltip: 'Can an e-invoice go?',
                  icon: const Icon(Icons.network_check_outlined),
                  onPressed: () => showDialog<void>(
                    context: context,
                    builder: (_) => const TransportReadinessDialog(),
                  ),
                ),
                IconButton(
                  key: const Key('einvoice-transport-edit'),
                  tooltip: 'Choose where invoices leave',
                  icon: const Icon(Icons.edit_outlined),
                  onPressed: () => showDialog<void>(
                    context: context,
                    builder: (_) => TransportSettingsDialog(current: s),
                  ),
                ),
              ],
            )
          : null,
    );
  }
}
