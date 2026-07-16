package com.shelfj.web;

import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;
import java.io.IOException;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Populates {@link TenantContext} from the identity headers the gateway forwards (after it
 * validates the JWT), and guarantees an {@code X-Request-Id} exists and is echoed on the response
 * (docs/ARCHITECTURE.md §14).
 *
 * <p>In production the gateway validates the JWT and injects {@code X-Tenant-Id} / {@code
 * X-User-Id} / {@code X-Roles}. In local/dev calls that hit a service directly, these may be absent
 * — protected endpoints then fail closed via {@link TenantContext#requireTenantId()}.
 */
@Provider
@Priority(Priorities.AUTHENTICATION)
public class TenantContextFilter implements ContainerRequestFilter, ContainerResponseFilter {

  @Inject TenantContext context;

  @Override
  public void filter(ContainerRequestContext req) throws IOException {
    String requestId = req.getHeaderString(HttpHeaders.REQUEST_ID);
    if (requestId == null || requestId.isBlank()) {
      requestId = UUID.randomUUID().toString();
    }

    UUID tenantId = parseUuid(req.getHeaderString(HttpHeaders.TENANT_ID));
    UUID userId = parseUuid(req.getHeaderString(HttpHeaders.USER_ID));
    Set<String> roles = parseRoles(req.getHeaderString(HttpHeaders.ROLES));
    Set<UUID> storeIds = parseUuids(req.getHeaderString(HttpHeaders.STORE_IDS));

    context.set(tenantId, userId, roles, storeIds, requestId);
    // stash for the response filter
    req.setProperty(HttpHeaders.REQUEST_ID, requestId);
  }

  @Override
  public void filter(ContainerRequestContext req, ContainerResponseContext resp)
      throws IOException {
    Object requestId = req.getProperty(HttpHeaders.REQUEST_ID);
    if (requestId != null) {
      resp.getHeaders().putSingle(HttpHeaders.REQUEST_ID, requestId);
    }
  }

  private static UUID parseUuid(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return UUID.fromString(value.trim());
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  private static Set<String> parseRoles(String value) {
    if (value == null || value.isBlank()) {
      return Set.of();
    }
    return Arrays.stream(value.split(","))
        .map(String::trim)
        .filter(s -> !s.isEmpty())
        .collect(Collectors.toUnmodifiableSet());
  }

  private static Set<UUID> parseUuids(String value) {
    if (value == null || value.isBlank()) {
      return Set.of();
    }
    return Arrays.stream(value.split(","))
        .map(String::trim)
        .filter(s -> !s.isEmpty())
        .map(TenantContextFilter::parseUuid)
        .filter(java.util.Objects::nonNull)
        .collect(Collectors.toUnmodifiableSet());
  }
}
