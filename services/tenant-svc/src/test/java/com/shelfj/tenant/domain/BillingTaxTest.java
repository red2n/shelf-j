package com.shelfj.tenant.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.shelfj.tenant.domain.BillingTax.Treatment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Which VAT treatment the platform's own invoice carries (21.9).
 *
 * <p>The case worth the test is the third one: a business in another member state with no checked
 * VAT number. Treating that as a reverse charge issues an invoice with no VAT that should have
 * carried some — and it is the platform, not the business, that owes the difference.
 */
class BillingTaxTest {

  /** The platform is established in Ireland, which is in the EU. */
  private static Treatment fromIreland(
      String buyerCountry, boolean buyerInEu, String vat, boolean checked) {
    return BillingTax.decide("IE", true, buyerCountry, buyerInEu, vat, checked);
  }

  @Test
  @DisplayName("A business in the platform's own country pays the platform's own rate")
  void ownCountryPaysTheOwnRate() {
    Treatment t = fromIreland("IE", true, null, false);

    assertEquals(BillingTax.DOMESTIC, t.code());
    assertTrue(t.needsRate());
    assertEquals("IE", BillingTax.rateCountry(t, "IE", "IE"));
    // A VAT number changes nothing at home: the reverse charge is a cross-border mechanism.
    assertEquals(BillingTax.DOMESTIC, fromIreland("ie", true, "IE1234567X", true).code());
  }

  @Test
  @DisplayName("Another member state with a checked VAT number is a reverse charge, and says so")
  void anotherMemberStateWithACheckedNumber() {
    Treatment t = fromIreland("DE", true, "DE123456789", true);

    assertEquals(BillingTax.REVERSE_CHARGE, t.code());
    assertFalse(t.needsRate(), "no VAT is charged");
    assertNull(BillingTax.rateCountry(t, "IE", "DE"));
    assertTrue(
        BillingTax.REVERSE_CHARGE_WORDING.startsWith("Reverse charge"),
        "the directive requires those words on the invoice");
    assertTrue(BillingTax.REVERSE_CHARGE_WORDING.contains("2006/112/EC"));
  }

  @Test
  @DisplayName("Another member state with NO checked number is charged its own country's rate")
  void theCaseThatIsEasyToGetWrong() {
    // No number at all: a business below its country's registration threshold, say.
    Treatment none = fromIreland("DE", true, null, false);
    assertEquals(BillingTax.DESTINATION, none.code());
    assertTrue(none.needsRate());
    assertEquals("DE", BillingTax.rateCountry(none, "IE", "DE"), "Germany's rate, not Ireland's");
    assertTrue(none.reason().contains("gave no VAT number"), none.reason());

    // A number nobody has verified is not a number you may rely on: the reverse charge rests on it
    // being real, and an unchecked one is treated as absent until somebody checks it.
    Treatment unchecked = fromIreland("DE", true, "DE123456789", false);
    assertEquals(BillingTax.DESTINATION, unchecked.code());
    assertTrue(unchecked.reason().contains("has not been checked"), unchecked.reason());
  }

  @Test
  @DisplayName("Outside the EU is outside the scope")
  void outsideTheUnion() {
    Treatment t = fromIreland("US", false, "not-an-eu-number", true);

    assertEquals(BillingTax.OUT_OF_SCOPE, t.code());
    assertFalse(t.needsRate());
    assertNull(BillingTax.rateCountry(t, "IE", "US"));

    // The United Kingdom is the case the dates exist for: a member once, and not on this invoice.
    assertEquals(BillingTax.OUT_OF_SCOPE, fromIreland("GB", false, "GB123456789", true).code());
  }

  @Test
  @DisplayName("A platform outside the EU charges its own country and nothing else")
  void aPlatformOutsideTheUnion() {
    // Its own country still carries its own rate.
    assertEquals(
        BillingTax.DOMESTIC, BillingTax.decide("US", false, "US", false, null, false).code());

    // Everywhere else is outside the VAT it charges — including the EU, where it would have to
    // register. Guessing at that would be inventing a tax position nobody has taken.
    Treatment eu = BillingTax.decide("US", false, "DE", true, "DE123456789", true);
    assertEquals(BillingTax.OUT_OF_SCOPE, eu.code());
    assertTrue(eu.reason().contains("not established in the EU"), eu.reason());
  }

  @Test
  @DisplayName("A country written any way at all reaches the same answer")
  void countriesAreReadLoosely() {
    assertEquals(
        BillingTax.DOMESTIC, BillingTax.decide("ie", true, " IE ", true, null, false).code());
    assertEquals(
        BillingTax.DESTINATION,
        fromIreland(" de ", true, "  ", false).code(),
        "blank is no number");
    assertEquals(
        BillingTax.OUT_OF_SCOPE, BillingTax.decide("IE", true, null, false, null, false).code());
  }
}
