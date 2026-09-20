package com.storeql.inventory.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/** Shelf capacity against what is in the store (07.17). */
public final class ShelfDtos {

  private ShelfDtos() {}

  /**
   * One line's shelves against its stock.
   *
   * @param capacity every unit the store's fixtures hold for the line — summed, because a line
   *     sited on a gondola and an end cap has two bays to fill
   * @param minPresentation the count below which the bay looks picked over: a merchandising
   *     minimum, which is a different number from a stock minimum and the reason this report exists
   * @param gap what it would take to fill the shelves, floored at zero
   * @param belowMinimum the shelf looks picked over now, whatever the reorder level says
   */
  @Schema(name = "ShelfGap")
  public record ShelfGapResponse(
      String storeId,
      String variantId,
      int capacity,
      int minPresentation,
      @Schema(description = "Available: on hand less what is held for an order.") String available,
      String gap,
      boolean belowMinimum) {}
}
