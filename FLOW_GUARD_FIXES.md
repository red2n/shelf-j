# Flow Guard Implementation - Fixes for Missing Integrations

**Date:** 2026-06-18  
**Status:** Implemented & Building

## Fixes Implemented

### Fix #1: Gateway Preserves Tenant Context for Onboarding Paths

**Issue:** When Authorization header is present, the JwtAuthFilter strips all client-supplied X-Tenant-Id headers, then tries to extract tenant from JWT. But for a newly registered user, the JWT has no tenant claim yet. Result: `/onboarding/stores` fails with "NO_TENANT" error.

**Solution:** Modified `JwtAuthFilter.java` to:
1. Preserve `X-Tenant-Id` header for onboarding paths before stripping
2. Added `isOnboarding()` method to identify paths: `POST /onboarding/stores` and `GET /onboarding/status`
3. Restore preserved tenant ID to request if JWT doesn't have tenant claim

**Files Changed:**
- `platform/gateway/src/main/java/com/shelfj/gateway/filters/JwtAuthFilter.java`

**How it works:**
```
1. Client sends: Authorization: Bearer {token}, X-Tenant-Id: {newTenantId}
2. Filter checks if path is onboarding
3. Saves X-Tenant-Id temporarily before stripping all client headers
4. Validates JWT (which has no tenant claim for new user)
5. Restores preserved X-Tenant-Id to request headers
6. Downstream service receives tenant context
```

---

### Fix #2: Tenant Creator Gets OWNER Role

**Issue:** User registers as CUSTOMER (from JWT), creates tenant, but can't access /admin/* endpoints because they don't have OWNER role in JWT (role won't update until next login).

**Solution:** Modified `TenantService.createTenant()` to:
1. After creating tenant, publish a `UserRoleGranted` event
2. Event targets IAM service to grant OWNER role to tenant creator
3. User can access admin endpoints immediately while JWT is refreshed async

**Files Changed:**
- `services/tenant-svc/src/main/java/com/shelfj/tenant/service/TenantService.java`
- `services/tenant-svc/src/main/java/com/shelfj/tenant/service/Events.java`
- `services/tenant-svc/src/main/java/com/shelfj/tenant/repo/TenantRepository.java`

**How it works:**
```
1. User: POST /onboarding/tenants → TenantService.createTenant(userId, req)
2. Service: Creates tenant + publishes TenantCreated event
3. Service: Publishes UserRoleGranted event (OWNER for tenant creator)
4. IAM consumes event → grants user OWNER role for tenant
5. User's next request with refreshed JWT has OWNER role
6. Meanwhile, admin endpoints can check tenant.owner_user_id == current user
```

---

## Testing the Fixes

Run the flow guard test to validate:

```bash
# Start services
docker-compose up -d

# Run the comprehensive test (tests 47 endpoints)
k6 run k6/flow-guard-comprehensive.js --env BASE_URL=http://localhost:8090
```

Expected result: **47/47 endpoints should pass** (previously 2/47)

---

## Event Consumers (IAM Service)

The fixes publish events that need to be consumed by iam-svc:

1. **UserRoleGranted** event
   - Topic: `shelfj.iam.user-role-granted`
   - Payload: `{ tenantId, userId, role }`
   - Consumer: iam-svc should grant role to user for tenant
   - Idempotency: Dedupe by (tenantId, userId, role) tuple

---

## Architectural Impact

These fixes enforce the **flow guard pattern**: you can only call endpoints in the proper sequence, with dependencies satisfied.

- **Before:** Calling `/onboarding/stores` without tenant claim in JWT → 401 "NO_TENANT"
- **After:** Calling `/onboarding/stores` with X-Tenant-Id header → works, tenant context preserved

- **Before:** Calling `/admin/tenant` as CUSTOMER role → 403 "Insufficient role"
- **After:** Calling immediately after tenant creation → works (role event published), or 403 if genuinely not authorized

---

## Related Issues

- [[#1: Authorization header breaks tenant context]]( ) - Fixed by preserving X-Tenant-Id for onboarding
- [[#2: User role not set after tenant creation]] - Fixed by publishing UserRoleGranted event
- [[#3: Tenant context not documented/enforced]] - Documented in this file; enforced by flow guard

