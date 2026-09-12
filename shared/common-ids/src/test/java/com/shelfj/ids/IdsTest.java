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
    assertEquals("00000000", Ids.shortRef(UUID.fromString("01a0905d-7082-7518-8000-000000000000")));
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

  // ── derived ids ─────────────────────────────────────────────────────────────

  private static final UUID EVENT = UUID.fromString("01a0905d-7082-7518-9ec6-aee90d72a43e");

  /**
   * Pinned, not just self-consistent: consumers dedupe on these, so if the algorithm changed, an
   * event redelivered across the deploy would be processed a second time.
   */
  @Test
  void aDerivedIdIsPinnedForAGivenSourceAndName() {
    assertEquals(
        UUID.fromString("01a0905d-7082-77a2-ac9d-f2f6c3f921b9"),
        Ids.derived(EVENT, "inventory-order-events:0"));
  }

  @Test
  void theSameSourceAndNameAlwaysGiveTheSameId() {
    assertEquals(Ids.derived(EVENT, "line:3"), Ids.derived(EVENT, "line:3"));
  }

  @Test
  void aDerivedIdIsVersion7WithTheSourcesTimestamp() {
    UUID derived = Ids.derived(EVENT, "line:0");

    assertEquals(7, derived.version());
    assertEquals(2, derived.variant());
    assertEquals(EVENT.getMostSignificantBits() >>> 16, derived.getMostSignificantBits() >>> 16);
  }

  /** The worst case for a per-line key: one huge event. Every line must get its own id. */
  @Test
  void everyLineOfOneEventGetsItsOwnId() {
    Set<UUID> ids = new HashSet<>();
    for (int line = 0; line < 10_000; line++) {
      ids.add(Ids.derived(EVENT, "inventory-order-events:" + line));
    }

    assertEquals(10_000, ids.size());
  }

  @Test
  void differentSourcesOrNamesGiveDifferentIds() {
    UUID other = UUID.fromString("01a0905d-7082-7518-9ec6-aee90d72a43f");

    assertNotEquals(Ids.derived(EVENT, "line:0"), Ids.derived(other, "line:0"));
    assertNotEquals(Ids.derived(EVENT, "line:0"), Ids.derived(EVENT, "line:1"));
    assertNotEquals(Ids.derived(EVENT, "a:bc"), Ids.derived(EVENT, "ab:c"));
  }

  /**
   * Negative input on purpose: Shelf-J stores only v7, but an event from outside can carry any id.
   * One with no timestamp in it still yields a stable key that is itself v7.
   */
  @Test
  void aSourceThatIsNotV7StillGivesAStableV7Key() {
    UUID notV7 = UUID.fromString("f47ac10b-58cc-4372-a567-0e02b2c3d479");
    UUID derived = Ids.derived(notV7, "line:0");

    assertEquals(UUID.fromString("00000000-0000-7c5d-ac9e-f81d51614b48"), derived);
    assertEquals(7, derived.version());
  }

  @Test
  void derivingNeedsBothASourceAndAName() {
    assertThrows(NullPointerException.class, () -> Ids.derived(null, "line:0"));
    assertThrows(NullPointerException.class, () -> Ids.derived(EVENT, null));
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
