package com.shelfj.einvoice;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * One invoice or credit note in the semantic model of EN 16931-1:2017, the same whichever syntax it
 * came in or goes out in. Each component names the business term it carries (BT-n) or the group
 * (BG-n), so a field can be checked against the standard rather than against this code.
 *
 * <p>Absent terms are {@code null}; absent repeatable groups are empty lists, never {@code null}.
 * Amounts keep the scale they were written with, so a document read and written again is the
 * document that was read.
 *
 * @param customizationId BT-24 specification identifier
 * @param profileId BT-23 business process type
 * @param number BT-1 invoice number
 * @param issueDate BT-2
 * @param typeCode BT-3, UNTDID 1001 (380 invoice, 381 credit note, 384 corrected invoice …)
 * @param currency BT-5
 * @param taxCurrency BT-6 VAT accounting currency
 * @param taxPointDate BT-7
 * @param taxPointDateCode BT-8, UNTDID 2005
 * @param dueDate BT-9 payment due date
 * @param buyerReference BT-10
 * @param projectReference BT-11
 * @param contractReference BT-12
 * @param orderReference BT-13 purchase order reference
 * @param salesOrderReference BT-14
 * @param receivingAdviceReference BT-15
 * @param despatchAdviceReference BT-16
 * @param tenderReference BT-17 tender or lot reference
 * @param invoicedObject BT-18 invoiced object identifier and its scheme
 * @param buyerAccountingReference BT-19
 * @param paymentTerms BT-20
 * @param notes BG-1
 * @param precedingInvoices BG-3
 * @param seller BG-4
 * @param buyer BG-7
 * @param payee BG-10, when someone other than the seller is paid
 * @param taxRepresentative BG-11
 * @param delivery BG-13
 * @param invoicingPeriod BG-14
 * @param payment BG-16 payment instructions
 * @param allowanceCharges BG-20 document level allowances and BG-21 document level charges
 * @param totals BG-22
 * @param vatBreakdown BG-23
 * @param supportingDocuments BG-24
 * @param lines BG-25
 */
