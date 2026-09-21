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
import java.util.Map;

/**
 * Reads a UN/CEFACT Cross Industry Invoice (D16B) into the EN 16931 model, by the syntax binding of
 * EN 16931-3-3 — the XML a Factur-X or ZUGFeRD PDF carries, and one of the two XRechnung accepts.
 */
final class CiiReader {

  static final String RSM = "urn:un:unece:uncefact:data:standard:CrossIndustryInvoice:100";
  static final String RAM =
      "urn:un:unece:uncefact:data:standard:ReusableAggregateBusinessInformationEntity:100";
  static final String QDT = "urn:un:unece:uncefact:data:standard:QualifiedDataType:100";
  static final String UDT = "urn:un:unece:uncefact:data:standard:UnqualifiedDataType:100";

  private CiiReader() {}

  static boolean accepts(XmlElement root) {
    return RSM.equals(root.namespace()) && "CrossIndustryInvoice".equals(root.name());
  }

  static Invoice read(XmlElement doc) {
    XmlElement header = doc.child("ExchangedDocument");
    XmlElement transaction = doc.child("SupplyChainTradeTransaction");
    if (header == null || transaction == null) {
      throw new EInvoiceFormatException(
          "NOT_AN_INVOICE",
          "a Cross Industry Invoice needs an ExchangedDocument and a SupplyChainTradeTransaction");
    }
    XmlElement context = orEmpty(doc.child("ExchangedDocumentContext"));
    XmlElement agreement = orEmpty(transaction.child("ApplicableHeaderTradeAgreement"));
    XmlElement delivery = orEmpty(transaction.child("ApplicableHeaderTradeDelivery"));
    XmlElement settlement = orEmpty(transaction.child("ApplicableHeaderTradeSettlement"));
    String currency = settlement.value("InvoiceCurrencyCode");
    String taxCurrency = settlement.value("TaxCurrencyCode");

    List<Note> notes = new ArrayList<>();
    for (XmlElement n : header.all("IncludedNote")) {
      Note note = Values.orNull(new Note(n.value("SubjectCode"), n.value("Content")));
      if (note != null) notes.add(note);
    }

    Identifier invoicedObject = null;
    String tender = null;
    List<SupportingDocument> documents = new ArrayList<>();
    for (XmlElement ref : agreement.all("AdditionalReferencedDocument")) {
      String type = ref.value("TypeCode");
      if ("130".equals(type)) {
        String id = ref.value("IssuerAssignedID");
        if (id != null) invoicedObject = new Identifier(id, ref.value("ReferenceTypeCode"));
      } else if ("50".equals(type)) {
        tender = ref.value("IssuerAssignedID");
      } else {
        XmlElement binary = ref.child("AttachmentBinaryObject");
        documents.add(
            new SupportingDocument(
                ref.value("IssuerAssignedID"),
                ref.value("Name"),
                ref.value("URIID"),
                binary == null ? null : binary.value(),
                binary == null ? null : binary.attribute("mimeCode"),
                binary == null ? null : binary.attribute("filename")));
      }
    }

    List<VatBreakdown> breakdown = new ArrayList<>();
    LocalDate taxPoint = null;
    String taxPointCode = null;
    for (XmlElement t : settlement.all("ApplicableTradeTax")) {
      breakdown.add(
          new VatBreakdown(
              Values.decimal(t.value("BasisAmount"), "BT-116"),
              Values.decimal(t.value("CalculatedAmount"), "BT-117"),
              t.value("CategoryCode"),
              Values.decimal(t.value("RateApplicablePercent"), "BT-119"),
              t.value("ExemptionReason"),
              t.value("ExemptionReasonCode")));
      if (taxPoint == null) taxPoint = Values.ciiDate(t.at("TaxPointDate", "DateString"), "BT-7");
      if (taxPointCode == null) taxPointCode = t.value("DueDateTypeCode");
    }

    XmlElement summation =
        orEmpty(settlement.child("SpecifiedTradeSettlementHeaderMonetarySummation"));
    BigDecimal vat = null;
    BigDecimal vatInTaxCurrency = null;
    for (XmlElement amount : summation.all("TaxTotalAmount")) {
      String amountCurrency = amount.attribute("currencyID");
      boolean inTaxCurrency =
          taxCurrency != null
              && taxCurrency.equals(amountCurrency)
              && !taxCurrency.equals(currency);
      if (inTaxCurrency) {
        vatInTaxCurrency = Values.decimal(amount, "BT-111");
      } else if (vat == null) {
        vat = Values.decimal(amount, "BT-110");
      }
    }
    Totals totals =
        Values.orNull(
            new Totals(
                Values.decimal(summation.value("LineTotalAmount"), "BT-106"),
                Values.decimal(summation.value("AllowanceTotalAmount"), "BT-107"),
                Values.decimal(summation.value("ChargeTotalAmount"), "BT-108"),
                Values.decimal(summation.value("TaxBasisTotalAmount"), "BT-109"),
                vat,
                vatInTaxCurrency,
                Values.decimal(summation.value("GrandTotalAmount"), "BT-112"),
                Values.decimal(summation.value("TotalPrepaidAmount"), "BT-113"),
                Values.decimal(summation.value("RoundingAmount"), "BT-114"),
                Values.decimal(summation.value("DuePayableAmount"), "BT-115")));

    XmlElement terms = orEmpty(settlement.child("SpecifiedTradePaymentTerms"));
    List<PrecedingInvoice> preceding = new ArrayList<>();
    for (XmlElement r : settlement.all("InvoiceReferencedDocument")) {
      preceding.add(
          new PrecedingInvoice(
              r.value("IssuerAssignedID"),
              Values.ciiDate(r.at("FormattedIssueDateTime", "DateTimeString"), "BT-26")));
    }

    return new Invoice(
        context.value("GuidelineSpecifiedDocumentContextParameter", "ID"),
        context.value("BusinessProcessSpecifiedDocumentContextParameter", "ID"),
        header.value("ID"),
        Values.ciiDate(header.at("IssueDateTime", "DateTimeString"), "BT-2"),
        header.value("TypeCode"),
        currency,
        taxCurrency,
        taxPoint,
        taxPointCode,
        Values.ciiDate(terms.at("DueDateDateTime", "DateTimeString"), "BT-9"),
        agreement.value("BuyerReference"),
        agreement.value("SpecifiedProcuringProject", "ID"),
        agreement.value("ContractReferencedDocument", "IssuerAssignedID"),
        agreement.value("BuyerOrderReferencedDocument", "IssuerAssignedID"),
        agreement.value("SellerOrderReferencedDocument", "IssuerAssignedID"),
        delivery.value("ReceivingAdviceReferencedDocument", "IssuerAssignedID"),
        delivery.value("DespatchAdviceReferencedDocument", "IssuerAssignedID"),
        tender,
        invoicedObject,
        settlement.value("ReceivableSpecifiedTradeAccountingAccount", "ID"),
        terms.value("Description"),
        notes,
        preceding,
        party(agreement.child("SellerTradeParty")),
        party(agreement.child("BuyerTradeParty")),
        payee(settlement.child("PayeeTradeParty")),
        taxRepresentative(agreement.child("SellerTaxRepresentativeTradeParty")),
        delivery(delivery),
        period(settlement.child("BillingSpecifiedPeriod")),
        payment(settlement, terms),
        settlement.all("SpecifiedTradeAllowanceCharge").stream()
            .map(CiiReader::allowanceCharge)
            .toList(),
        totals,
        breakdown,
        documents,
        transaction.all("IncludedSupplyChainTradeLineItem").stream().map(CiiReader::line).toList());
  }

