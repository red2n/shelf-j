package com.shelfj.gateway;

import com.shelfj.discovery.ServiceInstance;
import com.shelfj.discovery.ServiceRegistry;
import com.shelfj.web.ApiResponse;
import com.shelfj.web.ErrorBody;
import com.shelfj.web.HttpHeaders;
import io.helidon.webclient.api.HttpClientResponse;
import io.helidon.webclient.api.WebClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import java.util.Optional;
import java.util.UUID;

/**
 * The single public door. Routes {@code /api/{service}/{path...}} to the upstream resolved from
 * Consul.
 *
 * <p>Phase-0 scope: discovery-based routing + {@code X-Request-Id} propagation for GET/POST. This
 * is also the single place where, in later phases, JWT validation runs and verified identity is
 * injected downstream as {@code X-Tenant-Id}/{@code X-User-Id}/{@code X-Roles} (see {@link
 * #stampIdentity}). Business services trust those headers precisely because nothing else can reach
 * them (golden rule #2).
 */
@Path("/api")
@ApplicationScoped
public class ProxyResource {

  @Inject ServiceRegistry registry;
  @Inject WebClient webClient;
  @Inject GatewayConfig config;

  @GET
  @Path("/{service}/{path: .*}")
  @Produces(MediaType.APPLICATION_JSON)
  public Response proxyGet(
      @PathParam("service") String rawService,
      @PathParam("path") String rawPath,
      @Context UriInfo uriInfo,
      @Context jakarta.ws.rs.core.HttpHeaders inboundHeaders) {
    Route route = Route.of(rawService, rawPath);
    String service = route.service();
    String path = route.path();
    return resolve(service)
        .map(
            instance -> {
              String requestId = newRequestId();
              var req =
                  webClient
                      .get(instance.baseUri() + "/" + path)
                      .header(
                          io.helidon.http.HeaderNames.create(HttpHeaders.REQUEST_ID), requestId);
              addQueryParams(req, uriInfo);
              stampIdentity(req, inboundHeaders);
              return relay(req::request, service, requestId);
            })
        .orElseGet(() -> serviceUnavailable(service));
  }

  @POST
  @Path("/{service}/{path: .*}")
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  public Response proxyPost(
      @PathParam("service") String rawService,
      @PathParam("path") String rawPath,
      @Context UriInfo uriInfo,
      @Context jakarta.ws.rs.core.HttpHeaders inboundHeaders,
      String body) {
    Route route = Route.of(rawService, rawPath);
    String service = route.service();
    String path = route.path();
    return resolve(service)
        .map(
            instance -> {
              String requestId = newRequestId();
              var req =
                  webClient
                      .post(instance.baseUri() + "/" + path)
                      .header(io.helidon.http.HeaderNames.create(HttpHeaders.REQUEST_ID), requestId)
                      .header(io.helidon.http.HeaderNames.CONTENT_TYPE, MediaType.APPLICATION_JSON);
              addQueryParams(req, uriInfo);
              stampIdentity(req, inboundHeaders);
              return relay(() -> req.submit(body == null ? "" : body), service, requestId);
            })
        .orElseGet(() -> serviceUnavailable(service));
  }

  @PUT
  @Path("/{service}/{path: .*}")
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  public Response proxyPut(
      @PathParam("service") String rawService,
      @PathParam("path") String rawPath,
      @Context UriInfo uriInfo,
      @Context jakarta.ws.rs.core.HttpHeaders inboundHeaders,
      String body) {
    Route route = Route.of(rawService, rawPath);
    String service = route.service();
    String path = route.path();
    return resolve(service)
        .map(
            instance -> {
              String requestId = newRequestId();
              var req =
                  webClient
                      .put(instance.baseUri() + "/" + path)
                      .header(io.helidon.http.HeaderNames.create(HttpHeaders.REQUEST_ID), requestId)
                      .header(io.helidon.http.HeaderNames.CONTENT_TYPE, MediaType.APPLICATION_JSON);
              addQueryParams(req, uriInfo);
              stampIdentity(req, inboundHeaders);
              return relay(() -> req.submit(body == null ? "" : body), service, requestId);
            })
        .orElseGet(() -> serviceUnavailable(service));
  }

  @jakarta.ws.rs.PATCH
  @Path("/{service}/{path: .*}")
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  public Response proxyPatch(
      @PathParam("service") String rawService,
      @PathParam("path") String rawPath,
      @Context UriInfo uriInfo,
      @Context jakarta.ws.rs.core.HttpHeaders inboundHeaders,
      String body) {
    Route route = Route.of(rawService, rawPath);
    String service = route.service();
    String path = route.path();
    return resolve(service)
        .map(
            instance -> {
              String requestId = newRequestId();
              var req =
                  webClient
                      .patch(instance.baseUri() + "/" + path)
                      .header(io.helidon.http.HeaderNames.create(HttpHeaders.REQUEST_ID), requestId)
                      .header(io.helidon.http.HeaderNames.CONTENT_TYPE, MediaType.APPLICATION_JSON);
              addQueryParams(req, uriInfo);
              stampIdentity(req, inboundHeaders);
              return relay(() -> req.submit(body == null ? "" : body), service, requestId);
            })
        .orElseGet(() -> serviceUnavailable(service));
  }

