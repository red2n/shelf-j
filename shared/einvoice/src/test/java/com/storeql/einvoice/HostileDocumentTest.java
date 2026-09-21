package com.storeql.einvoice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What a supplier, an access point or an attacker can send instead of an invoice: every one is
 * refused with a stable code, before it reaches anything but its own bytes.
 */
class HostileDocumentTest {

  private static final String UBL_OPEN =
      "<Invoice xmlns=\"urn:oasis:names:specification:ubl:schema:xsd:Invoice-2\""
          + " xmlns:cbc=\"urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2\">";

  @Test
  void anExternalEntityIsRefusedAndTheFileIsNeverRead(@TempDir Path dir) throws Exception {
    Path secret = dir.resolve("secret.txt");
    Files.writeString(secret, "TOP-SECRET");
    String xxe =
        "<?xml version=\"1.0\"?><!DOCTYPE Invoice [<!ENTITY x SYSTEM \""
            + secret.toUri()
            + "\">]>"
            + UBL_OPEN
            + "<cbc:ID>&x;</cbc:ID></Invoice>";
    EInvoiceFormatException e = refused(xxe.getBytes(StandardCharsets.UTF_8));
    assertEquals("DTD_REFUSED", e.code());
    assertFalse(e.getMessage().contains("TOP-SECRET"));
  }

  @Test
  void anEntityExpansionBombIsRefusedBeforeItExpands() {
    StringBuilder bomb =
        new StringBuilder("<?xml version=\"1.0\"?><!DOCTYPE Invoice [<!ENTITY a0 \"lol\">");
    for (int i = 1; i < 10; i++) {
      bomb.append("<!ENTITY a")
          .append(i)
          .append(" \"")
          .append(("&a" + (i - 1) + ";").repeat(10))
          .append("\">");
    }
    bomb.append("]>").append(UBL_OPEN).append("<cbc:ID>&a9;</cbc:ID></Invoice>");
    assertEquals("DTD_REFUSED", refused(bomb.toString().getBytes(StandardCharsets.UTF_8)).code());
  }

  @Test
  void aDocumentOverTenMegabytesIsRefusedUnread() {
    byte[] big = new byte[SafeXml.MAX_BYTES + 1];
    java.util.Arrays.fill(big, (byte) ' ');
    assertEquals("TOO_LARGE", refused(big).code());
  }

  @Test
  void nestingDeeperThanTheCapIsRefused() {
    String deep =
        UBL_OPEN
            + "<a>".repeat(SafeXml.MAX_DEPTH)
            + "</a>".repeat(SafeXml.MAX_DEPTH)
            + "</Invoice>";
    assertEquals("TOO_LARGE", refused(deep.getBytes(StandardCharsets.UTF_8)).code());
  }

  @Test
  void moreElementsThanTheCapAreRefused() {
    String many = UBL_OPEN + "<a/>".repeat(SafeXml.MAX_ELEMENTS) + "</Invoice>";
    assertEquals("TOO_LARGE", refused(many.getBytes(StandardCharsets.UTF_8)).code());
  }

  @Test
  void anElementWithTooManyAttributesIsRefused() {
    StringBuilder attributes = new StringBuilder();
    for (int i = 0; i <= SafeXml.MAX_ATTRIBUTES; i++)
      attributes.append(" a").append(i).append("=\"x\"");
    String doc = UBL_OPEN + "<cbc:ID" + attributes + ">1</cbc:ID></Invoice>";
    assertEquals("TOO_LARGE", refused(doc.getBytes(StandardCharsets.UTF_8)).code());
  }

  @Test
  void brokenXmlAnEmptyBodyAndAStrangerAreEachRefusedWithTheirOwnCode() {
    assertEquals("NOT_XML", refused("<Invoice><unclosed>".getBytes(StandardCharsets.UTF_8)).code());
    assertEquals("EMPTY", refused(new byte[0]).code());
    assertEquals(
        "EMPTY", assertThrows(EInvoiceFormatException.class, () -> EInvoices.read(null)).code());
    EInvoiceFormatException stranger =
        refused(
            "<Order xmlns=\"urn:oasis:names:specification:ubl:schema:xsd:Order-2\"/>"
                .getBytes(StandardCharsets.UTF_8));
    assertEquals("NOT_AN_INVOICE", stranger.code());
    assertEquals(
        "NOT_AN_INVOICE",
        refused("<Invoice/>".getBytes(StandardCharsets.UTF_8)).code(),
        "an Invoice in no namespace is not UBL");
  }

