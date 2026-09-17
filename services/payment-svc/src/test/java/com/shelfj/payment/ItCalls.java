package com.shelfj.payment;

import com.shelfj.ids.Ids;
import com.shelfj.test.WebTargets;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.StringReader;
import java.util.UUID;

/**
 * How this module's integration tests call the service: as somebody, the way the gateway would have
 * stamped the request, and with the answer read once into its status and its body.
 */
final class ItCalls {

  private ItCalls() {}

  /** Who is calling, as the gateway's identity headers say. */
  record Caller(UUID tenantId, UUID userId, String roles) {

    static Caller owner(UUID tenantId) {
      return new Caller(tenantId, Ids.newId(), "OWNER");
    }

    Caller as(String role) {
      return new Caller(tenantId, Ids.newId(), role);
    }
  }

  /** An answer: its status and its body, the envelope or the problem. */
  record Answer(int status, JsonObject body) {

    JsonObject data() {
      return body.getJsonObject("data");
    }

    JsonArray list() {
      return body.getJsonArray("data");
    }

    /** The stable error code of a refusal, or null. */
    String code() {
      return body.containsKey("code") ? body.getString("code") : null;
    }

    String nextCursor() {
      JsonObject meta = body.getJsonObject("meta");
      // A field with nothing in it is left out of the answer rather than sent as null.
      return meta == null || !meta.containsKey("nextCursor") || meta.isNull("nextCursor")
          ? null
          : meta.getString("nextCursor");
    }
  }

  static Answer call(
      WebTarget target,
      String method,
      String pathAndQuery,
      Caller who,
      String json,
      String idempotencyKey) {
    Invocation.Builder b =
        WebTargets.at(target, pathAndQuery)
            .request()
            .header("X-Tenant-Id", who.tenantId())
            .header("X-User-Id", who.userId())
            .header("X-Roles", who.roles());
    if (idempotencyKey != null) b = b.header("Idempotency-Key", idempotencyKey);
    Response r =
        "GET".equals(method)
            ? b.get()
            : b.post(Entity.entity(json == null ? "{}" : json, MediaType.APPLICATION_JSON));
    String text = r.readEntity(String.class);
    return new Answer(
        r.getStatus(),
        text == null || text.isBlank()
            ? JsonObject.EMPTY_JSON_OBJECT
            : Json.createReader(new StringReader(text)).readObject());
  }

  static Answer post(WebTarget target, String path, Caller who, String json) {
    return call(target, "POST", path, who, json, null);
  }

  static Answer get(WebTarget target, String pathAndQuery, Caller who) {
    return call(target, "GET", pathAndQuery, who, null, null);
  }
}
