import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/legacy.dart';

import '../../core/constants.dart';
import '../../core/network/api_error.dart';
import 'storefront_providers.dart';

// ---------------------------------------------------------------------------
// A shopper's privacy under India's DPDP Act (13.12), and good sense anywhere:
// the notice in their language, consent by purpose withdrawn in one step, who
// takes their grievances, and the requests they make for their rights.
// ---------------------------------------------------------------------------

String _customer(String path) => '/${ApiConstants.customer}$path';

class PrivacyLanguage {
  final String code;
  final String name;
  final bool published;
  const PrivacyLanguage(this.code, this.name, this.published);
  factory PrivacyLanguage.fromJson(Map<String, dynamic> j) => PrivacyLanguage(
        j['code'] as String? ?? '',
        j['name'] as String? ?? '',
        j['published'] == true,
      );
}

class PrivacyPurpose {
  final String code;
  final String text;
  final bool tracking;
  const PrivacyPurpose(this.code, this.text, this.tracking);
  factory PrivacyPurpose.fromJson(Map<String, dynamic> j) => PrivacyPurpose(
        j['code'] as String? ?? '',
        j['text'] as String? ?? '',
        j['tracking'] == true,
      );
}

class PrivacyNoticeText {
  final String language;
  final String languageName;
  final int version;
  final String title;
  final String body;
  const PrivacyNoticeText({
    required this.language,
    required this.languageName,
    required this.version,
    required this.title,
    required this.body,
  });
  factory PrivacyNoticeText.fromJson(Map<String, dynamic> j) =>
      PrivacyNoticeText(
        language: j['language'] as String? ?? 'en',
        languageName: j['languageName'] as String? ?? '',
        version: (j['version'] as num?)?.toInt() ?? 0,
        title: j['title'] as String? ?? '',
        body: j['body'] as String? ?? '',
      );
}

class GrievanceContact {
  final String? name;
  final String? email;
  final String? phone;
  final String? address;
  final int responseDays;
  final bool hasContact;
  const GrievanceContact({
    this.name,
    this.email,
    this.phone,
    this.address,
    required this.responseDays,
    required this.hasContact,
  });
  factory GrievanceContact.fromJson(Map<String, dynamic> j) =>
      GrievanceContact(
        name: j['grievanceName'] as String?,
        email: j['grievanceEmail'] as String?,
        phone: j['grievancePhone'] as String?,
        address: j['grievanceAddress'] as String?,
        responseDays: (j['responseDays'] as num?)?.toInt() ?? 30,
        hasContact: j['hasGrievanceContact'] == true,
      );
}

/// The notice as served: in the language asked for, else English, else none.
class PrivacyNoticeView {
  final String requested;
  final String? served;
  final PrivacyNoticeText? notice;
  final List<PrivacyLanguage> languages;
  final List<PrivacyPurpose> purposes;
  final GrievanceContact contact;
  final bool dpdp;
  final String? dpdpFrom;
  const PrivacyNoticeView({
    required this.requested,
    this.served,
    this.notice,
    required this.languages,
    required this.purposes,
    required this.contact,
    required this.dpdp,
    this.dpdpFrom,
  });
  factory PrivacyNoticeView.fromJson(Map<String, dynamic> j) =>
      PrivacyNoticeView(
        requested: j['requested'] as String? ?? 'en',
        served: j['served'] as String?,
        notice: j['notice'] == null
            ? null
            : PrivacyNoticeText.fromJson(j['notice'] as Map<String, dynamic>),
        languages: [
          for (final l in (j['languages'] as List?) ?? const [])
            PrivacyLanguage.fromJson(l as Map<String, dynamic>)
        ],
        purposes: [
          for (final p in (j['purposes'] as List?) ?? const [])
            PrivacyPurpose.fromJson(p as Map<String, dynamic>)
        ],
        contact: GrievanceContact.fromJson(
            (j['settings'] as Map<String, dynamic>?) ?? const {}),
        dpdp: j['dpdp'] == true,
        dpdpFrom: j['dpdpFrom'] as String?,
      );
}

