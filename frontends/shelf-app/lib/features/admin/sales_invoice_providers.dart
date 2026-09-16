import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../core/constants.dart';
import '../../core/network/api_client.dart';

// ── Invoices and credit notes to business buyers (18.9) ─────────────────────

/// An invoice or credit note the business issued to a VAT-registered buyer,
/// as order-svc keeps it. The document itself is downloaded, never inlined.
class SalesInvoice {
  final String id;
  final String orderId;
  final String? returnId;
  final String kind;
  final String fullNumber;
  final String issueDate;
  final String buyerName;
  final String? buyerVatId;
  final String currency;
  final double netAmount;
  final double vatAmount;
  final double payableAmount;
  final String? precedingInvoiceId;
  final bool peppol;
  final List<String> formats;
  final List<String> irpProblems;

  const SalesInvoice({
    required this.id,
    required this.orderId,
    this.returnId,
    required this.kind,
    required this.fullNumber,
    required this.issueDate,
    required this.buyerName,
    this.buyerVatId,
    required this.currency,
    required this.netAmount,
    required this.vatAmount,
    required this.payableAmount,
    this.precedingInvoiceId,
    this.peppol = false,
    this.formats = const ['UBL', 'CII', 'FACTURX'],
    this.irpProblems = const [],
  });

  bool get creditNote => kind == 'CREDIT_NOTE';

  String get kindLabel => creditNote ? 'Credit note' : 'Invoice';

  factory SalesInvoice.fromJson(Map<String, dynamic> j) => SalesInvoice(
        id: j['id'] as String? ?? '',
        orderId: j['orderId'] as String? ?? '',
        returnId: j['returnId'] as String?,
        kind: j['kind'] as String? ?? 'INVOICE',
        fullNumber: j['fullNumber'] as String? ?? '',
        issueDate: j['issueDate'] as String? ?? '',
        buyerName: j['buyerName'] as String? ?? '',
        buyerVatId: j['buyerVatId'] as String?,
        currency: j['currency'] as String? ?? '',
        netAmount: (j['netAmount'] as num?)?.toDouble() ?? 0,
        vatAmount: (j['vatAmount'] as num?)?.toDouble() ?? 0,
        payableAmount: (j['payableAmount'] as num?)?.toDouble() ?? 0,
        precedingInvoiceId: j['precedingInvoiceId'] as String?,
        peppol: j['peppol'] as bool? ?? false,
        formats: ((j['formats'] as List?) ?? const ['UBL', 'CII', 'FACTURX'])
            .map((e) => e.toString())
            .toList(),
        irpProblems: ((j['irpProblems'] as List?) ?? const [])
            .map((e) => e.toString())
            .toList(),
      );
}

/// What a download format is called, and what its file is.
String salesInvoiceFormatLabel(String format) => switch (format) {
      'UBL' => 'UBL XML (as issued)',
      'CII' => 'CII XML',
      'FACTURX' => 'Factur-X PDF',
      'IRP' => 'IRP JSON (India)',
      _ => format,
    };

/// The file a document is saved as: its number, which a series and a year
/// make of it, never anything a buyer typed.
String salesInvoiceFileName(SalesInvoice inv, String format) {
  final base = inv.fullNumber.replaceAll(RegExp(r'[^A-Za-z0-9-]'), '-');
  return switch (format) {
    'CII' => '$base-cii.xml',
    'FACTURX' => '$base.pdf',
    'IRP' => '$base-irp.json',
    _ => '$base.xml',
  };
}

/// A sale's invoice and credit notes, newest first; empty when never invoiced.
final orderInvoicesProvider = FutureProvider.autoDispose
    .family<List<SalesInvoice>, String>((ref, orderId) async {
  final resp = await ref
      .read(apiClientProvider)
      .dio
      .get('/${ApiConstants.order}/admin/orders/$orderId/invoices');
  final items = (resp.data['data'] as List?) ?? [];
  return items
      .map((e) => SalesInvoice.fromJson(e as Map<String, dynamic>))
      .toList();
});

