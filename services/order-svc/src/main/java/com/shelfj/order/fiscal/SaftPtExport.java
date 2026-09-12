package com.shelfj.order.fiscal;

import com.shelfj.order.domain.Domain.FiscalReceipt;
import com.shelfj.order.repo.FiscalReceiptRepository.RegisterLine;
import com.shelfj.order.repo.FiscalReceiptRepository.RegisterTender;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

/**
 * The Portuguese audit file (18.5): SAF-T (PT) 1.04_01 (Portaria 302/2016), the sales-invoice
 * section, with each document's signature ({@code Hash}) and ATCUD as issued, the customer the till
 * sells to and the products the lines name.
 *
 * <p>What it assumes, and says: the customer of every till sale is the final consumer (NIF
 * 999999990); the tax region is the continent; the software's producer tax id is the tenant's own
 * unless a deployment configures another.
 */
public final class SaftPtExport {

  private SaftPtExport() {}

  public static final String NAMESPACE = "urn:OECD:StandardAuditFile-Tax:PT_1.04_01";
  public static final String VERSION = "1.04_01";

  private static final DateTimeFormatter DATE = DateTimeFormatter.ISO_LOCAL_DATE;
  private static final DateTimeFormatter ENTRY =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

  /** The continental rates and their SAF-T codes; a line's rate maps to the nearest. */
  private static final Map<String, BigDecimal> PT_RATES =
      Map.of(
          "NOR", new BigDecimal("23.00"),
          "INT", new BigDecimal("13.00"),
          "RED", new BigDecimal("6.00"),
          "ISE", new BigDecimal("0.00"));

  /** The SAF-T tax code nearest a rate. */
  public static String taxCode(BigDecimal ratePercent) {
    String best = "ISE";
    BigDecimal bestDistance = null;
    for (var e : PT_RATES.entrySet()) {
      BigDecimal d = e.getValue().subtract(ratePercent).abs();
      if (bestDistance == null || d.compareTo(bestDistance) < 0) {
        bestDistance = d;
        best = e.getKey();
      }
    }
    return best;
  }

  /** The SAF-T payment mechanism for a tender method. */
  public static String paymentMechanism(String method) {
    if (method == null) {
      return "OU";
    }
    return switch (method.toUpperCase(java.util.Locale.ROOT)) {
      case "CASH" -> "NU";
      case "CARD" -> "CC";
      default -> "OU";
    };
  }

  /**
   * Writes the file.
   *
   * @param s the register
   * @param producerTaxId the software producer's tax id for the header
   * @param productVersion the software version for the header
   * @return the XML document
   */
  public static String write(RegisterSnapshot s, String producerTaxId, String productVersion) {
    StringWriter out = new StringWriter();
    try {
      XMLStreamWriter w = XMLOutputFactory.newInstance().createXMLStreamWriter(out);
      w.writeStartDocument("UTF-8", "1.0");
      w.writeStartElement("AuditFile");
      w.writeDefaultNamespace(NAMESPACE);
      w.writeNamespace("xsi", "http://www.w3.org/2001/XMLSchema-instance");
      header(w, s, producerTaxId, productVersion);
      masterFiles(w, s);
      sourceDocuments(w, s);
      w.writeEndElement();
      w.writeEndDocument();
      w.close();
    } catch (XMLStreamException e) {
      throw new IllegalStateException("SAF-T write failed", e);
    }
    return out.toString();
  }

  private static void header(
      XMLStreamWriter w, RegisterSnapshot s, String producerTaxId, String productVersion)
      throws XMLStreamException {
    List<FiscalReceipt> docs = s.documents();
    LocalDate start =
        docs.isEmpty()
            ? LocalDate.of(Integer.parseInt(s.period().substring(0, 4)), 1, 1)
            : docs.get(0).issuedAt().atZone(ZoneOffset.UTC).toLocalDate();
    LocalDate end =
        docs.isEmpty()
            ? LocalDate.of(Integer.parseInt(s.period().substring(0, 4)), 12, 31)
            : docs.get(docs.size() - 1).issuedAt().atZone(ZoneOffset.UTC).toLocalDate();
    String nif = nz(s.settings().taxRegistrationNumber());
    w.writeStartElement("Header");
    el(w, "AuditFileVersion", VERSION);
    el(w, "CompanyID", nif);
    el(w, "TaxRegistrationNumber", nif);
    el(w, "TaxAccountingBasis", "F");
    el(w, "CompanyName", nz(s.business().legalName()));
    w.writeStartElement("CompanyAddress");
    el(w, "AddressDetail", join(s.store().line1(), s.store().line2()));
    el(w, "City", nz(s.store().city()));
    el(w, "PostalCode", nz(s.store().postalCode()));
    el(w, "Country", "PT");
    w.writeEndElement();
    el(w, "FiscalYear", s.period().substring(0, 4));
    el(w, "StartDate", DATE.format(start));
    el(w, "EndDate", DATE.format(end));
    el(w, "CurrencyCode", s.currency());
    el(w, "DateCreated", DATE.format(s.generatedAt().atZone(ZoneOffset.UTC).toLocalDate()));
    el(w, "TaxEntity", s.store().code());
    el(
        w,
        "ProductCompanyTaxID",
        producerTaxId == null || producerTaxId.isBlank() ? nif : producerTaxId);
    el(
        w,
        "SoftwareCertificateNumber",
        s.settings().certificateNumber() == null || s.settings().certificateNumber().isBlank()
            ? "0"
            : s.settings().certificateNumber());
    el(w, "ProductID", "Shelf-J/Shelf-J");
    el(w, "ProductVersion", productVersion);
    w.writeEndElement();
  }

