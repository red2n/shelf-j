package com.storeql.order.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** What order-svc tells the till when pricing-svc refuses a price (SJ-D56). */
class PricingClientRefusalTest {

  private static String error(String code, String message) {
    return "{\"error\":{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}}";
  }

  @Test
  @DisplayName("A refusal pricing-svc explains is relayed with its own status, code and message")
  void relayed() {
    var vat =
        PricingClient.relayedRefusal(
                409,
                error("PRICING_VAT_RATE_NOT_CONFIGURED", "no VAT rate is configured for code T1"))
            .orElseThrow();
    assertEquals(409, vat.status());
    assertEquals("PRICING_VAT_RATE_NOT_CONFIGURED", vat.code());
    assertEquals("no VAT rate is configured for code T1", vat.getMessage());

    var sticker =
        PricingClient.relayedRefusal(
                400, error("PRICING_MARKDOWN_VARIANT_MISMATCH", "another product"))
            .orElseThrow();
    assertEquals(400, sticker.status());
    assertEquals("PRICING_MARKDOWN_VARIANT_MISMATCH", sticker.code());
  }

  @Test
  @DisplayName("Anything else is not relayed: another status, no code, or a body that is not JSON")
  void notRelayed() {
    String vat = error("PRICING_VAT_RATE_NOT_CONFIGURED", "no VAT rate");
    for (int status : new int[] {200, 401, 403, 404, 500, 502, 503}) {
      assertTrue(PricingClient.relayedRefusal(status, vat).isEmpty(), "status " + status);
    }
    for (String body :
        new String[] {
          "", "{}", "not json", "{\"error\":null}", "{\"error\":{}}", error("", "blank"), "[]"
        }) {
      assertTrue(PricingClient.relayedRefusal(409, body).isEmpty(), body);
    }
  }
}
