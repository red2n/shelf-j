import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:dio/dio.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/constants.dart';
import '../../core/network/api_error.dart';
import 'storefront_providers.dart';
import 'storefront_shell.dart' show StorefrontAuthDialog;

/// One marketing channel as the shop currently has it recorded.
class MarketingPreference {
  final String channel;
  final bool granted;
  final String basis;

  const MarketingPreference({
    required this.channel,
    required this.granted,
    required this.basis,
  });

  factory MarketingPreference.fromJson(Map<String, dynamic> json) =>
      MarketingPreference(
        channel: json['channel'] as String? ?? '',
        granted: json['granted'] as bool? ?? false,
        basis: json['basis'] as String? ?? 'NONE',
      );
}

/// The shopper's marketing preferences at the shop they are browsing.
///
/// A channel the shop has never recorded simply has no entry: silence is not
/// consent, so the screen shows it off and sending is refused server-side.
final marketingPreferencesProvider =
    FutureProvider.autoDispose<List<MarketingPreference>?>((ref) async {
  final auth = ref.watch(storefrontAuthProvider);
  if (!auth.isSignedIn) return null;
  final dio = ref.watch(storefrontDioProvider);
  try {
    final resp = await dio.get('/${ApiConstants.customer}/customers/me/marketing');
    final data = (resp.data['data'] as List?) ?? const [];
    return data
        .map((e) => MarketingPreference.fromJson(e as Map<String, dynamic>))
        .toList();
  } on DioException catch (e) {
    // 404 means this shop holds no record of them yet — which is not an error,
    // it is a shopper who has never bought here and consented to nothing.
    if (e.response?.statusCode == 404) return const <MarketingPreference>[];
    rethrow;
  }
});

/// Privacy and marketing: what the shop may send, and everything it holds.
///
/// Two rights on one screen because they are two halves of the same question.
/// The switches are PECR reg.22 consent — recorded with the wording shown here,
/// because UK GDPR art.7(1) makes the shop prove what was agreed to. The
/// download is art.20 portability, and it deliberately fails loudly rather than
/// handing over a partial file: half of somebody's data is a wrong answer, not
/// a small one.
class StorefrontPrivacyScreen extends ConsumerStatefulWidget {
  const StorefrontPrivacyScreen({super.key});

  @override
  ConsumerState<StorefrontPrivacyScreen> createState() =>
      _StorefrontPrivacyScreenState();
}

