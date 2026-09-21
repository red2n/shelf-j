package com.storeql.pricing.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.storeql.pricing.domain.Domain.Measure;
import com.storeql.pricing.domain.Domain.UnitPrice;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** A unit price from what a shopper pays and what it buys (03.13). */
class UnitPricingTest {

  private static UnitPrice of(String price, String unit, String quantity, String currency) {
    return UnitPricing.of(
        new BigDecimal(price),
        unit == null ? null : new Measure(unit, quantity == null ? null : new BigDecimal(quantity)),
        currency);
  }

  private static void is(UnitPrice p, String amount, String unit, String label) {
    assertEquals(new BigDecimal(amount), p.amount());
    assertEquals(unit, p.unit());
    assertEquals(label, p.label());
  }

  @Test
  @DisplayName("Per litre, per kilogram, per item: the price divided by what it buys")
  void standardUnits() {
    is(of("1.80", "L", "0.75", "GBP"), "2.40", "L", "per litre");
    is(of("0.60", "KG", "0.1", "GBP"), "6.00", "KG", "per kg");
    is(of("2.10", "EA", "6", "GBP"), "0.35", "EA", "each");
    is(of("12.50", "M", "10", "EUR"), "1.25", "M", "per metre");
    is(of("45.00", "SQM", "2.5", "EUR"), "18.00", "SQM", "per m²");
    is(of("3.49", "KG", "1", "GBP"), "3.49", "KG", "per kg");
  }

  @Test
  @DisplayName("Rounded half-up to the currency's minor unit, including one with none")
  void rounding() {
    is(of("1.00", "EA", "3", "GBP"), "0.33", "EA", "each");
    is(of("2.00", "EA", "3", "GBP"), "0.67", "EA", "each");
    is(of("598", "KG", "0.5", "JPY"), "1196", "KG", "per kg");
    is(of("199", "L", "0.33", "JPY"), "603", "L", "per litre");
    is(of("1.005", "KG", "1", "BHD"), "1.005", "KG", "per kg");
    is(of("1.00", "EA", "3", "XXQ"), "0.33", "EA", "each");
  }

  @Test
  @DisplayName(
      "No measure, a measure of nothing, or a unit that is not standard gives no unit price")
  void nothingGuessed() {
    assertNull(of("1.00", null, null, "GBP"));
    assertNull(of("1.00", "KG", null, "GBP"));
    assertNull(of("1.00", "KG", "0", "GBP"));
    assertNull(of("1.00", "KG", "-1", "GBP"));
    assertNull(of("1.00", "BAG", "1", "GBP"));
    assertNull(UnitPricing.of(null, new Measure("KG", BigDecimal.ONE), "GBP"));
  }
}
