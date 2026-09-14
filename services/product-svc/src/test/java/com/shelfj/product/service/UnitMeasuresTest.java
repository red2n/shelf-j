package com.shelfj.product.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.shelfj.product.domain.Domain.UnitMeasure;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The measure a unit price is shown per, from how a variant is sold (03.13). */
class UnitMeasuresTest {

  private static final Map<String, String> CLASSES =
      Map.ofEntries(
          Map.entry("KG", "WEIGHT"),
          Map.entry("G", "WEIGHT"),
          Map.entry("LB", "WEIGHT"),
          Map.entry("L", "VOLUME"),
          Map.entry("ML", "VOLUME"),
          Map.entry("CL", "VOLUME"),
          Map.entry("M", "LENGTH"),
          Map.entry("CM", "LENGTH"),
          Map.entry("SQM", "AREA"),
          Map.entry("EA", "EACH"),
          Map.entry("DOZEN", "EACH"),
          Map.entry("BAG", "EACH"),
          Map.entry("DAY", "TIME"));

  private static final Map<String, BigDecimal> FACTORS =
      Map.of(
          "G>KG", new BigDecimal("0.001"),
          "LB>KG", new BigDecimal("0.45359237"),
          "ML>L", new BigDecimal("0.001"),
          "CL>L", new BigDecimal("0.01"),
          "CM>M", new BigDecimal("0.01"),
          "DOZEN>EA", new BigDecimal("12"));

  private static UnitMeasure of(String soldBy, String content, String uom, boolean catchWeight) {
    return UnitMeasures.of(
        soldBy,
        content == null ? null : new BigDecimal(content),
        uom,
        catchWeight,
        code -> Optional.ofNullable(CLASSES.get(code)),
        (from, to) -> Optional.ofNullable(FACTORS.get(from + ">" + to)));
  }

  private static void is(UnitMeasure m, String unit, String quantity) {
    assertEquals(unit, m.unit());
    assertEquals(0, new BigDecimal(quantity).compareTo(m.quantity()), m.quantity().toPlainString());
  }

  @Test
  @DisplayName("A pack sold by the each is priced per kilogram, litre or item from what it holds")
  void packs() {
    is(of("EACH", "750", "ML", false), "L", "0.75");
    is(of("EACH", "400", "G", false), "KG", "0.4");
    is(of("EACH", "6", "EA", false), "EA", "6");
    is(of("EACH", "1", "DOZEN", false), "EA", "12");
    is(of("EACH", "1", "EA", false), "EA", "1");
    is(of("EACH", "2.5", "SQM", false), "SQM", "2.5");
  }

  @Test
  @DisplayName(
      "Loose goods sold by a measure are priced per that measure, converted to the standard")
  void loose() {
    is(of("WEIGHT", null, "KG", false), "KG", "1");
    is(of("WEIGHT", "100", "G", false), "KG", "0.1");
    is(of("WEIGHT", "1", "LB", false), "KG", "0.453592");
    is(of("VOLUME", "70", "CL", false), "L", "0.7");
    is(of("LENGTH", null, "CM", false), "M", "0.01");
    is(of("WEIGHT", null, null, true), "KG", "1");
    is(of("EACH", null, null, true), "KG", "1");
  }

  @Test
  @DisplayName("Nothing is guessed: undeclared, unconvertible or contradictory measures give none")
  void nothingGuessed() {
    assertNull(of("EACH", null, null, false), "a pack with no content stated");
    assertNull(of("EACH", null, "ML", false), "a unit with no content, sold by the each");
    assertNull(of("EACH", "1", "BAG", false), "a bag holds an unknown number of items");
    assertNull(of("WEIGHT", "1", "L", false), "sold by weight, measured in litres");
    assertNull(of("VOLUME", null, "EA", false), "sold by volume, counted in items");
    assertNull(of("EACH", "0", "G", false), "nothing in the pack");
    assertNull(of("EACH", "-5", "G", false), "less than nothing");
    assertNull(of("EACH", "3", "DAY", false), "time is not a quantity of goods");
    assertNull(of("EACH", "2", "XYZ", false), "a unit nobody defined");
    assertNull(of("VOLUME", null, null, true), "catch weight means weight, not volume");
  }
}
