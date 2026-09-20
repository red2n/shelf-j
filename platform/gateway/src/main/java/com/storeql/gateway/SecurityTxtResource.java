package com.storeql.gateway;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Response;
import java.time.Clock;

/**
 * {@code GET /.well-known/security.txt} (RFC 9116), public: a researcher who found a vulnerability
 * has no account to sign in with. 404 when the deployment has not configured a valid file.
 */
@Path("/.well-known")
@ApplicationScoped
public class SecurityTxtResource {

  @Inject GatewayConfig config;

  Clock clock = Clock.systemUTC();

  @GET
  @Path("/security.txt")
  @Produces("text/plain; charset=utf-8")
  public Response securityTxt() {
    return SecurityTxt.render(config.securityTxt(), clock.instant())
        .map(body -> Response.ok(body).header("Cache-Control", "max-age=3600").build())
        .orElseGet(() -> Response.status(Response.Status.NOT_FOUND).build());
  }
}
