import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart';
import '../../core/network/api_error.dart';
import 'storefront_providers.dart';

final _day = DateFormat('d MMM yyyy');

String _remedyWord(String remedy) => switch (remedy) {
  'REFUND' => 'a refund',
  'REPLACEMENT' => 'a replacement',
  'REPAIR' => 'a repair',
  _ => remedy.toLowerCase(),
};

/// The shopper's product safety recalls (05.10), above their orders: the
/// notice as the shop wrote it — headline first, the product and its lot, the
/// hazard, what to do, where to turn — and the remedy they choose, once.
class RecallNoticesSection extends ConsumerWidget {
  const RecallNoticesSection({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final async = ref.watch(myRecallNoticesProvider);
    // A failed load says so: a shopper must never read "no recalls" off an
    // error, which is the one thing this section must not get wrong.
    if (async.hasError) {
      return Padding(
        padding: const EdgeInsets.fromLTRB(16, 16, 16, 0),
        child: Text(
          "Your safety recalls couldn't be checked. ${friendlyError(async.error!)}",
          key: const Key('recall-notices-error'),
          style: TextStyle(color: Theme.of(context).colorScheme.error),
        ),
      );
    }
    final notices = async.value;
    if (notices == null || notices.isEmpty) return const SizedBox.shrink();
    return Column(
      children: [
        for (final n in notices)
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 16, 16, 0),
            child: RecallNoticeCard(notice: n),
          ),
      ],
    );
  }
}

class RecallNoticeCard extends ConsumerStatefulWidget {
  final MyRecallNotice notice;
  const RecallNoticeCard({super.key, required this.notice});

  @override
  ConsumerState<RecallNoticeCard> createState() => _RecallNoticeCardState();
}

class _RecallNoticeCardState extends ConsumerState<RecallNoticeCard> {
  late MyRecallNotice _notice = widget.notice;
  bool _busy = false;
  String? _error;

  @override
  void didUpdateWidget(RecallNoticeCard old) {
    super.didUpdateWidget(old);
    // A refetch hands the card a fresh notice: show that, not what it last knew.
    if (!identical(old.notice, widget.notice)) {
      _notice = widget.notice;
      _error = null;
    }
  }

  Future<void> _choose(String remedy) async {
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final updated = await chooseMyRecallRemedy(
        ref.read(storefrontDioProvider),
        noticeId: _notice.id,
        remedy: remedy,
      );
      setState(() => _notice = updated);
      ref.invalidate(myRecallNoticesProvider);
    } catch (e) {
      setState(() => _error = friendlyError(e));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final n = _notice;
    final cs = Theme.of(context).colorScheme;
    final text = Theme.of(context).textTheme;
    final contact = [n.contactPhone, n.contactUrl].whereType<String>().join(' · ');
    return Card(
      key: Key('recall-notice-${n.id}'),
      color: cs.errorContainer,
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Icon(Icons.warning_amber_rounded, color: cs.onErrorContainer),
                const SizedBox(width: 8),
                Expanded(
                  child: Text(
                    'PRODUCT SAFETY RECALL',
                    style: text.titleMedium?.copyWith(
                      color: cs.onErrorContainer,
                      fontWeight: FontWeight.w700,
                      letterSpacing: 1,
                    ),
                  ),
                ),
                Text(n.reference,
                    style: TextStyle(color: cs.onErrorContainer)),
              ],
            ),
            const SizedBox(height: 8),
            Text(
              n.lines.map((l) => l.describe()).join('; ') +
                  (n.soldAt == null ? '' : ' — bought ${_day.format(n.soldAt!)}'),
              style: TextStyle(
                color: cs.onErrorContainer,
                fontWeight: FontWeight.w600,
              ),
            ),
            const SizedBox(height: 8),
            Text(
              'Stop using this product immediately. ${n.customerNotice}',
              style: TextStyle(color: cs.onErrorContainer),
            ),
            const SizedBox(height: 4),
            Text(
              'Why: ${n.reason}',
              style: TextStyle(color: cs.onErrorContainer),
            ),
            if (contact.isNotEmpty) ...[
              const SizedBox(height: 4),
              Text('Contact: $contact',
                  style: TextStyle(color: cs.onErrorContainer)),
            ],
            const SizedBox(height: 12),
            if (n.isResolved)
              Text(
                switch (n.resolution) {
                  'REFUNDED' => 'Settled: you were refunded.',
                  'REPLACED' => 'Settled: you were given a replacement.',
                  'REPAIRED' => 'Settled: the product was repaired.',
                  _ => 'Settled.',
                },
                key: const Key('recall-notice-settled'),
                style: TextStyle(color: cs.onErrorContainer),
              )
            else if (n.remedy != null)
              Text(
                'You chose ${_remedyWord(n.remedy!)}. Bring the product to any '
                'of our stores${contact.isEmpty ? '' : ', or get in touch: $contact'}.',
                key: const Key('recall-notice-chosen'),
                style: TextStyle(color: cs.onErrorContainer),
              )
            else ...[
              Text(
                'Your remedy — you choose'
                '${n.singleRemedyReason == null ? '' : ' (${n.singleRemedyReason})'}:',
                style: TextStyle(color: cs.onErrorContainer),
              ),
              const SizedBox(height: 4),
              Wrap(
                spacing: 8,
                children: [
                  for (final r in n.remedies)
                    FilledButton.tonal(
                      key: Key('recall-choose-$r'),
                      onPressed: _busy ? null : () => _choose(r),
                      child: Text('I want ${_remedyWord(r)}'),
                    ),
                ],
              ),
            ],
            if (_error != null) ...[
              const SizedBox(height: 8),
              Text(
                _error!,
                key: const Key('recall-notice-refused'),
                style: TextStyle(color: cs.error),
              ),
            ],
          ],
        ),
      ),
    );
  }
}