  private static void masterFiles(XMLStreamWriter w, RegisterSnapshot s) throws XMLStreamException {
    w.writeStartElement("MasterFiles");
    w.writeStartElement("Customer");
    el(w, "CustomerID", "CF");
    el(w, "AccountID", "Desconhecido");
    el(w, "CustomerTaxID", "999999990");
    el(w, "CompanyName", "Consumidor final");
    w.writeStartElement("BillingAddress");
    el(w, "AddressDetail", "Desconhecido");
    el(w, "City", "Desconhecido");
    el(w, "PostalCode", "0000-000");
    el(w, "Country", "PT");
    w.writeEndElement();
    el(w, "SelfBillingIndicator", "0");
    w.writeEndElement();

    Map<UUID, RegisterSnapshot.ProductName> products = new LinkedHashMap<>();
    for (RegisterLine l : s.lines()) {
      products.putIfAbsent(l.variantId(), s.productNames().get(l.variantId()));
    }
    for (var e : products.entrySet()) {
      var p = e.getValue();
      String code =
          p == null || p.sku() == null || p.sku().isBlank() ? e.getKey().toString() : p.sku();
      w.writeStartElement("Product");
      el(w, "ProductType", "P");
      el(w, "ProductCode", code);
      el(w, "ProductDescription", p == null || p.name() == null ? e.getKey().toString() : p.name());
      el(w, "ProductNumberCode", code);
      w.writeEndElement();
    }

    Map<String, BigDecimal> used = new TreeMap<>();
    for (FiscalReceipt d : s.documents()) {
      for (RegisterLine l : s.linesOf(d.number())) {
        String code = taxCode(s.ratePercentOf(l, d));
        used.putIfAbsent(code, PT_RATES.get(code));
      }
    }
    if (used.isEmpty()) {
      used.put("NOR", PT_RATES.get("NOR"));
    }
    w.writeStartElement("TaxTable");
    for (var e : used.entrySet()) {
      w.writeStartElement("TaxTableEntry");
      el(w, "TaxType", "IVA");
      el(w, "TaxCountryRegion", "PT");
      el(w, "TaxCode", e.getKey());
      el(
          w,
          "Description",
          switch (e.getKey()) {
            case "NOR" -> "Taxa normal";
            case "INT" -> "Taxa intermédia";
            case "RED" -> "Taxa reduzida";
            default -> "Isento";
          });
      el(w, "TaxPercentage", money(e.getValue()));
      w.writeEndElement();
    }
    w.writeEndElement();
    w.writeEndElement();
  }

  private static void sourceDocuments(XMLStreamWriter w, RegisterSnapshot s)
      throws XMLStreamException {
    BigDecimal totalCredit = BigDecimal.ZERO;
    for (FiscalReceipt d : s.documents()) {
      if (d.voidedAt() == null) {
        totalCredit = totalCredit.add(d.grossTotal().subtract(d.taxTotal()));
      }
    }
    w.writeStartElement("SourceDocuments");
    w.writeStartElement("SalesInvoices");
    el(w, "NumberOfEntries", Integer.toString(s.documents().size()));
    el(w, "TotalDebit", "0.00");
    el(w, "TotalCredit", money(totalCredit));
    for (FiscalReceipt d : s.documents()) {
      invoice(w, s, d);
    }
    w.writeEndElement();
    w.writeEndElement();
  }

