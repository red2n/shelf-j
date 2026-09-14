package com.shelfj.inventory.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GrossMarginTest {

  private static BigDecimal d(String v) {
    return new BigDecimal(v);
  }

  private static void same(BigDecimal actual, String expected) {
    assertEquals(0, actual.compareTo(d(expected)), actual + " vs " + expected);
  }

  @Test
  @DisplayName("Margin, its percentage and GMROI, over the window and a year")
  void marginAndGmroi() {
    var r = GrossMargin.row("v", d("36.00"), d("18.00"), null, d("21.00"), d("0"), d("0"), 30);
    same(r.grossMargin(), "18.00");
    same(r.marginPercent(), "50.0");
    same(r.gmroi(), "0.86");
    same(r.annualisedGmroi(), "10.43"); // 18 x 365 / (21 x 30)
  }

  @Test
  @DisplayName("A return takes its cost back; a loss is a negative margin, not a zero")
  void returnsAndLosses() {
    var r = GrossMargin.row("v", d("24.00"), d("18.00"), d("-6.00"), d("21.00"), null, null, 1);
    same(r.cogs(), "12.00");
    same(r.grossMargin(), "12.00");
    var loss = GrossMargin.row("v", d("10.00"), d("15.00"), null, d("50.00"), null, null, 7);
    same(loss.grossMargin(), "-5.00");
    same(loss.marginPercent(), "-50.0");
    same(loss.gmroi(), "-0.10");
  }

  @Test
  @DisplayName("Nothing earned has no margin percentage; nothing held has no GMROI")
  void nullsMeanNotApplicable() {
    var unpriced = GrossMargin.row("v", null, d("6.00"), null, d("10.00"), d("0"), d("1"), 10);
    assertNull(unpriced.marginPercent());
    same(unpriced.grossMargin(), "-6.00");
    same(unpriced.unpricedSaleQty(), "1");
    var nothingHeld = GrossMargin.row("v", d("5.00"), d("2.00"), null, d("0"), null, null, 0);
    assertNull(nothingHeld.gmroi());
    assertNull(nothingHeld.annualisedGmroi());
    same(nothingHeld.marginPercent(), "60.0");
  }
}
