package com.shelfj.iam.domain;

import java.util.Set;
import java.util.UUID;

/**
 * Everything an access token says about a login, as one reading of the database (SJ-D63): the
 * tenant and type from the user's row and the roles, store scope and permissions from its role
 * assignments, all from the same statement — so a change that lands while someone signs in is in
 * the token whole or not at all.
 *
 * @param tenantId the tenant the login belongs to; null for a shopper or a platform administrator
 * @param type STAFF or CUSTOMER
 * @param email the login
 * @param roles the distinct role names
 * @param storeIds the stores the login may work in; empty means not narrowed to any
 * @param permissions the permission set, or null when no role of theirs is a custom one
 */
public record TokenIdentity(
    UUID tenantId,
    String type,
    String email,
    Set<String> roles,
    Set<UUID> storeIds,
    Set<String> permissions) {

  public TokenIdentity {
    roles = Set.copyOf(roles);
    storeIds = Set.copyOf(storeIds);
    permissions = permissions == null ? null : Set.copyOf(permissions);
  }
}
