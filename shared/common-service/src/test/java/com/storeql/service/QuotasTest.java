package com.storeql.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.storeql.ids.Ids;
import com.storeql.web.ApiException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Whether one more metered thing may be done (21.10), as the service about to do it asks: refused
 * only when tenant-svc says a hard ceiling is in the way, with the figures; everything else —
 * unlimited, no answer, an answer that makes no sense — lets it through.
 */
class QuotasTest {

  private static final UUID TENANT = Ids.newId();

  private static Quotas answering(String body) {
    return Quotas.forTest((t, q) -> Optional.ofNullable(body));
  }

  @Test
  @DisplayName("A hard ceiling reached refuses, and says by how much")
  void refusedWithTheFigures() {
    Quotas q =
        answering(
            "{\"data\":{\"meter\":\"SMS\",\"used\":50,\"included\":50,\"hard\":true,"
                + "\"quantity\":2,\"allowed\":false}}");
    ApiException e =
        assertThrows(ApiException.class, () -> q.requireRoom(TENANT, "SMS", 2, "text parts"));
    assertEquals("USAGE_QUOTA_REACHED", e.code());
    assertEquals(409, e.status());
    assertTrue(e.getMessage().contains("50 text parts"), e.getMessage());
  }

  @Test
  @DisplayName("Room to spare, or no ceiling at all, is let through")
  void allowed() {
    answering("{\"data\":{\"used\":3,\"included\":50,\"hard\":true,\"allowed\":true}}")
        .requireRoom(TENANT, "SMS", 1, "text parts");
    answering("{\"data\":{\"used\":900,\"included\":null,\"hard\":false,\"allowed\":true}}")
        .requireRoom(TENANT, "SMS", 1, "text parts");
  }

  @Test
  @DisplayName("No answer, or one that makes no sense, fails open: a blip must not stop a shop")
  void failsOpen() {
    answering(null).requireRoom(TENANT, "SMS", 1, "text parts");
    answering("not json").requireRoom(TENANT, "SMS", 1, "text parts");
    answering("{\"data\":null}").requireRoom(TENANT, "SMS", 1, "text parts");
    answering("{\"data\":{\"used\":3}}").requireRoom(TENANT, "SMS", 1, "text parts");
    answering("{\"data\":{\"allowed\":\"no\"}}").requireRoom(TENANT, "SMS", 1, "text parts");
    Quotas.forTest((t, q) -> Optional.empty()).requireRoom(null, "SMS", 1, "text parts");
  }

  @Test
  @DisplayName("It asks for the meter and the quantity, for that business, every time")
  void asksEveryTime() {
    List<String> asked = new ArrayList<>();
    Quotas q =
        Quotas.forTest(
            (t, query) -> {
              asked.add(t + " " + query.get("meter") + " " + query.get("quantity"));
              return Optional.of("{\"data\":{\"used\":0,\"included\":5,\"allowed\":true}}");
            });
    q.requireRoom(TENANT, "SMS", 3, "text parts");
    q.requireRoom(TENANT, "SMS", 3, "text parts");
    assertEquals(
        List.of(TENANT + " SMS 3", TENANT + " SMS 3"), asked, "not cached: usage moves every text");
  }
}
