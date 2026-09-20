import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../core/constants.dart';
import '../../core/network/api_client.dart';
import '../../core/network/api_error.dart';
import 'providers/admin_providers.dart';

/// A manual journal (17.1): a date, a description and two or more lines that
/// balance, each a debit or a credit and never both.
///
/// The server refuses a journal that does not balance, and this dialog says so
/// first — the running difference is on screen as the lines are typed, and the
/// button stays disabled until it reads 0.00 — because a finance user typing
/// twenty lines should not learn on the twenty-first that the third was wrong.
class PostJournalDialog extends ConsumerStatefulWidget {
  const PostJournalDialog({super.key});

  static const maxLines = 50;

  @override
  ConsumerState<PostJournalDialog> createState() => _PostJournalDialogState();
}

class _JournalLine {
  final code = TextEditingController();
  final name = TextEditingController();
  final debit = TextEditingController();
  final credit = TextEditingController();

  void dispose() {
    code.dispose();
    name.dispose();
    debit.dispose();
    credit.dispose();
  }

  double get debitValue => double.tryParse(debit.text.trim()) ?? 0;
  double get creditValue => double.tryParse(credit.text.trim()) ?? 0;
  bool get bothSides => debitValue > 0 && creditValue > 0;
  bool get empty => debitValue == 0 && creditValue == 0;
}

