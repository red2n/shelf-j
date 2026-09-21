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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes the EN 16931 model as a UBL 2.1 Invoice, or a CreditNote for a credit note type code, in
 * the element order the UBL 2.1 schemas require.
 */
final class UblWriter {

  private UblWriter() {}

  static String write(Invoice inv) {
    boolean credit = inv.isCreditNote();
    Map<String, String> namespaces = new LinkedHashMap<>();
    namespaces.put("", credit ? UblReader.CREDIT_NOTE_NS : UblReader.INVOICE_NS);
    namespaces.put("cac", UblReader.CAC);
    namespaces.put("cbc", UblReader.CBC);
    XmlNode d = XmlNode.root(credit ? "CreditNote" : "Invoice", namespaces);
    String currency = inv.currency();

    d.leaf("cbc:CustomizationID", inv.customizationId())
        .leaf("cbc:ProfileID", inv.profileId())
        .leaf("cbc:ID", inv.number())
        .leaf("cbc:IssueDate", inv.issueDate());
    if (credit) {
      d.leaf("cbc:TaxPointDate", inv.taxPointDate()).leaf("cbc:CreditNoteTypeCode", inv.typeCode());
    } else {
      d.leaf("cbc:DueDate", inv.dueDate()).leaf("cbc:InvoiceTypeCode", inv.typeCode());
    }
    for (Note n : inv.notes()) {
      String text = n.text() == null ? "" : n.text();
      d.leaf("cbc:Note", n.subjectCode() == null ? text : "#" + n.subjectCode() + "#" + text);
    }
    if (!credit) d.leaf("cbc:TaxPointDate", inv.taxPointDate());
    d.leaf("cbc:DocumentCurrencyCode", currency)
        .leaf("cbc:TaxCurrencyCode", inv.taxCurrency())
        .leaf("cbc:AccountingCost", inv.buyerAccountingReference())
        .leaf("cbc:BuyerReference", inv.buyerReference());
    XmlNode period = d.group("cac:InvoicePeriod");
    dates(period, inv.invoicingPeriod());
    period.leaf("cbc:DescriptionCode", inv.taxPointDateCode());

    String orderId = inv.orderReference();
    if (orderId == null && inv.salesOrderReference() != null) orderId = UblReader.NOT_APPLICABLE;
    d.group("cac:OrderReference")
        .leaf("cbc:ID", orderId)
        .leaf("cbc:SalesOrderID", inv.salesOrderReference());
    for (PrecedingInvoice p : inv.precedingInvoices()) {
      d.group("cac:BillingReference")
          .group("cac:InvoiceDocumentReference")
          .leaf("cbc:ID", p.number())
          .leaf("cbc:IssueDate", p.issueDate());
    }
    d.group("cac:DespatchDocumentReference").leaf("cbc:ID", inv.despatchAdviceReference());
    d.group("cac:ReceiptDocumentReference").leaf("cbc:ID", inv.receivingAdviceReference());
    if (!credit) d.group("cac:OriginatorDocumentReference").leaf("cbc:ID", inv.tenderReference());
    d.group("cac:ContractDocumentReference").leaf("cbc:ID", inv.contractReference());
    Identifier object = inv.invoicedObject();
    if (object != null) {
      d.group("cac:AdditionalDocumentReference")
          .leaf("cbc:ID", object.id(), "schemeID", object.scheme())
          .leaf("cbc:DocumentTypeCode", "130");
    }
    if (credit && inv.projectReference() != null) {
      d.group("cac:AdditionalDocumentReference")
          .leaf("cbc:ID", inv.projectReference())
          .leaf("cbc:DocumentTypeCode", "50");
    }
    for (SupportingDocument s : inv.supportingDocuments()) {
      XmlNode r = d.group("cac:AdditionalDocumentReference");
      r.leaf("cbc:ID", s.reference()).leaf("cbc:DocumentDescription", s.description());
      XmlNode attachment = r.group("cac:Attachment");
      attachment
          .element("cbc:EmbeddedDocumentBinaryObject", s.attachmentBase64())
          .attribute("mimeCode", s.mimeCode())
          .attribute("filename", s.filename());
      attachment.group("cac:ExternalReference").leaf("cbc:URI", s.location());
    }
    if (credit) {
      d.group("cac:OriginatorDocumentReference").leaf("cbc:ID", inv.tenderReference());
    } else {
      d.group("cac:ProjectReference").leaf("cbc:ID", inv.projectReference());
    }

    PaymentInstructions payment = inv.payment();
    DirectDebit directDebit = payment == null ? null : payment.directDebit();
    String creditor = directDebit == null ? null : directDebit.creditorId();
    party(
        d.group("cac:AccountingSupplierParty").group("cac:Party"),
        inv.seller(),
        inv.payee() == null ? creditor : null);
    party(d.group("cac:AccountingCustomerParty").group("cac:Party"), inv.buyer(), null);
    payee(d, inv.payee(), creditor);
    taxRepresentative(d, inv.taxRepresentative());
    delivery(d, inv.delivery());
    paymentMeans(d, payment, credit ? inv.dueDate() : null, credit);
    d.group("cac:PaymentTerms").leaf("cbc:Note", inv.paymentTerms());
    for (AllowanceCharge ac : inv.allowanceCharges()) {
      allowanceCharge(d.group("cac:AllowanceCharge"), ac, currency, true);
    }
    taxTotals(d, inv);
    monetaryTotal(d, inv.totals(), currency);
    for (Line l : inv.lines()) {
      line(d.group(credit ? "cac:CreditNoteLine" : "cac:InvoiceLine"), l, currency, credit);
    }
    return d.toXml();
  }