/// Invoices a completed sale by hand; the one already issued when there is one.
Future<SalesInvoice> issueInvoice(Dio dio, String orderId) async {
  final resp =
      await dio.post('/${ApiConstants.order}/admin/orders/$orderId/invoice');
  return SalesInvoice.fromJson(resp.data['data'] as Map<String, dynamic>);
}

/// The document as bytes, in the format asked for.
Future<Uint8List> salesInvoiceDocument(
    Dio dio, String id, String format) async {
  final resp = await dio.get<List<int>>(
    '/${ApiConstants.order}/admin/sales-invoices/$id/document',
    queryParameters: {'format': format},
    options: Options(responseType: ResponseType.bytes),
  );
  return Uint8List.fromList(resp.data ?? const []);
}

// ── A customer's VAT registration (pricing-svc) ─────────────────────────────

/// Whether a customer is a VAT-registered business, and how an invoice names
/// it: the registered name, the VAT number, the country, and where it takes
/// e-invoices. None recorded means a receipt, not an invoice.
class CustomerVatStatus {
  final String customerId;
  final bool vatRegistered;
  final String? vatNumber;
  final String? legalName;
  final String? countryCode;
  final bool reverseChargeEligible;
  final String? einvoiceScheme;
  final String? einvoiceId;

  const CustomerVatStatus({
    required this.customerId,
    required this.vatRegistered,
    this.vatNumber,
    this.legalName,
    this.countryCode,
    this.reverseChargeEligible = false,
    this.einvoiceScheme,
    this.einvoiceId,
  });

  bool get hasElectronicAddress =>
      (einvoiceScheme ?? '').isNotEmpty && (einvoiceId ?? '').isNotEmpty;

  factory CustomerVatStatus.fromJson(Map<String, dynamic> j) =>
      CustomerVatStatus(
        customerId: j['customerId'] as String? ?? '',
        vatRegistered: j['vatRegistered'] as bool? ?? false,
        vatNumber: j['vatNumber'] as String?,
        legalName: j['legalName'] as String?,
        countryCode: j['countryCode'] as String?,
        reverseChargeEligible: j['reverseChargeEligible'] as bool? ?? false,
        einvoiceScheme: j['einvoiceScheme'] as String?,
        einvoiceId: j['einvoiceId'] as String?,
      );
}

/// The registration pricing-svc holds for a customer; null when none was ever
/// recorded, which the service answers as 404.
final customerVatStatusProvider = FutureProvider.autoDispose
    .family<CustomerVatStatus?, String>((ref, customerId) async {
  try {
    final resp = await ref
        .read(apiClientProvider)
        .dio
        .get('/${ApiConstants.pricing}/customer-vat-status/$customerId');
    return CustomerVatStatus.fromJson(
        resp.data['data'] as Map<String, dynamic>);
  } on DioException catch (e) {
    if (e.response?.statusCode == 404) return null;
    rethrow;
  }
});

/// Records a customer's registration. Blank fields are left out, so the
/// service's own rules — a VAT number parsed for its country, an electronic
/// address both or neither — answer in words rather than the form guessing.
Future<CustomerVatStatus> saveCustomerVatStatus(
  Dio dio, {
  required String customerId,
  required bool vatRegistered,
  required bool reverseChargeEligible,
  String? vatNumber,
  String? legalName,
  String? countryCode,
  String? einvoiceScheme,
  String? einvoiceId,
}) async {
  String? blankToNull(String? s) {
    final t = s?.trim() ?? '';
    return t.isEmpty ? null : t;
  }

  final body = <String, dynamic>{
    'customerId': customerId,
    'vatRegistered': vatRegistered,
    'reverseChargeEligible': reverseChargeEligible,
    'vatNumber': blankToNull(vatNumber),
    'legalName': blankToNull(legalName),
    'countryCode': blankToNull(countryCode)?.toUpperCase(),
    'einvoiceScheme': blankToNull(einvoiceScheme),
    'einvoiceId': blankToNull(einvoiceId),
  }..removeWhere((_, v) => v == null);
  final resp = await dio.post('/${ApiConstants.pricing}/customer-vat-status',
      data: body);
  return CustomerVatStatus.fromJson(resp.data['data'] as Map<String, dynamic>);
}
