package com.storeql.ids;

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
   * Negative input on purpose: StoreQL stores only v7, but an event from outside can carry any id.
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

  // ── what is accepted from outside (RFC 9562 version 7 only) ─────────────────

  @Test
  void aCanonicalV7IsAcceptedInEitherCase() {
    UUID id = Ids.newId();
    assertEquals(id, Ids.parse(id.toString()));
    assertEquals(id, Ids.parse(id.toString().toUpperCase(java.util.Locale.ROOT)));
    assertTrue(Ids.isV7(Ids.parse("01a0905d-7082-7518-9ec6-aee90d72a43e")));
  }

  @Test
  void everyOtherVersionIsRefused() {
    for (String other :
        new String[] {
          "f81d4fae-7dec-11d0-a765-00a0c91e6bf6", // v1, RFC 9562's own example
          "000003e8-cbb9-21ea-b201-00045a86c8a1", // v2
          "5df41881-3aed-3515-88a7-2f4a814cf09e", // v3
          "919108f7-52d1-4320-9bac-f847db4148a8", // v4
          "2ed6657d-e927-568b-95e1-2665a8aea6a2", // v5
          "1ec9414c-232a-6b00-b3c8-9f6bdeced846", // v6
          "320c3d4d-cc00-875b-8ec9-32d5f69181c0", // v8
          "00000000-0000-0000-0000-000000000000", // nil
          "ffffffff-ffff-ffff-ffff-ffffffffffff" // max
        }) {
      Ids.InvalidIdException e =
          org.junit.jupiter.api.Assertions.assertThrows(
              Ids.InvalidIdException.class, () -> Ids.parse(other), other);
      assertTrue(e.getMessage().contains("not a UUIDv7"), e.getMessage());
    }
  }

  @Test
  void aVersion7WithTheWrongVariantIsRefused() {
    // Version nibble 7, but the variant bits are 110 (Microsoft) and 111 (reserved), not 10.
    for (String wrong :
        new String[] {
          "01a0905d-7082-7518-cec6-aee90d72a43e", "01a0905d-7082-7518-eec6-aee90d72a43e"
        }) {
      org.junit.jupiter.api.Assertions.assertThrows(
          Ids.InvalidIdException.class, () -> Ids.parse(wrong), wrong);
    }
  }

  @Test
  void onlyTheCanonicalFormIsAccepted() {
    // UUID.fromString takes several of these; an id that is not written the one way is not an id.
    for (String odd :
        new String[] {
          "1-1-1-1-1",
          "01a0905d70827518 9ec6aee90d72a43e",
          "01a0905d70827518-9ec6-aee90d72a43e",
          "{01a0905d-7082-7518-9ec6-aee90d72a43e}",
          " 01a0905d-7082-7518-9ec6-aee90d72a43e",
          "01a0905d-7082-7518-9ec6-aee90d72a43e ",
          "01a0905d_7082_7518_9ec6_aee90d72a43e",
          "01a0905d-7082-7518-9ec6-aee90d72a43g",
          "urn:uuid:01a0905d-7082-7518-9ec6-aee90d72a43e",
          "",
        }) {
      org.junit.jupiter.api.Assertions.assertThrows(
          Ids.InvalidIdException.class, () -> Ids.parse(odd), odd);
    }
    org.junit.jupiter.api.Assertions.assertThrows(
        Ids.InvalidIdException.class, () -> Ids.parse(null));
  }

  @Test
  void everyIdMintedOrDerivedIsV7() {
    for (int i = 0; i < 10_000; i++) {
      UUID id = Ids.newId();
      assertTrue(Ids.isV7(id), id.toString());
      assertTrue(Ids.isV7(Ids.derived(id, "line:" + i)));
    }
    // Even from a source that is not v7: the derived key is still one StoreQL accepts.
    assertTrue(Ids.isV7(Ids.derived(new UUID(0x919108f752d14320L, 0x9bacf847db4148a8L), "x")));
  }

  @Test
  void requireV7NamesWhatItRefused() {
    UUID id = Ids.newId();
    assertEquals(id, Ids.requireV7(id, "an order"));
    Ids.InvalidIdException e =
        org.junit.jupiter.api.Assertions.assertThrows(
            Ids.InvalidIdException.class,
            () -> Ids.requireV7(new UUID(0x919108f752d14320L, 0x9bacf847db4148a8L), "an order"));
    assertTrue(e.getMessage().contains("an order"), e.getMessage());
    org.junit.jupiter.api.Assertions.assertThrows(
        Ids.InvalidIdException.class, () -> Ids.requireV7(null, "an order"));
  }

  /**
   * The same vector the Flutter app's derivedId is tested against, computed a third way (Python)
   * when it was written: a key derived on a till and a key derived on the server agree bit for bit.
   */
  @Test
  void derivedIdsMatchTheSharedVector() {
    assertEquals(
        "01a0905d-7082-7bcd-a20b-17cf9c3cdc20",
        Ids.derived(Ids.parse("01a0905d-7082-7518-9ec6-aee90d72a43e"), "pay:1").toString());
  }
}
