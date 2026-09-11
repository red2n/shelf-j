package com.shelfj.payment.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * Guest online checkout ran into this: JSON-B omits a null field from a DTO entirely rather than
 * serialising it as null, so a guest order's response has no {@code customerId} key at all, and
 * {@code JsonObject.isNull} throws on an absent key rather than returning true. Every guest payment
 * came back 503 "malformed response from order-svc" — the order stayed PENDING and the sweeper
 * cancelled it.
 *
 * <p>Events were unaffected, which is why it went unnoticed: order-svc's outbox payloads are
 * hand-built strings that do emit {@code "customerId":null}, so every consumer parsing an event saw
 * the key present. Only the clients parsing an HTTP DTO hit it.
 */
class OrderClientParseTest {

  private static String body(String customerIdField) {
    return "{\"data\":{"
        + customerIdField
        + "\"channel\":\"ONLINE\",\"total\":5.40,\"status\":\"PENDING\","
        + "\"storeId\":\"01a090ae-611e-700d-b30c-06bbb83aac12\"}}";
  }

  @Test
  void aGuestOrderHasNoCustomerIdKeyAtAll() {
    var info = OrderClient.parseOrder(body(""));
    assertNull(info.customerId());
    assertEquals("ONLINE", info.channel());
    assertEquals("PENDING", info.status());
    assertEquals(new BigDecimal("5.40"), info.total());
  }

  @Test
  void anExplicitNullIsAlsoAbsent() {
    assertNull(OrderClient.parseOrder(body("\"customerId\":null,")).customerId());
  }

  @Test
  void aCustomerOrderCarriesTheId() {
    var info =
        OrderClient.parseOrder(body("\"customerId\":\"01a090ae-611e-701a-acda-6dfc4bcb2f17\","));
    assertEquals("01a090ae-611e-701a-acda-6dfc4bcb2f17", info.customerId());
  }
}
