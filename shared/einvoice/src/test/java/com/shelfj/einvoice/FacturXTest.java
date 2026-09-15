package com.shelfj.einvoice;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.shelfj.einvoice.EInvoices.Container;
import com.shelfj.einvoice.EInvoices.Received;
import com.shelfj.einvoice.EInvoices.Syntax;
import com.shelfj.einvoice.Invoice.Line;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentNameDictionary;
import org.apache.pdfbox.pdmodel.PDEmbeddedFilesNameTreeNode;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification;
import org.apache.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.verapdf.gf.foundry.VeraGreenfieldFoundryProvider;
import org.verapdf.pdfa.Foundries;
import org.verapdf.pdfa.PDFAParser;
import org.verapdf.pdfa.PDFAValidator;
import org.verapdf.pdfa.flavours.PDFAFlavour;
import org.verapdf.pdfa.results.TestAssertion;
import org.verapdf.pdfa.results.ValidationResult;

/**
 * Factur-X written here is PDF/A-3b by the PDF Association's own validator, carries exactly the CII
 * this module writes, and reads back as the invoice it was made from; a PDF that is not one — or is
 * built to hurt the reader — is refused with a code.
 */
class FacturXTest {

  @BeforeAll
  static void validator() {
    VeraGreenfieldFoundryProvider.initialise();
  }

  @Test
  void aPeppolInvoiceBecomesAPdfA3bThatReadsBackAsTheSameInvoice() throws Exception {
    Invoice invoice = Examples.invoice("base-example.xml");
    byte[] pdf = EInvoices.toFacturX(invoice);
    assertPdfA3b(pdf);
    Received back = EInvoices.read(pdf);
    assertEquals(Container.PDF, back.container());
    assertEquals(Syntax.CII, back.syntax());
    assertEquals(FacturX.XML_FILENAME, back.embeddedFilename());
    assertEquals(Examples.acrossSyntaxes(invoice), Examples.acrossSyntaxes(back.invoice()));
  }

  @Test
  void theAttachmentIsTheCiiThisModuleWritesByteForByte() {
    Invoice invoice = Examples.invoice("CII_example1.xml");
    byte[] cii = EInvoices.toCii(invoice).getBytes(StandardCharsets.UTF_8);
    byte[] pdf = FacturX.create(invoice, cii, Instant.parse("2026-09-15T10:00:00Z"));
    assertArrayEquals(cii, FacturX.extract(pdf).xml());
    assertEquals(invoice, EInvoices.read(pdf).invoice());
  }

  @Test
  void aLongInvoiceRunsOntoMorePagesAndStaysPdfA() throws Exception {
    Invoice invoice = Examples.invoice("base-example.xml");
    List<Line> lines = new ArrayList<>();
    for (int i = 0; i < 180; i++) {
      Line template = invoice.lines().get(0);
      lines.add(Examples.with(template, "id", Integer.toString(i + 1)));
    }
    Invoice longOne = Examples.with(invoice, "lines", lines);
    byte[] pdf = EInvoices.toFacturX(longOne);
    try (PDDocument doc = Loader.loadPDF(pdf)) {
      assertTrue(doc.getNumberOfPages() > 1, doc.getNumberOfPages() + " page(s)");
    }
    assertPdfA3b(pdf);
  }

  @Test
  void textTheFontCannotDrawIsReplacedRatherThanBreakingThePdf() throws Exception {
    Invoice invoice = Examples.invoice("base-example.xml");
    Invoice.Party seller = Examples.with(invoice.seller(), "name", "株式会社 Shelf\u0001 Ltd");
    byte[] pdf = EInvoices.toFacturX(Examples.with(invoice, "seller", seller));
    assertPdfA3b(pdf);
    assertEquals(
        "株式会社 Shelf\u0001 Ltd".replace('\u0001', ' '),
        EInvoices.read(pdf).invoice().seller().name().replace('\u0001', ' '));
  }

  @Test
  void aPdfWithoutAnInvoiceAttachmentIsRefused() throws Exception {
    assertEquals("NO_EMBEDDED_INVOICE", refused(plainPdf()).code());
    byte[] otherName =
        pdfWithAttachment(
            "invoice-copy.xml",
            EInvoices.toCii(Examples.invoice("base-example.xml")).getBytes(StandardCharsets.UTF_8));
    assertEquals("NO_EMBEDDED_INVOICE", refused(otherName).code());
  }

  @Test
  void aZugferdAttachmentNameIsRecognised() throws Exception {
    Invoice invoice = Examples.invoice("CII_example1.xml");
    byte[] pdf =
        pdfWithAttachment(
            "ZUGFeRD-invoice.xml", EInvoices.toCii(invoice).getBytes(StandardCharsets.UTF_8));
    Received back = EInvoices.read(pdf);
    assertEquals("ZUGFeRD-invoice.xml", back.embeddedFilename());
    assertEquals(invoice, back.invoice());
  }