  private static XmlElement orEmpty(XmlElement e) {
    return e == null ? new XmlElement("", "", Map.of()) : e;
  }

  /** A party's own identifiers: {@code ram:ID} without a scheme, {@code ram:GlobalID} with one. */
  private static List<Identifier> identifiers(XmlElement p) {
    List<Identifier> ids = new ArrayList<>();
    for (XmlElement id : p.all("ID")) {
      if (id.value() != null) ids.add(new Identifier(id.value(), id.attribute("schemeID")));
    }
    for (XmlElement id : p.all("GlobalID")) {
      if (id.value() != null) ids.add(new Identifier(id.value(), id.attribute("schemeID")));
    }
    return ids;
  }

  private static Identifier identifier(XmlElement e) {
    if (e == null || e.value() == null) return null;
    return new Identifier(e.value(), e.attribute("schemeID"));
  }

  private static Party party(XmlElement p) {
    if (p == null) return null;
    String vatId = null;
    String taxRegistration = null;
    for (XmlElement registration : p.all("SpecifiedTaxRegistration")) {
      XmlElement id = registration.child("ID");
      if (id == null) continue;
      if ("FC".equals(id.attribute("schemeID"))) {
        taxRegistration = id.value();
      } else {
        vatId = id.value();
      }
    }
    return Values.orNull(
        new Party(
            p.value("Name"),
            p.value("SpecifiedLegalOrganization", "TradingBusinessName"),
            identifiers(p),
            identifier(p.at("SpecifiedLegalOrganization", "ID")),
            vatId,
            taxRegistration,
            p.value("Description"),
            identifier(p.at("URIUniversalCommunication", "URIID")),
            address(p.child("PostalTradeAddress")),
            contact(p.child("DefinedTradeContact"))));
  }