class PurposeConsent {
  final String purpose;
  final String text;
  final bool tracking;
  final bool granted;
  const PurposeConsent({
    required this.purpose,
    required this.text,
    required this.tracking,
    required this.granted,
  });
  factory PurposeConsent.fromJson(Map<String, dynamic> j) => PurposeConsent(
        purpose: j['purpose'] as String? ?? '',
        text: j['text'] as String? ?? '',
        tracking: j['tracking'] == true,
        granted: j['granted'] == true,
      );
}

/// What the shopper has agreed to, and what bears on it.
class MyPrivacy {
  final List<PurposeConsent> consents;
  final bool child;
  final bool canTrack;
  final String? guardianName;
  const MyPrivacy({
    required this.consents,
    required this.child,
    required this.canTrack,
    this.guardianName,
  });
  factory MyPrivacy.fromJson(Map<String, dynamic> j) => MyPrivacy(
        consents: [
          for (final c in (j['consents'] as List?) ?? const [])
            PurposeConsent.fromJson(c as Map<String, dynamic>)
        ],
        child: j['child'] == true,
        canTrack: j['canTrack'] == true,
        guardianName:
            (j['guardian'] as Map<String, dynamic>?)?['guardianName'] as String?,
      );
}

class PrivacyRequest {
  final String id;
  final String kind;
  final String? detail;
  final String dueOn;
  final String status;
  final bool overdue;
  final String? resolution;
  const PrivacyRequest({
    required this.id,
    required this.kind,
    this.detail,
    required this.dueOn,
    required this.status,
    required this.overdue,
    this.resolution,
  });
  factory PrivacyRequest.fromJson(Map<String, dynamic> j) => PrivacyRequest(
        id: j['id'] as String? ?? '',
        kind: j['kind'] as String? ?? '',
        detail: j['detail'] as String?,
        dueOn: j['dueOn'] as String? ?? '',
        status: j['status'] as String? ?? 'OPEN',
        overdue: j['overdue'] == true,
        resolution: j['resolution'] as String?,
      );
}

/// The kinds of request, in the words a person asks with.
const privacyRequestKinds = <String, String>{
  'ACCESS': 'A copy of what you hold about me',
  'CORRECTION': 'Correct my details',
  'ERASURE': 'Erase my data',
  'NOMINATION': 'Nominate someone to act for me',
  'GRIEVANCE': 'Raise a grievance',
};

String privacyRequestLabel(String kind) =>
    privacyRequestKinds[kind] ?? kind.toLowerCase();

/// The language the shopper reads the notice in.
final privacyLanguageProvider = StateProvider<String>((_) => 'en');

/// The notice, public: read before signing up.
final privacyNoticeProvider =
    FutureProvider.autoDispose<PrivacyNoticeView>((ref) async {
  final language = ref.watch(privacyLanguageProvider);
  final resp = await ref.watch(storefrontDioProvider).get(
        _customer('/customers/privacy/notice'),
        queryParameters: {'language': language},
      );
  return PrivacyNoticeView.fromJson(resp.data['data'] as Map<String, dynamic>);
});

/// The signed-in shopper's consents; null when the shop holds no record yet.
final myPrivacyProvider = FutureProvider.autoDispose<MyPrivacy?>((ref) async {
  if (!ref.watch(storefrontAuthProvider).isSignedIn) return null;
  final dio = ref.watch(storefrontDioProvider);
  try {
    final resp = await dio.get(_customer('/customers/me/privacy'));
    return MyPrivacy.fromJson(resp.data['data'] as Map<String, dynamic>);
  } on DioException catch (e) {
    if (e.response?.statusCode == 404) return null;
    rethrow;
  }
});

final myPrivacyRequestsProvider =
    FutureProvider.autoDispose<List<PrivacyRequest>>((ref) async {
  if (!ref.watch(storefrontAuthProvider).isSignedIn) return const [];
  final resp = await ref
      .watch(storefrontDioProvider)
      .get(_customer('/customers/me/privacy/requests'));
  return [
    for (final r in (resp.data['data'] as List?) ?? const [])
      PrivacyRequest.fromJson(r as Map<String, dynamic>)
  ];
});

