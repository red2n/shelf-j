package com.storeql.order.messaging;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storeql.ids.Ids;
import com.storeql.order.dto.Dtos.FulfilRequest;
import com.storeql.order.repo.OrderRepository;
import com.storeql.order.service.OrderService;
import com.storeql.web.ApiException;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * A wave picked at the store fulfils the orders it names, for the quantities picked, once — exactly
 * as the Fulfil button does. Written before the code.
 */
@ExtendWith(MockitoExtension.class)
class WavePickedHandlerTest {

  private static final UUID EVENT = Ids.newId();
  private static final UUID TENANT = Ids.newId();
  private static final UUID STORE = Ids.newId();
  private static final UUID WAVE = Ids.newId();
  private static final UUID ORDER_1 = Ids.newId();
  private static final UUID ORDER_2 = Ids.newId();
  private static final UUID APPLES = Ids.newId();
  private static final UUID PEARS = Ids.newId();

  @Mock OrderRepository repo;
  @Mock OrderService svc;
  private WavePickedHandler handler;

  @BeforeEach
  void setUp() {
    handler = new WavePickedHandler();
    handler.repo = repo;
    handler.svc = svc;
  }

  private static String payload() {
    return "{\"eventId\":\""
        + EVENT
        + "\",\"eventType\":\"WavePicked\",\"tenantId\":\""
        + TENANT
        + "\",\"aggregateId\":\""
        + WAVE
        + "\",\"waveId\":\""
        + WAVE
        + "\",\"storeId\":\""
        + STORE
        + "\",\"orders\":[{\"orderId\":\""
        + ORDER_1
        + "\",\"lines\":[{\"variantId\":\""
        + APPLES
        + "\",\"qty\":3},{\"variantId\":\""
        + PEARS
        + "\",\"qty\":2}]},{\"orderId\":\""
        + ORDER_2
        + "\",\"lines\":[{\"variantId\":\""
        + APPLES
        + "\",\"qty\":3}]}]}";
  }

  @Test
  void eachOrderNamedIsFulfilledForThePickedQuantitiesOnce() {
    when(repo.markProcessedIfNew(any(), eq(WavePickedHandler.CONSUMER))).thenReturn(true);
    handler.handle(payload());
    ArgumentCaptor<FulfilRequest> req = ArgumentCaptor.forClass(FulfilRequest.class);
    verify(svc).fulfilOrder(eq(TENANT), eq(ORDER_1), req.capture(), isNull(), isNull());
    verify(svc).fulfilOrder(eq(TENANT), eq(ORDER_2), any(), isNull(), isNull());
    org.junit.jupiter.api.Assertions.assertEquals(2, req.getValue().lines().size());
    org.junit.jupiter.api.Assertions.assertEquals(
        APPLES.toString(), req.getValue().lines().get(0).variantId());
    org.junit.jupiter.api.Assertions.assertEquals(
        new BigDecimal("3"), req.getValue().lines().get(0).qty());
    // Told again, nothing is fulfilled twice: each order is deduped on the event.
    when(repo.markProcessedIfNew(any(), eq(WavePickedHandler.CONSUMER))).thenReturn(false);
    handler.handle(payload());
    verify(svc, times(1)).fulfilOrder(eq(TENANT), eq(ORDER_1), any(), isNull(), isNull());
  }

  @Test
  void anOrderThatCannotBeFulfilledIsSkippedAndTheRestStillAre() {
    when(repo.markProcessedIfNew(any(), eq(WavePickedHandler.CONSUMER))).thenReturn(true);
    when(svc.fulfilOrder(eq(TENANT), eq(ORDER_1), any(), isNull(), isNull()))
        .thenThrow(ApiException.conflict("ORDER_NOT_FULFILLABLE", "cancelled meanwhile"));
    handler.handle(payload());
    verify(svc).fulfilOrder(eq(TENANT), eq(ORDER_2), any(), isNull(), isNull());
  }

  @Test
  void aMalformedEventIsSkippedWithoutTouchingAnOrder() {
    handler.handle("{\"eventType\":\"WavePicked\",\"tenantId\":\"not-an-id\"}");
    verify(svc, never()).fulfilOrder(any(), any(), any(), any(), any());
  }
}
