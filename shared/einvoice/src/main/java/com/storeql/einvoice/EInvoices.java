package com.storeql.einvoice;

import java.util.ArrayList;
import java.util.List;

/**
 * The way in and out: read any EN 16931 document a supplier or access point sends — UBL, CII, or a
 * Factur-X/ZUGFeRD PDF with CII inside — and write one in any of the three.
 */
public final class EInvoices {

  /** The XML syntax an invoice was written in. */
  public enum Syntax {
    UBL,
    CII,
    /**
     * Poland's FA(3). Not an EN 16931 syntax: KSeF hands its invoices out in this structure and
     * takes no EN 16931 document, so a Polish buyer's invoices arrive in it and are read into the
     * same model — which is what lets everything downstream stay unaware of where a document came
     * from.
     */
    FA3
  }

  /** How the XML arrived: on its own, or inside a hybrid PDF. */
  public enum Container {
    XML,
    PDF
  }

  /**
   * A document that has been read.
   *
   * @param container XML, or PDF for a Factur-X or ZUGFeRD hybrid
   * @param syntax the XML syntax
   * @param embeddedFilename the attachment the XML was found under, for a PDF
   * @param invoice the invoice
   * @param emptyElements how many empty elements the XML had (PEPPOL-EN16931-R008)
   * @param numericIndicators how many allowance or charge indicators were written as {@code 1} or
   *     {@code 0}, which XML allows and Peppol does not (PEPPOL-EN16931-R043)
   */
  public record Received(
      Container container,
      Syntax syntax,
      String embeddedFilename,
      Invoice invoice,
      int emptyElements,
      int numericIndicators) {}

  private EInvoices() {}

  /**
   * Reads a document as it arrived.
   *
   * @throws EInvoiceFormatException when it is not an EN 16931 invoice this can read safely
   */
  public static Received read(byte[] document) {
    if (document == null || document.length == 0) {
      throw new EInvoiceFormatException("EMPTY", "the document is empty");
    }
    if (FacturX.isPdf(document)) {
      FacturX.Attachment attachment = FacturX.extract(document);
      return fromXml(Container.PDF, attachment.filename(), SafeXml.parse(attachment.xml()));
    }
    return fromXml(Container.XML, null, SafeXml.parse(document));
  }

  private static Received fromXml(Container container, String filename, SafeXml.Parsed parsed) {
    XmlElement root = parsed.root();
    if (UblReader.accepts(root)) {
      return new Received(
          container,
          Syntax.UBL,
          filename,
          UblReader.read(root),
          parsed.emptyLeaves(),
          numericIndicators(root, "ChargeIndicator"));
    }
    if (CiiReader.accepts(root)) {
      return new Received(
          container,
          Syntax.CII,
          filename,
          CiiReader.read(root),
          parsed.emptyLeaves(),
          numericIndicators(root, "Indicator"));
    }
    if (Fa3Reader.accepts(root)) {
      return new Received(container, Syntax.FA3, filename, Fa3Reader.read(root), 0, 0);
    }
    String found = "{" + root.namespace() + "}" + root.name();
    throw new EInvoiceFormatException(
        "NOT_AN_INVOICE",
        "the document is neither a UBL Invoice or CreditNote nor a UN/CEFACT Cross Industry Invoice;"
            + " nor Poland's FA(3); its root is "
            + (found.length() > 120 ? found.substring(0, 120) + "…" : found));
  }

  private static int numericIndicators(XmlElement element, String name) {
    int count =
        name.equals(element.name()) && ("1".equals(element.value()) || "0".equals(element.value()))
            ? 1
            : 0;
    for (XmlElement child : element.children()) count += numericIndicators(child, name);
    return count;
  }

  /** UBL 2.1: an Invoice, or a CreditNote for a credit note type code. */
  public static String toUbl(Invoice invoice) {
    return UblWriter.write(invoice);
  }

  /** UN/CEFACT Cross Industry Invoice D16B. */
  public static String toCii(Invoice invoice) {
    return CiiWriter.write(invoice);
  }

  /** A Factur-X PDF/A-3 carrying the CII. */
  public static byte[] toFacturX(Invoice invoice) {
    return FacturX.create(invoice);
  }

  /**
   * Every rule the document breaks, under the profile it declares, including the Peppol rule about
   * the XML itself that the model cannot see.
   */
  public static List<Violation> validate(Received received) {
    // FA(3) is checked against FA(3)'s rules, not EN 16931's: the structures carry different
    // fields,
    // and reporting a Polish invoice as breaking a Peppol rule it was never written to would be
    // noise a buyer has to learn to ignore — which is how a real violation gets missed.
    if (received.syntax() == Syntax.FA3) return Fa3.check(received.invoice());
    Rules.Profile profile = Rules.profileOf(received.invoice());
    List<Violation> out = new ArrayList<>(Rules.check(received.invoice(), profile));
    if (profile == Rules.Profile.PEPPOL_BIS_3
        && received.syntax() == Syntax.UBL
        && received.emptyElements() > 0) {
      out.add(
          Violation.fatal(
              "PEPPOL-EN16931-R008",
              "Document MUST not contain empty elements: " + received.emptyElements() + " found."));
    }
    if (profile == Rules.Profile.PEPPOL_BIS_3 && received.numericIndicators() > 0) {
      out.add(
          Violation.fatal(
              "PEPPOL-EN16931-R043",
              "Allowance/charge ChargeIndicator value MUST equal 'true' or 'false'"));
    }
    return List.copyOf(out);
  }
}
