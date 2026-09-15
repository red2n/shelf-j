package com.shelfj.einvoice;

import com.shelfj.einvoice.Invoice.Address;
import com.shelfj.einvoice.Invoice.AllowanceCharge;
import com.shelfj.einvoice.Invoice.Attribute;
import com.shelfj.einvoice.Invoice.Card;
import com.shelfj.einvoice.Invoice.Classification;
import com.shelfj.einvoice.Invoice.Contact;
import com.shelfj.einvoice.Invoice.CreditTransfer;
import com.shelfj.einvoice.Invoice.Delivery;
import com.shelfj.einvoice.Invoice.DirectDebit;
import com.shelfj.einvoice.Invoice.Identifier;
import com.shelfj.einvoice.Invoice.Item;
import com.shelfj.einvoice.Invoice.Line;
import com.shelfj.einvoice.Invoice.Note;
import com.shelfj.einvoice.Invoice.Party;
import com.shelfj.einvoice.Invoice.Payee;
import com.shelfj.einvoice.Invoice.PaymentInstructions;
import com.shelfj.einvoice.Invoice.Period;
import com.shelfj.einvoice.Invoice.PrecedingInvoice;
import com.shelfj.einvoice.Invoice.Price;
import com.shelfj.einvoice.Invoice.SupportingDocument;
import com.shelfj.einvoice.Invoice.TaxRepresentative;
import com.shelfj.einvoice.Invoice.Totals;
import com.shelfj.einvoice.Invoice.VatBreakdown;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Writes the EN 16931 model as a UN/CEFACT Cross Industry Invoice (D16B), in the element order the
 * D16B schema requires — the XML inside a Factur-X.
 */
final class CiiWriter {

  /** CII requires a project a name as well as an identifier; EN 16931 carries only the latter. */
  static final String PROJECT_NAME = "Project reference";

  private static final Pattern IBAN = Pattern.compile("[A-Z]{2}[0-9]{2}[A-Z0-9]{11,30}");

  private CiiWriter() {}

  static String write(Invoice inv) {
    Map<String, String> namespaces = new LinkedHashMap<>();
    namespaces.put("rsm", CiiReader.RSM);
    namespaces.put("qdt", CiiReader.QDT);
    namespaces.put("ram", CiiReader.RAM);
    namespaces.put("udt", CiiReader.UDT);
    XmlNode d = XmlNode.root("rsm:CrossIndustryInvoice", namespaces);

    XmlNode context = d.group("rsm:ExchangedDocumentContext").required();
    context
        .group("ram:BusinessProcessSpecifiedDocumentContextParameter")
        .leaf("ram:ID", inv.profileId());
    context
        .group("ram:GuidelineSpecifiedDocumentContextParameter")
        .required()
        .leaf("ram:ID", inv.customizationId());

    XmlNode header = d.group("rsm:ExchangedDocument").required();
    header
        .leaf("ram:ID", inv.number())
        .leaf("ram:TypeCode", inv.typeCode())
        .ciiDate("ram:IssueDateTime", "udt:DateTimeString", inv.issueDate());
    for (Note n : inv.notes()) {
      header
          .group("ram:IncludedNote")
          .leaf("ram:Content", n.text())
          .leaf("ram:SubjectCode", n.subjectCode());
    }

    XmlNode transaction = d.group("rsm:SupplyChainTradeTransaction").required();
    for (Line l : inv.lines()) line(transaction.group("ram:IncludedSupplyChainTradeLineItem"), l);
    agreement(transaction.group("ram:ApplicableHeaderTradeAgreement").required(), inv);
    delivery(transaction.group("ram:ApplicableHeaderTradeDelivery").required(), inv);
    settlement(transaction.group("ram:ApplicableHeaderTradeSettlement").required(), inv);
    return d.toXml();
  }