public record Invoice(
    String customizationId,
    String profileId,
    String number,
    LocalDate issueDate,
    String typeCode,
    String currency,
    String taxCurrency,
    LocalDate taxPointDate,
    String taxPointDateCode,
    LocalDate dueDate,
    String buyerReference,
    String projectReference,
    String contractReference,
    String orderReference,
    String salesOrderReference,
    String receivingAdviceReference,
    String despatchAdviceReference,
    String tenderReference,
    Identifier invoicedObject,
    String buyerAccountingReference,
    String paymentTerms,
    List<Note> notes,
    List<PrecedingInvoice> precedingInvoices,
    Party seller,
    Party buyer,
    Payee payee,
    TaxRepresentative taxRepresentative,
    Delivery delivery,
    Period invoicingPeriod,
    PaymentInstructions payment,
    List<AllowanceCharge> allowanceCharges,
    Totals totals,
    List<VatBreakdown> vatBreakdown,
    List<SupportingDocument> supportingDocuments,
    List<Line> lines) {

  /** EN 16931 itself, as CII and Factur-X name it. */
  public static final String EN16931 = "urn:cen.eu:en16931:2017";

  /** Peppol BIS Billing 3.0 (PEPPOL-EN16931-R004). */
  public static final String PEPPOL_BIS_3 =
      "urn:cen.eu:en16931:2017#compliant#urn:fdc:peppol.eu:2017:poacc:billing:3.0";

  /** Peppol's billing process 01 (PEPPOL-EN16931-R007). */
  public static final String PEPPOL_BILLING_PROFILE = "urn:fdc:peppol.eu:2017:poacc:billing:01:1.0";

  /**
   * The type codes a UBL document carries as a {@code CreditNote} rather than an {@code Invoice}
   * (PEPPOL-EN16931-P0101).
   */
  public static final Set<String> CREDIT_NOTE_TYPES = Set.of("381", "396", "81", "83", "532");

  public Invoice {
    notes = listOf(notes);
    precedingInvoices = listOf(precedingInvoices);
    allowanceCharges = listOf(allowanceCharges);
    vatBreakdown = listOf(vatBreakdown);
    supportingDocuments = listOf(supportingDocuments);
    lines = listOf(lines);
  }

  /** Whether this is a credit note, which UBL writes under its own root element. */
  public boolean isCreditNote() {
    return typeCode != null && CREDIT_NOTE_TYPES.contains(typeCode.strip());
  }

  /** BG-20, the document level allowances. */
  public List<AllowanceCharge> allowances() {
    return allowanceCharges.stream().filter(ac -> !ac.charge()).toList();
  }

  /** BG-21, the document level charges. */
  public List<AllowanceCharge> charges() {
    return allowanceCharges.stream().filter(AllowanceCharge::charge).toList();
  }

  static <T> List<T> listOf(List<T> list) {
    return list == null ? List.of() : List.copyOf(list);
  }

  /** BG-1: a textual note, with its UNTDID 4451 subject when one is given (BT-21, BT-22). */
  public record Note(String subjectCode, String text) {}

  /** BG-3: an invoice this one corrects or credits (BT-25, BT-26). */
  public record PrecedingInvoice(String number, LocalDate issueDate) {}

  /** An identifier and the scheme that issues it: an EAS, ICD, UNTDID 1153 or 7143 code. */
  public record Identifier(String id, String scheme) {}

  /**
   * A postal address (BG-5, BG-8, BG-12, BG-15).
   *
   * @param line1 BT-35 address line 1
   * @param line2 BT-36 address line 2
   * @param line3 BT-162 address line 3
   * @param city BT-37
   * @param postcode BT-38
   * @param subdivision BT-39 country subdivision
   * @param country BT-40, ISO 3166-1 alpha-2
   */
  public record Address(
      String line1,
      String line2,
      String line3,
      String city,
      String postcode,
      String subdivision,
      String country) {}

  /** BG-6 and BG-9: who to contact (BT-41–43, BT-56–58). */
  public record Contact(String name, String phone, String email) {}

  /**
   * The seller (BG-4) or the buyer (BG-7).
   *
   * @param name BT-27 or BT-44, the registered name
   * @param tradingName BT-28 or BT-45
   * @param identifiers BT-29 or BT-46, each with its scheme when it has one
   * @param legalRegistration BT-30 or BT-47
   * @param vatId BT-31 or BT-48, prefixed with its issuing country
   * @param taxRegistrationId BT-32, the seller's tax registration other than VAT
   * @param additionalLegalInfo BT-33, the seller's share capital, legal form and the like
   * @param electronicAddress BT-34 or BT-49, where the document is delivered
   * @param address BG-5 or BG-8
   * @param contact BG-6 or BG-9
   */
  public record Party(
      String name,
      String tradingName,
      List<Identifier> identifiers,
      Identifier legalRegistration,
      String vatId,
      String taxRegistrationId,
      String additionalLegalInfo,
      Identifier electronicAddress,
      Address address,
      Contact contact) {

    public Party {
      identifiers = listOf(identifiers);
    }
  }

  /** BG-10: the payee, when someone other than the seller is paid (BT-59–61). */
  public record Payee(String name, Identifier identifier, Identifier legalRegistration) {}

  /** BG-11: the seller's tax representative (BT-62, BT-63, BG-12). */
  public record TaxRepresentative(String name, String vatId, Address address) {}

  /**
   * BG-13: where and when the goods went.
   *
   * @param partyName BT-70 deliver to party name
   * @param location BT-71 deliver to location identifier
   * @param actualDate BT-72 actual delivery date
   * @param address BG-15 deliver to address
   */
  public record Delivery(
      String partyName, Identifier location, LocalDate actualDate, Address address) {}

  /** BG-14 or BG-26: a period, either end of which may be open (BT-73/74, BT-134/135). */
  public record Period(LocalDate start, LocalDate end) {}

  /**
   * BG-16: how the invoice is to be paid.
   *
   * @param meansCode BT-81, UNTDID 4461 (30 credit transfer, 58 SEPA credit transfer, 59 SEPA
   *     direct debit, 48 card …)
   * @param meansText BT-82
   * @param remittanceInformation BT-83
   * @param creditTransfers BG-17
   * @param card BG-18
   * @param directDebit BG-19
   */
  public record PaymentInstructions(
      String meansCode,
      String meansText,
      String remittanceInformation,
      List<CreditTransfer> creditTransfers,
      Card card,
      DirectDebit directDebit) {

    public PaymentInstructions {
      creditTransfers = listOf(creditTransfers);
    }
  }

  /** BG-17: an account to pay into (BT-84 account, BT-85 name, BT-86 BIC or other provider). */
  public record CreditTransfer(String account, String accountName, String serviceProvider) {}

  /**
   * BG-18: the card paid with; BR-51 allows only the last digits of its number (BT-87, BT-88). UBL
   * also requires the card's network, which EN 16931 does not model; it is kept when read.
   */
  public record Card(String primaryAccountNumber, String network, String holderName) {}

  /** BG-19: a direct debit (BT-89 mandate, BT-90 creditor identifier, BT-91 debited account). */
  public record DirectDebit(String mandateReference, String creditorId, String debitedAccount) {}

  /**
   * A document level allowance (BG-20) or charge (BG-21), or a line level one (BG-27, BG-28), which
   * carries no VAT category of its own.
   *
   * @param charge true for a charge, false for an allowance
   * @param amount BT-92, BT-99, BT-136 or BT-141
   * @param baseAmount BT-93, BT-100, BT-137 or BT-142
   * @param percentage BT-94, BT-101, BT-138 or BT-143
   * @param vatCategory BT-95 or BT-102
   * @param vatRate BT-96 or BT-103
   * @param reason BT-97, BT-104, BT-139 or BT-144
   * @param reasonCode BT-98, BT-105, BT-140 or BT-145
   */
  public record AllowanceCharge(
      boolean charge,
      BigDecimal amount,
      BigDecimal baseAmount,
      BigDecimal percentage,
      String vatCategory,
      BigDecimal vatRate,
      String reason,
      String reasonCode) {}

  /**
   * BG-22: the document totals.
   *
   * @param lineNet BT-106 sum of invoice line net amounts
   * @param allowances BT-107 sum of document level allowances
   * @param charges BT-108 sum of document level charges
   * @param withoutVat BT-109 invoice total without VAT
   * @param vat BT-110 invoice total VAT
   * @param vatInTaxCurrency BT-111 invoice total VAT in the VAT accounting currency
   * @param withVat BT-112 invoice total with VAT
   * @param paid BT-113 paid amount
   * @param rounding BT-114 rounding amount
   * @param payable BT-115 amount due for payment
   */
  public record Totals(
      BigDecimal lineNet,
      BigDecimal allowances,
      BigDecimal charges,
      BigDecimal withoutVat,
      BigDecimal vat,
      BigDecimal vatInTaxCurrency,
      BigDecimal withVat,
      BigDecimal paid,
      BigDecimal rounding,
      BigDecimal payable) {}

  /**
   * BG-23: the VAT for one category and rate.
   *
   * @param taxableAmount BT-116
   * @param taxAmount BT-117
   * @param category BT-118, UNTDID 5305
   * @param rate BT-119
   * @param exemptionReason BT-120
   * @param exemptionReasonCode BT-121, VATEX
   */
  public record VatBreakdown(
      BigDecimal taxableAmount,
      BigDecimal taxAmount,
      String category,
      BigDecimal rate,
      String exemptionReason,
      String exemptionReasonCode) {}

  /**
   * BG-24: a document supporting the invoice, referenced or attached.
   *
   * @param reference BT-122
   * @param description BT-123
   * @param location BT-124 external document location
   * @param attachmentBase64 BT-125, the attached document as the base64 it travels in
   * @param mimeCode BT-125's MIME type
   * @param filename BT-125's file name
   */
  public record SupportingDocument(
      String reference,
      String description,
      String location,
      String attachmentBase64,
      String mimeCode,
      String filename) {}

  /**
   * BG-25: one invoice line.
   *
   * @param id BT-126
   * @param note BT-127
   * @param objectId BT-128 invoice line object identifier and its scheme
   * @param quantity BT-129 invoiced quantity
   * @param unitCode BT-130, UN/ECE Recommendation 20
   * @param netAmount BT-131
   * @param orderLineReference BT-132 referenced purchase order line
   * @param accountingReference BT-133 buyer accounting reference
   * @param period BG-26
   * @param allowanceCharges BG-27 line allowances and BG-28 line charges
   * @param price BG-29
   * @param vatCategory BT-151
   * @param vatRate BT-152
   * @param item BG-31
   */
  public record Line(
      String id,
      String note,
      Identifier objectId,
      BigDecimal quantity,
      String unitCode,
      BigDecimal netAmount,
      String orderLineReference,
      String accountingReference,
      Period period,
      List<AllowanceCharge> allowanceCharges,
      Price price,
      String vatCategory,
      BigDecimal vatRate,
      Item item) {

    public Line {
      allowanceCharges = listOf(allowanceCharges);
    }
  }

  /**
   * BG-29: the price of one unit.
   *
   * @param net BT-146 item net price
   * @param discount BT-147 item price discount
   * @param gross BT-148 item gross price
   * @param baseQuantity BT-149 the quantity the price is for
   * @param baseQuantityUnit BT-150
   */
  public record Price(
      BigDecimal net,
      BigDecimal discount,
      BigDecimal gross,
      BigDecimal baseQuantity,
      String baseQuantityUnit) {}

  /**
   * BG-31: what was sold.
   *
   * @param name BT-153
   * @param description BT-154
   * @param sellersId BT-155
   * @param buyersId BT-156
   * @param standardId BT-157, a GTIN with scheme 0160 for instance
   * @param classifications BT-158
   * @param originCountry BT-159
   * @param attributes BG-32
   */
  public record Item(
      String name,
      String description,
      String sellersId,
      String buyersId,
      Identifier standardId,
      List<Classification> classifications,
      String originCountry,
      List<Attribute> attributes) {

    public Item {
      classifications = listOf(classifications);
      attributes = listOf(attributes);
    }
  }

  /** BT-158: an item classification code, its UNTDID 7143 scheme and the scheme's version. */
  public record Classification(String code, String scheme, String schemeVersion) {}

  /** BG-32: an item attribute (BT-160 name, BT-161 value). */
  public record Attribute(String name, String value) {}
}
