package com.shelfj.order.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Which orders are handed over at the counter the moment they are paid for (SJ-D40).
 *
 * <p>Getting this wrong in one direction leaves till sales that never deduct stock, which is the
 * defect. Getting it wrong in the other fulfils an online click-and-collect order at payment and
 * deducts stock for goods still on the shelf waiting for the customer.
 */
class TillSaleTest {

  @Test
  @DisplayName("A POS sale handed over at the counter is a till sale")
  void instore() {
    assertTrue(OrderService.isTillSale("POS", "INSTORE"));
  }

  @Test
  @DisplayName("A POS sale sent as PICKUP is still a till sale — offline queues replay with it")
  void pickupFromTheTill() {
    assertTrue(OrderService.isTillSale("POS", "PICKUP"));
  }

  @Test
  @DisplayName("A POS sale going out on a van is not handed over at the counter")
  void deliveryFromTheTill() {
    assertFalse(OrderService.isTillSale("POS", "DELIVERY"));
  }

  @Test
  @DisplayName("No online order is a till sale, whatever its fulfilment type")
  void online() {
    assertFalse(OrderService.isTillSale("ONLINE", "PICKUP"));
    assertFalse(OrderService.isTillSale("ONLINE", "DELIVERY"));
    // placeOrder defaults a missing fulfilment type to INSTORE for either channel, so this case
    // is reachable, and it must not be fulfilled at payment.
    assertFalse(OrderService.isTillSale("ONLINE", "INSTORE"));
  }

  @Test
  @DisplayName("Missing values are not a till sale")
  void nulls() {
    assertFalse(OrderService.isTillSale(null, null));
    assertFalse(OrderService.isTillSale("POS", null));
    assertFalse(OrderService.isTillSale(null, "INSTORE"));
  }
}