  private static void dates(XmlNode n, Period p) {
    if (p != null) n.leaf("cbc:StartDate", p.start()).leaf("cbc:EndDate", p.end());
  }

  private static void identifier(XmlNode n, String element, Identifier id) {
    if (id != null) n.leaf(element, id.id(), "schemeID", id.scheme());
  }

  private static void party(XmlNode p, Party party, String sepaCreditor) {
    if (party != null) {
      identifier(p, "cbc:EndpointID", party.electronicAddress());
      for (Identifier id : party.identifiers()) {
        identifier(p.group("cac:PartyIdentification"), "cbc:ID", id);
      }
    }
    if (sepaCreditor != null) {
      p.group("cac:PartyIdentification").leaf("cbc:ID", sepaCreditor, "schemeID", UblReader.SEPA);
    }
    if (party == null) return;
    p.group("cac:PartyName").leaf("cbc:Name", party.tradingName());
    address(p.group("cac:PostalAddress"), party.address());
    taxScheme(p.group("cac:PartyTaxScheme"), party.vatId(), "VAT");
    taxScheme(p.group("cac:PartyTaxScheme"), party.taxRegistrationId(), "TAX");
    XmlNode legal = p.group("cac:PartyLegalEntity");
    legal.leaf("cbc:RegistrationName", party.name());
    identifier(legal, "cbc:CompanyID", party.legalRegistration());
    legal.leaf("cbc:CompanyLegalForm", party.additionalLegalInfo());
    Contact c = party.contact();
    if (c != null) {
      p.group("cac:Contact")
          .leaf("cbc:Name", c.name())
          .leaf("cbc:Telephone", c.phone())
          .leaf("cbc:ElectronicMail", c.email());
    }
  }

  private static void taxScheme(XmlNode n, String companyId, String scheme) {
    if (companyId == null) return;
    n.leaf("cbc:CompanyID", companyId).group("cac:TaxScheme").leaf("cbc:ID", scheme);
  }

  private static void address(XmlNode a, Address address) {
    if (address == null) return;
    a.leaf("cbc:StreetName", address.line1())
        .leaf("cbc:AdditionalStreetName", address.line2())
        .leaf("cbc:CityName", address.city())
        .leaf("cbc:PostalZone", address.postcode())
        .leaf("cbc:CountrySubentity", address.subdivision());
    a.group("cac:AddressLine").leaf("cbc:Line", address.line3());
    a.group("cac:Country").leaf("cbc:IdentificationCode", address.country());
  }

  private static void payee(XmlNode d, Payee payee, String creditor) {
    if (payee == null) return;
    XmlNode p = d.group("cac:PayeeParty");
    identifier(p.group("cac:PartyIdentification"), "cbc:ID", payee.identifier());
    if (creditor != null) {
      p.group("cac:PartyIdentification").leaf("cbc:ID", creditor, "schemeID", UblReader.SEPA);
    }
    p.group("cac:PartyName").leaf("cbc:Name", payee.name());
    identifier(p.group("cac:PartyLegalEntity"), "cbc:CompanyID", payee.legalRegistration());
  }

  private static void taxRepresentative(XmlNode d, TaxRepresentative rep) {
    if (rep == null) return;
    XmlNode t = d.group("cac:TaxRepresentativeParty");
    t.group("cac:PartyName").leaf("cbc:Name", rep.name());
    address(t.group("cac:PostalAddress"), rep.address());
    taxScheme(t.group("cac:PartyTaxScheme"), rep.vatId(), "VAT");
  }