  @Test
  void anEncryptedPdfIsRefusedWhetherOrNotItNeedsAPassword() throws Exception {
    assertEquals("PDF_ENCRYPTED", refused(encrypted("user")).code());
    assertEquals("PDF_ENCRYPTED", refused(encrypted("")).code());
  }

  @Test
  void aCompressedAttachmentThatExpandsPastTheCapIsStoppedAtTheCap() throws Exception {
    byte[] spaces = new byte[SafeXml.MAX_BYTES + 1024];
    Arrays.fill(spaces, (byte) ' ');
    byte[] bomb = pdfWithAttachment(FacturX.XML_FILENAME, spaces);
    assertTrue(bomb.length < 1024 * 1024, "the bomb is " + bomb.length + " bytes on the wire");
    assertEquals("TOO_LARGE", refused(bomb).code());
  }

  @Test
  void somethingThatOnlyLooksLikeAPdfIsUnreadableAndAnOversizedOneIsNotOpened() {
    byte[] fake = "%PDF-1.7\n this is not a PDF body at all".getBytes(StandardCharsets.UTF_8);
    assertEquals("PDF_UNREADABLE", refused(fake).code());
    byte[] huge = new byte[FacturX.MAX_PDF_BYTES + 1];
    byte[] header = "%PDF-1.7\n".getBytes(StandardCharsets.UTF_8);
    System.arraycopy(header, 0, huge, 0, header.length);
    assertEquals("TOO_LARGE", refused(huge).code());
  }

  // ── helpers ─────────────────────────────────────────────────────────────────

  private static void assertPdfA3b(byte[] pdf) throws Exception {
    try (PDFAParser parser =
            Foundries.defaultInstance()
                .createParser(new ByteArrayInputStream(pdf), PDFAFlavour.PDFA_3_B);
        PDFAValidator validator =
            Foundries.defaultInstance().createValidator(PDFAFlavour.PDFA_3_B, false)) {
      ValidationResult result = validator.validate(parser);
      String failures =
          result.getTestAssertions().stream()
              .filter(a -> a.getStatus() == TestAssertion.Status.FAILED)
              .map(
                  a ->
                      a.getRuleId().getClause()
                          + "-"
                          + a.getRuleId().getTestNumber()
                          + ": "
                          + a.getMessage())
              .distinct()
              .collect(Collectors.joining("\n"));
      assertTrue(result.isCompliant(), "not PDF/A-3b:\n" + failures);
    }
  }

  private static EInvoiceFormatException refused(byte[] document) {
    return assertThrows(EInvoiceFormatException.class, () -> EInvoices.read(document));
  }

  private static byte[] plainPdf() throws Exception {
    try (PDDocument doc = new PDDocument()) {
      doc.addPage(new PDPage());
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      doc.save(out);
      return out.toByteArray();
    }
  }

  private static byte[] pdfWithAttachment(String name, byte[] content) throws Exception {
    try (PDDocument doc = new PDDocument()) {
      doc.addPage(new PDPage());
      PDEmbeddedFile file =
          new PDEmbeddedFile(doc, new ByteArrayInputStream(content), COSName.FLATE_DECODE);
      file.setSubtype("text/xml");
      PDComplexFileSpecification spec = new PDComplexFileSpecification();
      spec.setFile(name);
      spec.setFileUnicode(name);
      spec.setEmbeddedFile(file);
      spec.setEmbeddedFileUnicode(file);
      PDEmbeddedFilesNameTreeNode tree = new PDEmbeddedFilesNameTreeNode();
      tree.setNames(Map.of(name, spec));
      PDDocumentNameDictionary names = new PDDocumentNameDictionary(doc.getDocumentCatalog());
      names.setEmbeddedFiles(tree);
      doc.getDocumentCatalog().setNames(names);
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      doc.save(out);
      return out.toByteArray();
    }
  }

  private static byte[] encrypted(String userPassword) throws Exception {
    byte[] pdf = EInvoices.toFacturX(Examples.invoice("base-example.xml"));
    try (PDDocument doc = Loader.loadPDF(pdf)) {
      StandardProtectionPolicy policy =
          new StandardProtectionPolicy("owner-secret", userPassword, new AccessPermission());
      policy.setEncryptionKeyLength(128);
      doc.protect(policy);
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      doc.save(out);
      return out.toByteArray();
    }
  }

  @Test
  void theHumanReadableTotalsAreTheModelsTotals() throws Exception {
    Invoice invoice = Examples.invoice("base-example.xml");
    byte[] pdf = EInvoices.toFacturX(invoice);
    try (PDDocument doc = Loader.loadPDF(pdf)) {
      String text = new org.apache.pdfbox.text.PDFTextStripper().getText(doc);
      for (BigDecimal amount :
          List.of(
              invoice.totals().withoutVat(), invoice.totals().vat(), invoice.totals().payable())) {
        assertTrue(
            text.contains(amount.toPlainString()),
            "the page does not show " + amount.toPlainString());
      }
      assertTrue(text.contains("Invoice " + invoice.number()));
    }
  }
}
