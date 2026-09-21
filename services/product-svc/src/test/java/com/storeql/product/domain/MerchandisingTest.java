package com.storeql.product.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.storeql.ids.Ids;
import com.storeql.product.domain.Merchandising.Fixture;
import com.storeql.product.domain.Merchandising.Planogram;
import com.storeql.product.domain.Merchandising.Position;
import com.storeql.product.domain.Merchandising.Reset;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The arithmetic of a shelf.
 *
 * <p>The cases worth writing are the ones where a plausible reading is wrong: depth counting
 * towards width (it goes backwards, not sideways), an absolute space variance hiding whether a
 * category is over- or under-spaced, and a share of a store with no shelf recorded.
 */
class MerchandisingTest {

  private static Position at(int shelf, int sequence, int facings, int depth) {
    return new Position(
        Ids.newId(),
        Ids.newId(),
        Ids.newId(),
        Ids.newId(),
        shelf,
        sequence,
        facings,
        depth,
        facings * depth,
        0);
  }

  private static Fixture fixture(int shelves, int widthMm) {
    return new Fixture(
        Ids.newId(),
        Ids.newId(),
        Ids.newId(),
        null,
        "G1",
        "Gondola 1",
        "GONDOLA",
        shelves,
        widthMm,
        Merchandising.ACTIVE,
        Instant.now(),
        Instant.now());
  }

  @Test
  @DisplayName("A fixture's shelf width is every shelf, because a share is a share of all of it")
  void totalWidth() {
    assertEquals(5 * 1250L, fixture(5, 1250).totalWidthMm());
    // The arithmetic is long, not int: a large store's fixtures multiply past two billion
    // millimetres
    // in aggregate, and a silent overflow would make every share wrong at once.
    assertEquals(30 * 20000L, fixture(30, 20000).totalWidthMm());
  }

  @Test
  @DisplayName("Only facings take width — depth goes backwards, not sideways")
  void widthCountsFacingsNotDepth() {
    // The mistake this guards: multiplying by depth as well rejects every layout that stacks
    // properly,
    // which is every well-merchandised shelf.
    List<Position> positions = List.of(at(1, 1, 3, 4), at(1, 2, 2, 9));
    assertEquals(3L * 80 + 2L * 65, Merchandising.widthUsedMm(positions, List.of(80, 65)));
  }

  @Test
  @DisplayName("A variant with no recorded width is placed but not checked")
  void aMissingWidthIsSkipped() {
    // Better than refusing the layout: most catalogues have gaps, and a planogram nobody can save
    // is
    // worse than one whose width check is partial and says so.
    List<Position> positions = List.of(at(1, 1, 3, 1), at(1, 2, 2, 1));
    // Arrays.asList and not List.of: List.of rejects a null element, so the test would throw before
    // reaching the code it is about.
    assertEquals(3L * 80, Merchandising.widthUsedMm(positions, java.util.Arrays.asList(80, null)));
  }

  @Test
  @DisplayName("A width for each position, or the answer would be quietly wrong")
  void widthsMustLineUp() {
    assertThrows(
        IllegalArgumentException.class,
        () -> Merchandising.widthUsedMm(List.of(at(1, 1, 1, 1)), List.of(80, 65)));
  }

  @Test
  @DisplayName("Capacity is what the layout holds, summed from what the database generated")
  void totalCapacity() {
    Planogram p =
        new Planogram(
            Ids.newId(),
            Ids.newId(),
            Ids.newId(),
            1,
            Merchandising.PUBLISHED,
            LocalDate.of(2026, 10, 1),
            null,
            null,
            null,
            Instant.now(),
            Instant.now(),
            List.of(at(1, 1, 3, 4), at(1, 2, 2, 2), at(2, 1, 5, 1)));
    assertEquals(12 + 4 + 5, p.totalCapacity());
  }

