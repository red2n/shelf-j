package com.shelfj.purchase.domain;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.comparesEqualTo;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.shelfj.web.ApiException;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Currency-aware rounding across the markets Shelf-J trades in. */
class MoneyTest {

  @Test
  @DisplayName("Minor units come from ISO 4217, not from an assumption of two")
  void minorUnitsPerCurrency() {
    assertThat(Money.scaleOf("GBP"), is(2));
    assertThat(Money.scaleOf("USD"), is(2));
    assertThat(Money.scaleOf("INR"), is(2));
    assertThat(Money.scaleOf("CNY"), is(2));
    // The one that breaks a hardcoded 2.
    assertThat(Money.scaleOf("JPY"), is(0));
    assertThat(Money.scaleOf("KRW"), is(0));
  }

  @Test
  @DisplayName("Codes are case- and whitespace-insensitive")
  void normalisation() {
    assertThat(Money.scaleOf(" jpy "), is(0));
    assertThat(Money.requireIso4217(" inr "), is("INR"));
  }

  @Test
  @DisplayName("An unrecognised code falls back to two places rather than throwing")
  void unknownCodeFallsBack() {
    assertThat(Money.scaleOf("ZZZ"), is(2));
    assertThat(Money.scaleOf(null), is(2));
  }

  @Test
  @DisplayName("Rounding is HALF_UP, at the currency's own scale")
  void roundingIsHalfUpAtCurrencyScale() {
    assertThat(
        Money.round(new BigDecimal("1.005"), "GBP"), comparesEqualTo(new BigDecimal("1.01")));
    assertThat(
        Money.round(new BigDecimal("1234.5"), "JPY"), comparesEqualTo(new BigDecimal("1235")));
    assertThat(
        Money.round(new BigDecimal("1234.4"), "JPY"), comparesEqualTo(new BigDecimal("1234")));
  }

  @Test
  @DisplayName("A JPY amount carries no decimal places at all, not merely zeroes")
  void yenScaleIsZeroNotZeroes() {
    assertThat(Money.round(new BigDecimal("1234"), "JPY").scale(), is(0));
    assertThat(Money.round(new BigDecimal("1234"), "GBP").scale(), is(2));
  }

  @Test
  @DisplayName("Client-supplied currency is validated at the boundary")
  void validationAtTheBoundary() {
    for (String good : new String[] {"GBP", "USD", "JPY", "INR", "CNY"}) {
      assertThat(Money.requireIso4217(good), is(good));
    }
    ApiException e = assertThrows(ApiException.class, () -> Money.requireIso4217("POUNDS"));
    assertThat(e.status(), is(400));
    assertThat(e.code(), is("PURCHASE_INVALID_CURRENCY"));

    assertThrows(ApiException.class, () -> Money.requireIso4217("ZZZ"));
    assertThrows(ApiException.class, () -> Money.requireIso4217(null));
    assertThrows(ApiException.class, () -> Money.requireIso4217(""));
  }

  @Test
  @DisplayName("null rounds to null — the caller decides what an absent amount means")
  void nullPassesThrough() {
    assertThat(Money.round(null, "GBP"), is((BigDecimal) null));
  }
}