  private static void delivery(XmlNode d, Delivery delivery) {
    if (delivery == null) return;
    XmlNode n = d.group("cac:Delivery");
    n.leaf("cbc:ActualDeliveryDate", delivery.actualDate());
    XmlNode location = n.group("cac:DeliveryLocation");
    identifier(location, "cbc:ID", delivery.location());
    address(location.group("cac:Address"), delivery.address());
    n.group("cac:DeliveryParty").group("cac:PartyName").leaf("cbc:Name", delivery.partyName());
  }

  /**
   * One PaymentMeans per account to pay into, each repeating the means code, as Peppol writes more
   * than one account. A credit note's due date (BT-9) has nowhere else to go in UBL, so it is
   * written on each, in a means of "instrument not defined" when there are no instructions.
   */
  private static void paymentMeans(
      XmlNode d, PaymentInstructions p, LocalDate creditNoteDue, boolean credit) {
    if (p == null && creditNoteDue == null) return;
    List<CreditTransfer> transfers = p == null ? List.of() : p.creditTransfers();
    int count = Math.max(1, transfers.size());
    for (int i = 0; i < count; i++) {
      XmlNode m = d.group("cac:PaymentMeans");
      m.element("cbc:PaymentMeansCode", p == null ? UblReader.UNDEFINED_MEANS : p.meansCode())
          .attribute("name", p == null ? null : p.meansText());
      if (credit) m.leaf("cbc:PaymentDueDate", creditNoteDue);
      if (p == null) continue;
      m.leaf("cbc:PaymentID", p.remittanceInformation());
      Card card = p.card();
      if (i == 0 && card != null) {
        m.group("cac:CardAccount")
            .leaf("cbc:PrimaryAccountNumberID", card.primaryAccountNumber())
            .leaf(
                "cbc:NetworkID", card.network() == null ? UblReader.NOT_APPLICABLE : card.network())
            .leaf("cbc:HolderName", card.holderName());
      }
      if (i < transfers.size()) {
        CreditTransfer t = transfers.get(i);
        XmlNode account = m.group("cac:PayeeFinancialAccount");
        account.leaf("cbc:ID", t.account()).leaf("cbc:Name", t.accountName());
        account.group("cac:FinancialInstitutionBranch").leaf("cbc:ID", t.serviceProvider());
      }
      DirectDebit dd = p.directDebit();
      if (i == 0 && dd != null) {
        XmlNode mandate = m.group("cac:PaymentMandate");
        mandate.leaf("cbc:ID", dd.mandateReference());
        mandate.group("cac:PayerFinancialAccount").leaf("cbc:ID", dd.debitedAccount());
      }
    }
  }

  private static void allowanceCharge(
      XmlNode n, AllowanceCharge ac, String currency, boolean document) {
    n.leaf("cbc:ChargeIndicator", Boolean.toString(ac.charge()))
        .leaf("cbc:AllowanceChargeReasonCode", ac.reasonCode())
        .leaf("cbc:AllowanceChargeReason", ac.reason())
        .leaf("cbc:MultiplierFactorNumeric", ac.percentage())
        .amount("cbc:Amount", ac.amount(), currency)
        .amount("cbc:BaseAmount", ac.baseAmount(), currency);
    if (document) {
      vatCategory(n.group("cac:TaxCategory"), ac.vatCategory(), ac.vatRate(), null, null);
    }
  }

  private static void vatCategory(
      XmlNode n, String category, BigDecimal rate, String reasonCode, String reason) {
    if (category == null && rate == null) return;
    n.leaf("cbc:ID", category)
        .leaf("cbc:Percent", rate)
        .leaf("cbc:TaxExemptionReasonCode", reasonCode)
        .leaf("cbc:TaxExemptionReason", reason)
        .group("cac:TaxScheme")
        .leaf("cbc:ID", "VAT");
  }

