package com.storeql.einvoice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.storeql.einvoice.EInvoices.Received;
import com.storeql.einvoice.EInvoices.Syntax;
import java.io.StringReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Every official example read, written in both syntaxes, validated against the syntax's own XSD and
 * read back as the same invoice: the model loses nothing a supplier sent, and what this writes a
 * receiving access point's schema accepts.
 */
class RoundTripTest {

  private static Schema ublInvoice;
  private static Schema ublCreditNote;
  private static Schema cii;

  @BeforeAll
  static void schemas() throws Exception {
    ublInvoice = schema("/xsd/ubl/maindoc/UBL-Invoice-2.1.xsd");
    ublCreditNote = schema("/xsd/ubl/maindoc/UBL-CreditNote-2.1.xsd");
    cii = schema("/xsd/cii/CrossIndustryInvoice_100pD16B.xsd");
  }

  private static Schema schema(String resource) throws Exception {
    SchemaFactory factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
    factory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "file,jar");
    factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
    URL url = RoundTripTest.class.getResource(resource);
    return factory.newSchema(url);
  }

  static List<String> examples() {
    return Examples.names().toList();
  }

  @ParameterizedTest
  @MethodSource("examples")
  void theExampleItselfIsSchemaValid(String name) throws Exception {
    Received received = EInvoices.read(Examples.bytes(name));
    String xml = new String(Examples.bytes(name), StandardCharsets.UTF_8);
    validate(
        received.syntax(), received.invoice().isCreditNote() || xml.contains("<CreditNote"), xml);
  }

  @ParameterizedTest
  @MethodSource("examples")
  void writtenAgainInItsOwnSyntaxItReadsTheSame(String name) throws Exception {
    Received received = EInvoices.read(Examples.bytes(name));
    Invoice invoice = received.invoice();
    String written =
        received.syntax() == Syntax.UBL ? EInvoices.toUbl(invoice) : EInvoices.toCii(invoice);
    validate(received.syntax(), invoice.isCreditNote(), written);
    assertEquals(invoice, EInvoices.read(written.getBytes(StandardCharsets.UTF_8)).invoice());
  }

  @ParameterizedTest
  @MethodSource("examples")
  void writtenInTheOtherSyntaxItReadsTheSame(String name) throws Exception {
    Received received = EInvoices.read(Examples.bytes(name));
    Invoice invoice = received.invoice();
    boolean toCii = received.syntax() == Syntax.UBL;
    String written = toCii ? EInvoices.toCii(invoice) : EInvoices.toUbl(invoice);
    validate(toCii ? Syntax.CII : Syntax.UBL, invoice.isCreditNote(), written);
    Invoice back = EInvoices.read(written.getBytes(StandardCharsets.UTF_8)).invoice();
    assertEquals(Examples.acrossSyntaxes(invoice), Examples.acrossSyntaxes(back));
  }

  @ParameterizedTest
  @MethodSource("examples")
  void noEmptyElementIsWritten(String name) {
    Invoice invoice = Examples.invoice(name);
    for (String xml : List.of(EInvoices.toUbl(invoice), EInvoices.toCii(invoice))) {
      Received back = EInvoices.read(xml.getBytes(StandardCharsets.UTF_8));
      int allowed = back.syntax() == Syntax.CII ? requiredEmptyCiiGroups(xml) : 0;
      assertEquals(allowed, back.emptyElements(), name + " written as " + back.syntax());
    }
  }

  /** CII's schema requires these groups even when there is nothing to put in them. */
  private static int requiredEmptyCiiGroups(String xml) {
    int count = 0;
    for (String group :
        List.of(
            "<ram:ApplicableHeaderTradeDelivery/>",
            "<ram:SpecifiedTradeProduct/>",
            "<ram:NetPriceProductTradePrice/>",
            "<ram:SpecifiedLineTradeAgreement/>",
            "<ram:GuidelineSpecifiedDocumentContextParameter/>")) {
      int at = xml.indexOf(group);
      while (at >= 0) {
        count++;
        at = xml.indexOf(group, at + 1);
      }
    }
    return count;
  }

  private void validate(Syntax syntax, boolean creditNote, String xml) throws Exception {
    Schema schema = syntax == Syntax.CII ? cii : creditNote ? ublCreditNote : ublInvoice;
    assertFalse(xml.isBlank());
    try {
      schema.newValidator().validate(new StreamSource(new StringReader(xml)));
    } catch (org.xml.sax.SAXParseException e) {
      String[] lines = xml.split("\n");
      int line = e.getLineNumber();
      String near = line > 0 && line <= lines.length ? lines[line - 1].strip() : "";
      assertTrue(
          false,
          syntax + " is not schema-valid at line " + line + " (" + near + "): " + e.getMessage());
    }
  }
}
