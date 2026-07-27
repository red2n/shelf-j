package com.shelfj.order.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.shelfj.order.client.PricingClient;
import com.shelfj.order.config.ServiceConfig;
import com.shelfj.order.domain.Domain.Order;
import com.shelfj.order.domain.Domain.OrderItem;
import com.shelfj.order.dto.Dtos.OrderItemRequest;
import com.shelfj.order.dto.Dtos.PlaceOrderRequest;
import com.shelfj.order.repo.OrderRepository;
import com.shelfj.service.StoreStatusRepository;
import com.shelfj.service.TenantStatusRepository;
import com.shelfj.web.ApiException;
import com.shelfj.web.TenantContext;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Gap #63 — placeOrder must not trust client-supplied money when pricing enforcement is on. */
@ExtendWith(MockitoExtension.class)
class OrderServicePricingTest {

  private static final UUID TENANT = UUID.randomUUID();
  private static final UUID STORE = UUID.randomUUID();
  private static final UUID VARIANT = UUID.randomUUID();

  @Mock OrderRepository repo;
  @Mock ServiceConfig config;
  @Mock PricingClient pricing;
  @Mock com.shelfj.order.client.InventoryClient inventory;
  @Mock TenantContext ctx;
  @Mock TenantStatusRepository tenantStatusRepo;
  @Mock StoreStatusRepository storeStatusRepo;

  private OrderService svc;

  @BeforeEach
  void setUp() {
    svc = new OrderService();
    svc.repo = repo;
    svc.config = config;
    svc.pricing = pricing;
    svc.inventory = inventory;
    svc.tenantStatusRepo = tenantStatusRepo;
    svc.storeStatusRepo = storeStatusRepo;
    when(ctx.requireTenantId()).thenReturn(TENANT);
    when(tenantStatusRepo.isActive(any())).thenReturn(true);
    when(storeStatusRepo.isActive(any(), any())).thenReturn(true);
  }

