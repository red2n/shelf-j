package com.storeql.gateway.integrity;

import com.storeql.web.HttpHeaders;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObjectBuilder;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Arrays;

/**
 * The platform's view of script integrity on the web shell (PCI DSS 11.6.1): the last check, and a
 * check run now. Platform administrators only; the JWT filter has already required a token.
 */
@Path("/admin/security/script-integrity")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
public class ScriptIntegrityResource {

  @Inject ScriptIntegrityMonitor monitor;

  @GET
  public Response last(@Context jakarta.ws.rs.core.HttpHeaders headers) {
    Response refused = requirePlatformAdmin(headers);
    if (refused != null) return refused;
    return Response.ok(render(monitor.last())).build();
  }

  @POST
  @Path("/check")
  public Response check(@Context jakarta.ws.rs.core.HttpHeaders headers) {
    Response refused = requirePlatformAdmin(headers);
    if (refused != null) return refused;
    if (!monitor.enabled()) {
      return Response.status(409)
          .entity(
              error(
                  "SCRIPT_INTEGRITY_NOT_CONFIGURED",
                  "no web shell url is configured for the monitor"))
          .build();
    }
    return Response.ok(render(monitor.check())).build();
  }

  private static Response requirePlatformAdmin(jakarta.ws.rs.core.HttpHeaders headers) {
    String roles = headers.getHeaderString(HttpHeaders.ROLES);
    boolean admin =
        roles != null && Arrays.stream(roles.split(",")).anyMatch("PLATFORM_ADMIN"::equals);
    if (admin) return null;
    return Response.status(403).entity(error("FORBIDDEN", "PLATFORM_ADMIN required")).build();
  }

  private static String error(String code, String message) {
    return Json.createObjectBuilder()
        .add("data", jakarta.json.JsonValue.NULL)
        .add(
            "error",
            Json.createObjectBuilder()
                .add("code", code)
                .add("message", message)
                .add("details", Json.createArrayBuilder()))
        .build()
        .toString();
  }

  private static String render(ScriptIntegrityMonitor.Report r) {
    JsonObjectBuilder data = Json.createObjectBuilder();
    if (r == null) {
      data.add("checked", false);
    } else {
      JsonArrayBuilder drift = Json.createArrayBuilder();
      for (var d : r.drift()) {
        drift.add(
            Json.createObjectBuilder()
                .add("kind", d.kind())
                .add("path", d.path())
                .add("detail", d.detail()));
      }
      data.add("checked", true)
          .add("checkedAt", r.checkedAt().toString())
          .add("webUrl", r.webUrl())
          .add("available", r.available())
          .add("scripts", r.scripts())
          .add("clean", r.clean())
          .add("drift", drift);
    }
    return Json.createObjectBuilder()
        .add("data", data)
        .add("error", jakarta.json.JsonValue.NULL)
        .build()
        .toString();
  }
}
