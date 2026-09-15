package com.shelfj.purchase.messaging;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import java.io.StringReader;
import java.math.BigDecimal;
import java.util.UUID;

/** Reading an event payload: the parse, and the optional fields the handlers here share. */
final class EventJson {

  private EventJson() {}

  static JsonObject parse(String json) {
    try (var reader = Json.createReader(new StringReader(json))) {
      return reader.readObject();
    }
  }

  /** A UUID field, or null when it is absent, null or blank. */
  static UUID optUuid(JsonObject o, String key) {
    if (!o.containsKey(key) || o.isNull(key)) return null;
    String v = o.getString(key, "");
    return v.isBlank() ? null : UUID.fromString(v);
  }

  /** A number field, or null when it is absent or null. */
  static BigDecimal optNumber(JsonObject o, String key) {
    if (!o.containsKey(key) || o.isNull(key)) return null;
    return o.getJsonNumber(key).bigDecimalValue();
  }
}
