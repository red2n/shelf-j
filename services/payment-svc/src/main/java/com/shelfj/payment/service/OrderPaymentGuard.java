package com.shelfj.payment.service;

import com.shelfj.payment.client.OrderClient;
import com.shelfj.web.ApiException;
import com.shelfj.web.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * The checks a customer-initiated payment claim must pass before any money moves.
 *
 * <p>Extracted so the two customer-facing paths — recording a tender directly, and opening a
 * payment intent with a provider — cannot drift apart. They guard the same thing: a request with no
 * staff role behind it, asserting something about an order it does not own. Duplicating these five
 * checks would mean the newer path silently missing one.
 *
 * <p>Every fact here comes from order-svc rather than the request (golden rule #1). A caller can
 * say anything; the order says what is true.
 */
@ApplicationScoped
public class OrderPaymentGuard {

  @Inject OrderClient orderClient;

  /**
   * The verified order, and the store to attribute the payment to.
   *
   * @param order what order-svc reports
   * @param storeId the order's own store, never the caller's claim
   */
  public record VerifiedOrder(OrderClient.OrderInfo order, UUID storeId) {}

  /**
   * Verifies an online payment claim against the order it targets.
   *
   * <p>A 404 rather than a 403 on the ownership check, matching the object-level reads elsewhere in
   * this service: telling a stranger that an order exists but is not theirs is itself a disclosure.
   *
   * @param tenantId owning tenant, from the verified JWT
   * @param orderId the order being paid for
   * @param amount the amount the caller claims to be paying
   * @param ctx caller identity, used for the ownership check
   * @return the verified order and the store to attribute payment to
   * @throws ApiException 404 if the order is not this tenant's, not ONLINE, or not the caller's;
   *     409 if it is not awaiting payment; 400 if the amount does not match the order total
   */
  public VerifiedOrder verifyOnlineClaim(
      UUID tenantId, UUID orderId, BigDecimal amount, TenantContext ctx) {
    OrderClient.OrderInfo order = orderClient.getOrder(tenantId, orderId);

    if (!"ONLINE".equalsIgnoreCase(order.channel())) {
      throw ApiException.notFound("PAYMENT_ORDER_NOT_FOUND", "order " + orderId + " not found");
    }
    // Only a PENDING order is awaiting payment — authorising against one already confirmed,
    // cancelled, or otherwise resolved would hold a customer's funds with no order-side effect to
    // match it.
    if (!"PENDING".equalsIgnoreCase(order.status())) {
      throw ApiException.conflict(
          "PAYMENT_ORDER_NOT_PAYABLE",
          "order " + orderId + " is not awaiting payment (status: " + order.status() + ")");
    }
    UUID callerId = ctx.userId();
    if (callerId != null
        && order.customerId() != null
        && !order.customerId().equals(callerId.toString())) {
      throw ApiException.notFound("PAYMENT_ORDER_NOT_FOUND", "order " + orderId + " not found");
    }
    if (order.total().compareTo(amount) != 0) {
      throw ApiException.badRequest(
          "PAYMENT_AMOUNT_MISMATCH",
          "tendered amount " + amount + " does not match order total " + order.total());
    }

    // The order's own storeId is authoritative, not any store the caller named — this path has no
    // staff role to trust, so an unverified store would let a guest attribute the payment to an
    // arbitrary store and corrupt that store's Z-report and reporting.
    UUID storeId = order.storeId() == null ? null : UUID.fromString(order.storeId());
    return new VerifiedOrder(order, storeId);
  }

  /**
   * Object-level authorization for payment reads, shared by tenders and intents.
   *
   * <p>Neither a tender nor an intent carries the buyer's identity directly — only the order it is
   * against — so ownership is resolved one hop away via order-svc (golden rule #1: never trust a
   * caller-supplied customerId, ask the owning service). Staff may read anything in their tenant;
   * an authenticated customer may read only what is against their own order. Denials are 404 rather
   * than 403 so ids cannot be probed for existence.
   *
   * <p>There is deliberately no exemption for a caller with no principal. No other service reads
   * payments, so that branch had no caller to serve — and a guest storefront request carries a
   * tenant with no principal, so it was reachable from outside rather than only from the mesh
   * (SJ-D13).
   *
   * @param tenantId owning tenant
   * @param orderId the order the record is against
   * @param ctx caller identity
   * @param notFound supplies the exception to throw, so each caller reports its own resource
   * @throws ApiException whatever {@code notFound} supplies, when the caller may not read it
   */
  public void requireOrderReadAccess(
      UUID tenantId,
      UUID orderId,
      TenantContext ctx,
      java.util.function.Supplier<ApiException> notFound) {
    if (isStaff(ctx)) {
      return;
    }
    OrderClient.OrderInfo order = orderClient.getOrder(tenantId, orderId);
    // ctx.userId() is null for an unidentified caller. Compare from the order's side so a null
    // principal cannot reach a .toString() — the NPE SJ-D13 uncovered when the exemption went away.
    if (order.customerId() == null
        || ctx.userId() == null
        || !order.customerId().equals(ctx.userId().toString())) {
      throw notFound.get();
    }
  }

  private static boolean isStaff(TenantContext ctx) {
    return ctx.hasRole("PLATFORM_ADMIN")
        || ctx.hasRole("OWNER")
        || ctx.hasRole("MANAGER")
        || ctx.hasRole("STOREKEEPER")
        || ctx.hasRole("CASHIER");
  }
}
