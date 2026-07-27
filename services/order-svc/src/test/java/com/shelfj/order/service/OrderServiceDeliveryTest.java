package com.shelfj.order.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.shelfj.order.client.PricingClient;
import com.shelfj.order.client.TenantClient;
import com.shelfj.order.config.ServiceConfig;
import com.shelfj.order.domain.Domain.Order;
import com.shelfj.order.dto.Dtos.OrderItemRequest;
import com.shelfj.order.dto.Dtos.PlaceOrderRequest;
import com.shelfj.order.repo.OrderRepository;
import com.shelfj.service.StoreStatusRepository;
import com.shelfj.service.TenantStatusRepository;
import com.shelfj.web.ApiException;
import com.shelfj.web.TenantContext;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** "Deliver to home" must capture a full address; other fulfilment types must not persist one. */
@ExtendWith(MockitoExtension.class)
class OrderServiceDeliveryTest {

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
  @Mock TenantClient tenants;

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
    svc.tenants = tenants;
    when(ctx.requireTenantId()).thenReturn(TENANT);
    when(tenantStatusRepo.isActive(any())).thenReturn(true);
    org.mockito.Mockito.lenient().when(storeStatusRepo.isActive(any(), any())).thenReturn(true);
    org.mockito.Mockito.lenient()
        .when(tenants.resolveFulfilment(any(), any()))
        .thenReturn(Optional.empty());
    org.mockito.Mockito.lenient().when(config.pricingEnforce()).thenReturn(false);
  }

  private static PlaceOrderRequest request(
      String fulfilmentType,
      String line1,
      String city,
      String postalCode,
      String recipientName,
      String recipientPhone) {
    return new PlaceOrderRequest(
        STORE.toString(),
        null,
        "ONLINE",
        fulfilmentType,
        List.of(new OrderItemRequest(VARIANT.toString(), BigDecimal.ONE, BigDecimal.TEN, null)),
        null,
        null,
        "USD",
        null,
        null,
        null,
        null,
        line1,
        null,
        city,
        postalCode,
        recipientName,
        recipientPhone,
        null,
        null);
  }

  @Test
  void deliveryWithoutAddressIsRejected() {
    ApiException e =
        assertThrows(
            ApiException.class,
            () -> svc.placeOrder(request("DELIVERY", null, null, null, null, null), ctx, null));
    assertEquals("ORDER_DELIVERY_ADDRESS_REQUIRED", e.code());
    verifyNoInteractions(repo);
  }

  @Test
  void deliveryWithPartialAddressIsRejected() {
    ApiException e =
        assertThrows(
            ApiException.class,
            () ->
                svc.placeOrder(
                    request("DELIVERY", "221B Baker St", "London", null, "Jane Doe", "555-0100"),
                    ctx,
                    null));
    assertEquals("ORDER_DELIVERY_ADDRESS_REQUIRED", e.code());
    verifyNoInteractions(repo);
  }

  @Test
  void deliveryWithFullAddressIsPersisted() {
    when(repo.createOrder(any(), anyList(), any())).thenAnswer(inv -> inv.getArgument(0));

    Order order =
        svc.placeOrder(
            request("DELIVERY", "221B Baker St", "London", "NW1 6XE", "Jane Doe", "555-0100"),
            ctx,
            null);

    assertEquals("221B Baker St", order.deliveryLine1());
    assertEquals("London", order.deliveryCity());
    assertEquals("NW1 6XE", order.deliveryPostalCode());
    assertEquals("Jane Doe", order.deliveryRecipientName());
    assertEquals("555-0100", order.deliveryRecipientPhone());
  }

  @Test
  void pickupIgnoresAnySuppliedAddressFields() {
    when(repo.createOrder(any(), anyList(), any())).thenAnswer(inv -> inv.getArgument(0));

    // A pickup order should never persist delivery details even if the client sends some
    // (e.g. stale client state from switching fulfilment type back and forth in the UI).
    Order order =
        svc.placeOrder(
            request("PICKUP", "221B Baker St", "London", "NW1 6XE", "Jane Doe", "555-0100"),
            ctx,
            null);

    assertEquals(null, order.deliveryLine1());
    assertEquals(null, order.deliveryCity());
  }
}