  @DELETE
  @Path("/{service}/{path: .*}")
  @Produces(MediaType.APPLICATION_JSON)
  public Response proxyDelete(
      @PathParam("service") String rawService,
      @PathParam("path") String rawPath,
      @Context UriInfo uriInfo,
      @Context jakarta.ws.rs.core.HttpHeaders inboundHeaders) {
    Route route = Route.of(rawService, rawPath);
    String service = route.service();
    String path = route.path();
    return resolve(service)
        .map(
            instance -> {
              String requestId = newRequestId();
              var req =
                  webClient
                      .delete(instance.baseUri() + "/" + path)
                      .header(
                          io.helidon.http.HeaderNames.create(HttpHeaders.REQUEST_ID), requestId);
              addQueryParams(req, uriInfo);
              stampIdentity(req, inboundHeaders);
              return relay(req::request, service, requestId);
            })
        .orElseGet(() -> serviceUnavailable(service));
  }

  // --- helpers ---

  /**
   * The effective upstream target after peeling off an optional API version segment. {@code
   * /api/v1/{service}/{path}} resolves to exactly the same upstream as the unversioned {@code
   * /api/{service}/{path}} alias, so versioning is a gateway-level concern and business services
   * stay version-agnostic. The version is currently informational (only {@code v1} exists);
   * version-specific routing can branch on it here later.
   */
  record Route(String service, String path) {
    static Route of(String service, String path) {
      String rest = path == null ? "" : path;
      if (service != null && service.matches("v\\d+")) {
        int slash = rest.indexOf('/');
        if (slash < 0) {
          return new Route(rest, "");
        }
        return new Route(rest.substring(0, slash), rest.substring(slash + 1));
      }
      return new Route(service, rest);
    }
  }

  private Optional<ServiceInstance> resolve(String service) {
    // Allowlist gate: only declared business services are routable. An internal service that
    // happens to register in Consul (config, discovery, observability) must not be reachable
    // from the internet just because the proxy can resolve it (golden rule #2).
    if (!config.routableServices().contains(service)) {
      return Optional.empty();
    }
    return registry.resolve(service);
  }

  /**
   * Forwards the verified identity headers to the upstream service. By the time this runs, {@link
   * JwtAuthFilter} has already stripped any client-supplied copies and replaced them with values
   * extracted from the validated JWT. Downstream services trust these headers because only the
   * gateway can reach them (golden rule #2).
   */
  private void stampIdentity(
      io.helidon.webclient.api.HttpClientRequest req, jakarta.ws.rs.core.HttpHeaders inbound) {
    forward(req, inbound, HttpHeaders.TENANT_ID);
    forward(req, inbound, HttpHeaders.USER_ID);
    forward(req, inbound, HttpHeaders.ROLES);
    // Client-controlled, not identity — forwarded so downstream writes can dedupe retries
    // (golden rule #11). Not stripped/overwritten: the client owns this value.
    forward(req, inbound, HttpHeaders.IDEMPOTENCY_KEY);
  }

  private void forward(
      io.helidon.webclient.api.HttpClientRequest req,
      jakarta.ws.rs.core.HttpHeaders inbound,
      String header) {
    String value = inbound.getHeaderString(header);
    if (value != null && !value.isBlank()) {
      req.header(io.helidon.http.HeaderNames.create(header), value);
    }
  }

  /**
   * Executes the upstream call and copies status + body back. The call is passed as a supplier so
   * connect/read failures (including the WebClient timeouts configured in {@link GatewayBeans})
   * surface as 504/502 envelopes instead of leaking as container 500s.
   */
  private Response relay(
      java.util.function.Supplier<HttpClientResponse> call, String service, String requestId) {
    HttpClientResponse upstream;
    try {
      upstream = call.get();
    } catch (RuntimeException e) {
      boolean timeout = hasCause(e, java.net.SocketTimeoutException.class);
      return Response.status(timeout ? 504 : 502)
          .header(HttpHeaders.REQUEST_ID, requestId)
          .type(MediaType.APPLICATION_JSON)
          .entity(
              ApiResponse.error(
                  ErrorBody.of(
                      timeout ? "UPSTREAM_TIMEOUT" : "UPSTREAM_ERROR",
                      "'" + service + "' did not answer" + (timeout ? " in time" : ""))))
          .build();
    }
    try (upstream) {
      int status = upstream.status().code();
      Response.ResponseBuilder rb =
          Response.status(status).header(HttpHeaders.REQUEST_ID, requestId);
      // Some upstream responses carry no body at all (e.g. a 405 from a path/method mismatch, or
      // any handler that returns a bare status) even when the status isn't 204/205/304.
      // HttpClientResponse.as(String.class) throws IllegalStateException — not an empty string —
      // for a truly absent entity, so probe hasEntity() first instead of relying on status alone.
      if (status != 204 && status != 205 && status != 304 && upstream.entity().hasEntity()) {
        rb.type(MediaType.APPLICATION_JSON).entity(upstream.as(String.class));
      }
      return rb.build();
    }
  }

  private static boolean hasCause(Throwable t, Class<? extends Throwable> type) {
    for (Throwable c = t; c != null; c = c.getCause()) {
      if (type.isInstance(c)) return true;
    }
    return false;
  }

  private Response serviceUnavailable(String service) {
    return Response.status(Response.Status.SERVICE_UNAVAILABLE)
        .type(MediaType.APPLICATION_JSON)
        .entity(
            ApiResponse.error(
                ErrorBody.of(
                    "UPSTREAM_UNAVAILABLE",
                    "No healthy instance of '" + service + "' in discovery")))
        .build();
  }

  private static void addQueryParams(
      io.helidon.webclient.api.HttpClientRequest req, UriInfo uriInfo) {
    uriInfo
        .getQueryParameters()
        .forEach((key, values) -> req.queryParam(key, values.toArray(String[]::new)));
  }

  private static String newRequestId() {
    return UUID.randomUUID().toString();
  }
}
