package com.storeql.product.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.storeql.ids.Ids;
import com.storeql.product.domain.Assortment.Change;
import com.storeql.product.domain.Assortment.Line;
import com.storeql.product.domain.Assortment.Review;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Range decisions: what is due, what is still intent, and what a review will act on.
 *
 * <p>The cases worth writing are the boundaries. A change dated today is due today, not tomorrow —
 * a range that goes live a day late is a promotion with nothing on the shelf. A change already
 * applied is never due again, because applying twice would re-list a line somebody has since
 * dropped by hand.
 */
class AssortmentTest {

  private static Change change(String action, LocalDate from, Instant appliedAt) {
    return new Change(
        Ids.newId(),
        Ids.newId(),
        Ids.newId(),
        Ids.newId(),
        null,
        action,
        from,
        "because",
        Ids.newId(),
        Instant.now(),
        appliedAt,
        null);
  }

  private static Line line(String decision, boolean ownBrand, Integer rank) {
    return line(Ids.newId(), decision, ownBrand, rank);
  }

  private static Line line(UUID variantId, String decision, boolean ownBrand, Integer rank) {
    return new Line(
        Ids.newId(),
        Ids.newId(),
        Ids.newId(),
        variantId,
        new BigDecimal("120.000"),
        new BigDecimal("340.00"),
        new BigDecimal("70.00"),
        "GBP",
        rank,
        decision,
        null,
        ownBrand);
  }

  @Test
  @DisplayName("A change dated today is due today, not tomorrow")
  void dueOnItsDay() {
    Change c = change(Assortment.LIST, LocalDate.of(2026, 10, 1), null);
    assertFalse(c.due(LocalDate.of(2026, 9, 30)), "not yet");
    assertTrue(
        c.due(LocalDate.of(2026, 10, 1)), "a range that goes live a day late is an empty shelf");
    assertTrue(
        c.due(LocalDate.of(2026, 10, 9)), "and a missed day does not make it stop being due");
  }

  @Test
  @DisplayName("An applied change is never due again")
  void appliedIsDone() {
    // Applying twice would re-list a line somebody has since dropped by hand, which is the quiet
    // way a
    // de-list gets undone.
    Change c = change(Assortment.DELIST, LocalDate.of(2026, 10, 1), Instant.now());
    assertTrue(c.applied());
    assertFalse(c.due(LocalDate.of(2026, 12, 1)));
  }

  @Test
  @DisplayName("A review knows what is left to decide, and what it will act on")
  void reviewProgress() {
    Review r =
        new Review(
            Ids.newId(),
            Ids.newId(),
            Ids.newId(),
            "Soft drinks H2",
            LocalDate.of(2026, 1, 1),
            LocalDate.of(2026, 7, 1),
            Assortment.OPEN,
            null,
            Instant.now(),
            null,
            List.of(
                line(Assortment.KEEP, false, 1),
                line(Assortment.DELIST, false, 40),
                line(Assortment.INTRODUCE, false, null),
                line(null, false, 12)));

    assertEquals(1, r.undecided(), "one line still has no decision");
    // KEEP is a decision and not an action: the line is already ranged, so closing the review does
    // nothing to it. Counting it would produce a no-op change for every line a buyer left alone.
    assertEquals(2, r.actionable().size());
    assertTrue(r.open());
  }

  @Test
  @DisplayName("Dropping an own-brand line asks for a note; dropping anything else does not")
  void ownBrandNeedsJustification() {
    // The remedy for a poor own-brand line is more often a reformulation or a price than a de-list,
    // and
    // the margin lost is the business's own. Not a refusal — a buyer may be right — but not a
    // reflex.
    assertTrue(Assortment.needsJustification(line(Assortment.DELIST, true, 40)));
    assertFalse(Assortment.needsJustification(line(Assortment.DELIST, false, 40)));
    assertFalse(Assortment.needsJustification(line(Assortment.KEEP, true, 2)));
    assertFalse(Assortment.needsJustification(line(null, true, 2)));
  }

  @Test
  @DisplayName("A review's lines are its own copy, so a caller cannot change them afterwards")
  void linesAreCopied() {
    List<Line> mutable = new java.util.ArrayList<>(List.of(line(Assortment.KEEP, false, 1)));
    Review r =
        new Review(
            Ids.newId(),
            Ids.newId(),
            Ids.newId(),
            "x",
            LocalDate.of(2026, 1, 1),
            LocalDate.of(2026, 7, 1),
            Assortment.OPEN,
            null,
            Instant.now(),
            null,
            mutable);
    mutable.add(line(Assortment.DELIST, false, 9));
    assertEquals(1, r.lines().size());
  }

