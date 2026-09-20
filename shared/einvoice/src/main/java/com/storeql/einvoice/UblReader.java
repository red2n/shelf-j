package com.storeql.einvoice;

import com.storeql.einvoice.Invoice.Address;
import com.storeql.einvoice.Invoice.AllowanceCharge;
import com.storeql.einvoice.Invoice.Attribute;
import com.storeql.einvoice.Invoice.Card;
import com.storeql.einvoice.Invoice.Classification;
import com.storeql.einvoice.Invoice.Contact;
import com.storeql.einvoice.Invoice.CreditTransfer;
import com.storeql.einvoice.Invoice.Delivery;
import com.storeql.einvoice.Invoice.DirectDebit;
import com.storeql.einvoice.Invoice.Identifier;
import com.storeql.einvoice.Invoice.Item;
import com.storeql.einvoice.Invoice.Line;
import com.storeql.einvoice.Invoice.Note;
import com.storeql.einvoice.Invoice.Party;
import com.storeql.einvoice.Invoice.Payee;
import com.storeql.einvoice.Invoice.PaymentInstructions;
import com.storeql.einvoice.Invoice.Period;
import com.storeql.einvoice.Invoice.PrecedingInvoice;
import com.storeql.einvoice.Invoice.Price;
import com.storeql.einvoice.Invoice.SupportingDocument;
import com.storeql.einvoice.Invoice.TaxRepresentative;
import com.storeql.einvoice.Invoice.Totals;
import com.storeql.einvoice.Invoice.VatBreakdown;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a UBL 2.1 Invoice or CreditNote into the EN 16931 model, by the syntax binding of EN
 * 16931-3-2 as Peppol BIS Billing 3.0 applies it.
 */
final class UblReader {

  static final String INVOICE_NS = "urn:oasis:names:specification:ubl:schema:xsd:Invoice-2";
  static final String CREDIT_NOTE_NS = "urn:oasis:names:specification:ubl:schema:xsd:CreditNote-2";
  static final String CAC =
      "urn:oasis:names:specification:ubl:schema:xsd:CommonAggregateComponents-2";
  static final String CBC = "urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2";

  /** The scheme a SEPA creditor identifier (BT-90) travels under, as a party identification. */
  static final String SEPA = "SEPA";

  /** What Peppol writes in an element UBL requires and EN 16931 has no value for. */
  static final String NOT_APPLICABLE = "NA";

  /**
   * UNTDID 4461 "instrument not defined": the payment means a credit note's due date travels in
   * when the invoice gives no payment instructions, since UBL's CreditNote has no DueDate of its
   * own.
   */
  static final String UNDEFINED_MEANS = "1";

  /** EN 16931-3-2 writes a note's subject code (BT-21) as a {@code #CODE#} prefix. */
  private static final Pattern NOTE_SUBJECT =
      Pattern.compile("#([A-Za-z]{3})#(.*)", Pattern.DOTALL);

  private UblReader() {}

  static boolean accepts(XmlElement root) {
    return INVOICE_NS.equals(root.namespace()) && "Invoice".equals(root.name())
        || CREDIT_NOTE_NS.equals(root.namespace()) && "CreditNote".equals(root.name());
  }