/// The notice in the shopper's language, the purposes, and who to write to.
class PrivacyNoticeSection extends ConsumerWidget {
  const PrivacyNoticeSection({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final theme = Theme.of(context);
    final muted = theme.textTheme.bodyMedium
        ?.copyWith(color: theme.colorScheme.onSurfaceVariant);
    final async = ref.watch(privacyNoticeProvider);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text('Your privacy notice', style: theme.textTheme.titleLarge),
        const SizedBox(height: 4),
        Text(
          'What this shop collects, why, and how to exercise your rights — '
          'in the language you choose.',
          style: muted,
        ),
        const SizedBox(height: 12),
        async.when(
          loading: () => const Padding(
            padding: EdgeInsets.symmetric(vertical: 24),
            child: Center(child: CircularProgressIndicator()),
          ),
          error: (e, _) => ListTile(
            leading: const Icon(Icons.error_outline),
            title: Text(friendlyError(e,
                fallback: 'Could not load the privacy notice.')),
            trailing: TextButton(
              onPressed: () => ref.invalidate(privacyNoticeProvider),
              child: const Text('Retry'),
            ),
          ),
          data: (view) => _notice(context, ref, view, muted),
        ),
      ],
    );
  }

  Widget _notice(BuildContext context, WidgetRef ref, PrivacyNoticeView view,
      TextStyle? muted) {
    final theme = Theme.of(context);
    final published = view.languages.where((l) => l.published).toList();
    final choices = published.isEmpty
        ? view.languages.where((l) => l.code == 'en').toList()
        : published;
    final current = choices.any((l) => l.code == view.requested)
        ? view.requested
        : (view.served ?? 'en');
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            DropdownButtonFormField<String>(
              key: const Key('privacy-language'),
              isExpanded: true,
              initialValue: current,
              decoration: const InputDecoration(labelText: 'Language'),
              items: [
                for (final l in choices)
                  DropdownMenuItem(value: l.code, child: Text(l.name)),
              ],
              onChanged: (v) {
                if (v != null) {
                  ref.read(privacyLanguageProvider.notifier).state = v;
                }
              },
            ),
            const SizedBox(height: 12),
            if (view.notice == null)
              Text('This shop has not published its notice yet.', style: muted)
            else ...[
              if (view.served != view.requested)
                Text(
                  'Not yet in that language; shown in ${view.notice!.languageName}.',
                  style: muted,
                ),
              Text(view.notice!.title, style: theme.textTheme.titleMedium),
              const SizedBox(height: 4),
              Text(view.notice!.body),
              const SizedBox(height: 4),
              Text('Version ${view.notice!.version}', style: muted),
            ],
            const SizedBox(height: 12),
            Text('What we ask your consent for', style: theme.textTheme.titleSmall),
            for (final p in view.purposes)
              Padding(
                padding: const EdgeInsets.only(top: 4),
                child: Text('• ${p.text}'),
              ),
            const SizedBox(height: 12),
            Text('Questions or grievances', style: theme.textTheme.titleSmall),
            const SizedBox(height: 4),
            if (!view.contact.hasContact)
              Text('This shop has not named a contact yet.', style: muted)
            else
              Text([
                if (view.contact.name != null) view.contact.name!,
                if (view.contact.email != null) view.contact.email!,
                if (view.contact.phone != null) view.contact.phone!,
                if (view.contact.address != null) view.contact.address!,
              ].join(' · ')),
            Text(
              'A request is answered within ${view.contact.responseDays} days.',
              style: muted,
            ),
            if (view.dpdp || view.dpdpFrom != null) ...[
              const SizedBox(height: 8),
              Text(
                view.dpdp
                    ? "India's Digital Personal Data Protection Act binds this shop. "
                        'You may complain to the Data Protection Board of India '
                        'if a grievance is not answered.'
                    : "India's Digital Personal Data Protection Act binds this "
                        'shop from ${view.dpdpFrom}.',
                style: muted,
              ),
            ],
          ],
        ),
      ),
    );
  }
}

/// Consent by purpose, each its own switch, and all of it withdrawn at once.
class ConsentsSection extends ConsumerStatefulWidget {
  const ConsentsSection({super.key});

  @override
  ConsumerState<ConsentsSection> createState() => _ConsentsSectionState();
}

class _ConsentsSectionState extends ConsumerState<ConsentsSection> {
  bool _busy = false;

