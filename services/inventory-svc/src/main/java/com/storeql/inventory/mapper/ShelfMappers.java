package com.storeql.inventory.mapper;

import com.storeql.inventory.dto.ShelfDtos;
import com.storeql.inventory.repo.ShelfTargetRepository.ShelfGap;
import java.util.List;

/** Shelf gaps on the wire (07.17). Quantities go out as strings, scale intact. */
public final class ShelfMappers {

  private ShelfMappers() {}

  public static ShelfDtos.ShelfGapResponse toDto(ShelfGap g) {
    return new ShelfDtos.ShelfGapResponse(
        g.storeId().toString(),
        g.variantId().toString(),
        g.capacity(),
        g.minPresentation(),
        g.onHand().toPlainString(),
        g.gap().toPlainString(),
        g.belowMinimum());
  }

  public static List<ShelfDtos.ShelfGapResponse> gaps(List<ShelfGap> all) {
    return all.stream().map(ShelfMappers::toDto).toList();
  }
}
