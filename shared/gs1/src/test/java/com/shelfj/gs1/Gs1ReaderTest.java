package com.shelfj.gs1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Reading a scanned code.
 *
 * <p>The cases worth writing are the ones where a wrong reading still looks like a reading: an AI
 * length off by one shifts every field after it, a measurement AI's last digit belongs to the AI
 * and not to the number, and a day of {@code 00} is a real date. Each of those fails silently — the
 * scan succeeds and names the wrong batch, the wrong weight or the wrong month.
 */
class Gs1ReaderTest {

  /** A GTIN-13 and its GTIN-14 form, check digits and all. */
  private static final String EAN13 = "5012345678900";

  private static final String EAN13_AS_GTIN14 = "05012345678900";

  /** GS1's own example GTIN-14. */
  private static final String GTIN14 = "09506000134352";

  private static final char GS = 0x1D;

  private static Gs1Scan read(String code) {
    Optional<Gs1Scan> scan = Gs1Reader.read(code);
    assertTrue(scan.isPresent(), () -> "expected a reading from " + code);
    return scan.get();
  }

  private static void unreadable(String code, String why) {
    assertTrue(Gs1Reader.read(code).isEmpty(), why + " — " + code);
  }

  @Nested
  @DisplayName("A GTIN")
  class Gtins {

    @Test
    @DisplayName("The four lengths are one identity, because a lookup has to match across them")
    void theFourLengthsAreOneIdentity() {
      // The point of normalising. A shop types the EAN-13 off the shelf edge; the case in the
      // stockroom carries the GTIN-14 of the same item. A string comparison says they differ.
      assertEquals(EAN13_AS_GTIN14, Gtin.normalise(EAN13));
      assertEquals(EAN13_AS_GTIN14, Gtin.normalise(EAN13_AS_GTIN14));
      assertEquals("00000012345670", Gtin.normalise("12345670"), "GTIN-8 pads to fourteen");
      assertEquals("00012345678905", Gtin.normalise("012345678905"), "UPC-A likewise");
    }

    @Test
    @DisplayName("A check digit is verified, never repaired")
    void theCheckDigitIsVerified() {
      // A single mistyped digit is the commonest error there is, and the alternative to refusing it
      // is
      // selling a different item at a different price.
      assertFalse(Gtin.valid("5012345678901"), "one digit out");
      assertFalse(Gtin.valid(""), "nothing at all");
      assertFalse(Gtin.valid("50123456789"), "eleven digits is not a GTIN length");
      assertFalse(Gtin.valid("501234567890a"), "not digits");
      assertTrue(Gtin.valid(GTIN14));
    }

    @Test
    @DisplayName("The weighting runs from the check digit backwards, not from the left")
    void theWeightingRunsFromTheRight() {
      // Weights alternate 3 and 1 from the digit before the check digit. Anchoring them on the left
      // instead is right for one GTIN length and wrong for the other three, so it reads EAN-13
      // correctly and refuses every GTIN-14 — a bug that only shows up on case codes.
      assertEquals(2, Gtin.checkDigit("0950600013435"), "the body of " + GTIN14);
    }
  }

  @Nested
  @DisplayName("A plain barcode")
  class Plain {

    @Test
    @DisplayName("Digits alone are a GTIN and carry nothing else")
    void digitsAlone() {
      Gs1Scan scan = read(EAN13);
      assertEquals(Gs1Scan.Format.PLAIN_GTIN, scan.format());
      assertEquals(EAN13_AS_GTIN14, scan.gtin());
      assertTrue(scan.batch().isEmpty());
      assertTrue(scan.expiry().isEmpty());
      assertTrue(scan.identifiesItem());
    }

    @Test
    @DisplayName("Something that is not a code at all is not a reading")
    void notACodeAtAll() {
      // A loyalty card, a coupon, a staff badge: ordinary things to scan, and the caller goes on to
      // treat them as the opaque string it already handles rather than seeing an error.
      unreadable("MEMBER-88213", "a membership number");
      unreadable("", "nothing");
      unreadable("   ", "whitespace");
      assertTrue(Gs1Reader.read(null).isEmpty(), "null");
    }
  }

  @Nested
  @DisplayName("An element string")
  class ElementStrings {

    @Test
    @DisplayName("A GTIN, a batch and an expiry off a food label")
    void aFoodLabel() {
      Gs1Scan scan = read("01" + GTIN14 + "17261231" + "10" + "ABC123");
      assertEquals(Gs1Scan.Format.ELEMENT_STRING, scan.format());
      assertEquals(GTIN14, scan.gtin());
      assertEquals(LocalDate.of(2026, 12, 31), scan.expiry().orElseThrow());
      assertEquals("ABC123", scan.batch().orElseThrow());
    }

    @Test
    @DisplayName(
        "A variable-length field ends at the separator, and what follows is read as itself")
    void aSeparatorEndsAVariableField() {
      // The failure this prevents: with the batch swallowing the separator and the digits after it,
      // the expiry is never seen and the batch is "ABC12317261231". The scan still succeeds.
      Gs1Scan scan = read("01" + GTIN14 + "10" + "ABC123" + GS + "17261231");
      assertEquals("ABC123", scan.batch().orElseThrow());
      assertEquals(LocalDate.of(2026, 12, 31), scan.expiry().orElseThrow());
    }