  private static PlaceOrderRequest request(BigDecimal clientUnitPrice, BigDecimal discount) {
    return new PlaceOrderRequest(
        STORE.toString(),
        null,
        "POS",
        "INSTORE",
        List.of(new OrderItemRequest(VARIANT.toString(), BigDecimal.ONE, clientUnitPrice, null)),
        null,
        discount,
        "USD",
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  @Test
  void enforcementOnUsesServerPriceAndIgnoresClientPrice() {
    when(config.pricingEnforce()).thenReturn(true);
    when(pricing.resolveLines(eq(TENANT), anyList(), eq(STORE), eq("POS")))
        .thenReturn(
            List.of(new PricingClient.ResolvedLine(new BigDecimal("7.77"), BigDecimal.ZERO)));
    when(repo.createOrder(any(), anyList(), any())).thenAnswer(inv -> inv.getArgument(0));

    // client claims the item costs 0.01 — the server-resolved 7.77 must win
    Order order = svc.placeOrder(request(new BigDecimal("0.01"), null), ctx, null);

    assertEquals(new BigDecimal("7.77"), order.subtotal());
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<OrderItem>> items = ArgumentCaptor.forClass(List.class);
    org.mockito.Mockito.verify(repo).createOrder(any(), items.capture(), any());
    assertEquals(new BigDecimal("7.77"), items.getValue().get(0).unitPrice());
  }

  @Test
  void enforcementOnFailsClosedWhenPriceCannotBeResolved() {
    when(config.pricingEnforce()).thenReturn(true);
    when(pricing.resolveLines(any(), any(), any(), any()))
        .thenThrow(ApiException.unprocessable("ORDER_PRICE_UNRESOLVED", "no price"));

    ApiException e =
        assertThrows(
            ApiException.class,
            () -> svc.placeOrder(request(new BigDecimal("0.01"), null), ctx, null));
    assertEquals("ORDER_PRICE_UNRESOLVED", e.code());
    verifyNoInteractions(repo);
  }

  @Test
  void enforcementOffTrustsClientPrice() {
    when(config.pricingEnforce()).thenReturn(false);
    when(repo.createOrder(any(), anyList(), any())).thenAnswer(inv -> inv.getArgument(0));

    Order order = svc.placeOrder(request(new BigDecimal("5.00"), null), ctx, null);

    assertEquals(new BigDecimal("5.00"), order.subtotal());
    verifyNoInteractions(pricing);
  }

  @Test
  void enforcementOffStillRequiresAUnitPrice() {
    when(config.pricingEnforce()).thenReturn(false);

    ApiException e =
        assertThrows(ApiException.class, () -> svc.placeOrder(request(null, null), ctx, null));
    assertEquals("ORDER_PRICE_REQUIRED", e.code());
  }

  @Test
  void discountLargerThanSubtotalIsRejected() {
    when(config.pricingEnforce()).thenReturn(false);
    org.mockito.Mockito.lenient().when(ctx.hasRole("MANAGER")).thenReturn(true);

    ApiException e =
        assertThrows(
            ApiException.class,
            () ->
                svc.placeOrder(
                    request(new BigDecimal("5.00"), new BigDecimal("10.00")), ctx, null));
    assertEquals("ORDER_DISCOUNT_EXCEEDS_SUBTOTAL", e.code());
    verifyNoInteractions(repo);
  }

  @Test
  void nonStaffCallerCannotSelfApplyADiscount() {
    when(config.pricingEnforce()).thenReturn(false);
    // ctx mock has no staff role stubbed -> hasRole(...) defaults to false for every role.

    ApiException e =
        assertThrows(
            ApiException.class,
            () ->
                svc.placeOrder(request(new BigDecimal("5.00"), new BigDecimal("1.00")), ctx, null));
    assertEquals("ORDER_DISCOUNT_NOT_ALLOWED", e.code());
    verifyNoInteractions(repo);
  }

  /**
   * Regression test for the tenant-aware store check in StoreStatusRepository: a storeId that
   * belongs to a different tenant (or the projection has recorded under one) must reject the
   * order, not just check whether *some* store with that id happens to be ACTIVE. Without this,
   * a caller could place an order under tenant A referencing a real, active store that actually
   * belongs to tenant B.
   */
  @Test
  void placeOrderRejectsAStoreThatBelongsToAnotherTenant() {
    when(config.pricingEnforce()).thenReturn(false);
    // The real repo method returns false here specifically because STORE is on record under a
    // different tenant than TENANT — simulated directly at the mock boundary since that
    // tenant-vs-projection comparison lives in StoreStatusRepository, not OrderService.
    when(storeStatusRepo.isActive(TENANT, STORE)).thenReturn(false);

    ApiException e =
        assertThrows(
            ApiException.class,
            () -> svc.placeOrder(request(new BigDecimal("5.00"), null), ctx, null));
    assertEquals("STORE_NOT_OPERATIONAL", e.code());
    verifyNoInteractions(repo);
  }

  @Test
  void enforcementOnDerivesTaxFromPricingAndIgnoresClientDiscount() {
    when(config.pricingEnforce()).thenReturn(true);
    when(pricing.resolveLines(eq(TENANT), anyList(), eq(STORE), eq("POS")))
        .thenReturn(
            List.of(
                new PricingClient.ResolvedLine(new BigDecimal("10.00"), new BigDecimal("2.00"))));
    when(repo.createOrder(any(), anyList(), any())).thenAnswer(inv -> inv.getArgument(0));

    // client tries to claim a 9.00 discount — must be ignored entirely when pricing is enforced.
    Order order =
        svc.placeOrder(request(new BigDecimal("0.01"), new BigDecimal("9.00")), ctx, null);

    assertEquals(new BigDecimal("10.00"), order.subtotal());
    assertEquals(new BigDecimal("2.00"), order.taxAmount());
    assertEquals(BigDecimal.ZERO, order.discountAmount());
    assertEquals(new BigDecimal("12.00"), order.total());
  }
}
