package com.shelfj.gateway.filters;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;
import java.io.IOException;

/**
 * Marks responses served via the unversioned {@code /api/{service}/...} alias as deprecated, so
 * clients are nudged toward the canonical {@code /api/v1/...} form (RFC 8594). Requests that
 * already carry a version segment ({@code /api/v1/...}) are left untouched.
 *
 * <p>This is the only signal that distinguishes the alias from the canonical path — routing itself
 * is identical (see {@link com.shelfj.gateway.ProxyResource.Route}). When the alias is eventually
 * retired, this filter and the unversioned routing go together.
 */
@Provider
@ApplicationScoped
@Priority(Priorities.USER + 90)
public class ApiVersionDeprecationFilter implements ContainerResponseFilter {

  @Override
  public void filter(ContainerRequestContext req, ContainerResponseContext res) throws IOException {
    String path = req.getUriInfo().getPath();
    if (path == null) {
      return;
    }
    while (path.startsWith("/")) {
      path = path.substring(1);
    }
    // Only the proxy alias: starts with api/ but the next segment is not a version token.
    if (path.startsWith("api/") && !path.replaceFirst("^api/", "").matches("^v\\d+(/.*)?$")) {
      res.getHeaders().putSingle("Deprecation", "true");
      res.getHeaders().putSingle("Link", "</api/v1>; rel=\"successor-version\"");
    }
  }
}