  private static void agreement(XmlNode a, Invoice inv) {
    a.leaf("ram:BuyerReference", inv.buyerReference());
    party(a.group("ram:SellerTradeParty").required(), inv.seller());
    party(a.group("ram:BuyerTradeParty").required(), inv.buyer());
    TaxRepresentative rep = inv.taxRepresentative();
    if (rep != null) {
      XmlNode t = a.group("ram:SellerTaxRepresentativeTradeParty");
      t.leaf("ram:Name", rep.name());
      address(t.group("ram:PostalTradeAddress"), rep.address());
      t.group("ram:SpecifiedTaxRegistration").leaf("ram:ID", rep.vatId(), "schemeID", "VA");
    }
    a.group("ram:SellerOrderReferencedDocument")
        .leaf("ram:IssuerAssignedID", inv.salesOrderReference());
    a.group("ram:BuyerOrderReferencedDocument").leaf("ram:IssuerAssignedID", inv.orderReference());
    a.group("ram:ContractReferencedDocument").leaf("ram:IssuerAssignedID", inv.contractReference());
    for (SupportingDocument s : inv.supportingDocuments()) {
      XmlNode r = a.group("ram:AdditionalReferencedDocument");
      r.leaf("ram:IssuerAssignedID", s.reference())
          .leaf("ram:URIID", s.location())
          .leaf("ram:TypeCode", "916")
          .leaf("ram:Name", s.description());
      r.element("ram:AttachmentBinaryObject", s.attachmentBase64())
          .attribute("mimeCode", s.mimeCode())
          .attribute("filename", s.filename());
    }
    if (inv.tenderReference() != null) {
      a.group("ram:AdditionalReferencedDocument")
          .leaf("ram:IssuerAssignedID", inv.tenderReference())
          .leaf("ram:TypeCode", "50");
    }
    Identifier object = inv.invoicedObject();
    if (object != null) {
      a.group("ram:AdditionalReferencedDocument")
          .leaf("ram:IssuerAssignedID", object.id())
          .leaf("ram:TypeCode", "130")
          .leaf("ram:ReferenceTypeCode", object.scheme());
    }
    if (inv.projectReference() != null) {
      a.group("ram:SpecifiedProcuringProject")
          .leaf("ram:ID", inv.projectReference())
          .leaf("ram:Name", PROJECT_NAME);
    }
  }

  private static void identifiers(XmlNode n, List<Identifier> ids) {
    for (Identifier id : ids) {
      if (id.scheme() == null) n.leaf("ram:ID", id.id());
    }
    for (Identifier id : ids) {
      if (id.scheme() != null) n.leaf("ram:GlobalID", id.id(), "schemeID", id.scheme());
    }
  }

  private static void party(XmlNode n, Party p) {
    if (p == null) return;
    identifiers(n, p.identifiers());
    n.leaf("ram:Name", p.name()).leaf("ram:Description", p.additionalLegalInfo());
    XmlNode legal = n.group("ram:SpecifiedLegalOrganization");
    Identifier registration = p.legalRegistration();
    if (registration != null) {
      legal.leaf("ram:ID", registration.id(), "schemeID", registration.scheme());
    }
    legal.leaf("ram:TradingBusinessName", p.tradingName());
    Contact c = p.contact();
    if (c != null) {
      XmlNode contact = n.group("ram:DefinedTradeContact");
      contact.leaf("ram:PersonName", c.name());
      contact.group("ram:TelephoneUniversalCommunication").leaf("ram:CompleteNumber", c.phone());
      contact.group("ram:EmailURIUniversalCommunication").leaf("ram:URIID", c.email());
    }
    address(n.group("ram:PostalTradeAddress"), p.address());
    Identifier endpoint = p.electronicAddress();
    if (endpoint != null) {
      n.group("ram:URIUniversalCommunication")
          .leaf("ram:URIID", endpoint.id(), "schemeID", endpoint.scheme());
    }
    n.group("ram:SpecifiedTaxRegistration").leaf("ram:ID", p.vatId(), "schemeID", "VA");
    n.group("ram:SpecifiedTaxRegistration").leaf("ram:ID", p.taxRegistrationId(), "schemeID", "FC");
  }