class _StorefrontPrivacyScreenState
    extends ConsumerState<StorefrontPrivacyScreen> {
  static const _notice =
      'Email me about offers, new lines and events at this shop. '
      'I can stop this at any time, from here or from any message.';

  static const _channels = <String, ({String label, String detail})>{
    'EMAIL': (label: 'Email', detail: 'Offers and news by email'),
    'SMS': (label: 'Text message', detail: 'Short updates by SMS'),
    'PHONE': (label: 'Phone', detail: 'Marketing calls'),
    'POST': (label: 'Post', detail: 'Leaflets and catalogues'),
  };

  bool _saving = false;
  String? _exporting;

  Future<void> _setChannel(String channel, bool granted) async {
    setState(() => _saving = true);
    final dio = ref.read(storefrontDioProvider);
    try {
      await dio.put(
        '/${ApiConstants.customer}/customers/me/marketing',
        data: {
          'channels': [
            {'channel': channel, 'granted': granted}
          ],
          'notice': granted ? _notice : null,
        },
      );
      ref.invalidate(marketingPreferencesProvider);
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(granted
                ? 'Saved. We will only send what you have agreed to.'
                : 'Saved. We will stop sending you these.'),
          ),
        );
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
              content: Text(
                  friendlyError(e, fallback: 'Could not save that just now.'))),
        );
      }
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  Future<void> _download() async {
    setState(() => _exporting = 'working');
    final dio = ref.read(storefrontDioProvider);
    try {
      final resp =
          await dio.get('/${ApiConstants.customer}/customers/me/export');
      final pretty =
          const JsonEncoder.withIndent('  ').convert(resp.data['data']);
      await Clipboard.setData(ClipboardData(text: pretty));
      if (mounted) {
        setState(() => _exporting = null);
        showDialog<void>(
          context: context,
          builder: (_) => _ExportDialog(json: pretty),
        );
      }
    } catch (e) {
      if (mounted) {
        setState(() => _exporting = null);
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(friendlyError(e,
                fallback:
                    'Your data could not be assembled just now. Nothing partial '
                    'has been sent — please try again shortly.')),
          ),
        );
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final auth = ref.watch(storefrontAuthProvider);
    if (!auth.isSignedIn) {
      return _SignInFirst();
    }
    final async = ref.watch(marketingPreferencesProvider);
    final theme = Theme.of(context);

    return RefreshIndicator(
      onRefresh: () async => ref.invalidate(marketingPreferencesProvider),
      child: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          Text('Marketing', style: theme.textTheme.titleLarge),
          const SizedBox(height: 4),
          Text(
            'You decide what this shop may send you. Nothing is on unless you '
            'turn it on, and you can turn it off again at any time — here, or '
            'from the link in any message we send.',
            style: theme.textTheme.bodyMedium
                ?.copyWith(color: theme.colorScheme.onSurfaceVariant),
          ),
          const SizedBox(height: 12),
          async.when(
            loading: () => const Padding(
              padding: EdgeInsets.symmetric(vertical: 24),
              child: Center(child: CircularProgressIndicator()),
            ),
            error: (e, _) => _InlineError(
              message: friendlyError(e,
                  fallback: 'Could not load your preferences.'),
              onRetry: () => ref.invalidate(marketingPreferencesProvider),
            ),
            data: (prefs) {
              final byChannel = {
                for (final p in prefs ?? const <MarketingPreference>[])
                  p.channel: p,
              };
              return Card(
                child: Column(
                  children: [
                    for (final entry in _channels.entries)
                      SwitchListTile(
                        value: byChannel[entry.key]?.granted ?? false,
                        onChanged: _saving
                            ? null
                            : (v) => _setChannel(entry.key, v),
                        title: Text(entry.value.label),
                        subtitle: Text(entry.value.detail),
                      ),
                  ],
                ),
              );
            },
          ),
          const SizedBox(height: 8),
          Text(
            _notice,
            style: theme.textTheme.bodySmall
                ?.copyWith(color: theme.colorScheme.onSurfaceVariant),
          ),
          const SizedBox(height: 28),
          Text('Your data', style: theme.textTheme.titleLarge),
          const SizedBox(height: 4),
          Text(
            'You can have a copy of everything this shop holds about you: your '
            'details, your addresses, your loyalty and store credit with their '
            'full history, what you have agreed to be sent, and every order you '
            'have placed here.',
            style: theme.textTheme.bodyMedium
                ?.copyWith(color: theme.colorScheme.onSurfaceVariant),
          ),
          const SizedBox(height: 12),
          FilledButton.icon(
            onPressed: _exporting == null ? _download : null,
            icon: _exporting == null
                ? const Icon(Icons.download_outlined)
                : const SizedBox(
                    width: 16,
                    height: 16,
                    child: CircularProgressIndicator(strokeWidth: 2)),
            label: Text(_exporting == null
                ? 'Download my data'
                : 'Gathering your data…'),
          ),
        ],
      ),
    );
  }
}

/// The assembled export. Copied to the clipboard as soon as it arrives, because
/// a browser download from inside the app frame is not always permitted — and
/// the person asked for their data, not for a file-save dialog to work.
class _ExportDialog extends StatelessWidget {
  const _ExportDialog({required this.json});

  final String json;

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: const Text('Your data'),
      content: SizedBox(
        width: 560,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text(
                'Copied to your clipboard, and shown below. It is JSON, so any '
                'other service can read it.'),
            const SizedBox(height: 12),
            Flexible(
              child: SingleChildScrollView(
                child: SelectableText(
                  json,
                  style: const TextStyle(fontFamily: 'monospace', fontSize: 12),
                ),
              ),
            ),
          ],
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Clipboard.setData(ClipboardData(text: json)),
          child: const Text('Copy again'),
        ),
        FilledButton(
          onPressed: () => Navigator.of(context).pop(),
          child: const Text('Close'),
        ),
      ],
    );
  }
}

class _SignInFirst extends StatelessWidget {
  @override
  Widget build(BuildContext context) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Icon(Icons.privacy_tip_outlined, size: 48),
            const SizedBox(height: 12),
            const Text(
              'Sign in to see what this shop may send you, and to ask for a '
              'copy of your data.',
              textAlign: TextAlign.center,
            ),
            const SizedBox(height: 16),
            FilledButton(
              onPressed: () => showDialog<void>(
                  context: context,
                  builder: (_) => const StorefrontAuthDialog()),
              child: const Text('Sign in'),
            ),
          ],
        ),
      ),
    );
  }
}

class _InlineError extends StatelessWidget {
  const _InlineError({required this.message, required this.onRetry});

  final String message;
  final VoidCallback onRetry;

  @override
  Widget build(BuildContext context) {
    return Card(
      child: ListTile(
        leading: const Icon(Icons.error_outline),
        title: Text(message),
        trailing: TextButton(onPressed: onRetry, child: const Text('Retry')),
      ),
    );
  }
}
