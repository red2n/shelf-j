import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/constants.dart';
import '../../core/format.dart';
import '../../core/network/api_client.dart';
import '../../core/network/api_error.dart';
import '../../core/spacing.dart';

// ---------------------------------------------------------------------------
// A child's guardian (13.12, DPDP Act s.9, Rules r.10): a customer under
// eighteen is not marketed to, profiled or measured without a parent's or
// guardian's verifiable consent. Staff record it here, with how the parent was
// verified, and withdraw it; the child's tracking consents fall with it.
// ---------------------------------------------------------------------------

String _privacyOf(String customerId, [String suffix = '']) =>
    '/${ApiConstants.customer}/customers/$customerId/privacy$suffix';

class GuardianConsent {
  final String guardianName;
  final String verification;
  final String? reference;
  final String givenAt;
  final bool standing;
  const GuardianConsent({
    required this.guardianName,
    required this.verification,
    this.reference,
    required this.givenAt,
    required this.standing,
  });
  factory GuardianConsent.fromJson(Map<String, dynamic> j) => GuardianConsent(
        guardianName: j['guardianName'] as String? ?? '',
        verification: j['verification'] as String? ?? '',
        reference: j['reference'] as String?,
        givenAt: j['givenAt'] as String? ?? '',
        standing: j['standing'] == true,
      );
}

/// A customer's consents as staff see them: purposes, whether they are a
/// child, and the guardian standing for them.
class CustomerPrivacy {
  final bool child;
  final bool canTrack;
  final GuardianConsent? guardian;
  final Map<String, bool> granted;
  const CustomerPrivacy({
    required this.child,
    required this.canTrack,
    this.guardian,
    required this.granted,
  });
  factory CustomerPrivacy.fromJson(Map<String, dynamic> j) => CustomerPrivacy(
        child: j['child'] == true,
        canTrack: j['canTrack'] == true,
        guardian: j['guardian'] == null
            ? null
            : GuardianConsent.fromJson(j['guardian'] as Map<String, dynamic>),
        granted: {
          for (final c in (j['consents'] as List?) ?? const [])
            (c as Map<String, dynamic>)['purpose'] as String? ?? '':
                c['granted'] == true
        },
      );
}

const guardianVerifications = <String, String>{
  'DETAILS_HELD': 'Details the shop already holds',
  'DOCUMENT_SEEN': 'A document seen over the counter',
  'DIGITAL_LOCKER': 'A Digital Locker token',
};

final customerPrivacyProvider = FutureProvider.autoDispose
    .family<CustomerPrivacy, String>((ref, customerId) async {
  final resp =
      await ref.read(apiClientProvider).dio.get(_privacyOf(customerId));
  return CustomerPrivacy.fromJson(resp.data['data'] as Map<String, dynamic>);
});

class GuardianConsentSection extends ConsumerStatefulWidget {
  const GuardianConsentSection({super.key, required this.customerId});
  final String customerId;

  @override
  ConsumerState<GuardianConsentSection> createState() =>
      _GuardianConsentSectionState();
}

class _GuardianConsentSectionState extends ConsumerState<GuardianConsentSection> {
  bool _busy = false;

