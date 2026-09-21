package com.storeql.order.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.storeql.einvoice.EInvoices;
import com.storeql.einvoice.Invoice;
import com.storeql.einvoice.Irp;
import com.storeql.einvoice.Rules;
import com.storeql.einvoice.Violation;
import com.storeql.order.domain.SalesInvoiceDraft.Address;
import com.storeql.order.domain.SalesInvoiceDraft.Buyer;
import com.storeql.order.domain.SalesInvoiceDraft.Document;
import com.storeql.order.domain.SalesInvoiceDraft.Line;
import com.storeql.order.domain.SalesInvoiceDraft.Seller;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A sale to a business, written as the EN 16931 document it owes: every rate and category held to
 * CEN's rules, the order's discount on the rates it came off, a Peppol document when both parties
 * are on the network, a credit note naming its invoice, and an Indian sale as the IRP would take
 * it.
 */
class SalesInvoiceDraftTest {

  private static final LocalDate DAY = LocalDate.of(2026, 9, 15);
  private static final Address LONDON =
      new Address("1 High Street", null, "London", "E1 6AN", null, "GB");
  private static final Seller SHOP =
      new Seller("Corner Shop Ltd", "Corner Shop", "GB123456789", null, null, LONDON);
  private static final Buyer CAFE =
      new Buyer(
          "Cafe Leeds Ltd",
          "GB555555555",
          null,
          null,
          new Address("2 Mill Lane", null, "Leeds", "LS1 4AB", null, "GB"),
          false);

  private static Line line(String name, String qty, String net, String rate) {
    return new Line(
        name,
        "SKU-" + name,
        null,
        new BigDecimal(qty),
        "PCS",
        new BigDecimal(net),
        new BigDecimal(rate),
        null);
  }

  private static Document doc(String type, String number, String discount, String payable) {
    return new Document(
        type,
        number,
        DAY,
        "GBP",
        "0192f000-0000-7000-8000-0000000000a1",
        SalesInvoiceDraft.TYPE_CREDIT_NOTE.equals(type) ? "INV/2026/000001" : null,
        SalesInvoiceDraft.TYPE_CREDIT_NOTE.equals(type) ? DAY : null,
        "Paid in full at the time of sale",
        discount == null ? null : new BigDecimal(discount),
        payable == null ? null : new BigDecimal(payable),
        false);
  }

  private static List<String> fatal(List<Violation> violations) {
    return violations.stream()
        .filter(Violation::isFatal)
        .map(v -> v.rule() + ": " + v.message())
        .toList();
  }

  private static void readsBackClean(Invoice inv) {
    for (byte[] bytes :
        List.of(
            EInvoices.toUbl(inv).getBytes(StandardCharsets.UTF_8),
            EInvoices.toCii(inv).getBytes(StandardCharsets.UTF_8))) {
      EInvoices.Received r = EInvoices.read(bytes);
      assertEquals(List.of(), fatal(EInvoices.validate(r)), r.syntax().name());
      assertEquals(
          0, inv.totals().payable().compareTo(r.invoice().totals().payable()), r.syntax().name());
    }
  }

  @Test
  void aSaleAtThreeRatesIsAnInvoiceThatBreaksNoRuleAndReadsBackTheSame() {
    Invoice inv =
        SalesInvoiceDraft.build(
            doc(SalesInvoiceDraft.TYPE_INVOICE, "INV/2026/000001", null, null),
            SHOP,
            CAFE,
            List.of(
                line("Apples", "10", "25.00", "20"),
                line("Milk", "2", "2.40", "0"),
                line("Tea", "1", "4.00", "5")));
    assertEquals(List.of(), fatal(Rules.check(inv)));
    assertEquals(Invoice.EN16931, inv.customizationId());
    assertEquals(new BigDecimal("31.40"), inv.totals().lineNet());
    assertEquals(new BigDecimal("5.20"), inv.totals().vat());
    assertEquals(new BigDecimal("36.60"), inv.totals().payable());
    assertEquals(
        List.of("S", "Z", "S"),
        inv.vatBreakdown().stream().map(Invoice.VatBreakdown::category).toList());
    assertEquals(new BigDecimal("2.5"), inv.lines().get(0).price().net());
    readsBackClean(inv);
  }

  @Test
  void theOrdersDiscountIsAnAllowanceOnTheRatesItCameOff() {
    Invoice inv =
        SalesInvoiceDraft.build(
            doc(SalesInvoiceDraft.TYPE_INVOICE, "INV/2026/000002", "3.00", "31.05"),
            SHOP,
            CAFE,
            List.of(line("Apples", "8", "20.00", "20"), line("Tea", "2", "10.00", "5")));
    assertEquals(List.of(), fatal(Rules.check(inv)));
    assertEquals(
        List.of(new BigDecimal("1.00"), new BigDecimal("2.00")),
        inv.allowanceCharges().stream().map(Invoice.AllowanceCharge::amount).sorted().toList());
    assertEquals(new BigDecimal("4.05"), inv.totals().vat());
    assertEquals(new BigDecimal("31.05"), inv.totals().payable());
    assertNull(inv.totals().rounding());
    readsBackClean(inv);
  }