  private static Address address(XmlElement a) {
    if (a == null) return null;
    return Values.orNull(
        new Address(
            a.value("LineOne"),
            a.value("LineTwo"),
            a.value("LineThree"),
            a.value("CityName"),
            a.value("PostcodeCode"),
            a.value("CountrySubDivisionName"),
            a.value("CountryID")));
  }

  private static Contact contact(XmlElement c) {
    if (c == null) return null;
    return Values.orNull(
        new Contact(
            c.value("PersonName"),
            c.value("TelephoneUniversalCommunication", "CompleteNumber"),
            c.value("EmailURIUniversalCommunication", "URIID")));
  }

  private static Payee payee(XmlElement p) {
    if (p == null) return null;
    List<Identifier> ids = identifiers(p);
    return Values.orNull(
        new Payee(
            p.value("Name"),
            ids.isEmpty() ? null : ids.get(0),
            identifier(p.at("SpecifiedLegalOrganization", "ID"))));
  }

  private static TaxRepresentative taxRepresentative(XmlElement p) {
    if (p == null) return null;
    return Values.orNull(
        new TaxRepresentative(
            p.value("Name"),
            p.value("SpecifiedTaxRegistration", "ID"),
            address(p.child("PostalTradeAddress"))));
  }

  private static Delivery delivery(XmlElement d) {
    XmlElement shipTo = orEmpty(d.child("ShipToTradeParty"));
    List<Identifier> locations = identifiers(shipTo);
    return Values.orNull(
        new Delivery(
            shipTo.value("Name"),
            locations.isEmpty() ? null : locations.get(0),
            Values.ciiDate(
                d.at("ActualDeliverySupplyChainEvent", "OccurrenceDateTime", "DateTimeString"),
                "BT-72"),
            address(shipTo.child("PostalTradeAddress"))));
  }

  private static Period period(XmlElement p) {
    if (p == null) return null;
    return Values.orNull(
        new Period(
            Values.ciiDate(p.at("StartDateTime", "DateTimeString"), "period start date"),
            Values.ciiDate(p.at("EndDateTime", "DateTimeString"), "period end date")));
  }

  private static PaymentInstructions payment(XmlElement settlement, XmlElement terms) {
    String code = null;
    String text = null;
    Card card = null;
    String debited = null;
    List<CreditTransfer> transfers = new ArrayList<>();
    for (XmlElement m : settlement.all("SpecifiedTradeSettlementPaymentMeans")) {
      if (code == null) code = m.value("TypeCode");
      if (text == null) text = m.value("Information");
      XmlElement cardElement = m.child("ApplicableTradeSettlementFinancialCard");
      if (card == null && cardElement != null) {
        card =
            Values.orNull(
                new Card(cardElement.value("ID"), null, cardElement.value("CardholderName")));
      }
      if (debited == null) debited = m.value("PayerPartyDebtorFinancialAccount", "IBANID");
      XmlElement account = m.child("PayeePartyCreditorFinancialAccount");
      if (account != null) {
        String number = account.value("IBANID");
        CreditTransfer t =
            Values.orNull(
                new CreditTransfer(
                    number != null ? number : account.value("ProprietaryID"),
                    account.value("AccountName"),
                    m.value("PayeeSpecifiedCreditorFinancialInstitution", "BICID")));
        if (t != null) transfers.add(t);
      }
    }
    DirectDebit directDebit =
        Values.orNull(
            new DirectDebit(
                terms.value("DirectDebitMandateID"),
                settlement.value("CreditorReferenceID"),
                debited));
    return Values.orNull(
        new PaymentInstructions(
            code, text, settlement.value("PaymentReference"), transfers, card, directDebit));
  }

