package com.storeql.inventory.service;

import com.storeql.inventory.repo.ShelfTargetRepository;
import com.storeql.inventory.repo.ShelfTargetRepository.ShelfGap;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.UUID;

/**
 * Replenishment read from the shelf rather than from a reorder level (07.17).
 *
 * <p>Thin on purpose: the arithmetic belongs in the query, because the sum across a line's fixtures
 * and the join to live stock are set operations, and pulling every target into memory to add them
 * up would be slower and no clearer. What this class is for is being the one door the resource
 * knocks on — and the place the replenishment run will read the same numbers from when it drives
 * ordering off the shelf rather than off a level.
 */
@ApplicationScoped
public class ShelfSpaceService {

  @Inject ShelfTargetRepository targets;

  /**
   * What it would take to fill a store's shelves, deepest gap first.
   *
   * @param tenantId the owning business; the first condition of the query
   * @param storeId the store whose shelves to read
   * @param limit rows to return
   */
  public List<ShelfGap> gaps(UUID tenantId, UUID storeId, int limit) {
    return targets.gaps(tenantId, storeId, limit);
  }
}