  private static void address(XmlNode a, Address address) {
    if (address == null) return;
    a.leaf("ram:PostcodeCode", address.postcode())
        .leaf("ram:LineOne", address.line1())
        .leaf("ram:LineTwo", address.line2())
        .leaf("ram:LineThree", address.line3())
        .leaf("ram:CityName", address.city())
        .leaf("ram:CountryID", address.country())
        .leaf("ram:CountrySubDivisionName", address.subdivision());
  }

  private static void delivery(XmlNode n, Invoice inv) {
    Delivery delivery = inv.delivery();
    if (delivery != null) {
      XmlNode shipTo = n.group("ram:ShipToTradeParty");
      Identifier location = delivery.location();
      if (location != null) identifiers(shipTo, List.of(location));
      shipTo.leaf("ram:Name", delivery.partyName());
      address(shipTo.group("ram:PostalTradeAddress"), delivery.address());
      n.group("ram:ActualDeliverySupplyChainEvent")
          .ciiDate("ram:OccurrenceDateTime", "udt:DateTimeString", delivery.actualDate());
    }
    n.group("ram:DespatchAdviceReferencedDocument")
        .leaf("ram:IssuerAssignedID", inv.despatchAdviceReference());
    n.group("ram:ReceivingAdviceReferencedDocument")
        .leaf("ram:IssuerAssignedID", inv.receivingAdviceReference());
  }

  private static void settlement(XmlNode s, Invoice inv) {
    PaymentInstructions payment = inv.payment();
    DirectDebit directDebit = payment == null ? null : payment.directDebit();
    s.leaf("ram:CreditorReferenceID", directDebit == null ? null : directDebit.creditorId())
        .leaf("ram:PaymentReference", payment == null ? null : payment.remittanceInformation())
        .leaf("ram:TaxCurrencyCode", inv.taxCurrency())
        .leaf("ram:InvoiceCurrencyCode", inv.currency());
    Payee payee = inv.payee();
    if (payee != null) {
      XmlNode p = s.group("ram:PayeeTradeParty");
      if (payee.identifier() != null) identifiers(p, List.of(payee.identifier()));
      p.leaf("ram:Name", payee.name());
      Identifier registration = payee.legalRegistration();
      if (registration != null) {
        p.group("ram:SpecifiedLegalOrganization")
            .leaf("ram:ID", registration.id(), "schemeID", registration.scheme());
      }
    }
    if (payment != null) means(s, payment);
    for (VatBreakdown b : inv.vatBreakdown()) {
      XmlNode t = s.group("ram:ApplicableTradeTax");
      t.leaf("ram:CalculatedAmount", b.taxAmount())
          .leaf("ram:TypeCode", "VAT")
          .leaf("ram:ExemptionReason", b.exemptionReason())
          .leaf("ram:BasisAmount", b.taxableAmount())
          .leaf("ram:CategoryCode", b.category())
          .leaf("ram:ExemptionReasonCode", b.exemptionReasonCode())
          .ciiDate("ram:TaxPointDate", "udt:DateString", inv.taxPointDate())
          .leaf("ram:DueDateTypeCode", inv.taxPointDateCode())
          .leaf("ram:RateApplicablePercent", b.rate());
    }
    period(s.group("ram:BillingSpecifiedPeriod"), inv.invoicingPeriod());
    for (AllowanceCharge ac : inv.allowanceCharges()) {
      allowanceCharge(s.group("ram:SpecifiedTradeAllowanceCharge"), ac, true);
    }
    s.group("ram:SpecifiedTradePaymentTerms")
        .leaf("ram:Description", inv.paymentTerms())
        .ciiDate("ram:DueDateDateTime", "udt:DateTimeString", inv.dueDate())
        .leaf(
            "ram:DirectDebitMandateID",
            directDebit == null ? null : directDebit.mandateReference());
    XmlNode summation = s.group("ram:SpecifiedTradeSettlementHeaderMonetarySummation").required();
    Totals t = inv.totals();
    if (t != null) {
      summation
          .leaf("ram:LineTotalAmount", t.lineNet())
          .leaf("ram:ChargeTotalAmount", t.charges())
          .leaf("ram:AllowanceTotalAmount", t.allowances())
          .leaf("ram:TaxBasisTotalAmount", t.withoutVat())
          .amount("ram:TaxTotalAmount", t.vat(), inv.currency())
          .amount("ram:TaxTotalAmount", t.vatInTaxCurrency(), inv.taxCurrency())
          .leaf("ram:RoundingAmount", t.rounding())
          .leaf("ram:GrandTotalAmount", t.withVat())
          .leaf("ram:TotalPrepaidAmount", t.paid())
          .leaf("ram:DuePayableAmount", t.payable());
    }
    for (PrecedingInvoice p : inv.precedingInvoices()) {
      s.group("ram:InvoiceReferencedDocument")
          .leaf("ram:IssuerAssignedID", p.number())
          .ciiDate("ram:FormattedIssueDateTime", "qdt:DateTimeString", p.issueDate());
    }
    s.group("ram:ReceivableSpecifiedTradeAccountingAccount")
        .leaf("ram:ID", inv.buyerAccountingReference());
  }

