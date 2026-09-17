package com.shelfj.iam.mfa;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CborTest {

  private static byte[] hex(String s) {
    return HexFormat.of().parseHex(s.replace(" ", ""));
  }

  @Test
  void theValuesAnAttestationUses() {
    // RFC 8949 appendix A examples.
    assertEquals(0L, Cbor.decode(hex("00")));
    assertEquals(23L, Cbor.decode(hex("17")));
    assertEquals(24L, Cbor.decode(hex("1818")));
    assertEquals(1000L, Cbor.decode(hex("1903e8")));
    assertEquals(1000000L, Cbor.decode(hex("1a000f4240")));
    assertEquals(-1L, Cbor.decode(hex("20")));
    assertEquals(-7L, Cbor.decode(hex("26")));
    assertEquals(-257L, Cbor.decode(hex("390100")));
    assertArrayEquals(hex("01020304"), (byte[]) Cbor.decode(hex("4401020304")));
    assertEquals("IETF", Cbor.decode(hex("6449455446")));
    assertEquals(List.of(1L, 2L, 3L), Cbor.decode(hex("83010203")));
    assertEquals(Boolean.TRUE, Cbor.decode(hex("f5")));
    assertEquals(Boolean.FALSE, Cbor.decode(hex("f4")));
    assertNull(Cbor.decode(hex("f6")));
    Map<?, ?> map = (Map<?, ?>) Cbor.decode(hex("a2 01 02 61 61 82 02 03"));
    assertEquals(2L, map.get(1L));
    assertEquals(List.of(2L, 3L), map.get("a"));
  }

  @Test
  void theReaderKeepsItsPlaceSoWhatFollowsAKeyCanBeRead() {
    byte[] data = hex("ff ff a1 01 02 99");
    Cbor reader = new Cbor(data, 2);
    assertEquals(Map.of(1L, 2L), reader.next());
    assertEquals(5, reader.position());
  }

  @Test
  void whatABrowserHasNoBusinessSendingIsRefused() {
    for (String bad :
        List.of(
            "", // nothing
            "5f4101ff", // an indefinite byte string
            "9f0102ff", // an indefinite array
            "bf6161 01ff", // an indefinite map
            "c11a514b67b0", // a tag
            "fb3ff199999999999a", // a float
            "f7", // undefined
            "44010203", // a string longer than the bytes that follow
            "1b8000000000000000", // a number past a long
            "5a7fffffff", // two gigabytes promised
            "a201020103", // a key given twice
            "a1820102 03", // a map keyed by an array
            "0001", // two values where one was asked for
            "98ff" + "00".repeat(255))) { // an array of 255: past what any of this needs
      assertThrows(IllegalArgumentException.class, () -> Cbor.decode(hex(bad)), bad);
    }
    // Nesting past a few levels: a well-formed bomb.
    assertThrows(IllegalArgumentException.class, () -> Cbor.decode(hex("81".repeat(20) + "00")));
  }
}
