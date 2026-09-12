package com.shelfj.pricing.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/** A VAT number with a wrong check digit is somebody else's return. */
class VrnTest {

  @Test
  void aNumberWithItsModulus97CheckDigitsPasses() {
    // 1234567 weighted 8..2 sums to 112; 112 − 97 − 97 = −82, so the check is 82.
    assertEquals("123456782", Vrn.normalise("123456782"));
    assertEquals("123456782", Vrn.normalise("GB 123 4567 82"));
    assertEquals("123456782", Vrn.normalise("gb123456782"));
  }

  @Test
  void aNumberIssuedUnderTheNewerSchemePassesTheModulus9755Check() {
    // The same seven digits with 55 added first: 167 − 97 − 97 = −27, so the check is 27.
    assertEquals("123456727", Vrn.normalise("123456727"));
  }

  @Test
  void aTypoAndTheWrongShapeAreRefused() {
    assertNull(Vrn.normalise("123456783"));
    assertNull(Vrn.normalise("12345678"));
    assertNull(Vrn.normalise("1234567820"));
    assertNull(Vrn.normalise("GB12345678O"));
    assertNull(Vrn.normalise(""));
    assertNull(Vrn.normalise(null));
  }
}
