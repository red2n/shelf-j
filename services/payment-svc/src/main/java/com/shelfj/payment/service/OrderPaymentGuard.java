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
    // Matched on the login the order was placed with: that is the id the shopper's token carries.
    // It used to be compared against customerId, which worked only because order-svc stamped the
    // login into that column — the confusion SJ-D44 unpicked. A guest order has neither id, and
    // stays payable by whoever holds its id, as before.
    UUID callerId = ctx.userId();
    if (callerId != null
        && order.loginId() != null
        && !order.loginId().equals(callerId.toString())) {
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
   * <p>There is deliberately no exemption for a caller with no principal <em>in general</em>. No
   * other service reads payments, so that branch had no caller to serve — and a guest storefront
   * request carries a tenant with no principal, so it was reachable from outside rather than only
   * from the mesh (SJ-D13).
   *
   * <p><b>An order with no customer is the one exception, and it is about the order rather than the
   * caller.</b> A guest checkout has no identity to bind to: {@code customerId} is null because
   * nobody was signed in. Requiring a matching principal therefore made the guest SCA flow
   * impossible — and both the gateway and {@code AdminAuthorizationFilter} open this path
   * specifically so a guest can poll their intent after returning from the provider, the gateway's
   * own comment saying so in as many words. Two layers deliberately allowed a request the third
   * refused, so the flow could never have worked.
   *
   * <p>For such an order the intent id is the capability: an unguessable UUID held only by whoever
   * opened the checkout. That is the same trust model as the provider's own client secret. It is
   * <em>not</em> the SJ-D13 bypass returning: that branch let an anonymous caller read <b>any</b>
   * order, customer-owned ones included. This one turns on the order having no owner at all, so an
   * order that belongs to somebody still requires being that somebody, and the amount of privacy
   * left to breach on an ownerless order is its own status and amount.
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
    // A guest order has no owner to match against, and the intent id is the capability. See the
    // class note above for why this is not SJ-D13's bypass: that one keyed on the CALLER having no
    // principal, which is attacker-controlled; this keys on the ORDER having no customer, which is
    // a fact about the order and cannot be arranged by the caller.
    // An order with no login was not placed by a signed-in shopper: either a guest checkout, whose
    // intent id is the capability, or a till sale, which no customer token can reach anyway because
    // this branch is only reached without a staff role. Checking customerId here instead would deny
    // the owner their own intent, since that id is the shop's record of them and not what their
    // token carries (SJ-D44).
    if (order.loginId() == null) {
      return;
    }
    // ctx.userId() is null for an unidentified caller. Compare from the order's side so a null
    // principal cannot reach a .toString() — the NPE SJ-D13 uncovered when the exemption went away.
    if (ctx.userId() == null || !order.loginId().equals(ctx.userId().toString())) {
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
