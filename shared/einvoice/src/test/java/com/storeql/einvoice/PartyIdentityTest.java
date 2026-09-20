package com.storeql.einvoice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The electronic address and VAT identifier a business types in, refused where they are wrong. */
class PartyIdentityTest {

  @Test
  void anAddressIsBothHalvesOrNothing() {
    assertNull(ElectronicAddress.parse(null, null));
    assertNull(ElectronicAddress.parse(" ", ""));
    assertEquals(
        new ElectronicAddress("9930", "DE123456789"),
        ElectronicAddress.parse(" 9930 ", "DE123456789 "));
    assertThrows(IllegalArgumentException.class, () -> ElectronicAddress.parse("9930", null));
    assertThrows(
        IllegalArgumentException.class, () -> ElectronicAddress.parse(null, "DE123456789"));
  }

  @Test
  void aSchemePeppolDoesNotRouteOnIsRefused() {
    IllegalArgumentException e =
        assertThrows(IllegalArgumentException.class, () -> ElectronicAddress.parse("9999", "123"));
    assertTrue(e.getMessage().contains("9999"));
    assertThrows(
        IllegalArgumentException.class,
        () -> ElectronicAddress.parse("<script>alert(1)</script>", "1"));
  }

  @Test
  void anIdentifierWithSpacesControlsOrTooLongIsRefused() {
    assertThrows(IllegalArgumentException.class, () -> ElectronicAddress.parse("9930", "DE 123"));
    assertThrows(IllegalArgumentException.class, () -> ElectronicAddress.parse("9930", "DE\0123"));
    assertThrows(
        IllegalArgumentException.class, () -> ElectronicAddress.parse("9930", "x".repeat(129)));
  }

  @Test
  void aGlnOrBelgianNumberWithAWrongCheckDigitIsRefused() {
    assertEquals("7300010000001", ElectronicAddress.parse("0088", "7300010000001").id());
    assertThrows(
        IllegalArgumentException.class, () -> ElectronicAddress.parse("0088", "7300010000002"));
    assertEquals("0417497106", ElectronicAddress.parse("0208", "0417497106").id());
    assertThrows(
        IllegalArgumentException.class, () -> ElectronicAddress.parse("0208", "0417497107"));
  }

  @Test
  void theSameParticipantIgnoresCaseInTheIdentifierButNotTheScheme() {
    ElectronicAddress a = new ElectronicAddress("9930", "de123456789");
    assertTrue(a.sameParticipant(new ElectronicAddress("9930", "DE123456789")));
    assertFalse(a.sameParticipant(new ElectronicAddress("9925", "DE123456789")));
    assertFalse(a.sameParticipant(null));
    assertEquals("9930:de123456789", a.toString());
    assertNull(ElectronicAddress.of(new Invoice.Identifier("123", null)));
  }

  @Test
  void aVatIdentifierIsNormalisedAndMustNameItsCountry() {
    assertEquals("GB123456789", VatIdentifier.parse(" gb 123.456-789 "));
    assertEquals("EL094259216", VatIdentifier.parse("EL094259216"));
    assertNull(VatIdentifier.parse("  "));
    assertThrows(IllegalArgumentException.class, () -> VatIdentifier.parse("123456789"));
    assertThrows(IllegalArgumentException.class, () -> VatIdentifier.parse("QQ123456789"));
    assertThrows(IllegalArgumentException.class, () -> VatIdentifier.parse("GB1"));
    assertTrue(VatIdentifier.same("GB 123 456 789", "gb123456789"));
    assertFalse(VatIdentifier.same(null, null));
  }
}
