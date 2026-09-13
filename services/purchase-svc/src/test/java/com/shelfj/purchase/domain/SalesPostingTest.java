package com.shelfj.purchase.domain;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import com.shelfj.purchase.domain.Domain.NominalLedgerEntry;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SalesPostingTest {

  private static final UUID TENANT = UUID.fromString("01a090ae-611e-702c-a97b-d1b8025478e1");
  private static final UUID ORDER = UUID.fromString("01a090ae-611e-7a00-8000-000000000001");
  private static final UUID STORE = UUID.fromString("01a090ae-611e-703c-a378-a4972ea461c8");
  private static final LocalDate DAY = LocalDate.of(2026, 9, 13);

  private static BigDecimal d(String v) {
    return new BigDecimal(v);
  }

  /** Debit less credit on one code across postings. */
  private static BigDecimal balance(List<NominalLedgerEntry> lines, String code) {
    BigDecimal b = BigDecimal.ZERO;
    for (var l : lines) {
      if (l.nominalCode().equals(code)) b = b.add(l.debit()).subtract(l.credit());
    }
    return b;
  }

  private static void same(BigDecimal actual, String expected) {
    assertThat(actual + " vs " + expected, actual.compareTo(d(expected)), is(0));
  }

  @Test
  @DisplayName("Each tender method is held in its own account, and an unmapped one is not guessed")
  void tendersMapToTheirControlAccounts() {
    assertThat(SalesPosting.controlFor("CASH"), is(SalesPosting.CASH_IN_TILLS));
    assertThat(SalesPosting.controlFor(" card "), is(SalesPosting.CARD_CLEARING));
    assertThat(SalesPosting.controlFor("upi"), is(SalesPosting.CARD_CLEARING));
    assertThat(SalesPosting.controlFor("WALLET"), is(SalesPosting.CARD_CLEARING));
    assertThat(SalesPosting.controlFor("GIFT_CARD"), is(SalesPosting.GIFT_CARD_LIABILITY));
    assertThat(SalesPosting.controlFor("VOUCHER"), is(SalesPosting.GIFT_CARD_LIABILITY));
    assertThat(SalesPosting.controlFor("STORE_CREDIT"), is(SalesPosting.STORE_CREDIT_LIABILITY));
    assertThat(SalesPosting.controlFor(null), is(SalesPosting.UNALLOCATED_RECEIPTS));
    assertThat(SalesPosting.controlFor("BARTER"), is(SalesPosting.UNALLOCATED_RECEIPTS));
  }

  @Test
  @DisplayName("A sale paid in cash and card nets clearing to zero and posts revenue net of VAT")
  void aSplitTenderSaleNetsClearing() {
    List<NominalLedgerEntry> lines = new ArrayList<>();
    lines.addAll(SalesPosting.tender(TENANT, ORDER, STORE, "CASH", d("70.00"), DAY));
    lines.addAll(SalesPosting.tender(TENANT, ORDER, STORE, "CARD", d("50.00"), DAY));
    var sale = SalesPosting.sale(TENANT, ORDER, STORE, d("120.00"), d("20.00"), DAY);
    assertThat(sale.size(), is(3));
    lines.addAll(sale);

    same(balance(lines, Domain.CODE_SALES_CLEARING), "0");
    same(balance(lines, Domain.CODE_CASH_IN_TILLS), "70.00");
    same(balance(lines, Domain.CODE_CARD_CLEARING), "50.00");
    same(balance(lines, Domain.CODE_SALES), "-100.00");
    same(balance(lines, Domain.CODE_VAT_OUTPUT), "-20.00");
    for (var l : sale) {
      assertThat(l.sourceType(), is(Domain.SOURCE_SALE));
      assertThat(l.sourceRef(), is(ORDER));
      assertThat(l.storeId(), is(STORE));
      assertThat(l.entryDate(), is(DAY));
    }
  }

  @Test
  @DisplayName("Nothing to post posts nothing; VAT is held between zero and the total")
  void edgesPostNothingOrClamp() {
    assertThat(SalesPosting.sale(TENANT, ORDER, STORE, d("0"), d("0"), DAY).isEmpty(), is(true));
    assertThat(SalesPosting.sale(TENANT, ORDER, STORE, null, null, DAY).isEmpty(), is(true));
    assertThat(SalesPosting.tender(TENANT, ORDER, STORE, "CASH", d("0"), DAY).isEmpty(), is(true));
    assertThat(SalesPosting.tender(TENANT, ORDER, STORE, "CASH", d("-5"), DAY).isEmpty(), is(true));
    var noVat = SalesPosting.sale(TENANT, ORDER, STORE, d("10.00"), null, DAY);
    assertThat(noVat.size(), is(2));
    same(balance(noVat, Domain.CODE_SALES), "-10.00");
    var negativeVat = SalesPosting.sale(TENANT, ORDER, STORE, d("10.00"), d("-3"), DAY);
    same(balance(negativeVat, Domain.CODE_SALES), "-10.00");
    var tooMuchVat = SalesPosting.sale(TENANT, ORDER, STORE, d("10.00"), d("15"), DAY);
    same(balance(tooMuchVat, Domain.CODE_VAT_OUTPUT), "-10.00");
    assertThat(
        SalesPosting.refund(TENANT, ORDER, STORE, List.of(), d("10"), d("0"), true, DAY).isEmpty(),
        is(true));
  }

  @Test
  @DisplayName("A refund takes revenue and VAT back in the sale's ratio and credits the tender")
  void aRefundReversesInTheSalesRatio() {
    var lines =
        SalesPosting.refund(
            TENANT,
            ORDER,
            STORE,
            List.of(new SalesPosting.Allocation("CARD", d("30.00"))),
            d("120.00"),
            d("20.00"),
            true,
            DAY);
    same(balance(lines, Domain.CODE_SALES), "25.00");
    same(balance(lines, Domain.CODE_VAT_OUTPUT), "5.00");
    same(balance(lines, Domain.CODE_CARD_CLEARING), "-30.00");

    // A third of a 30.00 sale with 5.00 VAT: VAT 1.67 rounded, sales takes the rest.
    var rounded =
        SalesPosting.refund(
            TENANT,
            ORDER,
            STORE,
            List.of(new SalesPosting.Allocation("CASH", d("10.00"))),
            d("30.00"),
            d("5.00"),
            true,
            DAY);
    same(balance(rounded, Domain.CODE_VAT_OUTPUT), "1.67");
    same(balance(rounded, Domain.CODE_SALES), "8.33");
  }

  @Test
  @DisplayName("A split refund credits each account once; one against an unseen sale uses clearing")
  void splitAndUnconfirmedRefunds() {
    var split =
        SalesPosting.refund(
            TENANT,
            ORDER,
            STORE,
            List.of(
                new SalesPosting.Allocation("CASH", d("5.00")),
                new SalesPosting.Allocation("CARD", d("4.00")),
                new SalesPosting.Allocation("CASH", d("1.00")),
                new SalesPosting.Allocation("STORE_CREDIT", d("0"))),
            d("100.00"),
            d("0"),
            true,
            DAY);
    same(balance(split, Domain.CODE_CASH_IN_TILLS), "-6.00");
    same(balance(split, Domain.CODE_CARD_CLEARING), "-4.00");
    assertThat(split.stream().filter(l -> l.credit().signum() > 0).count(), is(2L));

    var unseen =
        SalesPosting.refund(
            TENANT,
            ORDER,
            STORE,
            List.of(new SalesPosting.Allocation("GIFT_CARD", d("12.00"))),
            null,
            null,
            false,
            DAY);
    same(balance(unseen, Domain.CODE_SALES_CLEARING), "12.00");
    same(balance(unseen, Domain.CODE_GIFT_CARD_LIABILITY), "-12.00");
    same(balance(unseen, Domain.CODE_SALES), "0");
  }
}
