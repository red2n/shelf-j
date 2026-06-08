package com.shelfj.web;

import jakarta.enterprise.context.RequestScoped;
import java.util.Set;
import java.util.UUID;

/**
 * Request-scoped holder of the authenticated principal.
 *
 * <p><strong>This is the ONLY sanctioned source of {@code tenantId} (golden rule #3).</strong>
 * Services read the tenant and user from here — never from the request body, path, or query. It is
 * populated by {@link TenantContextFilter} from identity the gateway forwards after it validates
 * the JWT.
 *
 * <p>Inject it where you need the caller's identity:
 *
 * <pre>{@code
 * @Inject TenantContext ctx;
 * ... ctx.requireTenantId() ...
 * }</pre>
 */
@RequestScoped
public class TenantContext {

  private UUID tenantId;
  private UUID userId;
  private Set<String> roles = Set.of();
  private String requestId;

  public UUID tenantId() {
    return tenantId;
  }

  public UUID userId() {
    return userId;
  }

  public Set<String> roles() {
    return roles;
  }

  public String requestId() {
    return requestId;
  }

  /**
   * Tenant id, or throw 401 if the request carried no tenant (e.g. a protected route reached
   * unauthenticated).
   */
  public UUID requireTenantId() {
    if (tenantId == null) {
      throw ApiException.unauthorized("NO_TENANT", "No tenant in request context");
    }
    return tenantId;
  }

  public boolean hasRole(String role) {
    return roles.contains(role);
  }

  // --- populated by the filter ---
  void set(UUID tenantId, UUID userId, Set<String> roles, String requestId) {
    this.tenantId = tenantId;
    this.userId = userId;
    this.roles = roles == null ? Set.of() : Set.copyOf(roles);
    this.requestId = requestId;
  }
}
