/// Checks on UK and SEPA bank details keyed into a form, in the shapes
/// purchase-svc accepts; the server also checks an IBAN's check digits.
library;

/// The digits of a sort code or account number keyed with spaces or dashes.
String bankDigits(String? v) => (v ?? '').replaceAll(RegExp(r'[\s-]'), '');

/// A sort code: six digits, required once an account number is keyed.
String? validSortCode(String? v, {required bool accountKeyed}) {
  final t = bankDigits(v);
  if (t.isEmpty) {
    return accountKeyed ? 'Required with an account number' : null;
  }
  return RegExp(r'^\d{6}$').hasMatch(t) ? null : 'Six digits';
}

/// A UK account number: eight digits, required once a sort code is keyed.
String? validAccountNumber(String? v, {required bool sortCodeKeyed}) {
  final t = bankDigits(v);
  if (t.isEmpty) return sortCodeKeyed ? 'Required with a sort code' : null;
  return RegExp(r'^\d{8}$').hasMatch(t) ? null : 'Eight digits';
}

/// An IBAN's shape, required once a BIC is keyed.
String? validIban(String? v, {required bool bicKeyed}) {
  final t = (v ?? '').replaceAll(' ', '').toUpperCase();
  if (t.isEmpty) return bicKeyed ? 'Required with a BIC' : null;
  return RegExp(r'^[A-Z]{2}\d{2}[A-Z0-9]{11,30}$').hasMatch(t)
      ? null
      : 'Not an IBAN';
}

/// A BIC, optional: eight or eleven characters.
String? validBic(String? v) {
  final t = v?.trim() ?? '';
  if (t.isEmpty) return null;
  return RegExp(r'^[A-Za-z]{6}[A-Za-z0-9]{2}([A-Za-z0-9]{3})?$').hasMatch(t)
      ? null
      : 'Eight or eleven characters';
}
