package com.shelfj.einvoice;

import static com.shelfj.einvoice.TestInvoices.totals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * India's INV-01 written from the EN 16931 model: CGST and SGST within a state, IGST across, the
 * HSN or SAC code on every item, credit notes naming their invoice, and the refusals the portal
 * would give.
 */
class IrpTest {

  private static final String MUMBAI_SUPPLIER = "27AAPFU0939F1ZV";
  private static final String MUMBAI_BUYER = gstin("27AABCU9603R1Z");
  private static final String BENGALURU_BUYER = "29AAGCB7383J1Z4";

  @Test
  void aSupplyWithinAStateIsTaxedAsCentralAndStateGst() {
    String json = Irp.toJson(invoice("INV/2026/001", "380", MUMBAI_BUYER, lines(), List.of()));
    assertTrue(
        json.startsWith(
            "{\"Version\":\"1.1\",\"TranDtls\":{\"TaxSch\":\"GST\",\"SupTyp\":\"B2B\",\"RegRev\":\"N\"}"),
        json);
    assertTrue(
        json.contains(
            "\"DocDtls\":{\"Typ\":\"INV\",\"No\":\"INV/2026/001\",\"Dt\":\"15/09/2026\"}"),
        json);
    assertTrue(
        json.contains(
            "\"SellerDtls\":{\"Gstin\":\"27AAPFU0939F1ZV\",\"LglNm\":\"Kiran Traders Pvt Ltd\",\"Addr1\":\"12 Marine Drive\",\"Loc\":\"Mumbai\",\"Pin\":400002,\"Stcd\":\"27\"}"),
        json);
    assertTrue(json.contains("\"Pos\":\"27\",\"Stcd\":\"27\""), json);
    // Ten strips of tablets at 100.00, HSN 30049099, 18%: half central, half state.
    assertTrue(
        json.contains(
            "{\"SlNo\":\"1\",\"PrdDesc\":\"Paracetamol 500 mg strip\",\"IsServc\":\"N\",\"HsnCd\":\"30049099\",\"Qty\":10,\"Unit\":\"NOS\",\"UnitPrice\":100,\"TotAmt\":1000.00,\"Discount\":0.00,\"AssAmt\":1000.00,\"GstRt\":18,\"IgstAmt\":0.00,\"CgstAmt\":90.00,\"SgstAmt\":90.00,\"TotItemVal\":1180.00}"),
        json);
    // A service carries its SAC code and no unit.
    assertTrue(
        json.contains("\"IsServc\":\"Y\",\"HsnCd\":\"998314\",\"Qty\":1,\"UnitPrice\":500,"), json);
    assertTrue(
        json.contains(
            "\"ValDtls\":{\"AssVal\":1500.00,\"CgstVal\":135.00,\"SgstVal\":135.00,\"IgstVal\":0.00,\"RndOffAmt\":0.00,\"TotInvVal\":1770.00}"),
        json);
  }

  @Test
  void aSupplyToAnotherStateIsIntegratedGst() {
    String json = Irp.toJson(invoice("INV-7", "380", BENGALURU_BUYER, lines(), List.of()));
    assertTrue(json.contains("\"Pos\":\"29\",\"Stcd\":\"29\""), json);
    assertTrue(json.contains("\"IgstAmt\":180.00,\"CgstAmt\":0.00,\"SgstAmt\":0.00"), json);
    assertTrue(json.contains("\"IgstVal\":270.00"), json);
  }

  @Test
  void aDiscountIsTheDifferenceBetweenListPriceAndTaxableValue() {
    Invoice.Line discounted =
        line(
            "Paracetamol 500 mg strip", "30049099", "10", "C62", "90.00", "100.00", "900.00", "18");
    String json =
        Irp.toJson(
            invoice("INV-8", "380", MUMBAI_BUYER, List.of(discounted), List.of(), "1062.00"));
    assertTrue(
        json.contains("\"UnitPrice\":100,\"TotAmt\":1000.00,\"Discount\":100.00,\"AssAmt\":900.00"),
        json);
  }

