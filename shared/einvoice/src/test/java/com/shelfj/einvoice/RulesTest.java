package com.shelfj.einvoice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.shelfj.einvoice.Invoice.AllowanceCharge;
import com.shelfj.einvoice.Invoice.Card;
import com.shelfj.einvoice.Invoice.Identifier;
import com.shelfj.einvoice.Invoice.Line;
import com.shelfj.einvoice.Invoice.Party;
import com.shelfj.einvoice.Invoice.PaymentInstructions;
import com.shelfj.einvoice.Invoice.Totals;
import com.shelfj.einvoice.Invoice.VatBreakdown;
import java.math.BigDecimal;
import java.util.List;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The CEN and Peppol rules, each broken on its own: an invoice that passes, changed in one term,
 * must fail on exactly the rule that term carries — and a change inside the slack a rule allows
 * must not.
 */
class RulesTest {

  /** OpenPEPPOL's base example: a standard-rated invoice at 25% that breaks no rule. */
  private static Invoice base() {
    return Examples.invoice("base-example.xml");
  }

  static List<String> examples() {
    return Examples.names().toList();
  }

  @ParameterizedTest
  @MethodSource("examples")
  void noOfficialExampleBreaksAFatalRule(String name) {
    EInvoices.Received received = EInvoices.read(Examples.bytes(name));
    List<Violation> fatal =
        EInvoices.validate(received).stream().filter(Violation::isFatal).toList();
    assertEquals(List.of(), fatal, name);
  }

  @Test
  void theBaseExampleIsCleanUnderBothProfiles() {
    assertEquals(List.of(), Rules.check(base(), Rules.Profile.PEPPOL_BIS_3));
    assertEquals(List.of(), Rules.check(base(), Rules.Profile.EN16931));
  }

  @Test
  void aMissingSellerNameBreaksBr06() {
    Invoice inv = base();
    assertBreaks(Examples.with(inv, "seller", Examples.with(inv.seller(), "name", null)), "BR-06");
  }

  @Test
  void lineTotalsThatDoNotAddUpBreakBrCo10AndBrCo13() {
    assertBreaks(
        totals(t -> Examples.with(t, "lineNet", new BigDecimal("1299"))), "BR-CO-10", "BR-CO-13");
  }

  @Test
  void aTotalWithVatThatIsNotTheSumBreaksBrCo15() {
    assertBreaks(
        totals(t -> Examples.with(t, "withVat", new BigDecimal("1656.26"))),
        "BR-CO-15",
        "BR-CO-16");
  }

  @Test
  void aVatAmountMoreThanAUnitOutBreaksBrCo17AndBrS09() {
    Invoice inv = breakdown(b -> Examples.with(b, "taxAmount", new BigDecimal("333.26")));
    assertBreaks(inv, "BR-CO-17", "BR-S-09", "BR-CO-14");
  }

  @Test
  void aVatAmountInsideTheUnitOfSlackIsNotAVatRuleBreach() {
    Invoice inv = breakdown(b -> Examples.with(b, "taxAmount", new BigDecimal("331.90")));
    List<String> rules = rules(inv);
    assertFalse(rules.contains("BR-CO-17"), rules.toString());
    assertFalse(rules.contains("BR-S-09"), rules.toString());
    assertTrue(rules.contains("BR-CO-14"), "the total still has to equal the breakdown: " + rules);
  }

  @Test
  void aTaxableAmountOutByMoreThanAUnitBreaksBrS08ButInsideItDoesNot() {
    assertBreaks(
        breakdown(b -> Examples.with(b, "taxableAmount", new BigDecimal("1327"))), "BR-S-08");
    assertFalse(
        rules(breakdown(b -> Examples.with(b, "taxableAmount", new BigDecimal("1325.50"))))
            .contains("BR-S-08"));
  }

  @Test
  void anExemptInvoiceMustSayWhy() {
    Invoice exempt = recategorised("E", null);
    assertBreaks(exempt, "BR-E-10");
    Invoice explained = recategorised("E", "Exempt under the national rules for education");
    assertFalse(rules(explained).contains("BR-E-10"), rules(explained).toString());
    assertFalse(
        rules(explained).stream().anyMatch(r -> r.startsWith("BR-E-")),
        rules(explained).toString());
  }

  @Test
  void aReverseChargeInvoiceNeedsTheBuyersVatIdentity() {
    Invoice reverse = recategorised("AE", "Reverse charge");
    assertFalse(
        rules(reverse).stream().anyMatch(r -> r.startsWith("BR-AE-")), rules(reverse).toString());
    Party anonymous =
        Examples.with(Examples.with(reverse.buyer(), "vatId", null), "legalRegistration", null);
    assertBreaks(Examples.with(reverse, "buyer", anonymous), "BR-AE-02");
  }