  @Test
  @DisplayName("The version in force is published and not replaced")
  void inForce() {
    UUID later = Ids.newId();
    Planogram published =
        new Planogram(
            Ids.newId(),
            Ids.newId(),
            Ids.newId(),
            2,
            Merchandising.PUBLISHED,
            LocalDate.of(2026, 10, 1),
            null,
            null,
            null,
            Instant.now(),
            Instant.now(),
            List.of());
    Planogram replaced =
        new Planogram(
            Ids.newId(),
            Ids.newId(),
            Ids.newId(),
            1,
            Merchandising.SUPERSEDED,
            LocalDate.of(2026, 9, 1),
            null,
            null,
            later,
            Instant.now(),
            Instant.now(),
            List.of());
    Planogram drawing =
        new Planogram(
            Ids.newId(),
            Ids.newId(),
            Ids.newId(),
            3,
            Merchandising.DRAFT,
            LocalDate.of(2026, 11, 1),
            null,
            null,
            null,
            Instant.now(),
            null,
            List.of());

    assertTrue(published.inForce());
    assertFalse(replaced.inForce(), "a replaced version is history, not the shelf");
    assertFalse(drawing.inForce(), "a drawing is not a shelf");
    assertTrue(drawing.draft());
  }

  @Test
  @DisplayName(
      "A category's actual share is measured, and a store with no shelf is zero not an error")
  void actualShare() {
    assertEquals(new BigDecimal("0.0825"), Merchandising.actualShare(825, 10000));
    assertEquals(new BigDecimal("0.3333"), Merchandising.actualShare(1000, 3000));
    // A store whose fixtures nobody has recorded yet: a share of nothing is nothing. Throwing here
    // would make the whole space report unreadable for one unmeasured store.
    assertEquals(BigDecimal.ZERO, Merchandising.actualShare(500, 0));
    assertEquals(BigDecimal.ZERO, Merchandising.actualShare(0, 0));
  }

  @Test
  @DisplayName("Space variance is signed, because over and under are different problems")
  void spaceVarianceIsSigned() {
    // An absolute difference tells a buyer the size of the gap and hides which way to close it.
    assertEquals(
        new BigDecimal("0.0175"),
        Merchandising.spaceVariance(new BigDecimal("0.0650"), new BigDecimal("0.0825")));
    assertEquals(
        new BigDecimal("-0.0175"),
        Merchandising.spaceVariance(new BigDecimal("0.0825"), new BigDecimal("0.0650")));
    assertEquals(
        BigDecimal.ZERO.setScale(4),
        Merchandising.spaceVariance(new BigDecimal("0.1000"), new BigDecimal("0.1000")));
  }

  @Test
  @DisplayName("A reset is late when it is still planned after its day, and that is derived")
  void resetOverdue() {
    Reset planned =
        new Reset(
            Ids.newId(),
            Ids.newId(),
            Ids.newId(),
            "Spring soft drinks",
            LocalDate.of(2026, 10, 1),
            Merchandising.PLANNED,
            null,
            Instant.now(),
            null,
            List.of());
    assertFalse(planned.overdue(LocalDate.of(2026, 9, 30)));
    assertFalse(planned.overdue(LocalDate.of(2026, 10, 1)), "on the day it is due, not late");
    assertTrue(planned.overdue(LocalDate.of(2026, 10, 2)));

    Reset done =
        new Reset(
            Ids.newId(),
            Ids.newId(),
            Ids.newId(),
            "Spring soft drinks",
            LocalDate.of(2026, 10, 1),
            Merchandising.COMPLETED,
            null,
            Instant.now(),
            Instant.now(),
            List.of());
    assertFalse(done.overdue(LocalDate.of(2027, 1, 1)), "a done reset is never late");
    assertFalse(done.open());
  }

  @Test
  @DisplayName("A reset's planogram list is its own copy, so a caller cannot change it afterwards")
  void resetListIsCopied() {
    List<UUID> mutable = new java.util.ArrayList<>(List.of(Ids.newId()));
    Reset r =
        new Reset(
            Ids.newId(),
            Ids.newId(),
            Ids.newId(),
            "x",
            LocalDate.of(2026, 10, 1),
            Merchandising.PLANNED,
            null,
            Instant.now(),
            null,
            mutable);
    mutable.add(Ids.newId());
    assertEquals(1, r.planogramIds().size());
  }
}