  @Test
  void aCreditNoteNamesTheInvoiceItCredits() {
    String json =
        Irp.toJson(
            invoice(
                "CRN-1",
                "381",
                MUMBAI_BUYER,
                lines(),
                List.of(new Invoice.PrecedingInvoice("INV/2026/001", LocalDate.of(2026, 9, 15)))));
    assertTrue(json.contains("\"Typ\":\"CRN\""), json);
    assertTrue(
        json.endsWith(
            "\"RefDtls\":{\"PrecDocDtls\":[{\"InvNo\":\"INV/2026/001\",\"InvDt\":\"15/09/2026\"}]}}"),
        json);
  }

  @Test
  void aCleanDocumentBreaksNothing() {
    assertEquals(
        List.of(), Irp.check(invoice("INV/2026/001", "380", MUMBAI_BUYER, lines(), List.of())));
  }

  @Test
  void whatThePortalWouldRefuseIsNamedByRule() {
    Invoice.Line noHsn = line("Unclassified", null, "1", "C62", "10.00", null, "10.00", "18");
    Invoice.Line vatRate =
        line("Taxed at a VAT rate", "30049099", "1", "C62", "10.00", null, "10.00", "20");
    Invoice bad =
        TestInvoices.invoice(
            "inv 1",
            "388",
            "EUR",
            party("Kiran Traders Pvt Ltd", "27AAPFU0939F1ZW", "12 Marine Drive", "Mumbai", "4000"),
            party("X", "GB123456789", "", "Pu", null),
            List.of(
                new Invoice.AllowanceCharge(
                    false, BigDecimal.ONE, null, null, "S", BigDecimal.TEN, "Loyal", "95")),
            List.of(noHsn, vatRate),
            List.of(),
            totals("20.00", "20.00"),
            List.of());
    Set<String> rules = Irp.check(bad).stream().map(Violation::rule).collect(Collectors.toSet());
    for (String rule :
        List.of(
            "IRP-DOC-01",
            "IRP-DOC-02",
            "IRP-DOC-04",
            "IRP-SELLER-01",
            "IRP-SELLER-05",
            "IRP-BUYER-01",
            "IRP-BUYER-02",
            "IRP-BUYER-03",
            "IRP-BUYER-04",
            "IRP-VAL-01",
            "IRP-ITEM-02",
            "IRP-ITEM-03")) {
      assertTrue(rules.contains(rule), rule + " in " + rules);
    }
    IllegalArgumentException e =
        assertThrows(IllegalArgumentException.class, () -> Irp.toJson(bad));
    assertTrue(e.getMessage().startsWith("IRP-"), e.getMessage());
  }

  @Test
  void totalsThatDoNotMatchTheItemsBeyondTenRupeesAreRefused() {
    Invoice off = invoice("INV-9", "380", MUMBAI_BUYER, lines(), List.of(), "1790.00");
    assertTrue(Irp.check(off).stream().anyMatch(v -> "IRP-VAL-03".equals(v.rule())));
    Invoice rounded = invoice("INV-9", "380", MUMBAI_BUYER, lines(), List.of(), "1770.40");
    assertTrue(Irp.toJson(rounded).contains("\"RndOffAmt\":0.40,\"TotInvVal\":1770.40"));
  }

  @Test
  void aChargeOrANegativeItemHasNoPlaceInInv01() {
    Invoice.Line charged = line("Delivered", "30049099", "1", "C62", "10.00", null, "15.00", "18");
    Invoice.Line negative = line("Refund", "30049099", "1", "C62", "-10.00", null, "-10.00", "18");
    Set<String> rules =
        Irp.check(
                invoice(
                    "INV-10", "380", MUMBAI_BUYER, List.of(charged, negative), List.of(), "5.90"))
            .stream()
            .map(Violation::rule)
            .collect(Collectors.toSet());
    assertTrue(rules.contains("IRP-ITEM-07"), rules.toString());
    assertTrue(rules.contains("IRP-ITEM-06"), rules.toString());
  }

