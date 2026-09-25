package com.storeql.order.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One checkout placed as several orders, one per store (order orchestration): the shopper's reading
 * of it. Each part is an ordinary order with its own store, lines, holds and total; the group's
 * total is theirs added up.
 *
 * @param parts the orders, the delivery-area store's first
 */
public record OrderGroup(
    UUID id,
    UUID tenantId,
    UUID customerId,
    UUID loginId,
    BigDecimal total,
    String currency,
    Instant createdAt,
    List<Part> parts) {

  public OrderGroup {
    parts = List.copyOf(parts);
  }

  /**
   * One order of the group, as the group lists it.
   *
   * @param units how many items it carries, its lines' quantities added up
   */
  public record Part(
      UUID orderId, UUID storeId, String status, BigDecimal total, BigDecimal units) {}
}
