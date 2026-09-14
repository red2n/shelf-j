import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../core/constants.dart';
import '../../core/network/api_client.dart';
import '../../core/network/api_error.dart';
import 'procurement_providers.dart';

/// The decision on a flagged supplier invoice (07.7): approve it for payment or
/// reject it, with a reason either way.
///
/// The server does the deciding — one decision per invoice, management only,
/// the reversal posted on a reject — and this dialog collects the reason it
/// insists on. A decision about money with no reason is the thing an auditor
/// asks about first, so the button stays disabled until one is typed rather
/// than letting the server say so afterwards.
class ResolveInvoiceDialog extends ConsumerStatefulWidget {
  const ResolveInvoiceDialog({
    super.key,
    required this.invoice,
    required this.approve,
  });

  final SupplierInvoice invoice;

  /// `true` to approve, `false` to reject.
  final bool approve;

  @override
  ConsumerState<ResolveInvoiceDialog> createState() =>
      _ResolveInvoiceDialogState();
}

class _ResolveInvoiceDialogState extends ConsumerState<ResolveInvoiceDialog> {
  static const maxReason = 500;
  final _reason = TextEditingController();
  bool _busy = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    _reason.addListener(() => setState(() {}));
  }

  @override
  void dispose() {
    _reason.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    final reason = _reason.text.trim();
    if (reason.isEmpty || _busy) return;
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      await ref.read(apiClientProvider).dio.post(
        '/${ApiConstants.purchase}/supplier-invoices/${widget.invoice.id}/resolve',
        data: {
          'action': widget.approve ? 'APPROVE' : 'REJECT',
          'reason': reason,
        },
      );
      if (!mounted) return;
      ref.invalidate(supplierInvoicesProvider);
      Navigator.pop(context, true);
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(
        content: Text(widget.approve
            ? '${widget.invoice.invoiceNumber} approved for payment.'
            : '${widget.invoice.invoiceNumber} rejected; its posting is reversed.'),
      ));
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _busy = false;
        _error = friendlyError(e,
            fallback: widget.approve
                ? 'Could not approve the invoice.'
                : 'Could not reject the invoice.');
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final approve = widget.approve;
    final canSubmit = !_busy && _reason.text.trim().isNotEmpty;
    return AlertDialog(
      title: Text(
          '${approve ? 'Approve' : 'Reject'} ${widget.invoice.invoiceNumber}'),
      content: SizedBox(
        width: 440,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Text(
              approve
                  ? 'The invoice is released for payment as captured. Its posting stands.'
                  : 'The invoice is refused. Its posting is reversed, its VAT leaves the '
                      'return, and the quantities it billed are freed for a corrected invoice.',
              style: TextStyle(color: cs.outline, fontSize: 13),
            ),
            const SizedBox(height: 12),
            TextField(
              key: const Key('resolve-invoice-reason'),
              controller: _reason,
              maxLength: maxReason,
              maxLines: 3,
              autofocus: true,
              decoration: const InputDecoration(
                labelText: 'Reason',
                helperText: 'Kept with the invoice. Required.',
              ),
            ),
            if (_error != null)
              Padding(
                padding: const EdgeInsets.only(top: 8),
                child: Text(_error!, style: TextStyle(color: cs.error)),
              ),
          ],
        ),
      ),
      actions: [
        TextButton(
          onPressed: _busy ? null : () => Navigator.pop(context, false),
          child: const Text('Cancel'),
        ),
        FilledButton(
          key: const Key('resolve-invoice-submit'),
          onPressed: canSubmit ? _submit : null,
          style: approve
              ? null
              : FilledButton.styleFrom(
                  backgroundColor: cs.error,
                  foregroundColor: cs.onError,
                ),
          child: Text(approve ? 'Approve' : 'Reject'),
        ),
      ],
    );
  }
}