  /** One payment means per account to pay into, as {@link UblWriter} writes them. */
  private static void means(XmlNode s, PaymentInstructions p) {
    List<CreditTransfer> transfers = p.creditTransfers();
    int count = Math.max(1, transfers.size());
    for (int i = 0; i < count; i++) {
      XmlNode m = s.group("ram:SpecifiedTradeSettlementPaymentMeans");
      m.leaf("ram:TypeCode", p.meansCode()).leaf("ram:Information", p.meansText());
      Card card = p.card();
      if (i == 0 && card != null) {
        m.group("ram:ApplicableTradeSettlementFinancialCard")
            .leaf("ram:ID", card.primaryAccountNumber())
            .leaf("ram:CardholderName", card.holderName());
      }
      DirectDebit dd = p.directDebit();
      if (i == 0 && dd != null) {
        m.group("ram:PayerPartyDebtorFinancialAccount").leaf("ram:IBANID", dd.debitedAccount());
      }
      if (i < transfers.size()) {
        CreditTransfer t = transfers.get(i);
        boolean iban = t.account() != null && IBAN.matcher(t.account().replace(" ", "")).matches();
        m.group("ram:PayeePartyCreditorFinancialAccount")
            .leaf("ram:IBANID", iban ? t.account() : null)
            .leaf("ram:AccountName", t.accountName())
            .leaf("ram:ProprietaryID", iban ? null : t.account());
        m.group("ram:PayeeSpecifiedCreditorFinancialInstitution")
            .leaf("ram:BICID", t.serviceProvider());
      }
    }
  }

  private static void period(XmlNode n, Period p) {
    if (p == null) return;
    n.ciiDate("ram:StartDateTime", "udt:DateTimeString", p.start())
        .ciiDate("ram:EndDateTime", "udt:DateTimeString", p.end());
  }

  private static void allowanceCharge(XmlNode n, AllowanceCharge ac, boolean document) {
    n.group("ram:ChargeIndicator").leaf("udt:Indicator", Boolean.toString(ac.charge()));
    n.leaf("ram:CalculationPercent", ac.percentage())
        .leaf("ram:BasisAmount", ac.baseAmount())
        .leaf("ram:ActualAmount", ac.amount())
        .leaf("ram:ReasonCode", ac.reasonCode())
        .leaf("ram:Reason", ac.reason());
    if (document && (ac.vatCategory() != null || ac.vatRate() != null)) {
      n.group("ram:CategoryTradeTax")
          .leaf("ram:TypeCode", "VAT")
          .leaf("ram:CategoryCode", ac.vatCategory())
          .leaf("ram:RateApplicablePercent", ac.vatRate());
    }
  }