  private static AllowanceCharge allowanceCharge(XmlElement a) {
    return new AllowanceCharge(
        Values.indicator(a.value("ChargeIndicator", "Indicator"), "ChargeIndicator"),
        Values.decimal(a.value("ActualAmount"), "allowance or charge amount"),
        Values.decimal(a.value("BasisAmount"), "allowance or charge base amount"),
        Values.decimal(a.value("CalculationPercent"), "allowance or charge percentage"),
        a.value("CategoryTradeTax", "CategoryCode"),
        Values.decimal(
            a.value("CategoryTradeTax", "RateApplicablePercent"), "allowance or charge VAT rate"),
        a.value("Reason"),
        a.value("ReasonCode"));
  }

  private static Line line(XmlElement li) {
    XmlElement document = orEmpty(li.child("AssociatedDocumentLineDocument"));
    XmlElement agreement = orEmpty(li.child("SpecifiedLineTradeAgreement"));
    XmlElement settlement = orEmpty(li.child("SpecifiedLineTradeSettlement"));
    XmlElement quantity = li.at("SpecifiedLineTradeDelivery", "BilledQuantity");
    XmlElement gross = agreement.child("GrossPriceProductTradePrice");
    XmlElement net = agreement.child("NetPriceProductTradePrice");
    XmlElement base = net == null ? null : net.child("BasisQuantity");
    if (base == null && gross != null) base = gross.child("BasisQuantity");
    Price price =
        Values.orNull(
            new Price(
                net == null ? null : Values.decimal(net.value("ChargeAmount"), "BT-146"),
                gross == null
                    ? null
                    : Values.decimal(
                        gross.value("AppliedTradeAllowanceCharge", "ActualAmount"), "BT-147"),
                gross == null ? null : Values.decimal(gross.value("ChargeAmount"), "BT-148"),
                Values.decimal(base, "BT-149"),
                base == null ? null : base.attribute("unitCode")));
    Identifier object = null;
    for (XmlElement ref : settlement.all("AdditionalReferencedDocument")) {
      String id = ref.value("IssuerAssignedID");
      if (object == null && id != null) object = new Identifier(id, ref.value("ReferenceTypeCode"));
    }
    XmlElement tax = orEmpty(settlement.child("ApplicableTradeTax"));
    return new Line(
        document.value("LineID"),
        document.value("IncludedNote", "Content"),
        object,
        Values.decimal(quantity, "BT-129"),
        quantity == null ? null : quantity.attribute("unitCode"),
        Values.decimal(
            settlement.value("SpecifiedTradeSettlementLineMonetarySummation", "LineTotalAmount"),
            "BT-131"),
        agreement.value("BuyerOrderReferencedDocument", "LineID"),
        settlement.value("ReceivableSpecifiedTradeAccountingAccount", "ID"),
        period(settlement.child("BillingSpecifiedPeriod")),
        settlement.all("SpecifiedTradeAllowanceCharge").stream()
            .map(CiiReader::allowanceCharge)
            .toList(),
        price,
        tax.value("CategoryCode"),
        Values.decimal(tax.value("RateApplicablePercent"), "BT-152"),
        item(li.child("SpecifiedTradeProduct")));
  }

  private static Item item(XmlElement p) {
    if (p == null) return null;
    List<Classification> classifications = new ArrayList<>();
    for (XmlElement c : p.all("DesignatedProductClassification")) {
      XmlElement code = c.child("ClassCode");
      if (code != null && code.value() != null) {
        classifications.add(
            new Classification(
                code.value(), code.attribute("listID"), code.attribute("listVersionID")));
      }
    }
    List<Attribute> attributes = new ArrayList<>();
    for (XmlElement a : p.all("ApplicableProductCharacteristic")) {
      attributes.add(new Attribute(a.value("Description"), a.value("Value")));
    }
    return Values.orNull(
        new Item(
            p.value("Name"),
            p.value("Description"),
            p.value("SellerAssignedID"),
            p.value("BuyerAssignedID"),
            identifier(p.child("GlobalID")),
            classifications,
            p.value("OriginTradeCountry", "ID"),
            attributes));
  }
}