  static Invoice read(XmlElement doc) {
    boolean credit = "CreditNote".equals(doc.name());
    String currency = doc.value("DocumentCurrencyCode");
    String taxCurrency = doc.value("TaxCurrencyCode");

    String project = doc.value("ProjectReference", "ID");
    Identifier invoicedObject = null;
    List<SupportingDocument> documents = new ArrayList<>();
    for (XmlElement ref : doc.all("AdditionalDocumentReference")) {
      String type = ref.value("DocumentTypeCode");
      if ("130".equals(type)) {
        invoicedObject = identifier(ref.child("ID"));
      } else if ("50".equals(type)) {
        project = ref.value("ID");
      } else {
        documents.add(document(ref));
      }
    }

    List<XmlElement> means = doc.all("PaymentMeans");
    LocalDate due = Values.isoDate(doc.value("DueDate"), "BT-9");
    for (XmlElement m : means) {
      if (due == null) due = Values.isoDate(m.value("PaymentDueDate"), "BT-9");
    }

    XmlElement order = doc.child("OrderReference");
    String orderId = order == null ? null : order.value("ID");
    String salesOrder = order == null ? null : order.value("SalesOrderID");
    if (NOT_APPLICABLE.equals(orderId) && salesOrder != null) orderId = null;

    List<VatBreakdown> breakdown = new ArrayList<>();
    BigDecimal vat = null;
    BigDecimal vatInTaxCurrency = null;
    for (XmlElement total : doc.all("TaxTotal")) {
      XmlElement amount = total.child("TaxAmount");
      List<XmlElement> subtotals = total.all("TaxSubtotal");
      String amountCurrency = amount == null ? null : amount.attribute("currencyID");
      boolean inTaxCurrency =
          subtotals.isEmpty()
              && taxCurrency != null
              && taxCurrency.equals(amountCurrency)
              && !taxCurrency.equals(currency);
      if (inTaxCurrency) {
        vatInTaxCurrency = Values.decimal(amount, "BT-111");
      } else {
        if (vat == null) vat = Values.decimal(amount, "BT-110");
        for (XmlElement s : subtotals) breakdown.add(vatBreakdown(s));
      }
    }
    Totals totals =
        Values.orNull(
            new Totals(
                amount(doc, "BT-106", "LineExtensionAmount"),
                amount(doc, "BT-107", "AllowanceTotalAmount"),
                amount(doc, "BT-108", "ChargeTotalAmount"),
                amount(doc, "BT-109", "TaxExclusiveAmount"),
                vat,
                vatInTaxCurrency,
                amount(doc, "BT-112", "TaxInclusiveAmount"),
                amount(doc, "BT-113", "PrepaidAmount"),
                amount(doc, "BT-114", "PayableRoundingAmount"),
                amount(doc, "BT-115", "PayableAmount")));

    List<Note> notes = new ArrayList<>();
    for (XmlElement n : doc.all("Note")) {
      String text = n.value();
      if (text == null) continue;
      Matcher m = NOTE_SUBJECT.matcher(text);
      notes.add(
          m.matches()
              ? new Note(m.group(1).toUpperCase(Locale.ROOT), blankToNull(m.group(2)))
              : new Note(null, text));
    }

    List<PrecedingInvoice> preceding = new ArrayList<>();
    for (XmlElement b : doc.all("BillingReference")) {
      XmlElement r = b.child("InvoiceDocumentReference");
      if (r != null) {
        preceding.add(
            new PrecedingInvoice(r.value("ID"), Values.isoDate(r.value("IssueDate"), "BT-26")));
      }
    }

    XmlElement seller = doc.at("AccountingSupplierParty", "Party");
    XmlElement payee = doc.child("PayeeParty");
    String creditor = sepaCreditor(payee) != null ? sepaCreditor(payee) : sepaCreditor(seller);
    XmlElement period = doc.child("InvoicePeriod");

    return new Invoice(
        doc.value("CustomizationID"),
        doc.value("ProfileID"),
        doc.value("ID"),
        Values.isoDate(doc.value("IssueDate"), "BT-2"),
        doc.value(credit ? "CreditNoteTypeCode" : "InvoiceTypeCode"),
        currency,
        taxCurrency,
        Values.isoDate(doc.value("TaxPointDate"), "BT-7"),
        period == null ? null : period.value("DescriptionCode"),
        due,
        doc.value("BuyerReference"),
        project,
        doc.value("ContractDocumentReference", "ID"),
        orderId,
        salesOrder,
        doc.value("ReceiptDocumentReference", "ID"),
        doc.value("DespatchDocumentReference", "ID"),
        doc.value("OriginatorDocumentReference", "ID"),
        invoicedObject,
        doc.value("AccountingCost"),
        doc.value("PaymentTerms", "Note"),
        notes,
        preceding,
        party(seller),
        party(doc.at("AccountingCustomerParty", "Party")),
        payee(payee),
        taxRepresentative(doc.child("TaxRepresentativeParty")),
        delivery(doc.child("Delivery")),
        period(period),
        payment(means, creditor, credit),
        doc.all("AllowanceCharge").stream().map(UblReader::allowanceCharge).toList(),
        totals,
        breakdown,
        documents,
        doc.all(credit ? "CreditNoteLine" : "InvoiceLine").stream()
            .map(l -> line(l, credit))
            .toList());
  }

  private static BigDecimal amount(XmlElement doc, String term, String element) {
    return Values.decimal(doc.value("LegalMonetaryTotal", element), term);
  }

  private static String blankToNull(String s) {
    return s == null || s.isBlank() ? null : s.strip();
  }

  static Identifier identifier(XmlElement e) {
    if (e == null || e.value() == null) return null;
    return new Identifier(e.value(), e.attribute("schemeID"));
  }

  private static String sepaCreditor(XmlElement party) {
    if (party == null) return null;
    for (XmlElement pi : party.all("PartyIdentification")) {
      Identifier id = identifier(pi.child("ID"));
      if (id != null && SEPA.equalsIgnoreCase(id.scheme())) return id.id();
    }
    return null;
  }