  @Test
  void notSubjectToVatForbidsTheSellersVatIdentifierAndARate() {
    Invoice notSubject = recategorised("O", "Not subject to VAT");
    assertBreaks(notSubject, "BR-O-02", "BR-O-04");
    Party unregistered = Examples.with(notSubject.seller(), "vatId", null);
    Invoice clean = Examples.with(notSubject, "seller", unregistered);
    clean = Examples.with(clean, "buyer", Examples.with(clean.buyer(), "vatId", null));
    assertFalse(
        rules(clean).stream().anyMatch(r -> r.startsWith("BR-O-")), rules(clean).toString());
    Line rated = Examples.with(clean.lines().get(0), "vatRate", BigDecimal.ZERO);
    assertBreaks(withLine(clean, 0, rated), "BR-O-05");
  }

  @Test
  void aThirdDecimalOnAnAmountBreaksBrDec() {
    assertBreaks(totals(t -> Examples.with(t, "lineNet", new BigDecimal("1300.000"))), "BR-DEC-09");
  }

  @Test
  void codesOffTheirListsBreakTheCodeListRules() {
    Invoice inv = base();
    Line first = inv.lines().get(0);
    assertBreaks(withLine(inv, 0, Examples.with(first, "unitCode", "QQQ")), "BR-CL-23");
    assertBreaks(Examples.with(inv, "currency", "ABC"), "BR-CL-04");
    assertBreaks(
        Examples.with(inv, "seller", Examples.with(inv.seller(), "vatId", "QQ123")), "BR-CO-09");
    Identifier unknownScheme = new Identifier("12345", "9999");
    Invoice routed =
        Examples.with(inv, "buyer", Examples.with(inv.buyer(), "electronicAddress", unknownScheme));
    assertBreaks(routed, "BR-CL-25", "PEPPOL-EN16931-CL008");
  }

  @Test
  void peppolNeedsABuyerReferenceOrAnOrderReference() {
    assertBreaks(Examples.with(base(), "buyerReference", null), "PEPPOL-EN16931-R003");
    Invoice ordered =
        Examples.with(Examples.with(base(), "buyerReference", null), "orderReference", "PO-1");
    assertFalse(rules(ordered).contains("PEPPOL-EN16931-R003"));
  }

  @Test
  void peppolNeedsBothElectronicAddresses() {
    Invoice inv = base();
    Invoice noSeller =
        Examples.with(inv, "seller", Examples.with(inv.seller(), "electronicAddress", null));
    Invoice noBuyer =
        Examples.with(inv, "buyer", Examples.with(inv.buyer(), "electronicAddress", null));
    assertBreaks(noSeller, "PEPPOL-EN16931-R020");
    assertBreaks(noBuyer, "PEPPOL-EN16931-R010");
  }

  @Test
  void aLineNetAmountBeyondPeppolsTwoCentsBreaksR120() {
    Invoice inv = base();
    Line first = inv.lines().get(0);
    assertTrue(
        rules(withLine(inv, 0, Examples.with(first, "netAmount", new BigDecimal("2800.05"))))
            .contains("PEPPOL-EN16931-R120"));
    assertFalse(
        rules(withLine(inv, 0, Examples.with(first, "netAmount", new BigDecimal("2800.02"))))
            .contains("PEPPOL-EN16931-R120"));
  }

  @Test
  void aGlnWithTheWrongCheckDigitBreaksPeppolCommonR040() {
    Invoice inv = base();
    Identifier badGln = new Identifier("9482348239847239875", "0088");
    assertBreaks(
        Examples.with(inv, "seller", Examples.with(inv.seller(), "electronicAddress", badGln)),
        "PEPPOL-COMMON-R040");
  }

  @Test
  void theEn16931ProfileDoesNotApplyPeppolsRules() {
    Invoice inv = Examples.with(base(), "customizationId", Invoice.EN16931);
    inv = Examples.with(inv, "buyer", Examples.with(inv.buyer(), "electronicAddress", null));
    inv = Examples.with(inv, "buyerReference", null);
    assertEquals(Rules.Profile.EN16931, Rules.profileOf(inv));
    assertEquals(List.of(), Rules.check(inv));
    assertTrue(rules(inv, Rules.Profile.PEPPOL_BIS_3).contains("PEPPOL-EN16931-R004"));
  }

  @Test
  void anAmountDueWithoutADueDateOrTermsIsABrCo25Warning() {
    Invoice inv = Examples.with(Examples.with(base(), "dueDate", null), "paymentTerms", null);
    List<Violation> found = Rules.check(inv);
    assertEquals(1, found.size(), found.toString());
    assertEquals("BR-CO-25", found.get(0).rule());
    assertFalse(found.get(0).isFatal());
  }

  @Test
  void anInvoiceWithoutATotalVatAmountPassesWhenItsTotalsNeedNone() {
    Invoice inv = Examples.invoice("CII_example7.xml");
    assertEquals(null, inv.totals().vat());
    assertEquals(List.of(), Rules.check(inv).stream().filter(Violation::isFatal).toList());
    Invoice.Totals added =
        Examples.with(inv.totals(), "withVat", inv.totals().withoutVat().add(BigDecimal.ONE));
    assertBreaks(Examples.with(inv, "totals", added), "BR-CO-15");
  }