  private static void invoice(XMLStreamWriter w, RegisterSnapshot s, FiscalReceipt d)
      throws XMLStreamException {
    var pt = d.pt();
    LocalDate date = d.issuedAt().atZone(ZoneOffset.UTC).toLocalDate();
    String sourceId = d.issuedBy() == null ? "system" : d.issuedBy().toString();
    w.writeStartElement("Invoice");
    el(
        w,
        "InvoiceNo",
        pt == null ? PtSignature.invoiceNo(d.fullNumber(), d.number()) : pt.invoiceNo());
    el(w, "ATCUD", pt == null || pt.atcud() == null ? "0-" + d.number() : pt.atcud());
    w.writeStartElement("DocumentStatus");
    el(w, "InvoiceStatus", d.voidedAt() == null ? "N" : "A");
    el(w, "InvoiceStatusDate", ENTRY.format(d.voidedAt() == null ? d.issuedAt() : d.voidedAt()));
    if (d.voidedAt() != null && d.voidReason() != null) {
      el(w, "Reason", d.voidReason());
    }
    el(w, "SourceID", sourceId);
    el(w, "SourceBilling", "P");
    w.writeEndElement();
    el(w, "Hash", pt == null ? "0" : pt.hash());
    el(w, "HashControl", pt == null ? "0" : pt.hashControl());
    el(w, "Period", Integer.toString(date.getMonthValue()));
    el(w, "InvoiceDate", DATE.format(date));
    el(w, "InvoiceType", "FS");
    w.writeStartElement("SpecialRegimes");
    el(w, "SelfBillingIndicator", "0");
    el(w, "CashVATSchemeIndicator", "0");
    el(w, "ThirdPartiesBillingIndicator", "0");
    w.writeEndElement();
    el(w, "SourceID", sourceId);
    el(w, "SystemEntryDate", ENTRY.format(d.issuedAt()));
    el(w, "CustomerID", "CF");

    int n = 0;
    BigDecimal net = BigDecimal.ZERO;
    BigDecimal tax = BigDecimal.ZERO;
    for (RegisterLine l : s.linesOf(d.number())) {
      n++;
      BigDecimal vat = s.vatOf(l, d);
      BigDecimal rate = s.ratePercentOf(l, d);
      String code = taxCode(rate);
      var p = s.productNames().get(l.variantId());
      String productCode =
          p == null || p.sku() == null || p.sku().isBlank() ? l.variantId().toString() : p.sku();
      w.writeStartElement("Line");
      el(w, "LineNumber", Integer.toString(n));
      el(w, "ProductCode", productCode);
      el(w, "ProductDescription", s.nameOf(l));
      el(w, "Quantity", l.qty().setScale(3, RoundingMode.HALF_UP).toPlainString());
      el(w, "UnitOfMeasure", p == null || p.unit() == null ? "UN" : p.unit());
      el(w, "UnitPrice", l.unitPrice().setScale(4, RoundingMode.HALF_UP).toPlainString());
      el(w, "TaxPointDate", DATE.format(date));
      el(w, "Description", s.nameOf(l));
      el(w, "CreditAmount", money(l.lineTotal()));
      w.writeStartElement("Tax");
      el(w, "TaxType", "IVA");
      el(w, "TaxCountryRegion", "PT");
      el(w, "TaxCode", code);
      el(w, "TaxPercentage", money(PT_RATES.get(code)));
      w.writeEndElement();
      if ("ISE".equals(code)) {
        el(w, "TaxExemptionReason", "Isento artigo 9.º do CIVA");
        el(w, "TaxExemptionCode", "M07");
      }
      w.writeEndElement();
      net = net.add(l.lineTotal());
      tax = tax.add(vat);
    }

    w.writeStartElement("DocumentTotals");
    el(w, "TaxPayable", money(d.taxTotal()));
    el(w, "NetTotal", money(d.grossTotal().subtract(d.taxTotal())));
    el(w, "GrossTotal", money(d.grossTotal()));
    List<RegisterTender> tenders = s.tendersOf(d.number());
    if (tenders.isEmpty()) {
      var o = s.orders().get(d.number());
      tenders =
          List.of(
              new RegisterTender(d.number(), o == null ? null : o.paymentMethod(), d.grossTotal()));
    }
    for (RegisterTender t : tenders) {
      w.writeStartElement("Payment");
      el(w, "PaymentMechanism", paymentMechanism(t.method()));
      el(w, "PaymentAmount", money(t.amount()));
      el(w, "PaymentDate", DATE.format(date));
      w.writeEndElement();
    }
    w.writeEndElement();
    w.writeEndElement();
  }

  private static void el(XMLStreamWriter w, String name, String text) throws XMLStreamException {
    w.writeStartElement(name);
    w.writeCharacters(text == null ? "" : text);
    w.writeEndElement();
  }

  private static String money(BigDecimal v) {
    return v.setScale(2, RoundingMode.HALF_UP).toPlainString();
  }

  private static String nz(String v) {
    return v == null ? "" : v;
  }

  private static String join(String a, String b) {
    if (a == null || a.isBlank()) {
      return nz(b);
    }
    return b == null || b.isBlank() ? a : a + ", " + b;
  }
}