  private static Party party(XmlElement p) {
    if (p == null) return null;
    List<Identifier> ids = new ArrayList<>();
    for (XmlElement pi : p.all("PartyIdentification")) {
      Identifier id = identifier(pi.child("ID"));
      if (id != null && !SEPA.equalsIgnoreCase(id.scheme())) ids.add(id);
    }
    String vatId = null;
    String taxRegistration = null;
    for (XmlElement scheme : p.all("PartyTaxScheme")) {
      if ("VAT".equalsIgnoreCase(scheme.value("TaxScheme", "ID"))) {
        vatId = scheme.value("CompanyID");
      } else {
        taxRegistration = scheme.value("CompanyID");
      }
    }
    return Values.orNull(
        new Party(
            p.value("PartyLegalEntity", "RegistrationName"),
            p.value("PartyName", "Name"),
            ids,
            identifier(p.at("PartyLegalEntity", "CompanyID")),
            vatId,
            taxRegistration,
            p.value("PartyLegalEntity", "CompanyLegalForm"),
            identifier(p.child("EndpointID")),
            address(p.child("PostalAddress")),
            contact(p.child("Contact"))));
  }

  private static Address address(XmlElement a) {
    if (a == null) return null;
    return Values.orNull(
        new Address(
            a.value("StreetName"),
            a.value("AdditionalStreetName"),
            a.value("AddressLine", "Line"),
            a.value("CityName"),
            a.value("PostalZone"),
            a.value("CountrySubentity"),
            a.value("Country", "IdentificationCode")));
  }

  private static Contact contact(XmlElement c) {
    if (c == null) return null;
    return Values.orNull(
        new Contact(c.value("Name"), c.value("Telephone"), c.value("ElectronicMail")));
  }

  private static Payee payee(XmlElement p) {
    if (p == null) return null;
    Identifier identifier = null;
    for (XmlElement pi : p.all("PartyIdentification")) {
      Identifier id = identifier(pi.child("ID"));
      if (identifier == null && id != null && !SEPA.equalsIgnoreCase(id.scheme())) identifier = id;
    }
    return Values.orNull(
        new Payee(
            p.value("PartyName", "Name"),
            identifier,
            identifier(p.at("PartyLegalEntity", "CompanyID"))));
  }

  private static TaxRepresentative taxRepresentative(XmlElement p) {
    if (p == null) return null;
    return Values.orNull(
        new TaxRepresentative(
            p.value("PartyName", "Name"),
            p.value("PartyTaxScheme", "CompanyID"),
            address(p.child("PostalAddress"))));
  }

  private static Delivery delivery(XmlElement d) {
    if (d == null) return null;
    return Values.orNull(
        new Delivery(
            d.value("DeliveryParty", "PartyName", "Name"),
            identifier(d.at("DeliveryLocation", "ID")),
            Values.isoDate(d.value("ActualDeliveryDate"), "BT-72"),
            address(d.at("DeliveryLocation", "Address"))));
  }

  private static Period period(XmlElement p) {
    if (p == null) return null;
    return Values.orNull(
        new Period(
            Values.isoDate(p.value("StartDate"), "period start date"),
            Values.isoDate(p.value("EndDate"), "period end date")));
  }

  private static PaymentInstructions payment(
      List<XmlElement> means, String creditor, boolean credit) {
    if (means.isEmpty() && creditor == null) return null;
    String code = null;
    String text = null;
    String remittance = null;
    Card card = null;
    String mandate = null;
    String debited = null;
    List<CreditTransfer> transfers = new ArrayList<>();
    for (XmlElement m : means) {
      XmlElement c = m.child("PaymentMeansCode");
      if (code == null && c != null) {
        code = c.value();
        text = c.attribute("name");
      }
      if (remittance == null) remittance = m.value("PaymentID");
      XmlElement account = m.child("PayeeFinancialAccount");
      if (account != null) {
        CreditTransfer t =
            Values.orNull(
                new CreditTransfer(
                    account.value("ID"),
                    account.value("Name"),
                    account.value("FinancialInstitutionBranch", "ID")));
        if (t != null) transfers.add(t);
      }
      XmlElement cardAccount = m.child("CardAccount");
      if (card == null && cardAccount != null) {
        String network = cardAccount.value("NetworkID");
        card =
            Values.orNull(
                new Card(
                    cardAccount.value("PrimaryAccountNumberID"),
                    NOT_APPLICABLE.equals(network) ? null : network,
                    cardAccount.value("HolderName")));
      }
      if (mandate == null) mandate = m.value("PaymentMandate", "ID");
      if (debited == null) debited = m.value("PaymentMandate", "PayerFinancialAccount", "ID");
    }
    DirectDebit directDebit = Values.orNull(new DirectDebit(mandate, creditor, debited));
    boolean carriesOnlyTheDueDate =
        credit
            && UNDEFINED_MEANS.equals(code)
            && text == null
            && remittance == null
            && transfers.isEmpty()
            && card == null
            && directDebit == null;
    if (carriesOnlyTheDueDate) return null;
    return Values.orNull(
        new PaymentInstructions(code, text, remittance, transfers, card, directDebit));
  }

