package com.shelfj.test;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.Response;
import java.io.StringReader;

/**
 * The check every service's integration tests make on its tenant data (21.14): that the owner's
 * manifest is served, so the service's catalog has no table it cannot tie to a business, no
 * exclusion naming something its schema no longer has, and nothing else that would stop a complete
 * export. A service whose migrations add a table without deciding how it leaves fails here, in its
 * own build, rather than in a departing business's export.
 */
public final class TenantDataChecks {

  private TenantDataChecks() {}

  /**
   * Fetches the manifest as an owner and fails unless it is complete.
   *
   * @param target the test's injected target
   * @param tenantId any tenant id; the manifest of a business with no rows is still complete
   * @return the manifest, for further assertions
   * @throws AssertionError with the service's answer when the manifest is not served
   */
  public static JsonObject assertExportable(WebTarget target, String tenantId) {
    try (Response r =
        target
            .path("/admin/tenant-data")
            .request()
            .header("X-Tenant-Id", tenantId)
            .header("X-User-Id", "01a090ae-611e-700b-bde4-50df0324c37c")
            .header("X-Roles", "OWNER")
            .get()) {
      String body = r.readEntity(String.class);
      if (r.getStatus() != 200) {
        throw new AssertionError(
            "tenant data manifest not served (" + r.getStatus() + "): " + body);
      }
      JsonObject data;
      try (var reader = Json.createReader(new StringReader(body))) {
        data = reader.readObject().getJsonObject("data");
      }
      if (data == null || data.getJsonArray("tables").isEmpty()) {
        throw new AssertionError("tenant data manifest lists no tables: " + body);
      }
      if (!data.getJsonObject("excludedTables").containsKey("outbox")) {
        throw new AssertionError("tenant data manifest does not name what it leaves out: " + body);
      }
      return data;
    }
  }
}
