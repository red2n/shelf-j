package com.shelfj.tenant.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.shelfj.ids.Ids;
import com.shelfj.tenant.domain.Broadcasts.Broadcast;
import com.shelfj.tenant.domain.Broadcasts.Reach;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The rules a notice turns on: who it is addressed to, when it stops being current, who is woken.
 */
class BroadcastsTest {

  private static final Instant NOW = Instant.parse("2026-09-20T10:00:00Z");

  private static Broadcast notice(UUID storeId, String role, String priority, Instant expires) {
    return new Broadcast(
        Ids.newId(),
        Ids.newId(),
        "Price change Monday",
        "Bananas go up 10p.",
        priority,
        storeId,
        role,
        false,
        NOW.minusSeconds(3600),
        expires,
        Broadcasts.PUBLISHED,
        Ids.newId(),
        null,
        null,
        null);
  }

  @Test
  @DisplayName(
      "A notice is addressed by store and by role or tier, and to everybody when neither is set")
  void addressed() {
    UUID here = Ids.newId();
    UUID elsewhere = Ids.newId();
    assertTrue(notice(null, null, Broadcasts.INFO, null).addressedTo(here, List.of("CASHIER")));
    assertTrue(notice(here, null, Broadcasts.INFO, null).addressedTo(here, List.of()));
    assertFalse(
        notice(here, null, Broadcasts.INFO, null).addressedTo(elsewhere, List.of("MANAGER")));
    Broadcast keepers = notice(null, "STOREKEEPER", Broadcasts.INFO, null);
    assertTrue(
        keepers.addressedTo(here, List.of("storekeeper")), "case is not a difference of role");
    assertFalse(keepers.addressedTo(here, List.of("CASHIER")));
    assertFalse(keepers.addressedTo(here, null), "no roles at all is nobody the notice names");
    // A custom role keeps its tier, and a notice to the tier reaches it.
    assertTrue(keepers.addressedTo(here, List.of("NIGHT_STOCK", "STOREKEEPER")));
  }

  @Test
  @DisplayName("A notice is current until it expires or is withdrawn")
  void current() {
    assertTrue(notice(null, null, Broadcasts.INFO, null).currentAt(NOW), "pinned until withdrawn");
    assertTrue(notice(null, null, Broadcasts.INFO, NOW.plusSeconds(60)).currentAt(NOW));
    assertFalse(
        notice(null, null, Broadcasts.INFO, NOW).currentAt(NOW), "expired at this very moment");
    assertFalse(notice(null, null, Broadcasts.INFO, NOW.minusSeconds(1)).currentAt(NOW));
    Broadcast withdrawn =
        new Broadcast(
            Ids.newId(),
            Ids.newId(),
            "t",
            "b",
            Broadcasts.URGENT,
            null,
            null,
            true,
            NOW.minusSeconds(60),
            null,
            Broadcasts.WITHDRAWN,
            Ids.newId(),
            NOW,
            Ids.newId(),
            "wrong price");
    assertFalse(withdrawn.currentAt(NOW));
  }

  @Test
  @DisplayName("Only an urgent notice wakes the store's devices")
  void urgency() {
    assertTrue(notice(null, null, Broadcasts.URGENT, null).wakesDevices());
    assertFalse(notice(null, null, Broadcasts.IMPORTANT, null).wakesDevices());
    assertFalse(notice(null, null, Broadcasts.INFO, null).wakesDevices());
  }

  @Test
  @DisplayName(
      "Reach names who has not acknowledged, and is complete only when nobody is outstanding")
  void reach() {
    UUID a = Ids.newId();
    Reach partial = new Reach(Ids.newId(), 3, 2, List.of(a));
    assertFalse(partial.complete());
    assertEquals(List.of(a), partial.outstanding());
    assertTrue(new Reach(Ids.newId(), 3, 3, List.of()).complete());
    assertTrue(
        new Reach(Ids.newId(), 0, 0, null).complete(),
        "a store with nobody addressed has nobody outstanding");
  }

  @Test
  @DisplayName("A notice that tells nobody anything is refused, and the reason says why")
  void refusals() {
    assertTrue(Broadcasts.problem("", "b", Broadcasts.INFO, NOW, null).contains("title"));
    assertTrue(
        Broadcasts.problem("t", " ", Broadcasts.INFO, NOW, null).contains("something to say"));
    assertTrue(
        Broadcasts.problem("t", "x".repeat(4001), Broadcasts.INFO, NOW, null).contains("document"));
    assertTrue(
        Broadcasts.problem("t", "b", "SHOUT", NOW, null).contains("INFO, IMPORTANT or URGENT"));
    assertTrue(Broadcasts.problem("t", "b", null, NOW, null).contains("INFO, IMPORTANT or URGENT"));
    assertTrue(
        Broadcasts.problem("t", "b", Broadcasts.INFO, NOW, NOW).contains("expires after"),
        "expiring the moment it is published reads as sent and tells nobody anything");
    assertTrue(
        Broadcasts.problem("t", "b", Broadcasts.INFO, NOW, NOW.minusSeconds(1))
            .contains("expires after"));
    assertNull(Broadcasts.problem("t", "b", Broadcasts.URGENT, NOW, NOW.plusSeconds(1)));
    assertNull(Broadcasts.problem("t", "x".repeat(4000), Broadcasts.INFO, NOW, null));
  }
}
