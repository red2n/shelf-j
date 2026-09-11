package com.shelfj.payment.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.shelfj.ids.Ids;
import com.shelfj.payment.client.OrderClient;
import com.shelfj.web.ApiException;
import com.shelfj.web.TenantContext;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Who may read a payment record, resolved through the order it is against.
 *
 * <p>The guest case is the one these tests exist for. A guest checkout has no {@code customerId} to
 * match a principal against, so requiring one made {@code GET /payments/intents/{id}} impossible
 * for exactly the shopper both the gateway and {@code AdminAuthorizationFilter} open that path for
 * — two layers deliberately permitting a request the third refused, so the SCA return flow could
 * never have completed.
 *
 * <p>The distinction that keeps this from being SJ-D13's bypass is asserted below: the exemption
 * turns on the <b>order</b> having no owner, not on the <b>caller</b> having no principal. The
 * first is a fact about the order; the second is attacker-controlled, which is precisely why SJ-D13
 * removed it.
 */
@ExtendWith(MockitoExtension.class)
class OrderPaymentGuardReadAccessTest {

  private static final UUID TENANT = Ids.newId();
  private static final UUID ORDER = Ids.newId();

  @Mock OrderClient orderClient;
  @InjectMocks OrderPaymentGuard guard;

  /**
   * A mock rather than a real {@link TenantContext}: its setter is package-private, and the guard
   * only ever asks it two questions.
   */
  private static TenantContext ctx(UUID userId, String... roles) {
    TenantContext c = org.mockito.Mockito.mock(TenantContext.class);
    Set<String> held = Set.of(roles);
    org.mockito.Mockito.lenient().when(c.userId()).thenReturn(userId);
    org.mockito.Mockito.lenient()
        .when(c.hasRole(org.mockito.ArgumentMatchers.anyString()))
        .thenAnswer(inv -> held.contains(inv.getArgument(0)));
    return c;
  }

  private void orderOwnedBy(String customerId) {
    when(orderClient.getOrder(any(), any()))
        .thenReturn(
            new OrderClient.OrderInfo(
                customerId, "ONLINE", new java.math.BigDecimal("10.00"), "CONFIRMED", null, "GBP"));
  }

  private void check(TenantContext c) {
    guard.requireOrderReadAccess(TENANT, ORDER, c, () -> ApiException.notFound("NOT_FOUND", "no"));
  }

  @Test
  @DisplayName(
      "A guest may read the intent for an order that has no customer — the SCA return flow")
  void guestMayReadAGuestOrder() {
    orderOwnedBy(null);
    assertDoesNotThrow(() -> check(ctx(null)));
  }

  @Test
  @DisplayName("Not SJ-D13: an anonymous caller still cannot read an order that HAS an owner")
  void anonymousCannotReadAnOwnedOrder() {
    orderOwnedBy(Ids.newId().toString());
    assertThrows(ApiException.class, () -> check(ctx(null)));
  }

  @Test
  @DisplayName("A signed-in customer reads their own order and not somebody else's")
  void customerReadsOnlyTheirOwn() {
    UUID me = Ids.newId();
    orderOwnedBy(me.toString());
    assertDoesNotThrow(() -> check(ctx(me, "CUSTOMER")));

    orderOwnedBy(Ids.newId().toString());
    assertThrows(ApiException.class, () -> check(ctx(me, "CUSTOMER")));
  }

  @Test
  @DisplayName("Staff read anything in their own tenant, without an order lookup at all")
  void staffReadAnything() {
    assertDoesNotThrow(() -> check(ctx(Ids.newId(), "CASHIER")));
  }
}