  @Test
  void aDecimalWithACommaOrAnExponentIsNotANumber() {
    assertEquals(
        "BAD_VALUE", refused(ublWith("<cbc:TaxAmount>1,00</cbc:TaxAmount>", "TaxTotal")).code());
    EInvoiceFormatException exponent =
        refused(ublWith("<cbc:TaxAmount>1e999999999</cbc:TaxAmount>", "TaxTotal"));
    assertEquals("BAD_VALUE", exponent.code());
  }

  @Test
  void aDateInTheWrongShapeIsRefused() {
    String doc = UBL_OPEN + "<cbc:IssueDate>13/11/2017</cbc:IssueDate></Invoice>";
    assertEquals("BAD_VALUE", refused(doc.getBytes(StandardCharsets.UTF_8)).code());
    String february = UBL_OPEN + "<cbc:IssueDate>2017-02-30</cbc:IssueDate></Invoice>";
    assertEquals("BAD_VALUE", refused(february.getBytes(StandardCharsets.UTF_8)).code());
  }

  @Test
  void aCiiDateInAnotherFormatThan102IsRefused() {
    String cii =
        new String(Examples.bytes("CII_example1.xml"), StandardCharsets.UTF_8)
            .replace(
                "<udt:DateTimeString format=\"102\">20150109</udt:DateTimeString>",
                "<udt:DateTimeString format=\"610\">201501</udt:DateTimeString>");
    assertEquals("BAD_VALUE", refused(cii.getBytes(StandardCharsets.UTF_8)).code());
  }

  @Test
  void aUtf16InvoiceWithAByteOrderMarkReadsTheSameAsItsUtf8Original() {
    String utf8 =
        new String(Examples.bytes("base-example.xml"), StandardCharsets.UTF_8)
            .replace("encoding=\"UTF-8\"", "encoding=\"UTF-16\"");
    byte[] utf16 = utf8.getBytes(StandardCharsets.UTF_16);
    assertEquals(Examples.invoice("base-example.xml"), EInvoices.read(utf16).invoice());
  }

  @Test
  void controlCharactersInAnInvoiceWrittenOutStillMakeADocumentThatReads() {
    Invoice inv = Examples.invoice("base-example.xml");
    Invoice.Party seller = Examples.with(inv.seller(), "name", "Bad\0Name\7 <Ltd> & \"Co\"");
    Invoice dirty = Examples.with(inv, "seller", seller);
    for (String xml : java.util.List.of(EInvoices.toUbl(dirty), EInvoices.toCii(dirty))) {
      Invoice back = EInvoices.read(xml.getBytes(StandardCharsets.UTF_8)).invoice();
      assertEquals("Bad Name  <Ltd> & \"Co\"", back.seller().name());
    }
  }

  @Test
  void theRefusalNeverEchoesMoreThanAShortExcerptOfWhatWasSent() {
    String longRoot = "<" + "x".repeat(900) + " xmlns=\"urn:" + "y".repeat(900) + "\"/>";
    EInvoiceFormatException e = refused(longRoot.getBytes(StandardCharsets.UTF_8));
    assertEquals("NOT_AN_INVOICE", e.code());
    assertTrue(e.getMessage().length() < 400, e.getMessage().length() + " characters");
  }

  private static byte[] ublWith(String content, String group) {
    return (UBL_OPEN
            + "<cac:"
            + group
            + " xmlns:cac=\"urn:oasis:names:specification:ubl:schema:xsd:CommonAggregateComponents-2\">"
            + content
            + "</cac:"
            + group
            + "></Invoice>")
        .getBytes(StandardCharsets.UTF_8);
  }

  private static EInvoiceFormatException refused(byte[] document) {
    return assertThrows(EInvoiceFormatException.class, () -> EInvoices.read(document));
  }
}