  Future<void> _choose(String purpose, bool granted) async {
    setState(() => _busy = true);
    try {
      await ref.read(storefrontDioProvider).put(
        _customer('/customers/me/privacy/consents'),
        data: {
          'choices': [
            {'purpose': purpose, 'granted': granted}
          ],
          'language': ref.read(privacyLanguageProvider),
        },
      );
      ref.invalidate(myPrivacyProvider);
      _say(granted ? 'Saved.' : 'Withdrawn.');
    } catch (e) {
      _say(friendlyError(e, fallback: 'Could not save that just now.'));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _withdrawAll() async {
    setState(() => _busy = true);
    try {
      await ref
          .read(storefrontDioProvider)
          .delete(_customer('/customers/me/privacy/consents'));
      ref.invalidate(myPrivacyProvider);
      _say('Every consent withdrawn.');
    } catch (e) {
      _say(friendlyError(e, fallback: 'Could not withdraw just now.'));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  void _say(String text) {
    if (mounted) {
      ScaffoldMessenger.of(context)
          .showSnackBar(SnackBar(content: Text(text)));
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final muted = theme.textTheme.bodyMedium
        ?.copyWith(color: theme.colorScheme.onSurfaceVariant);
    final async = ref.watch(myPrivacyProvider);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text('Your consents', style: theme.textTheme.titleLarge),
        const SizedBox(height: 4),
        Text(
          'Each purpose on its own. Withdrawing is as easy as giving: one tap, '
          'nothing to fill in.',
          style: muted,
        ),
        const SizedBox(height: 12),
        async.when(
          loading: () => const Padding(
            padding: EdgeInsets.symmetric(vertical: 24),
            child: Center(child: CircularProgressIndicator()),
          ),
          error: (e, _) => ListTile(
            leading: const Icon(Icons.error_outline),
            title: Text(friendlyError(e, fallback: 'Could not load your consents.')),
            trailing: TextButton(
              onPressed: () => ref.invalidate(myPrivacyProvider),
              child: const Text('Retry'),
            ),
          ),
          data: (mine) {
            if (mine == null) {
              return Text('Sign in to see what you have agreed to.', style: muted);
            }
            return Card(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  if (mine.child && !mine.canTrack)
                    const ListTile(
                      key: Key('privacy-child'),
                      leading: Icon(Icons.family_restroom_outlined),
                      title: Text('A parent or guardian must consent first'),
                      subtitle: Text(
                        'You are under 18. Offers, personalisation and analytics '
                        'stay off until a parent or guardian gives consent at the '
                        'shop. Loyalty is yours to choose.',
                      ),
                    ),
                  if (mine.child && mine.canTrack)
                    ListTile(
                      leading: const Icon(Icons.family_restroom_outlined),
                      title: Text('${mine.guardianName} has consented for you'),
                    ),
                  for (final c in mine.consents)
                    SwitchListTile.adaptive(
                      key: Key('consent-${c.purpose}'),
                      value: c.granted,
                      onChanged: _busy || (c.tracking && !mine.canTrack)
                          ? null
                          : (v) => _choose(c.purpose, v),
                      title: Text(c.text.split(':').first),
                      subtitle: Text(c.text.contains(':')
                          ? c.text.split(':').sublist(1).join(':').trim()
                          : c.text),
                    ),
                  Padding(
                    padding: const EdgeInsets.all(8),
                    child: Align(
                      alignment: Alignment.centerRight,
                      child: TextButton.icon(
                        key: const Key('privacy-withdraw-all'),
                        onPressed: _busy ||
                                !mine.consents.any((c) => c.granted)
                            ? null
                            : _withdrawAll,
                        icon: const Icon(Icons.block_outlined),
                        label: const Text('Withdraw every consent'),
                      ),
                    ),
                  ),
                ],
              ),
            );
          },
        ),
      ],
    );
  }
}

/// What the shopper has asked for, and the form to ask.
class RequestsSection extends ConsumerWidget {
  const RequestsSection({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final theme = Theme.of(context);
    final muted = theme.textTheme.bodyMedium
        ?.copyWith(color: theme.colorScheme.onSurfaceVariant);
    final async = ref.watch(myPrivacyRequestsProvider);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text('Your requests', style: theme.textTheme.titleLarge),
        const SizedBox(height: 4),
        Text(
          'Ask for a copy of your data, a correction, erasure, to nominate '
          'someone to act for you, or to raise a grievance. Each is answered '
          'by the day shown.',
          style: muted,
        ),
        const SizedBox(height: 12),
        async.when(
          loading: () => const SizedBox.shrink(),
          error: (e, _) => Text(
              friendlyError(e, fallback: 'Could not load your requests.'),
              style: muted),
          data: (requests) => Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              for (final r in requests)
                Card(
                  key: Key('request-${r.id}'),
                  child: ListTile(
                    leading: Icon(r.status == 'OPEN'
                        ? Icons.hourglass_top_outlined
                        : Icons.task_alt),
                    title: Text(privacyRequestLabel(r.kind)),
                    subtitle: Text(r.status == 'OPEN'
                        ? (r.overdue
                            ? 'Overdue: was due by ${r.dueOn}'
                            : 'Due by ${r.dueOn}')
                        : '${r.status.toLowerCase()}: ${r.resolution ?? ''}'),
                  ),
                ),
            ],
          ),
        ),
        const SizedBox(height: 8),
        FilledButton.tonalIcon(
          key: const Key('privacy-ask'),
          onPressed: () => showDialog<void>(
            context: context,
            builder: (_) => const RequestDialog(),
          ),
          icon: const Icon(Icons.gavel_outlined),
          label: const Text('Ask for your rights'),
        ),
      ],
    );
  }
}