  @Test
  void anUnmappedUnitIsWrittenAsOtherAndSaysSo() {
    Invoice.Line odd =
        line("Paracetamol 500 mg strip", "30049099", "10", "XYZ", "100.00", null, "1000.00", "18");
    Invoice inv = invoice("INV-11", "380", MUMBAI_BUYER, List.of(odd), List.of(), "1180.00");
    List<Violation> v = Irp.check(inv);
    assertEquals(1, v.size(), v.toString());
    assertFalse(v.get(0).isFatal());
    assertTrue(Irp.toJson(inv).contains("\"Unit\":\"OTH\""));
  }

  @Test
  void textIsEscapedNotInjected() {
    Invoice.Line quoted =
        line(
            "Tablets \"strong\"\n\\ 500",
            "30049099",
            "10",
            "C62",
            "100.00",
            null,
            "1000.00",
            "18");
    String json =
        Irp.toJson(invoice("INV-12", "380", MUMBAI_BUYER, List.of(quoted), List.of(), "1180.00"));
    assertTrue(json.contains("\"PrdDesc\":\"Tablets \\\"strong\\\"\\n\\\\ 500\\u0001\""), json);
  }

  // ── builders ─────────────────────────────────────────────────────────────────

  private static String gstin(String first14) {
    return first14 + Gstin.checkCharacter(first14);
  }

  private static List<Invoice.Line> lines() {
    return List.of(
        line("Paracetamol 500 mg strip", "30049099", "10", "C62", "100.00", null, "1000.00", "18"),
        line("Pharmacy consultancy", "998314", "1", "C62", "500.00", null, "500.00", "18"));
  }

  private static Invoice.Line line(
      String name,
      String hsn,
      String qty,
      String unit,
      String net,
      String gross,
      String amount,
      String rate) {
    return new Invoice.Line(
        "1",
        null,
        null,
        new BigDecimal(qty),
        unit,
        new BigDecimal(amount),
        null,
        null,
        null,
        List.of(),
        new Invoice.Price(
            new BigDecimal(net), null, gross == null ? null : new BigDecimal(gross), null, null),
        "S",
        new BigDecimal(rate),
        new Invoice.Item(
            name,
            null,
            null,
            null,
            null,
            hsn == null ? List.of() : List.of(new Invoice.Classification(hsn, "HS", null)),
            null,
            List.of()));
  }

  private static Invoice.Party party(
      String name, String gstin, String line1, String city, String pin) {
    return new Invoice.Party(
        name,
        null,
        List.of(),
        null,
        gstin,
        null,
        null,
        null,
        new Invoice.Address(line1, null, null, city, pin, null, "IN"),
        null);
  }

  private static Invoice invoice(
      String number,
      String type,
      String buyerGstin,
      List<Invoice.Line> lines,
      List<Invoice.PrecedingInvoice> preceding) {
    return invoice(number, type, buyerGstin, lines, preceding, "1770.00");
  }

  private static Invoice invoice(
      String number,
      String type,
      String buyerGstin,
      List<Invoice.Line> lines,
      List<Invoice.PrecedingInvoice> preceding,
      String gross) {
    BigDecimal net =
        lines.stream().map(Invoice.Line::netAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    return TestInvoices.invoice(
        number,
        type,
        "INR",
        party("Kiran Traders Pvt Ltd", MUMBAI_SUPPLIER, "12 Marine Drive", "Mumbai", "400002"),
        party("Nirmal Pharma LLP", buyerGstin, "4 Residency Road", "Bengaluru", "560025"),
        List.of(),
        lines,
        preceding,
        totals(net.toPlainString(), gross),
        List.of());
  }
}
