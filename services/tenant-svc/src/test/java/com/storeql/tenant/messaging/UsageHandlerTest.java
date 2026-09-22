package com.storeql.tenant.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.storeql.ids.Ids;
import com.storeql.tenant.repo.UsageRepository;
import com.storeql.tenant.service.UsageService;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * One event, one usage record (21.10): what is read out of {@code OrderPlaced} and {@code SmsSent},
 * what is skipped, and that a failure to write is never mistaken for a bad event.
 */
class UsageHandlerTest {

  private static final UUID TENANT = Ids.newId();

  /** Stands in for the service: records what it was asked, or fails as a database would. */
  private static final class Recording extends UsageService {
    final List<String> calls = new ArrayList<>();
    boolean fail;

    @Override
    public UsageRepository.Recorded record(
        UUID tenantId, String meterKey, long quantity, String sourceRef) {
      if (fail) throw new IllegalStateException("the database is away");
      calls.add(tenantId + " " + meterKey + " " + quantity + " " + sourceRef);
      return new UsageRepository.Recorded(true, quantity, List.of());
    }
  }

  private static UsageHandler with(Recording r) {
    UsageHandler h = new UsageHandler();
    h.usage = r;
    return h;
  }

  private static String sms(Object parts) {
    return "{\"tenantId\":\""
        + TENANT
        + "\",\"eventId\":\""
        + Ids.newId()
        + "\",\"parts\":"
        + parts
        + "}";
  }

  @Test
  @DisplayName("An order placed is one order, counted by its id")
  void anOrder() {
    Recording r = new Recording();
    UUID orderId = Ids.newId();
    assertTrue(
        with(r)
            .orderPlaced(
                "{\"eventType\":\"OrderPlaced\",\"tenantId\":\""
                    + TENANT
                    + "\",\"orderId\":\""
                    + orderId
                    + "\",\"channel\":\"POS\",\"storeId\":\""
                    + Ids.newId()
                    + "\",\"customerId\":null,\"loginId\":null}"));
    assertEquals(List.of(TENANT + " ORDERS 1 order:" + orderId), r.calls);
  }

  @Test
  @DisplayName("A text is counted by the parts it was sent as")
  void aText() {
    Recording r = new Recording();
    UUID eventId = Ids.newId();
    assertTrue(
        with(r)
            .smsSent(
                "{\"eventId\":\"" + eventId + "\",\"tenantId\":\"" + TENANT + "\",\"parts\":3}"));
    assertEquals(List.of(TENANT + " SMS 3 sms:" + eventId), r.calls);
  }

  @Test
  @DisplayName("A malformed event is skipped, never counted as something")
  void malformed() {
    Recording r = new Recording();
    UsageHandler h = with(r);
    assertFalse(h.orderPlaced("not json"));
    assertFalse(h.orderPlaced("{\"tenantId\":\"" + TENANT + "\"}"));
    assertFalse(h.orderPlaced("{\"tenantId\":\"nope\",\"orderId\":\"" + Ids.newId() + "\"}"));
    assertFalse(h.smsSent("{\"tenantId\":\"" + TENANT + "\",\"eventId\":\"" + Ids.newId() + "\"}"));
    assertFalse(h.smsSent(sms(0)));
    assertFalse(h.smsSent(sms(11)), "more parts than a text can have is not a text");
    assertFalse(h.smsSent(sms("1.5")));
    assertEquals(List.of(), r.calls);
  }

  @Test
  @DisplayName("A write that fails reaches the loop, to be redelivered")
  void aFailureIsNotABadEvent() {
    Recording r = new Recording();
    r.fail = true;
    UsageHandler h = with(r);
    String order = "{\"tenantId\":\"" + TENANT + "\",\"orderId\":\"" + Ids.newId() + "\"}";
    assertThrows(IllegalStateException.class, () -> h.orderPlaced(order));
    assertThrows(IllegalStateException.class, () -> h.smsSent(sms(1)));
  }
}