  @Test
  void aTillDiscountOffTheTotalIsStatedNetSoTheVatFollowsWhatWasPaid() {
    List<Line> lines = List.of(line("Apples", "8", "20.00", "20"), line("Tea", "2", "10.00", "5"));
    // The lines come to 34.50 with VAT; the cashier took 5.00 off that total, after the tax.
    BigDecimal net = SalesInvoiceDraft.netDiscount(lines, new BigDecimal("29.50"));
    assertEquals(new BigDecimal("4.35"), net);
    Invoice inv =
        SalesInvoiceDraft.build(
            doc(SalesInvoiceDraft.TYPE_INVOICE, "INV/2026/000005", net.toPlainString(), "29.50"),
            SHOP,
            CAFE,
            lines);
    // 17.10 at 20% and 8.55 at 5%: the VAT on what was actually paid, not on the full lines.
    assertEquals(new BigDecimal("3.85"), inv.totals().vat());
    assertEquals(new BigDecimal("29.50"), inv.totals().payable());
    assertNull(inv.totals().rounding());
    assertEquals(List.of(), fatal(Rules.check(inv)));
    readsBackClean(inv);
    // Paid in full, or more than the lines come to: nothing came off.
    assertEquals(
        new BigDecimal("0.00"), SalesInvoiceDraft.netDiscount(lines, new BigDecimal("34.50")));
    assertEquals(
        new BigDecimal("0.00"), SalesInvoiceDraft.netDiscount(lines, new BigDecimal("40.00")));
    assertEquals(new BigDecimal("0.00"), SalesInvoiceDraft.netDiscount(lines, null));
  }

  @Test
  void aPennyTheSaleRoundedDifferentlyIsRoundingAndMoreIsNot() {
    List<Line> lines = List.of(line("Apples", "8", "20.00", "20"), line("Tea", "2", "10.00", "5"));
    Invoice penny =
        SalesInvoiceDraft.build(
            doc(SalesInvoiceDraft.TYPE_INVOICE, "INV/2026/000003", "3.00", "31.06"),
            SHOP,
            CAFE,
            lines);
    assertEquals(new BigDecimal("0.01"), penny.totals().rounding());
    assertEquals(new BigDecimal("31.06"), penny.totals().payable());
    assertEquals(List.of(), fatal(Rules.check(penny)));
    Invoice pounds =
        SalesInvoiceDraft.build(
            doc(SalesInvoiceDraft.TYPE_INVOICE, "INV/2026/000004", "3.00", "40.00"),
            SHOP,
            CAFE,
            lines);
    assertNull(pounds.totals().rounding());
    assertEquals(new BigDecimal("31.05"), pounds.totals().payable());
  }

  @Test
  void anExemptLineSaysWhyAndAReverseChargeBuyerIsCategoryAe() {
    Line exempt =
        new Line(
            "Course",
            "SKU-C",
            null,
            BigDecimal.ONE,
            "C62",
            new BigDecimal("100.00"),
            BigDecimal.ZERO,
            "Exempt education");
    Invoice e =
        SalesInvoiceDraft.build(
            doc(SalesInvoiceDraft.TYPE_INVOICE, "INV/2026/000005", null, null),
            SHOP,
            CAFE,
            List.of(exempt));
    assertEquals("E", e.lines().get(0).vatCategory());
    assertEquals("Exempt education", e.vatBreakdown().get(0).exemptionReason());
    assertEquals(List.of(), fatal(Rules.check(e)));

    Buyer berlin =
        new Buyer(
            "Muster GmbH",
            "DE123456789",
            null,
            null,
            new Address("Hauptstrasse 1", null, "Berlin", "10115", null, "DE"),
            true);
    Invoice ae =
        SalesInvoiceDraft.build(
            doc(SalesInvoiceDraft.TYPE_INVOICE, "INV/2026/000006", null, null),
            SHOP,
            berlin,
            List.of(line("Apples", "10", "25.00", "0")));
    assertEquals("AE", ae.lines().get(0).vatCategory());
    assertEquals(0, ae.totals().vat().signum());
    assertEquals(List.of(), fatal(Rules.check(ae)));
    readsBackClean(ae);
  }