  Future<void> _withdraw() async {
    setState(() => _busy = true);
    try {
      await ref
          .read(apiClientProvider)
          .dio
          .delete(_privacyOf(widget.customerId, '/guardian'));
      ref.invalidate(customerPrivacyProvider(widget.customerId));
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(
            content: Text(friendlyError(e, fallback: 'Could not withdraw.'))));
      }
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final async = ref.watch(customerPrivacyProvider(widget.customerId));
    return async.when(
      loading: () => const SizedBox.shrink(),
      error: (e, _) => Text(friendlyError(e, fallback: 'Privacy not loaded.'),
          style: theme.textTheme.bodySmall),
      data: (p) {
        final g = p.guardian;
        return Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('Privacy', style: theme.textTheme.titleSmall),
            const SizedBox(height: AppSpacing.xs),
            Text(
              [
                for (final e in p.granted.entries)
                  '${e.key.toLowerCase()} ${e.value ? 'on' : 'off'}'
              ].join(' · '),
              style: theme.textTheme.bodySmall,
            ),
            if (p.child) ...[
              const SizedBox(height: AppSpacing.xs),
              Text(
                g != null && g.standing
                    ? 'Under 18. ${g.guardianName} consented on '
                        '${AppFormat.dateTime(g.givenAt)} '
                        '(${guardianVerifications[g.verification] ?? g.verification}).'
                    : 'Under 18. Offers, personalisation and analytics stay off '
                        'until a parent or guardian consents (DPDP Act s.9).',
                key: const Key('guardian-status'),
                style: theme.textTheme.bodySmall,
              ),
              const SizedBox(height: AppSpacing.xs),
              g != null && g.standing
                  ? OutlinedButton(
                      key: const Key('guardian-withdraw'),
                      onPressed: _busy ? null : _withdraw,
                      child: const Text("Withdraw the guardian's consent"),
                    )
                  : FilledButton.tonal(
                      key: const Key('guardian-record'),
                      onPressed: _busy
                          ? null
                          : () async {
                              await showDialog<void>(
                                context: context,
                                builder: (_) => GuardianConsentDialog(
                                    customerId: widget.customerId),
                              );
                              ref.invalidate(
                                  customerPrivacyProvider(widget.customerId));
                            },
                      child: const Text("Record a guardian's consent"),
                    ),
            ],
          ],
        );
      },
    );
  }
}

class GuardianConsentDialog extends ConsumerStatefulWidget {
  const GuardianConsentDialog({super.key, required this.customerId});
  final String customerId;

  @override
  ConsumerState<GuardianConsentDialog> createState() =>
      _GuardianConsentDialogState();
}

class _GuardianConsentDialogState extends ConsumerState<GuardianConsentDialog> {
  final _name = TextEditingController();
  final _reference = TextEditingController();
  String _verification = 'DOCUMENT_SEEN';
  bool _busy = false;
  String? _error;

  @override
  void dispose() {
    _name.dispose();
    _reference.dispose();
    super.dispose();
  }

  Future<void> _record() async {
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      await ref.read(apiClientProvider).dio.post(
        _privacyOf(widget.customerId, '/guardian'),
        data: {
          'guardianName': _name.text.trim(),
          'verification': _verification,
          if (_reference.text.trim().isNotEmpty)
            'reference': _reference.text.trim(),
        },
      );
      if (mounted) Navigator.of(context).pop();
    } catch (e) {
      setState(() => _error = friendlyError(e, fallback: 'Could not record.'));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: const Text("A parent's or guardian's consent"),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          TextField(
            key: const Key('guardian-name'),
            controller: _name,
            maxLength: 120,
            decoration: const InputDecoration(
                labelText: "Parent's or guardian's name", counterText: ''),
          ),
          DropdownButtonFormField<String>(
            key: const Key('guardian-verification'),
            isExpanded: true,
            initialValue: _verification,
            decoration:
                const InputDecoration(labelText: 'How they were verified'),
            items: [
              for (final e in guardianVerifications.entries)
                DropdownMenuItem(value: e.key, child: Text(e.value)),
            ],
            onChanged: (v) => setState(() => _verification = v ?? 'DOCUMENT_SEEN'),
          ),
          TextField(
            key: const Key('guardian-reference'),
            controller: _reference,
            maxLength: 200,
            decoration: const InputDecoration(
              labelText: 'What was seen (optional)',
              helperText: 'Never a document number.',
              counterText: '',
            ),
          ),
          if (_error != null)
            Text(_error!,
                style: TextStyle(color: Theme.of(context).colorScheme.error)),
        ],
      ),
      actions: [
        TextButton(
            onPressed: _busy ? null : () => Navigator.of(context).pop(),
            child: const Text('Cancel')),
        FilledButton(
            key: const Key('guardian-save'),
            onPressed: _busy ? null : _record,
            child: const Text('Record')),
      ],
    );
  }
}
