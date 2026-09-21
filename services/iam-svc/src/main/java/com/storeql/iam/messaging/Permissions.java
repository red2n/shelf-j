package com.storeql.iam.messaging;

import jakarta.json.JsonObject;
import jakarta.json.JsonString;
import java.util.LinkedHashSet;
import java.util.Set;

/** Reads a {@code permissions} array out of a tenant-svc event payload. */
final class Permissions {

  private Permissions() {}

  /**
   * @param obj the payload
   * @return the codes named, in order, empty when the array is absent or empty
   */
  /**
   * @param obj the payload
   * @param field the name of an ISO-8601 instant in it
   * @return the instant, or null when absent or unreadable — an unversioned event applies
   *     unconditionally, which is what a payload from before versions existed should do
   */
  static java.time.Instant instant(JsonObject obj, String field) {
    String v = obj.getString(field, null);
    if (v == null || v.isBlank()) return null;
    try {
      return java.time.Instant.parse(v);
    } catch (java.time.format.DateTimeParseException e) {
      return null;
    }
  }

  static Set<String> parse(JsonObject obj) {
    Set<String> out = new LinkedHashSet<>();
    if (obj.containsKey("permissions") && !obj.isNull("permissions")) {
      for (JsonString v : obj.getJsonArray("permissions").getValuesAs(JsonString.class)) {
        String s = v.getString().trim();
        if (!s.isEmpty()) out.add(s);
      }
    }
    return out;
  }
}
