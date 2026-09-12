package com.shelfj.pricing.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** The sticker's barcode is what the till reads; a digit wrong is a wrong price at the till. */
class MarkdownLabelTest {

  @Test
  void aStickerIsPrefixItemPriceAndCheck() {
    String code = MarkdownLabel.encode(42, new BigDecimal("7.00"));
    assertEquals(13, code.length());
    assertTrue(code.startsWith("2100042"));
    assertEquals("00700", code.substring(7, 12));
    assertTrue(MarkdownLabel.isLabel(code));
    assertEquals(new BigDecimal("7.00"), MarkdownLabel.priceOf(code));
  }

  @Test
  void theCheckDigitIsEan13s() {
    // 4006381333931 is a well-known valid EAN-13.
    assertEquals('1', MarkdownLabel.checkDigit("400638133393"));
    String code = MarkdownLabel.encode(1, new BigDecimal("0.99"));
    String tampered = code.substring(0, 12) + (code.charAt(12) == '0' ? '1' : '0');
    assertFalse(MarkdownLabel.isLabel(tampered));
    assertNull(MarkdownLabel.priceOf(tampered));
  }

  @Test
  void itemNumbersWrapAtFiveDigitsAndPricesMustFit() {
    assertTrue(MarkdownLabel.encode(100001, BigDecimal.ONE).startsWith("2100001"));
    assertThrows(
        IllegalArgumentException.class, () -> MarkdownLabel.encode(1, new BigDecimal("1000.00")));
    assertThrows(
        IllegalArgumentException.class, () -> MarkdownLabel.encode(1, new BigDecimal("-1")));
    assertEquals(
        new BigDecimal("999.99"),
        MarkdownLabel.priceOf(MarkdownLabel.encode(7, new BigDecimal("999.99"))));
  }

  @Test
  void anOrdinaryBarcodeOrAScaleLabelIsNotASticker() {
    assertFalse(MarkdownLabel.isLabel("5012345678900"));
    assertFalse(MarkdownLabel.isLabel("2012345678901"));
    assertFalse(MarkdownLabel.isLabel("21ABC"));
    assertFalse(MarkdownLabel.isLabel(null));
  }
}