class RequestDialog extends ConsumerStatefulWidget {
  const RequestDialog({super.key});

  @override
  ConsumerState<RequestDialog> createState() => _RequestDialogState();
}

class _RequestDialogState extends ConsumerState<RequestDialog> {
  String _kind = 'ACCESS';
  final _detail = TextEditingController();
  final _nominee = TextEditingController();
  final _nomineeContact = TextEditingController();
  bool _busy = false;
  String? _error;

  @override
  void dispose() {
    _detail.dispose();
    _nominee.dispose();
    _nomineeContact.dispose();
    super.dispose();
  }

  Future<void> _send() async {
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      await ref.read(storefrontDioProvider).post(
        _customer('/customers/me/privacy/requests'),
        data: {
          'kind': _kind,
          if (_detail.text.trim().isNotEmpty) 'detail': _detail.text.trim(),
          if (_kind == 'NOMINATION') 'nomineeName': _nominee.text.trim(),
          if (_kind == 'NOMINATION' && _nomineeContact.text.trim().isNotEmpty)
            'nomineeContact': _nomineeContact.text.trim(),
        },
      );
      ref.invalidate(myPrivacyRequestsProvider);
      if (mounted) Navigator.of(context).pop();
    } catch (e) {
      setState(() => _error =
          friendlyError(e, fallback: 'Could not send the request just now.'));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: const Text('Ask for your rights'),
      content: SingleChildScrollView(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            DropdownButtonFormField<String>(
              key: const Key('request-kind'),
              isExpanded: true,
              initialValue: _kind,
              items: [
                for (final e in privacyRequestKinds.entries)
                  DropdownMenuItem(value: e.key, child: Text(e.value)),
              ],
              onChanged: (v) => setState(() => _kind = v ?? 'ACCESS'),
              decoration: const InputDecoration(labelText: 'Request'),
            ),
            TextField(
              key: const Key('request-detail'),
              controller: _detail,
              maxLines: 3,
              maxLength: 2000,
              decoration: const InputDecoration(
                  labelText: 'Tell us more (optional)', counterText: ''),
            ),
            if (_kind == 'NOMINATION') ...[
              TextField(
                key: const Key('request-nominee'),
                controller: _nominee,
                decoration: const InputDecoration(labelText: 'Who may act for you'),
              ),
              TextField(
                controller: _nomineeContact,
                decoration:
                    const InputDecoration(labelText: 'How to reach them (optional)'),
              ),
            ],
            if (_error != null)
              Padding(
                padding: const EdgeInsets.only(top: 8),
                child: Text(_error!,
                    style: TextStyle(color: Theme.of(context).colorScheme.error)),
              ),
          ],
        ),
      ),
      actions: [
        TextButton(
          onPressed: _busy ? null : () => Navigator.of(context).pop(),
          child: const Text('Cancel'),
        ),
        FilledButton(
          key: const Key('request-send'),
          onPressed: _busy ? null : _send,
          child: const Text('Send'),
        ),
      ],
    );
  }
}
