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
  private String email;
  private Set<String> roles = Set.of();
  private Set<UUID> storeIds = Set.of();
  private String requestId;

  /**
   * @return the caller's tenant id, or {@code null} if the request carried none
   */
  public UUID tenantId() {
    return tenantId;
  }

  /**
   * @return the caller's user id, or {@code null} if the request carried no authenticated user
   */
  public UUID userId() {
    return userId;
  }

  /**
   * The caller's own email address, as the gateway read it from the verified JWT. Present only for
   * a request that carried a token with an email claim: a guest storefront request, a
   * service-to-service call and a token minted before the claim existed all have none.
   *
   * @return the caller's email address, or {@code null} if the request carried none
   */
  public String email() {
    return email;
  }

  /**
   * @return the caller's roles; empty (never {@code null}) if the request carried none
   */
  public Set<String> roles() {
    return roles;
  }

  /** Stores the caller may operate in. Empty means unrestricted (e.g. OWNER/PLATFORM_ADMIN). */
  public Set<UUID> storeIds() {
    return storeIds;
  }

  /**
   * @return the correlation id for this request ({@link HttpHeaders#REQUEST_ID})
   */
  public String requestId() {
    return requestId;
  }

  /**
   * @return the tenant id
   * @throws ApiException 401 {@code NO_TENANT} if the request carried no tenant (e.g. a protected
   *     route reached unauthenticated)
   */
  public UUID requireTenantId() {
    if (tenantId == null) {
      throw ApiException.unauthorized("NO_TENANT", "No tenant in request context");
    }
    return tenantId;
  }

  /**
   * @return the user id
   * @throws ApiException 401 {@code NO_USER} if the request carried no authenticated user (e.g. a
   *     route that requires a principal reached without one)
   */
  public UUID requireUserId() {
    if (userId == null) {
      throw ApiException.unauthorized("NO_USER", "No authenticated user in request context");
    }
    return userId;
  }

  /**
   * @param role the role to check for, e.g. {@code "MANAGER"}
   * @return {@code true} if the caller has {@code role}
   */
  public boolean hasRole(String role) {
    return roles.contains(role);
  }

  /**
   * Throw 403 unless the caller has at least one of the supplied roles.
   *
   * <pre>{@code ctx.requireAnyRole("ADMIN", "STAFF"); }</pre>
   *
   * @param required the acceptable roles; the caller must have at least one
   * @throws ApiException 403 {@code FORBIDDEN} if the caller has none of {@code required}
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
   *
   * @param storeId the store the caller is about to operate on
   * @throws ApiException 403 {@code STORE_ACCESS_DENIED} if the caller is store-restricted and
   *     {@code storeId} is not one of their assigned stores
   */
  public void requireStoreAccess(UUID storeId) {
    if (!storeIds.isEmpty() && !storeIds.contains(storeId)) {
      throw ApiException.forbidden("STORE_ACCESS_DENIED", "Caller is not assigned to this store");
    }
  }

  /**
   * Populates this request-scoped context from identity headers. Called once per request by {@link
   * TenantContextFilter}; not for use outside the filter chain.
   *
   * @param tenantId the caller's tenant, or {@code null} if unauthenticated
   * @param userId the caller's user id, or {@code null} if unauthenticated
   * @param roles the caller's roles; {@code null} is stored as empty
   * @param storeIds the stores the caller may operate in; {@code null} is stored as empty (=
   *     unrestricted)
   * @param requestId the correlation id for this request
   */
  void set(UUID tenantId, UUID userId, Set<String> roles, Set<UUID> storeIds, String requestId) {
    this.tenantId = tenantId;
    this.userId = userId;
    this.roles = roles == null ? Set.of() : Set.copyOf(roles);
    this.storeIds = storeIds == null ? Set.of() : Set.copyOf(storeIds);
    this.requestId = requestId;
  }

  /**
   * Records the caller's email address. Separate from {@link #set} so the identity a service
   * already trusts is not re-plumbed through every test that builds a context.
   *
   * @param email the email claim from the verified JWT, or {@code null}
   */
  void setEmail(String email) {
    this.email = email == null || email.isBlank() ? null : email.trim();
  }
}
