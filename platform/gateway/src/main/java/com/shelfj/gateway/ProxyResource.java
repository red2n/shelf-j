package com.shelfj.gateway;

import java.util.Optional;
import java.util.UUID;
import com.shelfj.discovery.ConsulClient;
import com.shelfj.discovery.ServiceInstance;
import com.shelfj.web.ApiResponse;
import com.shelfj.web.ErrorBody;
import com.shelfj.web.HttpHeaders;
import io.helidon.webclient.api.HttpClientResponse;
import io.helidon.webclient.api.WebClient;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

/**
 * The single public door. Routes {@code /api/{service}/{path...}} to the upstream resolved from Consul.
 *
 * <p>Phase-0 scope: discovery-based routing + {@code X-Request-Id} propagation for GET/POST. This is also the
 * single place where, in later phases, JWT validation runs and verified identity is injected downstream as
 * {@code X-Tenant-Id}/{@code X-User-Id}/{@code X-Roles} (see {@link #stampIdentity}). Business services trust
 * those headers precisely because nothing else can reach them (golden rule #2).</p>
 */
@Path("/api")
@ApplicationScoped
public class ProxyResource {

    @Inject
    GatewayConfig config;

    private ConsulClient consul;
    private WebClient webClient;

    @PostConstruct
    void init() {
        this.consul = new ConsulClient(config.consulHost(), config.consulPort());
        this.webClient = WebClient.builder().build();
    }

    @GET
    @Path("/{service}/{path: .*}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response proxyGet(@PathParam("service") String service,
                             @PathParam("path") String path,
                             @Context UriInfo uriInfo,
                             @Context jakarta.ws.rs.core.HttpHeaders inboundHeaders) {
        return resolve(service).map(instance -> {
            String requestId = newRequestId();
            String target = instance.baseUri() + "/" + path + queryString(uriInfo);
            var req = webClient.get(target)
                    .header(io.helidon.http.HeaderNames.create(HttpHeaders.REQUEST_ID), requestId);
            stampIdentity(req, inboundHeaders);
            return relay(req.request(), requestId);
        }).orElseGet(() -> serviceUnavailable(service));
    }

    @POST
    @Path("/{service}/{path: .*}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response proxyPost(@PathParam("service") String service,
                              @PathParam("path") String path,
                              @Context UriInfo uriInfo,
                              @Context jakarta.ws.rs.core.HttpHeaders inboundHeaders,
                              String body) {
        return resolve(service).map(instance -> {
            String requestId = newRequestId();
            String target = instance.baseUri() + "/" + path + queryString(uriInfo);
            var req = webClient.post(target)
                    .header(io.helidon.http.HeaderNames.create(HttpHeaders.REQUEST_ID), requestId)
                    .header(io.helidon.http.HeaderNames.CONTENT_TYPE, MediaType.APPLICATION_JSON);
            stampIdentity(req, inboundHeaders);
            return relay(req.submit(body == null ? "" : body), requestId);
        }).orElseGet(() -> serviceUnavailable(service));
    }

    // --- helpers ---

    private Optional<ServiceInstance> resolve(String service) {
        return consul.resolve(service);
    }

    /**
     * The security boundary. <strong>Phase 0:</strong> forwards the caller-supplied identity headers as-is so the
     * flow is demonstrable without an auth service. <strong>Phase 2+:</strong> this MUST instead validate the
     * inbound JWT and set X-Tenant-Id / X-User-Id / X-Roles from the verified claims, ignoring/stripping any
     * client-supplied copies. Downstream services trust these headers because only the gateway can reach them.
     */
    private void stampIdentity(io.helidon.webclient.api.HttpClientRequest req,
                               jakarta.ws.rs.core.HttpHeaders inbound) {
        forward(req, inbound, HttpHeaders.TENANT_ID);
        forward(req, inbound, HttpHeaders.USER_ID);
        forward(req, inbound, HttpHeaders.ROLES);
    }

    private void forward(io.helidon.webclient.api.HttpClientRequest req,
                         jakarta.ws.rs.core.HttpHeaders inbound, String header) {
        String value = inbound.getHeaderString(header);
        if (value != null && !value.isBlank()) {
            req.header(io.helidon.http.HeaderNames.create(header), value);
        }
    }

    private Response relay(HttpClientResponse upstream, String requestId) {
        String payload = upstream.as(String.class);
        return Response.status(upstream.status().code())
                .type(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.REQUEST_ID, requestId)
                .entity(payload)
                .build();
    }

    private Response serviceUnavailable(String service) {
        return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                .type(MediaType.APPLICATION_JSON)
                .entity(ApiResponse.error(ErrorBody.of("UPSTREAM_UNAVAILABLE",
                        "No healthy instance of '" + service + "' in discovery")))
                .build();
    }

    private static String newRequestId() {
        return UUID.randomUUID().toString();
    }

    private static String queryString(UriInfo uriInfo) {
        String q = uriInfo.getRequestUri().getRawQuery();
        return (q == null || q.isBlank()) ? "" : "?" + q;
    }
}
