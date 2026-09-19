package com.shelfj.product.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/** Fixtures, planograms, space plans and category resets (07.17). */
public final class MerchandisingDtos {

  private MerchandisingDtos() {}

  // ── fixtures ────────────────────────────────────────────────────────────────

  @Schema(name = "Fixture")
  public record FixtureResponse(
      String id,
      String storeId,
      @Schema(description = "tenant-svc's zone, when the fixture has been pinned to an aisle.")
          String zoneId,
      String code,
      String name,
      @Schema(description = "GONDOLA, END_CAP, CHILLER, FREEZER, SHELF_RUN, BIN or COUNTER.")
          String kind,
      int shelfCount,
      int shelfWidthMm,
      @Schema(description = "Every millimetre of shelf it offers — shelves times width.")
          long totalWidthMm,
      String status,
      String createdAt) {}

  @Schema(name = "AddFixtureRequest")
  public record AddFixtureRequest(
      @NotBlank String storeId,
      @Schema(description = "Optional: the zone it stands in, once somebody has decided.")
          String zoneId,
      @NotBlank @Size(max = 40) String code,
      @NotBlank @Size(max = 120) String name,
      @NotBlank @Size(max = 20) String kind,
      @Schema(description = "Shelves, top to bottom.") @Min(1) @Max(30) int shelfCount,
      @Schema(description = "How wide one shelf is, in millimetres.") @Min(100) @Max(20000)
          int shelfWidthMm) {}

  // ── planograms ──────────────────────────────────────────────────────────────

  /**
   * One variant in one slot.
   *
   * @param capacity facings times depth, computed by the database. Read-only: a capacity sent by a
   *     caller could disagree with the layout it came from
   */
  @Schema(name = "PlanogramPosition")
  public record PositionResponse(
      String id,
      String variantId,
      @Schema(description = "1 is the top shelf.") int shelf,
      @Schema(description = "Left to right within the shelf.") int sequence,
      @Schema(description = "How many units face the customer.") int facings,
      @Schema(description = "How many sit behind each facing.") int depth,
      @Schema(description = "facings x depth, generated.") int capacity,
      @Schema(description = "The count below which the shelf looks picked over.")
          int minPresentation) {}

  @Schema(name = "PlanogramPositionRequest")
  public record PositionRequest(
      @NotBlank String variantId,
      @Min(1) int shelf,
      @Min(1) int sequence,
      @Min(1) @Max(99) int facings,
      @Min(1) @Max(99) int depth,
      @Schema(
              description =
                  "The merchandising minimum — a different number from a stock minimum, and the"
                      + " reason replenishment is driven from the shelf. At most facings x depth.")
          @Min(0)
          int minPresentation) {}

  @Schema(name = "SetPositionsRequest")
  public record SetPositionsRequest(@NotNull @Valid List<PositionRequest> positions) {}

  /**
   * How one shelf of a layout fits.
   *
   * @param unmeasured positions whose variant has no recorded facing width — the honest caveat on
   *     the numbers beside it. A layout is never refused for want of a measurement
   */
  @Schema(name = "ShelfFit")
  public record ShelfFitResponse(
      int shelf, long usedMm, long availableMm, int unmeasured, boolean overflows) {}

  @Schema(name = "Planogram")
  public record PlanogramResponse(
      String id,
      String fixtureId,
      int version,
      @Schema(description = "DRAFT, PUBLISHED or SUPERSEDED.") String status,
      String effectiveFrom,
      String note,
      @Schema(description = "The version this one replaced.") String supersedes,
      @Schema(description = "Set once a later version replaced this one.") String supersededBy,
      @Schema(description = "Every unit the layout holds when full.") int totalCapacity,
      String createdAt,
      String publishedAt,
      List<PositionResponse> positions) {}

  @Schema(name = "StartPlanogramRequest")
  public record StartPlanogramRequest(
      @Schema(description = "The day it takes effect — a layout is drawn weeks before the reset.")
          @NotBlank
          String effectiveFrom,
      @Size(max = 500) String note) {}

  // ── space planning ──────────────────────────────────────────────────────────

  @Schema(name = "SpacePlanRequest")
  public record SpacePlanRequest(
      @NotBlank String storeId,
      @NotBlank String categoryId,
      @Schema(description = "A share of the store's shelf width: 0.0825 is 8.25 per cent.") @NotNull
          BigDecimal targetShare,
      String reviewOn,
      @Size(max = 500) String note) {}

  /**
   * A category's promise against what its shelves give it.
   *
   * @param actualShare measured from the planograms in force, so it moves when a shelf is re-laid
   *     and not when somebody updates a number
   * @param variance signed — over- and under-spaced are different problems, and an absolute figure
   *     hides which one this is
   */
  @Schema(name = "SpaceLine")
  public record SpaceLineResponse(
      String categoryId,
      String targetShare,
      String actualShare,
      String variance,
      long actualMm,
      String reviewOn) {}

  // ── resets ──────────────────────────────────────────────────────────────────

  @Schema(name = "CategoryReset")
  public record ResetResponse(
      String id,
      String categoryId,
      String name,
      String scheduledFor,
      @Schema(description = "PLANNED, COMPLETED or CANCELLED.") String status,
      String cancelledReason,
      @Schema(description = "Still planned after its day — derived, never stored.") boolean overdue,
      String createdAt,
      String completedAt,
      @Schema(description = "The published layouts this reset puts on the shelf.")
          List<String> planogramIds) {}

  @Schema(name = "PlanResetRequest")
  public record PlanResetRequest(
      @NotBlank String categoryId,
      @NotBlank @Size(max = 120) String name,
      @NotBlank String scheduledFor) {}

  @Schema(name = "AttachPlanogramRequest")
  public record AttachPlanogramRequest(@NotBlank String planogramId) {}

  @Schema(name = "CancelResetRequest")
  public record CancelResetRequest(
      @Schema(
              description =
                  "Why it was called off. Required: an abandoned reset with no reason is"
                      + " what somebody asks about in six months.")
          @NotBlank
          @Size(max = 300)
          String reason) {}

  /**
   * How wide one facing of a variant is.
   *
   * @param facingWidthMm millimetres, or null to say the width is not known after all. Kept on the
   *     variant because a bottle is the width it is on every shelf it stands on
   */
  @Schema(name = "FacingWidthRequest")
  public record FacingWidthRequest(
      @Schema(description = "Millimetres, 1..5000. Null clears it.") @Min(1) @Max(5000)
          Integer facingWidthMm) {}

  // ── own brand ───────────────────────────────────────────────────────────────

  @Schema(name = "OwnBrandRequest")
  public record OwnBrandRequest(
      @Schema(
              description =
                  "True for a brand the business owns. Changes margin, range protection in a review,"
                      + " and who answers for a recall.")
          boolean ownBrand) {}
}