  private static void line(XmlNode li, Line l) {
    XmlNode document = li.group("ram:AssociatedDocumentLineDocument").required();
    document.leaf("ram:LineID", l.id());
    document.group("ram:IncludedNote").leaf("ram:Content", l.note());

    XmlNode product = li.group("ram:SpecifiedTradeProduct").required();
    Item item = l.item();
    if (item != null) {
      Identifier standard = item.standardId();
      if (standard != null)
        product.leaf("ram:GlobalID", standard.id(), "schemeID", standard.scheme());
      product
          .leaf("ram:SellerAssignedID", item.sellersId())
          .leaf("ram:BuyerAssignedID", item.buyersId())
          .leaf("ram:Name", item.name())
          .leaf("ram:Description", item.description());
      for (Attribute a : item.attributes()) {
        product
            .group("ram:ApplicableProductCharacteristic")
            .leaf("ram:Description", a.name())
            .leaf("ram:Value", a.value());
      }
      for (Classification c : item.classifications()) {
        product
            .group("ram:DesignatedProductClassification")
            .element("ram:ClassCode", c.code())
            .attribute("listID", c.scheme())
            .attribute("listVersionID", c.schemeVersion());
      }
      product.group("ram:OriginTradeCountry").leaf("ram:ID", item.originCountry());
    }

    XmlNode agreement = li.group("ram:SpecifiedLineTradeAgreement").required();
    agreement.group("ram:BuyerOrderReferencedDocument").leaf("ram:LineID", l.orderLineReference());
    Price price = l.price();
    // CII carries a price discount (BT-147) only inside a gross price, whose amount its schema
    // requires; a discount given without one is written against net plus discount, which is what
    // PEPPOL-EN16931-R046 says the gross price is.
    BigDecimal grossPrice =
        price == null
            ? null
            : price.gross() != null
                ? price.gross()
                : price.discount() != null && price.net() != null
                    ? price.net().add(price.discount())
                    : null;
    if (grossPrice != null) {
      XmlNode gross = agreement.group("ram:GrossPriceProductTradePrice");
      gross.leaf("ram:ChargeAmount", grossPrice);
      if (price.discount() != null) {
        XmlNode discount = gross.group("ram:AppliedTradeAllowanceCharge");
        discount.group("ram:ChargeIndicator").leaf("udt:Indicator", "false");
        discount.leaf("ram:ActualAmount", price.discount());
      }
    }
    XmlNode net = agreement.group("ram:NetPriceProductTradePrice").required();
    if (price != null) {
      net.leaf("ram:ChargeAmount", price.net())
          .quantity("ram:BasisQuantity", price.baseQuantity(), price.baseQuantityUnit());
    }

    li.group("ram:SpecifiedLineTradeDelivery")
        .required()
        .quantity("ram:BilledQuantity", l.quantity(), l.unitCode());

    XmlNode settlement = li.group("ram:SpecifiedLineTradeSettlement").required();
    if (l.vatCategory() != null || l.vatRate() != null) {
      settlement
          .group("ram:ApplicableTradeTax")
          .leaf("ram:TypeCode", "VAT")
          .leaf("ram:CategoryCode", l.vatCategory())
          .leaf("ram:RateApplicablePercent", l.vatRate());
    }
    period(settlement.group("ram:BillingSpecifiedPeriod"), l.period());
    for (AllowanceCharge ac : l.allowanceCharges()) {
      allowanceCharge(settlement.group("ram:SpecifiedTradeAllowanceCharge"), ac, false);
    }
    settlement
        .group("ram:SpecifiedTradeSettlementLineMonetarySummation")
        .required()
        .leaf("ram:LineTotalAmount", l.netAmount());
    Identifier object = l.objectId();
    if (object != null) {
      settlement
          .group("ram:AdditionalReferencedDocument")
          .leaf("ram:IssuerAssignedID", object.id())
          .leaf("ram:TypeCode", "130")
          .leaf("ram:ReferenceTypeCode", object.scheme());
    }
    settlement
        .group("ram:ReceivableSpecifiedTradeAccountingAccount")
        .leaf("ram:ID", l.accountingReference());
  }
}