  private static AllowanceCharge allowanceCharge(XmlElement a) {
    return new AllowanceCharge(
        Values.indicator(a.value("ChargeIndicator"), "ChargeIndicator"),
        Values.decimal(a.value("Amount"), "allowance or charge amount"),
        Values.decimal(a.value("BaseAmount"), "allowance or charge base amount"),
        Values.decimal(a.value("MultiplierFactorNumeric"), "allowance or charge percentage"),
        a.value("TaxCategory", "ID"),
        Values.decimal(a.value("TaxCategory", "Percent"), "allowance or charge VAT rate"),
        a.value("AllowanceChargeReason"),
        a.value("AllowanceChargeReasonCode"));
  }

  private static VatBreakdown vatBreakdown(XmlElement s) {
    return new VatBreakdown(
        Values.decimal(s.value("TaxableAmount"), "BT-116"),
        Values.decimal(s.value("TaxAmount"), "BT-117"),
        s.value("TaxCategory", "ID"),
        Values.decimal(s.value("TaxCategory", "Percent"), "BT-119"),
        s.value("TaxCategory", "TaxExemptionReason"),
        s.value("TaxCategory", "TaxExemptionReasonCode"));
  }

  private static SupportingDocument document(XmlElement ref) {
    XmlElement binary = ref.at("Attachment", "EmbeddedDocumentBinaryObject");
    return new SupportingDocument(
        ref.value("ID"),
        ref.value("DocumentDescription"),
        ref.value("Attachment", "ExternalReference", "URI"),
        binary == null ? null : binary.value(),
        binary == null ? null : binary.attribute("mimeCode"),
        binary == null ? null : binary.attribute("filename"));
  }

  private static Line line(XmlElement l, boolean credit) {
    XmlElement quantity = l.child(credit ? "CreditedQuantity" : "InvoicedQuantity");
    Identifier object = null;
    for (XmlElement ref : l.all("DocumentReference")) {
      if (object == null) object = identifier(ref.child("ID"));
    }
    XmlElement item = l.child("Item");
    return new Line(
        l.value("ID"),
        l.value("Note"),
        object,
        Values.decimal(quantity, "BT-129"),
        quantity == null ? null : quantity.attribute("unitCode"),
        Values.decimal(l.value("LineExtensionAmount"), "BT-131"),
        l.value("OrderLineReference", "LineID"),
        l.value("AccountingCost"),
        period(l.child("InvoicePeriod")),
        l.all("AllowanceCharge").stream().map(UblReader::allowanceCharge).toList(),
        price(l.child("Price")),
        item == null ? null : item.value("ClassifiedTaxCategory", "ID"),
        item == null
            ? null
            : Values.decimal(item.value("ClassifiedTaxCategory", "Percent"), "BT-152"),
        item(item));
  }

  private static Price price(XmlElement p) {
    if (p == null) return null;
    XmlElement base = p.child("BaseQuantity");
    return Values.orNull(
        new Price(
            Values.decimal(p.value("PriceAmount"), "BT-146"),
            Values.decimal(p.value("AllowanceCharge", "Amount"), "BT-147"),
            Values.decimal(p.value("AllowanceCharge", "BaseAmount"), "BT-148"),
            Values.decimal(base, "BT-149"),
            base == null ? null : base.attribute("unitCode")));
  }

  private static Item item(XmlElement i) {
    if (i == null) return null;
    List<Classification> classifications = new ArrayList<>();
    for (XmlElement c : i.all("CommodityClassification")) {
      XmlElement code = c.child("ItemClassificationCode");
      if (code != null && code.value() != null) {
        classifications.add(
            new Classification(
                code.value(), code.attribute("listID"), code.attribute("listVersionID")));
      }
    }
    List<Attribute> attributes = new ArrayList<>();
    for (XmlElement a : i.all("AdditionalItemProperty")) {
      attributes.add(new Attribute(a.value("Name"), a.value("Value")));
    }
    return Values.orNull(
        new Item(
            i.value("Name"),
            i.value("Description"),
            i.value("SellersItemIdentification", "ID"),
            i.value("BuyersItemIdentification", "ID"),
            identifier(i.at("StandardItemIdentification", "ID")),
            classifications,
            i.value("OriginCountry", "IdentificationCode"),
            attributes));
  }
}
