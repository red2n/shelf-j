package com.shelfj.cart.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Domain records owned by cart-svc. Never returned over HTTP — use DTOs. */
public final class Domain {

  private Domain() {}

  public record Cart(
      UUID id,
      UUID tenantId,
      UUID customerId, // null for guest carts
      String sessionId, // null for authenticated carts
      UUID storeId,
      String status, // ACTIVE | CHECKED_OUT | ABANDONED
      Instant createdAt,
      Instant updatedAt) {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_CHECKED_OUT = "CHECKED_OUT";
    public static final String STATUS_ABANDONED = "ABANDONED";
  }

  public record CartItem(
      UUID id,
      UUID cartId,
      UUID tenantId,
      UUID variantId,
      BigDecimal qty,
      BigDecimal unitPrice, // null until pricing-svc enriches the view
      Instant addedAt) {}
}