  private static void taxTotals(XmlNode d, Invoice inv) {
    String currency = inv.currency();
    Totals t = inv.totals();
    BigDecimal vat = t == null ? null : t.vat();
    if (vat == null && !inv.vatBreakdown().isEmpty()) {
      // UBL's schema requires the total CII may leave out; BR-CO-14 defines it as this sum.
      vat = BigDecimal.ZERO;
      for (VatBreakdown b : inv.vatBreakdown()) {
        if (b.taxAmount() != null) vat = vat.add(b.taxAmount());
      }
    }
    if (vat != null || !inv.vatBreakdown().isEmpty()) {
      XmlNode total = d.group("cac:TaxTotal");
      total.amount("cbc:TaxAmount", vat, currency);
      for (VatBreakdown b : inv.vatBreakdown()) {
        XmlNode s = total.group("cac:TaxSubtotal");
        s.amount("cbc:TaxableAmount", b.taxableAmount(), currency)
            .amount("cbc:TaxAmount", b.taxAmount(), currency);
        vatCategory(
            s.group("cac:TaxCategory"),
            b.category(),
            b.rate(),
            b.exemptionReasonCode(),
            b.exemptionReason());
      }
    }
    if (t != null && t.vatInTaxCurrency() != null) {
      d.group("cac:TaxTotal").amount("cbc:TaxAmount", t.vatInTaxCurrency(), inv.taxCurrency());
    }
  }

  private static void monetaryTotal(XmlNode d, Totals t, String currency) {
    if (t == null) return;
    d.group("cac:LegalMonetaryTotal")
        .amount("cbc:LineExtensionAmount", t.lineNet(), currency)
        .amount("cbc:TaxExclusiveAmount", t.withoutVat(), currency)
        .amount("cbc:TaxInclusiveAmount", t.withVat(), currency)
        .amount("cbc:AllowanceTotalAmount", t.allowances(), currency)
        .amount("cbc:ChargeTotalAmount", t.charges(), currency)
        .amount("cbc:PrepaidAmount", t.paid(), currency)
        .amount("cbc:PayableRoundingAmount", t.rounding(), currency)
        .amount("cbc:PayableAmount", t.payable(), currency);
  }

  private static void line(XmlNode n, Line l, String currency, boolean credit) {
    n.leaf("cbc:ID", l.id())
        .leaf("cbc:Note", l.note())
        .quantity(
            credit ? "cbc:CreditedQuantity" : "cbc:InvoicedQuantity", l.quantity(), l.unitCode())
        .amount("cbc:LineExtensionAmount", l.netAmount(), currency)
        .leaf("cbc:AccountingCost", l.accountingReference());
    dates(n.group("cac:InvoicePeriod"), l.period());
    n.group("cac:OrderLineReference").leaf("cbc:LineID", l.orderLineReference());
    Identifier object = l.objectId();
    if (object != null) {
      n.group("cac:DocumentReference")
          .leaf("cbc:ID", object.id(), "schemeID", object.scheme())
          .leaf("cbc:DocumentTypeCode", "130");
    }
    for (AllowanceCharge ac : l.allowanceCharges()) {
      allowanceCharge(n.group("cac:AllowanceCharge"), ac, currency, false);
    }
    item(n.group("cac:Item"), l);
    price(n.group("cac:Price"), l.price(), currency);
  }

  private static void item(XmlNode n, Line l) {
    Item i = l.item();
    if (i != null) {
      n.leaf("cbc:Description", i.description()).leaf("cbc:Name", i.name());
      n.group("cac:BuyersItemIdentification").leaf("cbc:ID", i.buyersId());
      n.group("cac:SellersItemIdentification").leaf("cbc:ID", i.sellersId());
      identifier(n.group("cac:StandardItemIdentification"), "cbc:ID", i.standardId());
      n.group("cac:OriginCountry").leaf("cbc:IdentificationCode", i.originCountry());
      for (Classification c : i.classifications()) {
        n.group("cac:CommodityClassification")
            .element("cbc:ItemClassificationCode", c.code())
            .attribute("listID", c.scheme())
            .attribute("listVersionID", c.schemeVersion());
      }
    }
    vatCategory(n.group("cac:ClassifiedTaxCategory"), l.vatCategory(), l.vatRate(), null, null);
    if (i != null) {
      for (Attribute a : i.attributes()) {
        n.group("cac:AdditionalItemProperty")
            .leaf("cbc:Name", a.name())
            .leaf("cbc:Value", a.value());
      }
    }
  }

  private static void price(XmlNode n, Price p, String currency) {
    if (p == null) return;
    n.amount("cbc:PriceAmount", p.net(), currency)
        .quantity("cbc:BaseQuantity", p.baseQuantity(), p.baseQuantityUnit());
    if (p.discount() != null || p.gross() != null) {
      n.group("cac:AllowanceCharge")
          .leaf("cbc:ChargeIndicator", "false")
          .amount("cbc:Amount", p.discount(), currency)
          .amount("cbc:BaseAmount", p.gross(), currency);
    }
  }
}