  @Test
  void aNumericChargeIndicatorIsReadButBreaksPeppolR043() {
    String ubl =
        EInvoices.toUbl(base())
            .replace(
                "<cbc:ChargeIndicator>true</cbc:ChargeIndicator>",
                "<cbc:ChargeIndicator>1</cbc:ChargeIndicator>");
    EInvoices.Received received =
        EInvoices.read(ubl.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    assertEquals(base(), received.invoice());
    assertTrue(
        EInvoices.validate(received).stream()
            .anyMatch(v -> "PEPPOL-EN16931-R043".equals(v.rule())));
  }

  @Test
  void aCreditTransferWithoutAnAccountBreaksBr61() {
    Invoice inv = base();
    assertBreaks(
        Examples.with(inv, "payment", Examples.with(inv.payment(), "creditTransfers", List.of())),
        "BR-61");
  }

  @Test
  void aFullCardNumberIsAWarningNotAFailure() {
    Invoice inv = base();
    PaymentInstructions card =
        Examples.with(inv.payment(), "card", new Card("4111111111111111", "VISA", "A Buyer"));
    List<Violation> found = Rules.check(Examples.with(inv, "payment", card));
    assertEquals(
        List.of(new Violation("BR-51", Violation.Severity.WARNING, found.get(0).message())), found);
  }

  @Test
  void roundingIsXpathsHalfTowardsPositiveInfinity() {
    assertEquals(new BigDecimal("0.13"), Rules.round2(new BigDecimal("0.125")));
    assertEquals(new BigDecimal("-0.12"), Rules.round2(new BigDecimal("-0.125")));
    assertEquals(new BigDecimal("2.68"), Rules.round2(new BigDecimal("2.675")));
  }

  @Test
  void nationalIdentifierChecksAcceptRealNumbersAndRefuseAChangedDigit() {
    assertTrue(Rules.gln("7300010000001"));
    assertFalse(Rules.gln("7300010000002"));
    assertTrue(Rules.norwegianOrganisation("974760673"));
    assertFalse(Rules.norwegianOrganisation("974760674"));
    assertTrue(Rules.australianBusinessNumber("51824753556"));
    assertFalse(Rules.australianBusinessNumber("51824753557"));
    assertFalse(Rules.gln("73000100000O1"), "a letter O is not a digit");
  }

  // ── helpers ─────────────────────────────────────────────────────────────────

  private static Invoice totals(UnaryOperator<Totals> change) {
    Invoice inv = base();
    return Examples.with(inv, "totals", change.apply(inv.totals()));
  }

  private static Invoice breakdown(UnaryOperator<VatBreakdown> change) {
    Invoice inv = base();
    return Examples.with(inv, "vatBreakdown", List.of(change.apply(inv.vatBreakdown().get(0))));
  }

  private static Invoice withLine(Invoice inv, int index, Line line) {
    List<Line> lines = new java.util.ArrayList<>(inv.lines());
    lines.set(index, line);
    return Examples.with(inv, "lines", lines);
  }

  /**
   * The base example with every line, charge and breakdown moved to a zero-rated category, its VAT
   * gone and its totals made to add up again.
   */
  private static Invoice recategorised(String category, String exemptionReason) {
    Invoice inv = base();
    BigDecimal zero = "O".equals(category) ? null : BigDecimal.ZERO;
    List<Line> lines =
        inv.lines().stream()
            .map(l -> Examples.with(Examples.with(l, "vatCategory", category), "vatRate", zero))
            .toList();
    List<AllowanceCharge> charges =
        inv.allowanceCharges().stream()
            .map(a -> Examples.with(Examples.with(a, "vatCategory", category), "vatRate", zero))
            .toList();
    VatBreakdown only = inv.vatBreakdown().get(0);
    VatBreakdown breakdown =
        new VatBreakdown(
            only.taxableAmount(), new BigDecimal("0.00"), category, zero, exemptionReason, null);
    Totals t = inv.totals();
    Totals totals =
        new Totals(
            t.lineNet(),
            t.allowances(),
            t.charges(),
            t.withoutVat(),
            new BigDecimal("0.00"),
            null,
            t.withoutVat(),
            null,
            null,
            t.withoutVat());
    Invoice out = Examples.with(inv, "lines", lines);
    out = Examples.with(out, "allowanceCharges", charges);
    out = Examples.with(out, "vatBreakdown", List.of(breakdown));
    return Examples.with(out, "totals", totals);
  }

  private static List<String> rules(Invoice inv) {
    return Rules.check(inv).stream().map(Violation::rule).toList();
  }

  private static List<String> rules(Invoice inv, Rules.Profile profile) {
    return Rules.check(inv, profile).stream().map(Violation::rule).toList();
  }

  private static void assertBreaks(Invoice inv, String... expected) {
    List<String> found = rules(inv);
    for (String rule : expected) {
      assertTrue(found.contains(rule), "expected " + rule + " among " + found);
    }
  }
}
