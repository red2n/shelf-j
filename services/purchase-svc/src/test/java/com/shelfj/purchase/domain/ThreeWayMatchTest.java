package com.shelfj.purchase.domain;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;

import com.shelfj.purchase.domain.ThreeWayMatch.InvoicedLine;
import com.shelfj.purchase.domain.ThreeWayMatch.MatchLine;
import com.shelfj.purchase.domain.ThreeWayMatch.OrderPosition;
import com.shelfj.purchase.domain.ThreeWayMatch.Tolerance;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Ordered against received against invoiced.
 *
 * <p>Every case here is a way a supplier's invoice can be wrong, and each one is a way businesses
 * actually lose money: billed for what never arrived, billed twice for one delivery, billed at a
 * price nobody agreed, billed for something never ordered at all.
 */
class ThreeWayMatchTest {

  private static final UUID A = UUID.randomUUID();
  private static final UUID B = UUID.randomUUID();

  private static BigDecimal d(String v) {
    return new BigDecimal(v);
  }

  private static OrderPosition pos(
      UUID variant, String ordered, String received, String invoiced, String price) {
    return new OrderPosition(variant, d(ordered), d(received), d(invoiced), d(price));
  }

  private static List<MatchLine> match(List<InvoicedLine> inv, List<OrderPosition> pos) {
    return ThreeWayMatch.match(inv, pos, Tolerance.EXACT);
  }

  private static InvoicedLine line(UUID variant, String qty, String price) {
    return new InvoicedLine(variant, d(qty), d(price));
  }

  // ── the happy case ──────────────────────────────────────────────────────────

  @Test
  @DisplayName("Invoiced exactly what arrived, at the agreed price — no variance")
  void cleanMatch() {
    var r = match(List.of(line(A, "60", "2.50")), List.of(pos(A, "100", "60", "0", "2.50")));
    assertThat(r.get(0).variances(), is(empty()));
    assertThat(r.get(0).matched(), is(true));
  }

  @Test
  @DisplayName("A part-delivered order billed for the part is correct, not a shortfall")
  void partialDeliveryIsNotAVariance() {
    // Ordered 100, 60 arrived, invoiced 60. Matching quantity against ORDERED rather than RECEIVED
    // would flag this — and it is the single commonest legitimate case in procurement.
    var r = match(List.of(line(A, "60", "2.50")), List.of(pos(A, "100", "60", "0", "2.50")));
    assertThat(r.get(0).variances(), is(empty()));
  }

  // ── the ways money is lost ──────────────────────────────────────────────────

  @Test
  @DisplayName("Billed for more than arrived")
  void overInvoiced() {
    var r = match(List.of(line(A, "100", "2.50")), List.of(pos(A, "100", "60", "0", "2.50")));
    assertThat(r.get(0).variances(), contains(ThreeWayMatch.INVOICED_ABOVE_RECEIVED));
  }

  @Test
  @DisplayName("Billed before anything arrived")
  void nothingReceived() {
    var r = match(List.of(line(A, "10", "2.50")), List.of(pos(A, "100", "0", "0", "2.50")));
    assertThat(r.get(0).variances(), contains(ThreeWayMatch.NOT_RECEIVED));
  }

  @Test
  @DisplayName("Billed for something never ordered")
  void notOnOrder() {
    var r = match(List.of(line(B, "5", "9.99")), List.of(pos(A, "100", "60", "0", "2.50")));
    assertThat(r.get(0).variances(), contains(ThreeWayMatch.NOT_ON_ORDER));
    // Reported as its own thing rather than a quantity variance: the question is not "how many",
    // it is "what is this".
    assertThat(r.get(0).orderedUnitPrice(), is((BigDecimal) null));
  }

  @Test
  @DisplayName("Charged above the agreed price")
  void priceCreep() {
    var r = match(List.of(line(A, "60", "2.75")), List.of(pos(A, "100", "60", "0", "2.50")));
    assertThat(r.get(0).variances(), contains(ThreeWayMatch.PRICE_ABOVE_ORDER));
  }

  @Test
  @DisplayName("Charged BELOW the agreed price is still a variance")
  void priceBelow() {
    // Not a gift. It usually means the wrong goods, the wrong order, or a credit that should have
    // been a credit note — and a control that only looks one way misses half of them.
    var r = match(List.of(line(A, "60", "2.00")), List.of(pos(A, "100", "60", "0", "2.50")));
    assertThat(r.get(0).variances(), contains(ThreeWayMatch.PRICE_BELOW_ORDER));
  }

  @Test
  @DisplayName("Quantity and price can both be wrong, and both are reported")
  void bothWrong() {
    var r = match(List.of(line(A, "100", "3.00")), List.of(pos(A, "100", "60", "0", "2.50")));
    assertThat(
        r.get(0).variances(),
        containsInAnyOrder(ThreeWayMatch.INVOICED_ABOVE_RECEIVED, ThreeWayMatch.PRICE_ABOVE_ORDER));
  }

  // ── partial invoicing, which is the shape partial receipt taught ────────────

