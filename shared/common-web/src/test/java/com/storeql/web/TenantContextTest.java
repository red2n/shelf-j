package com.storeql.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.storeql.ids.Ids;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** A request acting for a tenant the service resolved itself (07.13, the transport seam). */
class TenantContextTest {

  @Test
  void assumingATenantScopesTheRequestToItWithOneRoleAndNoUser() {
    TenantContext ctx = new TenantContext();
    UUID staffTenant = Ids.newId();
    UUID staff = Ids.newId();
    ctx.set(staffTenant, staff, Set.of("OWNER", "MANAGER"), Set.of(Ids.newId()), "req-1");

    UUID receiver = Ids.newId();
    ctx.assume(receiver, "MANAGER");

    assertEquals(receiver, ctx.requireTenantId());
    assertNull(ctx.userId(), "a delivery is written by nobody");
    assertThrows(ApiException.class, ctx::requireUserId);
    assertTrue(ctx.hasRole("MANAGER"));
    assertTrue(!ctx.hasRole("OWNER"), "the caller's own roles are gone");
    assertTrue(ctx.storeIds().isEmpty(), "no store restriction survives");
    assertEquals("req-1", ctx.requestId(), "the correlation id is the request's still");
  }

  @Test
  void assumingNeedsATenantAndARole() {
    TenantContext ctx = new TenantContext();
    assertThrows(NullPointerException.class, () -> ctx.assume(null, "MANAGER"));
    assertThrows(NullPointerException.class, () -> ctx.assume(Ids.newId(), null));
    assertNull(ctx.tenantId(), "a refused assumption changes nothing");
  }
}
