package com.shelfj.cart.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Request/response records for cart-svc. These are the API contract — never expose domain types.
 */
public final class Dtos {

  private Dtos() {}

  // ── Requests ───────────────────────────────────────────────────────────────

  @Schema(name = "CreateCartRequest", description = "Create or resume the caller's active cart.")
  public record CreateCartRequest(
      @Schema(description = "UUID of the store this cart is scoped to.") @NotBlank String storeId,
      // null for authenticated customers. For guests, a previously server-minted token to RESUME an
      // existing cart; when absent (or no cart matches it) the server mints a new high-entropy
      // token and returns it in CartResponse.sessionId. A client-chosen value never creates a cart.
      @Schema(
              description =
                  "Guest session token to resume an existing cart. Null for authenticated"
                      + " customers. Never used to create a cart under a client-chosen id — the"
                      + " server always mints a new token for new guest carts.")
          String sessionId) {}

  @Schema(name = "AddItemRequest", description = "Add an item to a cart, or increment its qty.")
  public record AddItemRequest(
      @Schema(description = "UUID of the cart to add the item to.") @NotBlank String cartId,
      @Schema(description = "UUID of the product variant being added.") @NotBlank String variantId,
      @NotNull @DecimalMin("0.0001") BigDecimal qty,
      @Schema(
              description =
                  "Optional; stored as-is. The definitive price is enforced at checkout, not"
                      + " here.")
          BigDecimal unitPrice,
      @Schema(
              description =
                  "Required to operate on a guest cart via its session token; ignored for"
                      + " authenticated carts.")
          String sessionId) {}

  @Schema(name = "UpdateItemQtyRequest", description = "Change the qty of an existing cart item.")
  public record UpdateItemQtyRequest(
      @Schema(description = "UUID of the cart the item belongs to.") @NotBlank String cartId,
      @NotNull @DecimalMin("0.0001") BigDecimal qty,
      @Schema(
              description =
                  "Required to operate on a guest cart via its session token; ignored for"
                      + " authenticated carts.")
          String sessionId) {}

  @Schema(name = "MergeCartRequest", description = "Merge a guest cart into the customer's cart.")
  public record MergeCartRequest(
      @Schema(description = "Guest session token identifying the cart to merge in.") @NotBlank
          String sessionId) {}

  // ── Responses ──────────────────────────────────────────────────────────────

  @Schema(name = "CartResponse", description = "A cart, without its line items.")
  public record CartResponse(
      String id,
      String tenantId,
      @Schema(description = "Null for guest carts.") String customerId,
      @Schema(description = "Server-minted guest session token; null for authenticated carts.")
          String sessionId,
      @Schema(description = "UUID of the store this cart is scoped to.") String storeId,
      @Schema(description = "ACTIVE or CHECKED_OUT.") String status,
      String createdAt,
      String updatedAt) {}

  @Schema(name = "CartItemResponse", description = "A single line item within a cart.")
  public record CartItemResponse(
      String id,
      String cartId,
      @Schema(description = "UUID of the product variant.") String variantId,
      BigDecimal qty,
      @Schema(
              description =
                  "Price captured when the item was added; not authoritative at checkout.")
          BigDecimal unitPrice,
      String addedAt) {}

  @Schema(name = "CartViewResponse", description = "A cart together with its line items.")
  public record CartViewResponse(CartResponse cart, List<CartItemResponse> items) {}
}