    @Test
    @DisplayName("A variable-length field at the end needs no separator")
    void aTrailingVariableField() {
      Gs1Scan scan = read("01" + GTIN14 + "10" + "LOT-9");
      assertEquals("LOT-9", scan.batch().orElseThrow());
    }

    @Test
    @DisplayName("The human form with brackets reads the same as the scan")
    void theBracketedForm() {
      // So that pasting what a supplier e-mailed finds the same item the scanner does.
      Gs1Scan scan = read("(01)" + GTIN14 + "(10)ABC123(17)261231");
      assertEquals(GTIN14, scan.gtin());
      assertEquals(
          "ABC123", scan.batch().orElseThrow(), "the bracket ends the batch, not a separator");
      assertEquals(LocalDate.of(2026, 12, 31), scan.expiry().orElseThrow());
    }

    @Test
    @DisplayName("An AI this platform does not read refuses the whole code, and does not skip it")
    void anUnknownAiRefusesTheCode() {
      // Skipping it means guessing where its data ended. Guess wrong and the rest of a food label
      // is
      // read as different fields — quietly. Refusing sends the caller back to the opaque string.
      unreadable("01" + GTIN14 + "8888" + "whatever", "an AI outside the table");
      unreadable("99" + GTIN14, "not an AI at all");
    }

    @Test
    @DisplayName("A truncated fixed-length field is a misread, not a short value")
    void aTruncatedFixedField() {
      unreadable("01" + "0950600013", "ten digits where a GTIN needs fourteen");
      unreadable("01" + GTIN14 + "172612", "four digits where a date needs six");
    }

    @Test
    @DisplayName("A GTIN that fails its check digit fails the whole reading")
    void aBadGtinFailsTheReading() {
      // Not "a code that identifies no item": a misread GTIN with a good batch beside it would
      // otherwise arrive as a batch with nothing to attach it to.
      unreadable("01" + "09506000134353" + "10ABC", "the check digit is wrong");
    }

    @Test
    @DisplayName("The same AI twice is a misread, because a code cannot name two batches")
    void theSameAiTwice() {
      unreadable("01" + GTIN14 + "10AB" + GS + "10CD", "two batches");
    }

    @Test
    @DisplayName("A code with no GTIN is still a reading — a pallet label carries an SSCC")
    void aPalletLabel() {
      Gs1Scan scan = read("00" + "106141412345678908");
      assertEquals("106141412345678908", scan.sscc().orElseThrow());
      assertFalse(scan.identifiesItem(), "it names the pallet, not what is on it");
    }

    @Test
    @DisplayName("An over-long variable field is a misread rather than a truncated value")
    void anOverLongVariableField() {
      // AI 10 allows twenty characters. Twenty-five means the length was misread somewhere earlier,
      // and quietly keeping the first twenty would invent a batch number.
      unreadable("01" + GTIN14 + "10" + "A".repeat(25), "a batch longer than the standard allows");
    }
  }

  @Nested
  @DisplayName("Measures and money")
  class Measures {

    @Test
    @DisplayName("The AI's last digit is the decimal places, and belongs to the AI")
    void theDecimalPlacesAreInTheAi() {
      // 3103 is kilograms to three places. Reading the AI as three characters puts the 3 into the
      // number and weighs the item ten times over.
      assertEquals(
          new BigDecimal("1.250"), read("01" + GTIN14 + "3103001250").netWeightKg().orElseThrow());
      assertEquals(
          new BigDecimal("125.0"), read("01" + GTIN14 + "3101001250").netWeightKg().orElseThrow());
      assertEquals(
          new BigDecimal("1250"), read("01" + GTIN14 + "3100001250").netWeightKg().orElseThrow());
    }

    @Test
    @DisplayName("Pounds stay pounds; no conversion happens in a parser")
    void poundsStayPounds() {
      Gs1Scan scan = read("01" + GTIN14 + "3202000200");
      assertEquals(new BigDecimal("2.00"), scan.netWeightLb().orElseThrow());
      assertTrue(scan.netWeightKg().isEmpty(), "a conversion is a decision, not a reading");
    }

    @Test
    @DisplayName("An amount in the shop's own currency, scaled")
    void anAmountPayable() {
      Gs1Scan scan = read("01" + GTIN14 + "3922" + "1250");
      assertEquals(new BigDecimal("12.50"), scan.amountPayable().orElseThrow());
    }

    @Test
    @DisplayName("An amount with a currency has the currency in front of it, not inside the number")
    void anAmountWithACurrency() {
      // 3932 = amount with ISO 4217, two decimals. 826 is sterling; the amount is 1250 → 12.50.
      // Scaling the whole field reads this as 8,261.25.
      Gs1Scan scan = read("01" + GTIN14 + "3932" + "8261250");
      assertEquals(new BigDecimal("12.50"), scan.amountPayable().orElseThrow());
      assertEquals("826", scan.currencyNumeric().orElseThrow());
    }
  }

