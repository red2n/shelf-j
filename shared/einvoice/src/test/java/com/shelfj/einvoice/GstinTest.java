package com.shelfj.einvoice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** India's GST identification number: its shape, its state and its check character. */
class GstinTest {

  @Test
  void registeredNumbersParseAsTyped() {
    assertEquals("27AAPFU0939F1ZV", Gstin.parse("27AAPFU0939F1ZV"));
    assertEquals("29AAGCB7383J1Z4", Gstin.parse(" 29aagcb7383j1z4 "));
    assertEquals("09AAACH7409R1ZZ", Gstin.parse("09-AAACH7409R1ZZ"));
    assertNull(Gstin.parse(null));
    assertNull(Gstin.parse("  "));
    assertTrue(Gstin.valid("27AAPFU0939F1ZV"));
    assertEquals("27", Gstin.stateCode("27AAPFU0939F1ZV"));
    assertTrue(Gstin.isStateCode("38"));
    assertTrue(Gstin.isStateCode("97"));
    assertFalse(Gstin.isStateCode("96"));
  }

  @Test
  void aWrongCheckCharacterIsRefused() {
    IllegalArgumentException e =
        assertThrows(IllegalArgumentException.class, () -> Gstin.parse("27AAPFU0939F1ZW"));
    assertTrue(e.getMessage().contains("check character"));
    // One character mistyped anywhere changes what the check character must be.
    assertFalse(Gstin.valid("27AAPFU0938F1ZV"));
    assertFalse(Gstin.valid("72AAPFU0939F1ZV"));
  }

  @Test
  void aShapeOrStateGstDoesNotHaveIsRefused() {
    assertThrows(IllegalArgumentException.class, () -> Gstin.parse("27AAPFU0939F1Z"));
    assertThrows(IllegalArgumentException.class, () -> Gstin.parse("27AAPFU0939F1XV"));
    assertThrows(IllegalArgumentException.class, () -> Gstin.parse("GB123456789"));
    String stateless = "99AAPFU0939F1Z";
    IllegalArgumentException e =
        assertThrows(
            IllegalArgumentException.class,
            () -> Gstin.parse(stateless + Gstin.checkCharacter(stateless)));
    assertTrue(e.getMessage().contains("99"));
    assertFalse(Gstin.valid("<script>alert(1)</script>"));
  }
}
