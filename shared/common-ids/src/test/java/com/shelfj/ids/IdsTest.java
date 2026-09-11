package com.shelfj.ids;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IdsTest {

  private static final long NOW = 1_757_590_000_000L;

  @Test
  void aShortRefIsTheLastEightHexDigits() {
    assertEquals("0d72a43e", Ids.shortRef(UUID.fromString("01a0905d-7082-7518-9ec6-aee90d72a43e")));
  }

  @Test
  void leadingZerosInTheTailAreKept() {
    assertEquals("0000abcd", Ids.shortRef(UUID.fromString("01a0905d-7082-7518-8000-00000000abcd")));
    assertEquals("00000000", Ids.shortRef(new UUID(0, 0)));
  }

  /** Rows stored before the switch keep v4 ids; their handles must come out the same way. */
  @Test
  void legacyV4IdsGetTheirTailToo() {
    assertEquals("b2c3d479", Ids.shortRef(UUID.fromString("f47ac10b-58cc-4372-a567-0e02b2c3d479")));
  }

  /**
   * The case this exists for: a burst of ids inside one millisecond, as a busy till or a bulk
   * import makes. Their first eight characters are all the same clock reading; their handles must
   * not be.
   */
  @Test
  void idsFromTheSameMillisecondShareTheirHeadButNotTheirShortRef() {
    UuidV7Generator oneMillisecond =
        new UuidV7Generator(() -> NOW, new SplittableRandom(7)::nextLong);
    Set<String> heads = new HashSet<>();
    Set<String> refs = new HashSet<>();
    for (int i = 0; i < 1_000; i++) {
      UUID id = oneMillisecond.next();
      heads.add(id.toString().substring(0, 8));
      refs.add(Ids.shortRef(id));
    }

    assertEquals(1, heads.size(), "a v7 id's first eight characters are its timestamp");
    assertEquals(1_000, refs.size());
  }

  /** Lowercase hex matters: inventory recognises its own batch numbers by that shape. */
  @Test
  void everyShortRefIsEightLowercaseHexDigits() {
    for (int i = 0; i < 10_000; i++) {
      String ref = Ids.shortRef(Ids.newId());
      assertEquals(Ids.SHORT_REF_LENGTH, ref.length());
      assertTrue(ref.matches("[0-9a-f]{8}"), ref);
    }
  }

  @Test
  void aNullIdIsRejected() {
    NullPointerException e = assertThrows(NullPointerException.class, () -> Ids.shortRef(null));
    assertEquals("id", e.getMessage());
  }

  /** A handle for people, not a key: different ids can share one, so nothing may look up by it. */
  @Test
  void twoDifferentIdsCanShareAShortRef() {
    UUID a = UUID.fromString("01a0905d-7082-7518-9ec6-aee90d72a43e");
    UUID b = UUID.fromString("01a0ffff-0000-7000-8000-00000d72a43e");

    assertNotEquals(a, b);
    assertEquals(Ids.shortRef(a), Ids.shortRef(b));
  }
}
