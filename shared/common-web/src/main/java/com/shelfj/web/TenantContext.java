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
  private Set<UUID> storeIds = Set.of();
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

  /** Stores the caller may operate in. Empty means unrestricted (e.g. OWNER/PLATFORM_ADMIN). */
  public Set<UUID> storeIds() {
    return storeIds;
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

  /**
   * User id, or throw 401 if the request carried no authenticated user (e.g. a route that requires
   * a principal reached without one).
   */
  public UUID requireUserId() {
    if (userId == null) {
      throw ApiException.unauthorized("NO_USER", "No authenticated user in request context");
    }
    return userId;
  }

  public boolean hasRole(String role) {
    return roles.contains(role);
  }

  /**
   * Throw 403 unless the caller has at least one of the supplied roles.
   *
   * <pre>{@code ctx.requireAnyRole("ADMIN", "STAFF"); }</pre>
   */
  public void requireAnyRole(String... required) {
    for (String r : required) {
      if (roles.contains(r)) return;
    }
    throw ApiException.forbidden("FORBIDDEN", "Insufficient role for this operation");
  }

  /**
   * Throw 403 if the caller is store-restricted and {@code storeId} is not one of their assigned
   * stores. A caller with no store restriction (empty {@link #storeIds()} — e.g. OWNER,
   * PLATFORM_ADMIN) may operate on any store in their tenant, so this is a no-op for them.
   *
   * <pre>{@code ctx.requireStoreAccess(storeId); }</pre>
   */
  public void requireStoreAccess(UUID storeId) {
    if (!storeIds.isEmpty() && !storeIds.contains(storeId)) {
      throw ApiException.forbidden("STORE_ACCESS_DENIED", "Caller is not assigned to this store");
    }
  }

  // --- populated by the filter ---
  void set(UUID tenantId, UUID userId, Set<String> roles, Set<UUID> storeIds, String requestId) {
    this.tenantId = tenantId;
    this.userId = userId;
    this.roles = roles == null ? Set.of() : Set.copyOf(roles);
    this.storeIds = storeIds == null ? Set.of() : Set.copyOf(storeIds);
    this.requestId = requestId;
  }
}
