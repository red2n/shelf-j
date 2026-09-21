package com.storeql.product.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Merchandising: what gets shelf space, how much, and where it sits (07.17).
 *
 * <p>The arithmetic of a shelf, kept here rather than in SQL or a resource because it is the part
 * worth testing without a database: does a layout physically fit, how much does it hold, and what
 * share of a store did a category actually get as against what it was promised.
 *
 * <p>Two rules run through all of it.
 *
 * <p><b>A published planogram is immutable.</b> A change is a new version that supersedes it and
 * both stay, the same rule as an invoice, a statutory filing and a card attempt elsewhere here —
 * because somebody has to be able to ask what the shelf was supposed to look like last Tuesday, and
 * an edited row cannot answer that.
 *
 * <p><b>Capacity is facings &times; depth and is computed by the database.</b> Nothing in Java may
 * disagree with it. The methods here that use capacity take the value they are given rather than
 * recomputing it, so there is one definition and one place it can be wrong.
 */
public final class Merchandising {

  private Merchandising() {}

  // ── fixtures ────────────────────────────────────────────────────────────────

  public static final Set<String> FIXTURE_KINDS =
      Set.of("GONDOLA", "END_CAP", "CHILLER", "FREEZER", "SHELF_RUN", "BIN", "COUNTER");

  public static final String ACTIVE = "ACTIVE";
  public static final String RETIRED = "RETIRED";

  /**
   * A run of shelving, a chiller or an end cap.
   *
   * @param zoneId tenant-svc's zone, or null before anybody has decided which aisle it stands in.
   *     No foreign key: that table belongs to another service
   * @param shelfWidthMm what makes a layout checkable — facings times a variant's width either fits
   *     the shelf or does not
   */
  public record Fixture(
      UUID id,
      UUID tenantId,
      UUID storeId,
      UUID zoneId,
      String code,
      String name,
      String kind,
      int shelfCount,
      int shelfWidthMm,
      String status,
      Instant createdAt,
      Instant updatedAt) {

    public boolean active() {
      return ACTIVE.equals(status);
    }

    /** Every millimetre of shelf this fixture offers, which is what a space share is a share of. */
    public long totalWidthMm() {
      return (long) shelfCount * shelfWidthMm;
    }
  }

  // ── planograms ──────────────────────────────────────────────────────────────

  public static final String DRAFT = "DRAFT";
  public static final String PUBLISHED = "PUBLISHED";
  public static final String SUPERSEDED = "SUPERSEDED";

  /**
   * A fixture's layout at a version.
   *
   * @param supersededBy set when a later version replaced this one; null means this is the one that
   *     stands, which is what the partial unique index keys on
   */
  public record Planogram(
      UUID id,
      UUID tenantId,
      UUID fixtureId,
      int version,
      String status,
      LocalDate effectiveFrom,
      String note,
      UUID supersedes,
      UUID supersededBy,
      Instant createdAt,
      Instant publishedAt,
      List<Position> positions) {

    public Planogram {
      positions = positions == null ? List.of() : List.copyOf(positions);
    }

    public boolean draft() {
      return DRAFT.equals(status);
    }

    /** Whether this is the version in force: published and not replaced. */
    public boolean inForce() {
      return PUBLISHED.equals(status) && supersededBy == null;
    }

    /** Every unit the layout holds when full, which is what replenishment aims at. */
    public int totalCapacity() {
      return positions.stream().mapToInt(Position::capacity).sum();
    }
  }

  /**
   * One variant in one slot.
   *
   * @param facings how many units face the customer across the shelf
   * @param depth how many sit behind each facing
   * @param capacity {@code facings * depth}, as the database generated it. Passed in rather than
   *     recomputed so there is exactly one definition of it
   * @param minPresentation the count below which the shelf looks picked over — a merchandising
   *     minimum, which is a different number from a stock minimum and the reason replenishment is
   *     driven from the shelf and not only from the stockroom
   */
  public record Position(
      UUID id,
      UUID tenantId,
      UUID planogramId,
      UUID variantId,
      int shelf,
      int sequence,
      int facings,
      int depth,
      int capacity,
      int minPresentation) {}

  /**
   * Whether a shelf's positions fit across it.
   *
   * <p>Only the facings count towards width: depth goes backwards, not sideways, and a reading that
   * multiplied by depth would reject every layout that used the shelf properly.
   *
   * @param widths each position's variant width in millimetres, in the same order as {@code
   *     positions}
   * @return the millimetres used, which the caller compares against the fixture's shelf width
   */
  public static long widthUsedMm(List<Position> positions, List<Integer> widths) {
    if (positions.size() != widths.size()) {
      throw new IllegalArgumentException("a width is needed for each position");
    }
    long used = 0;
    for (int i = 0; i < positions.size(); i++) {
      Integer w = widths.get(i);
      if (w == null) continue; // a variant with no recorded width cannot be checked, only placed
      used += (long) positions.get(i).facings() * w;
    }
    return used;
  }

  // ── space planning ──────────────────────────────────────────────────────────

  /** What share of a store's shelf width a category is meant to hold. */
  public record SpacePlan(
      UUID id,
      UUID tenantId,
      UUID storeId,
      UUID categoryId,
      BigDecimal targetShare,
      LocalDate reviewOn,
      String note,
      Instant createdAt,
      Instant updatedAt) {}

  /**
   * A category's promised share against the share its drawn planograms actually give it.
   *
   * @param actualMm the millimetres of facing the category's positions occupy in force today
   * @param storeMm every millimetre of shelf the store has
   * @return the same shape as the target, 0..1 to four places, so the two are comparable at a
   *     glance; {@link BigDecimal#ZERO} when the store has no shelf recorded, because a share of
   *     nothing is zero and not an error
   */
  public static BigDecimal actualShare(long actualMm, long storeMm) {
    if (storeMm <= 0) return BigDecimal.ZERO;
    return BigDecimal.valueOf(actualMm)
        .divide(BigDecimal.valueOf(storeMm), 4, RoundingMode.HALF_UP);
  }

  /**
   * How far a category is from its promise, as a signed share.
   *
   * <p>Signed on purpose: over-spaced and under-spaced are different problems with different
   * remedies, and an absolute difference hides which one a buyer is looking at.
   */
  public static BigDecimal spaceVariance(BigDecimal target, BigDecimal actual) {
    return actual.subtract(target).setScale(4, RoundingMode.HALF_UP);
  }

  // ── resets ──────────────────────────────────────────────────────────────────

  public static final String PLANNED = "PLANNED";
  public static final String COMPLETED = "COMPLETED";
  public static final String CANCELLED = "CANCELLED";

  /** The day a set of published planograms goes on the shelf together. */
  public record Reset(
      UUID id,
      UUID tenantId,
      UUID categoryId,
      String name,
      LocalDate scheduledFor,
      String status,
      String cancelledReason,
      Instant createdAt,
      Instant completedAt,
      List<UUID> planogramIds) {

    public Reset {
      planogramIds = planogramIds == null ? List.of() : List.copyOf(planogramIds);
    }

    public boolean open() {
      return PLANNED.equals(status);
    }

    /**
     * Whether this reset is late: still planned after the day it was due.
     *
     * <p>Derived rather than stored, like every other deadline on this platform — a stored
     * "overdue" flag is wrong the moment nobody runs the job that sets it.
     */
    public boolean overdue(LocalDate asOf) {
      return open() && scheduledFor.isBefore(asOf);
    }
  }

  /** The replenishment target a shelf implies, published for inventory-svc to project. */
  public record ShelfTarget(
      UUID tenantId, UUID storeId, UUID variantId, int capacity, int minPresentation) {}
}