  @Test
  @DisplayName("A product is de-listed only when every one of its variants was dropped")
  void delistNeedsEveryVariant() {
    // The two levels do not line up: a review reads variants, because that is what sells and what
    // gets ranked, while a range is held per product. De-listing on a partial reading takes lines
    // off the shelf that nobody looked at.
    UUID product = Ids.newId();
    UUID v1 = Ids.newId();
    UUID v2 = Ids.newId();
    Map<UUID, UUID> byVariant = Map.of(v1, product, v2, product);

    var both =
        Assortment.rangeActions(
            List.of(line(v1, Assortment.DELIST, false, 40), line(v2, Assortment.DELIST, false, 41)),
            byVariant,
            Map.of(product, 2));
    assertEquals(1, both.actions().size());
    assertEquals(Assortment.DELIST, both.actions().get(0).action());
    assertEquals(product, both.actions().get(0).productId());

    // One of the two reviewed, and dropped: the other variant is still on sale and unread.
    var partial =
        Assortment.rangeActions(
            List.of(line(v1, Assortment.DELIST, false, 40)),
            Map.of(v1, product),
            Map.of(product, 2));
    assertTrue(partial.actions().isEmpty(), "the unreviewed variant is still sold");
    assertEquals(1, partial.leftAlone().size());
    assertEquals(Assortment.UNREVIEWED_SIBLING, partial.leftAlone().get(0).reason());
  }

  @Test
  @DisplayName("Bringing a variant in beats dropping its sibling")
  void introduceWins() {
    // A new flavour replacing an old one: both decisions land on the same product, and de-listing
    // it
    // on the way in would take the replacement off the shelf with the line it replaces.
    UUID product = Ids.newId();
    UUID out = Ids.newId();
    UUID in = Ids.newId();
    var outcome =
        Assortment.rangeActions(
            List.of(
                line(out, Assortment.DELIST, false, 39),
                line(in, Assortment.INTRODUCE, false, null)),
            Map.of(out, product, in, product),
            Map.of(product, 2));
    assertEquals(1, outcome.actions().size());
    assertEquals(Assortment.LIST, outcome.actions().get(0).action());
    assertTrue(outcome.leftAlone().isEmpty());
  }

  @Test
  @DisplayName("A kept sibling keeps the product ranged, and says so")
  void keptSiblingHoldsTheRange() {
    UUID product = Ids.newId();
    UUID keep = Ids.newId();
    UUID drop = Ids.newId();
    var outcome =
        Assortment.rangeActions(
            List.of(
                line(keep, Assortment.KEEP, false, 3), line(drop, Assortment.DELIST, false, 44)),
            Map.of(keep, product, drop, product),
            Map.of(product, 2));
    assertTrue(outcome.actions().isEmpty());
    assertEquals(Assortment.KEPT_SIBLING, outcome.leftAlone().get(0).reason());
  }

  @Test
  @DisplayName("Lines left at KEEP produce nothing at all")
  void keepProducesNothing() {
    // Not even a note: a change per untouched line would fill the log with no-ops and bury the
    // decisions that matter.
    UUID product = Ids.newId();
    UUID v = Ids.newId();
    var outcome =
        Assortment.rangeActions(
            List.of(line(v, Assortment.KEEP, false, 1)), Map.of(v, product), Map.of(product, 1));
    assertTrue(outcome.actions().isEmpty());
    assertTrue(outcome.leftAlone().isEmpty());
  }

  @Test
  @DisplayName("An undecided line is not an action, and a foreign variant is ignored")
  void undecidedAndUnknownAreSkipped() {
    UUID product = Ids.newId();
    UUID mine = Ids.newId();
    UUID foreign = Ids.newId();
    var outcome =
        Assortment.rangeActions(
            List.of(line(mine, null, false, 2), line(foreign, Assortment.DELIST, false, 50)),
            Map.of(mine, product),
            Map.of(product, 1));
    assertTrue(outcome.actions().isEmpty(), "a variant the tenant does not own decides nothing");
    assertTrue(outcome.leftAlone().isEmpty());
  }
}