  @Test
  void bothPartiesOnPeppolMakeAPeppolBisDocument() {
    Seller onPeppol =
        new Seller("Corner Shop Ltd", null, "GB123456789", "0088", "5790000435975", LONDON);
    Buyer cafe =
        new Buyer("Cafe Leeds Ltd", "GB555555555", "9932", "GB555555555", CAFE.address(), false);
    Invoice inv =
        SalesInvoiceDraft.build(
            doc(SalesInvoiceDraft.TYPE_INVOICE, "INV/2026/000007", null, null),
            onPeppol,
            cafe,
            List.of(line("Apples", "10", "25.00", "20")));
    assertEquals(Invoice.PEPPOL_BIS_3, inv.customizationId());
    assertEquals(Rules.Profile.PEPPOL_BIS_3, Rules.profileOf(inv));
    assertEquals(List.of(), fatal(Rules.check(inv)));
    readsBackClean(inv);
    Invoice oneSided =
        SalesInvoiceDraft.build(
            doc(SalesInvoiceDraft.TYPE_INVOICE, "INV/2026/000008", null, null),
            onPeppol,
            CAFE,
            List.of(line("Apples", "10", "25.00", "20")));
    assertEquals(
        Invoice.EN16931, oneSided.customizationId(), "a buyer with no address is not on Peppol");
  }

  @Test
  void aCreditNoteNamesTheInvoiceItCredits() {
    Invoice cn =
        SalesInvoiceDraft.build(
            doc(SalesInvoiceDraft.TYPE_CREDIT_NOTE, "CRN/2026/000001", null, null),
            SHOP,
            CAFE,
            List.of(line("Apples", "2", "5.00", "20")));
    assertTrue(cn.isCreditNote());
    assertEquals("INV/2026/000001", cn.precedingInvoices().get(0).number());
    assertEquals(List.of(), fatal(Rules.check(cn)));
    assertTrue(EInvoices.toUbl(cn).contains("<CreditNote"));
    readsBackClean(cn);
  }

  @Test
  void anIndianSaleSpreadsItsDiscountIntoTheLinesAndPassesThePortalsChecks() {
    Seller mumbai =
        new Seller(
            "Kiran Traders Pvt Ltd",
            null,
            "27AAPFU0939F1ZV",
            null,
            null,
            new Address("12 Marine Drive", null, "Mumbai", "400002", null, "IN"));
    Buyer bengaluru =
        new Buyer(
            "Nirmal Pharma LLP",
            "29AAGCB7383J1Z4",
            null,
            null,
            new Address("4 Residency Road", null, "Bengaluru", "560025", null, "IN"),
            false);
    List<Line> lines =
        List.of(
            new Line(
                "Paracetamol 500 mg strip",
                "PARA",
                "30049099",
                new BigDecimal("10"),
                "PCS",
                new BigDecimal("1000.00"),
                new BigDecimal("18"),
                null),
            new Line(
                "Pharmacy consultancy",
                "CONS",
                "998314",
                BigDecimal.ONE,
                "C62",
                new BigDecimal("500.00"),
                new BigDecimal("18"),
                null));
    Document d =
        new Document(
            SalesInvoiceDraft.TYPE_INVOICE,
            "INV/2026/000001",
            DAY,
            "INR",
            "ORDER-1",
            null,
            null,
            null,
            new BigDecimal("150.00"),
            null,
            true);
    Invoice inv = SalesInvoiceDraft.build(d, mumbai, bengaluru, lines);
    assertTrue(inv.allowanceCharges().isEmpty(), "the discount is in the lines");
    assertEquals(new BigDecimal("900.00"), inv.lines().get(0).netAmount());
    assertEquals(new BigDecimal("450.00"), inv.lines().get(1).netAmount());
    assertEquals(List.of(), fatal(Irp.check(inv)));
    String json = Irp.toJson(inv);
    assertTrue(json.contains("\"AssVal\":1350.00"), json);
    assertTrue(json.contains("\"IgstVal\":243.00"), json);
  }

  @Test
  void aDocumentWithNoLinesOrNoQuantityIsRefused() {
    Document d = doc(SalesInvoiceDraft.TYPE_INVOICE, "INV/2026/000009", null, null);
    assertThrows(
        IllegalArgumentException.class, () -> SalesInvoiceDraft.build(d, SHOP, CAFE, List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> SalesInvoiceDraft.build(d, SHOP, CAFE, List.of(line("Apples", "0", "25.00", "20"))));
  }

  @Test
  void unitsAreNamedAsRecommendation20NamesThem() {
    assertEquals("KGM", SalesInvoiceDraft.unitCode(" kg "));
    assertEquals("LTR", SalesInvoiceDraft.unitCode("L"));
    assertEquals("XPK", SalesInvoiceDraft.unitCode("pack"));
    assertEquals("C62", SalesInvoiceDraft.unitCode(null));
    assertEquals("C62", SalesInvoiceDraft.unitCode("PCS"));
    assertEquals("C62", SalesInvoiceDraft.unitCode("<script>"));
  }
}