  @Nested
  @DisplayName("A GS1 date")
  class Dates {

    @Test
    @DisplayName("A day of 00 is the end of the month, which is what \"best before end\" prints as")
    void dayZeroIsTheEndOfTheMonth() {
      // A till that cannot read this stops a legitimate sale; one that reads it as the 1st sells a
      // month early or refuses a month late.
      assertEquals(LocalDate.of(2026, 12, 31), Gs1Dates.parse("261200", 2026).orElseThrow());
      assertEquals(
          LocalDate.of(2028, 2, 29), Gs1Dates.parse("280200", 2026).orElseThrow(), "a leap year");
      assertEquals(LocalDate.of(2027, 2, 28), Gs1Dates.parse("270200", 2026).orElseThrow());
    }

    @Test
    @DisplayName("The century comes from the standard's window, not from prepending \"20\"")
    void theCenturyWindow() {
      // GS1's rule turns on the difference between the two-digit years: 51 to 99 ahead is the
      // previous
      // century, 50 to 99 behind is the next one, everything else is this one. So in 2026 a year of
      // 51
      // is 2051 — twenty-five years off, an ordinary long-life date — while 80 is 1980, which is
      // what a
      // production date on something old reads as and what prepending "20" would put in the future.
      assertEquals(2026, Gs1Dates.parse("260601", 2026).orElseThrow().getYear());
      assertEquals(2049, Gs1Dates.parse("490601", 2026).orElseThrow().getYear());
      assertEquals(2051, Gs1Dates.parse("510601", 2026).orElseThrow().getYear(), "still ahead");
      assertEquals(
          1980, Gs1Dates.parse("800601", 2026).orElseThrow().getYear(), "54 ahead is behind");
      // And the window moves with the clock, which is why no century is hard-coded: in 2080 a
      // two-digit 10 is thirty years ahead, not seventy behind.
      assertEquals(2110, Gs1Dates.parse("100601", 2080).orElseThrow().getYear());
    }

    @Test
    @DisplayName("A date that is not a date is no date, not a guess")
    void notADate() {
      assertTrue(Gs1Dates.parse("261301", 2026).isEmpty(), "month 13");
      assertTrue(Gs1Dates.parse("260231", 2026).isEmpty(), "31 February");
      assertTrue(Gs1Dates.parse("2612", 2026).isEmpty(), "four digits");
      assertTrue(Gs1Dates.parse("26120a", 2026).isEmpty(), "not digits");
      assertTrue(Gs1Dates.parse(null, 2026).isEmpty());
    }
  }

  @Nested
  @DisplayName("A Digital Link")
  class DigitalLinks {

    @Test
    @DisplayName("The QR code Sunrise 2027 is about: a GTIN in the path")
    void aGtinInThePath() {
      Gs1Scan scan = read("https://id.gs1.org/01/" + GTIN14);
      assertEquals(Gs1Scan.Format.DIGITAL_LINK, scan.format());
      assertEquals(GTIN14, scan.gtin());
    }

    @Test
    @DisplayName("A brand's own domain reads the same, because the standard is resolver-agnostic")
    void aBrandsOwnDomain() {
      // Refusing anything that is not id.gs1.org would refuse most real packaging.
      assertEquals(GTIN14, read("https://example.co.uk/01/" + GTIN14).gtin());
      assertEquals(
          GTIN14, read("https://shop.example.com/some/path/01/" + GTIN14 + "/10/AB").gtin());
    }

    @Test
    @DisplayName("Batch in the path, expiry and weight in the query")
    void pathAndQuery() {
      Gs1Scan scan = read("https://id.gs1.org/01/" + GTIN14 + "/10/ABC123?17=261231&3103=001250");
      assertEquals("ABC123", scan.batch().orElseThrow());
      assertEquals(LocalDate.of(2026, 12, 31), scan.expiry().orElseThrow());
      assertEquals(new BigDecimal("1.250"), scan.netWeightKg().orElseThrow());
    }

    @Test
    @DisplayName("A percent-encoded batch is decoded, because a lot number may carry a slash")
    void aPercentEncodedBatch() {
      assertEquals(
          "AB/12", read("https://id.gs1.org/01/" + GTIN14 + "/10/AB%2F12").batch().orElseThrow());
    }

    @Test
    @DisplayName("A query parameter that is not an AI is ignored, not fatal")
    void anUnknownQueryParameter() {
      // Unlike an element string, each pair is delimited — skipping one misplaces nothing.
      // Marketing
      // adds tracking parameters to these URIs constantly.
      Gs1Scan scan = read("https://id.gs1.org/01/" + GTIN14 + "?utm_source=pack&17=261231");
      assertEquals(GTIN14, scan.gtin());
      assertEquals(LocalDate.of(2026, 12, 31), scan.expiry().orElseThrow());
    }

    @Test
    @DisplayName("A link with no GTIN identifies nothing this till can sell")
    void noGtin() {
      unreadable("https://id.gs1.org/", "no keys at all");
      unreadable("https://example.com/promotions/summer", "an ordinary web page");
      unreadable("https://id.gs1.org/01/09506000134353", "a GTIN failing its check digit");
    }
  }
}
