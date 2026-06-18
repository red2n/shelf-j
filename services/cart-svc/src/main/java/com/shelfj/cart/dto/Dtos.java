package com.shelfj.cart.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;

/**
 * Request/response records for cart-svc. These are the API contract — never expose domain types.
 */
public final class Dtos {

  private Dtos() {}

  // ── Requests ───────────────────────────────────────────────────────────────

  public record CreateCartRequest(
      @NotBlank String storeId,
      String sessionId // null for authenticated customers; guest session token otherwise
      ) {}

  public record AddItemRequest(
      @NotBlank String cartId,
      @NotBlank String variantId,
      @NotNull @DecimalMin("0.0001") BigDecimal qty,
      BigDecimal unitPrice, // optional; stored as-is; definitive price enforced at checkout
      String sessionId // required to operate on a guest cart; ignored for authenticated carts
      ) {}

  public record UpdateItemQtyRequest(
      @NotBlank String cartId,
      @NotNull @DecimalMin("0.0001") BigDecimal qty,
      String sessionId // required to operate on a guest cart; ignored for authenticated carts
      ) {}

  public record MergeCartRequest(
      @NotBlank String sessionId // guest session to merge into the authenticated customer's cart
      ) {}

  // ── Responses ──────────────────────────────────────────────────────────────

  public record CartResponse(
      String id,
      String tenantId,
      String customerId,
      String sessionId,
      String storeId,
      String status,
      String createdAt,
      String updatedAt) {}

  public record CartItemResponse(
      String id,
      String cartId,
      String variantId,
      BigDecimal qty,
      BigDecimal unitPrice,
      String addedAt) {}

  public record CartViewResponse(CartResponse cart, List<CartItemResponse> items) {}
}