class _PostJournalDialogState extends ConsumerState<PostJournalDialog> {
  final _date = TextEditingController(text: yyyyMmDd(DateTime.now()));
  final _description = TextEditingController();
  final _lines = <_JournalLine>[_JournalLine(), _JournalLine()];
  bool _busy = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    _date.addListener(_refresh);
    _description.addListener(_refresh);
    for (final l in _lines) {
      _listen(l);
    }
  }

  void _listen(_JournalLine l) {
    l.debit.addListener(_refresh);
    l.credit.addListener(_refresh);
    l.code.addListener(_refresh);
  }

  void _refresh() => setState(() {});

  @override
  void dispose() {
    _date.dispose();
    _description.dispose();
    for (final l in _lines) {
      l.dispose();
    }
    super.dispose();
  }

  double get _totalDebit => _lines.fold(0, (s, l) => s + l.debitValue);
  double get _totalCredit => _lines.fold(0, (s, l) => s + l.creditValue);
  double get _difference =>
      double.parse((_totalDebit - _totalCredit).toStringAsFixed(2));

  static final _codeShape = RegExp(r'^[A-Za-z0-9]{1,10}$');

  /// Why the journal cannot be posted yet, or null when it can.
  String? get _blocker {
    if (_description.text.trim().isEmpty) return 'Describe the journal.';
    if (!RegExp(r'^\d{4}-\d{2}-\d{2}$').hasMatch(_date.text.trim())) {
      return 'Date as yyyy-MM-dd.';
    }
    final live = _lines.where((l) => !l.empty).toList();
    if (live.length < 2) return 'At least two lines with an amount.';
    for (final l in live) {
      if (!_codeShape.hasMatch(l.code.text.trim())) {
        return 'Every line needs a nominal code of 1–10 letters or digits.';
      }
      if (l.bothSides) return 'A line is a debit or a credit, not both.';
      if (l.debitValue < 0 || l.creditValue < 0) return 'Amounts cannot be negative.';
    }
    if (_difference != 0) {
      return 'Debits and credits differ by ${_difference.abs().toStringAsFixed(2)}.';
    }
    return null;
  }

  Future<void> _submit() async {
    if (_blocker != null || _busy) return;
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      await ref.read(apiClientProvider).dio.post(
        '/${ApiConstants.purchase}/nominal-ledger/journals',
        data: {
          'entryDate': _date.text.trim(),
          'description': _description.text.trim(),
          'lines': [
            for (final l in _lines.where((l) => !l.empty))
              {
                'nominalCode': l.code.text.trim(),
                if (l.name.text.trim().isNotEmpty)
                  'nominalName': l.name.text.trim(),
                if (l.debitValue > 0) 'debit': l.debit.text.trim(),
                if (l.creditValue > 0) 'credit': l.credit.text.trim(),
              },
          ],
        },
      );
      if (!mounted) return;
      Navigator.pop(context, true);
      ScaffoldMessenger.of(context)
          .showSnackBar(const SnackBar(content: Text('Journal posted.')));
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _busy = false;
        _error = friendlyError(e, fallback: 'Could not post the journal.');
      });
    }
  }

  void _addLine() {
    if (_lines.length >= PostJournalDialog.maxLines) return;
    final l = _JournalLine();
    _listen(l);
    setState(() => _lines.add(l));
  }

  void _removeLine(int i) {
    if (_lines.length <= 2) return;
    final l = _lines.removeAt(i);
    setState(() {});
    l.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final blocker = _blocker;
    return AlertDialog(
      title: const Text('Post a journal'),
      content: SizedBox(
        width: 640,
        child: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Row(children: [
                SizedBox(
                  width: 150,
                  child: TextField(
                    key: const Key('journal-date'),
                    controller: _date,
                    decoration: const InputDecoration(labelText: 'Date'),
                  ),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: TextField(
                    key: const Key('journal-description'),
                    controller: _description,
                    maxLength: 500,
                    decoration:
                        const InputDecoration(labelText: 'Description'),
                  ),
                ),
              ]),
              const SizedBox(height: 8),
              for (var i = 0; i < _lines.length; i++)
                Padding(
                  padding: const EdgeInsets.only(bottom: 6),
                  child: Row(children: [
                    SizedBox(
                      width: 90,
                      child: TextField(
                        key: Key('journal-code-$i'),
                        controller: _lines[i].code,
                        maxLength: 10,
                        decoration: const InputDecoration(
                            labelText: 'Code', counterText: ''),
                      ),
                    ),
                    const SizedBox(width: 8),
                    Expanded(
                      child: TextField(
                        key: Key('journal-name-$i'),
                        controller: _lines[i].name,
                        maxLength: 100,
                        decoration: const InputDecoration(
                            labelText: 'Account', counterText: ''),
                      ),
                    ),
                    const SizedBox(width: 8),
                    SizedBox(
                      width: 100,
                      child: TextField(
                        key: Key('journal-debit-$i'),
                        controller: _lines[i].debit,
                        keyboardType: const TextInputType.numberWithOptions(
                            decimal: true),
                        decoration: const InputDecoration(labelText: 'Debit'),
                      ),
                    ),
                    const SizedBox(width: 8),
                    SizedBox(
                      width: 100,
                      child: TextField(
                        key: Key('journal-credit-$i'),
                        controller: _lines[i].credit,
                        keyboardType: const TextInputType.numberWithOptions(
                            decimal: true),
                        decoration: const InputDecoration(labelText: 'Credit'),
                      ),
                    ),
                    IconButton(
                      key: Key('journal-remove-$i'),
                      tooltip: 'Remove line',
                      onPressed:
                          _lines.length > 2 ? () => _removeLine(i) : null,
                      icon: const Icon(Icons.remove_circle_outline, size: 18),
                    ),
                  ]),
                ),
              Row(children: [
                TextButton.icon(
                  key: const Key('journal-add-line'),
                  onPressed: _lines.length < PostJournalDialog.maxLines
                      ? _addLine
                      : null,
                  icon: const Icon(Icons.add, size: 18),
                  label: const Text('Add line'),
                ),
                const SizedBox(width: 8),
                Expanded(
                  child: Text(
                    'Dr ${_totalDebit.toStringAsFixed(2)} · Cr ${_totalCredit.toStringAsFixed(2)} · '
                    'difference ${_difference.toStringAsFixed(2)}',
                    key: const Key('journal-totals'),
                    textAlign: TextAlign.right,
                    style: TextStyle(
                      fontFamily: 'monospace',
                      fontSize: 12,
                      color: _difference == 0 ? cs.outline : cs.error,
                    ),
                  ),
                ),
              ]),
              if (blocker != null && _error == null)
                Padding(
                  padding: const EdgeInsets.only(top: 4),
                  child: Text(blocker,
                      style: TextStyle(color: cs.outline, fontSize: 12)),
                ),
              if (_error != null)
                Padding(
                  padding: const EdgeInsets.only(top: 8),
                  child: Text(_error!, style: TextStyle(color: cs.error)),
                ),
            ],
          ),
        ),
      ),
      actions: [
        TextButton(
          onPressed: _busy ? null : () => Navigator.pop(context, false),
          child: const Text('Cancel'),
        ),
        FilledButton(
          key: const Key('journal-post'),
          onPressed: blocker == null && !_busy ? _submit : null,
          child: const Text('Post'),
        ),
      ],
    );
  }
}