  @Test
  @DisplayName("A second invoice for the rest of a delivery is not over-invoicing")
  void cumulativeAcrossInvoices() {
    // 60 arrived; the supplier billed 40 last week and 20 now. Matching each invoice against the
    // whole receipt in isolation would pass both; matching cumulatively is what makes the third
    // invoice for another 20 fail.
    var r = match(List.of(line(A, "20", "2.50")), List.of(pos(A, "100", "60", "40", "2.50")));
    assertThat(r.get(0).variances(), is(empty()));
    assertThat(r.get(0).qtyInvoicedTotal(), is(d("60")));
  }

  @Test
  @DisplayName("…but one more after that is")
  void cumulativeCatchesTheThird() {
    var r = match(List.of(line(A, "20", "2.50")), List.of(pos(A, "100", "60", "60", "2.50")));
    assertThat(r.get(0).variances(), contains(ThreeWayMatch.INVOICED_ABOVE_RECEIVED));
  }

  // ── tolerance ───────────────────────────────────────────────────────────────

  @Test
  @DisplayName("A price inside the tolerance band is not flagged; outside it is")
  void priceTolerance() {
    var band = new Tolerance(d("5"), BigDecimal.ZERO); // 5%
    var positions = List.of(pos(A, "100", "60", "0", "2.00"));
    // 2.10 is +5%, exactly on the band.
    assertThat(
        ThreeWayMatch.match(List.of(line(A, "60", "2.10")), positions, band).get(0).variances(),
        is(empty()));
    assertThat(
        ThreeWayMatch.match(List.of(line(A, "60", "2.11")), positions, band).get(0).variances(),
        contains(ThreeWayMatch.PRICE_ABOVE_ORDER));
    // The band is symmetric — 1.90 is -5%.
    assertThat(
        ThreeWayMatch.match(List.of(line(A, "60", "1.90")), positions, band).get(0).variances(),
        is(empty()));
    assertThat(
        ThreeWayMatch.match(List.of(line(A, "60", "1.89")), positions, band).get(0).variances(),
        contains(ThreeWayMatch.PRICE_BELOW_ORDER));
  }

  @Test
  @DisplayName("A quantity inside the tolerance band is not flagged")
  void qtyTolerance() {
    var band = new Tolerance(BigDecimal.ZERO, d("10")); // 10% over-delivery billed
    var positions = List.of(pos(A, "100", "60", "0", "2.50"));
    assertThat(
        ThreeWayMatch.match(List.of(line(A, "66", "2.50")), positions, band).get(0).variances(),
        is(empty()));
    assertThat(
        ThreeWayMatch.match(List.of(line(A, "67", "2.50")), positions, band).get(0).variances(),
        contains(ThreeWayMatch.INVOICED_ABOVE_RECEIVED));
  }

  @Test
  @DisplayName("A negative tolerance is refused at construction, not silently treated as zero")
  void negativeToleranceRefused() {
    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalArgumentException.class, () -> new Tolerance(d("-1"), BigDecimal.ZERO));
    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalArgumentException.class, () -> new Tolerance(BigDecimal.ZERO, null));
  }

  // ── multi-currency and precision ────────────────────────────────────────────

  @Test
  @DisplayName("Whole-yen prices match exactly, with no invented decimals")
  void yenPrices() {
    var r = match(List.of(line(A, "3", "1234")), List.of(pos(A, "3", "3", "0", "1234")));
    assertThat(r.get(0).variances(), is(empty()));
    // One yen out is a variance — there is no sub-unit to hide in.
    var off = match(List.of(line(A, "3", "1235")), List.of(pos(A, "3", "3", "0", "1234")));
    assertThat(off.get(0).variances(), contains(ThreeWayMatch.PRICE_ABOVE_ORDER));
  }

  @Test
  @DisplayName("A sub-penny trade price is compared at its own precision")
  void subPennyPrice() {
    // 1,000 screws at 0.0125. Comparing at 2dp would make 0.0125 and 0.0149 look identical.
    var r =
        match(List.of(line(A, "1000", "0.0149")), List.of(pos(A, "1000", "1000", "0", "0.0125")));
    assertThat(r.get(0).variances(), contains(ThreeWayMatch.PRICE_ABOVE_ORDER));
  }

  @Test
  @DisplayName("A fractional quantity — 2.5 kg — matches like any other")
  void fractionalQuantity() {
    var r = match(List.of(line(A, "2.5", "4.40")), List.of(pos(A, "2.5", "2.5", "0", "4.40")));
    assertThat(r.get(0).variances(), is(empty()));
  }

  @Test
  @DisplayName("Every invoiced line comes back, in the order supplied")
  void ordering() {
    var r =
        match(
            List.of(line(A, "60", "2.50"), line(B, "5", "9.99")),
            List.of(pos(A, "100", "60", "0", "2.50")));
    assertThat(r.size(), is(2));
    assertThat(r.get(0).variantId(), is(A));
    assertThat(r.get(1).variantId(), is(B));
  }
}
