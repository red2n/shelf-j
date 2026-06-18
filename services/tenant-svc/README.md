# Tenant Service (tenant-svc)

**Type:** Business Service  
**Port (dev):** 8002  
**Repository:** `services/tenant-svc/`  
**Role:** Multi-tenant business management, location hierarchy (Tenants → Stores → Zones), staff assignment

---

## Quick Overview

| Aspect | Details |
|--------|---------|
| **Responsibility** | Create/manage tenants (businesses), stores (physical locations), zones (aisles/sections), staff assignments |
| **Primary Domain** | Tenant Management & Location Model |
| **Key Tables** | `tenants`, `stores`, `zones`, `staff_assignments`, `tenant_inventory_config` |
| **Events Published** | `TenantCreated`, `StoreCreated`, `ZoneCreated`, `StaffAssigned`, `TenantStatusChanged`, `StoreStatusChanged` |
| **Events Consumed** | `UserRoleGranted` (from iam-svc when user should get OWNER role) |
| **Sync Dependencies** | None (doesn't call other services; other services call it for tenant/store info) |
| **Technology** | Helidon MP, PostgreSQL, Kafka |

---

## 1. How Requests Reach Tenant Service

### 1.1 Tenant Creation Flow (Multi-step)

```
Client (Business Owner)
  │
  ├─ Step 1: POST /api/iam-svc/auth/register
  │   └─ Response: { accessToken (no tenant claim yet) }
  │
  ├─ Step 2: POST /api/tenant-svc/onboarding/tenants
  │   ├─ Headers: Authorization: Bearer {accessToken}
  │   │           (X-Tenant-Id not needed yet, gateway uses X-User-Id from JWT)
  │   ├─ Body: { businessName, legalName, country, currency }
  │   │
  │   ↓ (Through gateway, validates Bearer token)
  │
  │   Tenant Service (OnboardingResource)
  │   ├─ Extract: userId from context
  │   ├─ Generate: tenantId = UUID
  │   ├─ Create: Tenant row (with owner_user_id = userId)
  │   ├─ Publish: TenantCreated event
  │   ├─ Publish: UserRoleGranted event (grant OWNER to creator)
  │   └─ Return: 201 { tenantId, businessName, ... }
  │
  ├─ Step 3: POST /api/tenant-svc/onboarding/stores
  │   ├─ Headers: Authorization: Bearer {accessToken}
  │   │           X-Tenant-Id: {newTenantId}  (from Step 2)
  │   ├─ Body: { name, code, type, address, timezone }
  │   │
  │   ↓ (Gateway flow guard: preserves X-Tenant-Id for onboarding)
  │
  │   Tenant Service (OnboardingResource)
  │   ├─ Extract: tenantId from X-Tenant-Id header
  │   ├─ Validate: Tenant exists and is ACTIVE
  │   ├─ Create: Store row (with default zone)
  │   ├─ Publish: StoreCreated + ZoneCreated events
  │   └─ Return: 201 { storeId, name, ... }
  │
  └─ Step 4: User logs in again (or calls refresh)
      ├─ POST /api/iam-svc/auth/refresh
      ├─ IAM service now includes tenant + OWNER role in JWT
      ├─ Response: { accessToken (now with tenant claim + OWNER role) }
      └─ Now can call /admin/* endpoints
```

### 1.2 Admin Operations (with full tenant context)

```
Client (Business Admin/Owner)
  │
  └─ GET /api/tenant-svc/admin/stores
     ├─ Headers: Authorization: Bearer {accessToken}  (with tenant claim)
     │           (Gateway extracts X-Tenant-Id from JWT)
     │
     ↓ (Through gateway with full tenant context)
     │
     Tenant Service (AdminResource)
     ├─ Extract: tenantId from context
     ├─ Validate: User has OWNER or MANAGER role for this tenant
     ├─ Query: SELECT * FROM stores WHERE tenant_id = ?
     └─ Return: 200 { stores: [...] }
```

### 1.3 Public Endpoints (Storefront)

```
Guest/Customer
  │
  └─ GET /api/tenant-svc/storefront/stores/{storeId}
     ├─ Headers: X-Storefront-Tenant: {tenantId}  (from domain/subdomain)
     │           (NO Bearer token required - public)
     │
     ↓ (Gateway allows public storefront paths)
     │
     Tenant Service (StorefrontResource)
     ├─ Lookup: Store by storeId + tenantId
     ├─ Check: showPrices flag, status
     └─ Return: 200 { store details (public data only) }
```

---

## 2. Request Dispatching

### 2.1 Resource Layer

```java
// Three resource classes: Onboarding, Admin, Storefront

@Path("/onboarding")
public class OnboardingResource {
  @POST @Path("/tenants")
  public Response createTenant(CreateTenantRequest req) {
    UUID userId = ctx.requireUserId();  // No tenant context yet
    var tenant = service.createTenant(userId, req);
    return Response.status(201).entity(ApiResponse.ok(...)).build();
  }

  @POST @Path("/stores")
  public Response createStore(CreateStoreRequest req) {
    UUID tenantId = ctx.requireTenantId();  // From X-Tenant-Id header (just created)
    var store = service.createDefaultStore(tenantId, req);
    return Response.status(201).entity(ApiResponse.ok(...)).build();
  }

  @GET @Path("/status")
  public ApiResponse<OnboardingStatus> status() {
    UUID tenantId = ctx.requireTenantId();
    return ApiResponse.ok(service.onboardingStatus(tenantId));
  }
}

@Path("/admin")
public class AdminResource {
  @GET @Path("/tenant")
  public ApiResponse<TenantResponse> getTenant() {
    UUID tenantId = ctx.requireTenantId();
    return ApiResponse.ok(Mappers.toTenant(service.getTenant(tenantId)));
  }

  @GET @Path("/stores")
  public ApiResponse<List<StoreResponse>> listStores() {
    UUID tenantId = ctx.requireTenantId();
    var stores = service.listStores(tenantId);
    return ApiResponse.ok(stores.stream().map(Mappers::toStore).toList());
  }

  @POST @Path("/stores")
  public Response addStore(CreateStoreRequest req) {
    UUID tenantId = ctx.requireTenantId();
    var store = service.addStore(tenantId, req);
    return Response.status(201).entity(ApiResponse.ok(...)).build();
  }

  @GET @Path("/stores/{storeId}")
  public ApiResponse<StoreResponse> getStore(@PathParam("storeId") UUID storeId) {
    UUID tenantId = ctx.requireTenantId();
    return ApiResponse.ok(Mappers.toStore(service.getStore(tenantId, storeId)));
  }

  @GET @Path("/stores/{storeId}/zones")
  public ApiResponse<List<ZoneResponse>> listZones(
      @PathParam("storeId") UUID storeId) {
    UUID tenantId = ctx.requireTenantId();
    var zones = service.listZones(tenantId, storeId);
    return ApiResponse.ok(zones.stream().map(Mappers::toZone).toList());
  }

  @POST @Path("/stores/{storeId}/zones")
  public Response addZone(
      @PathParam("storeId") UUID storeId,
      CreateZoneRequest req) {
    UUID tenantId = ctx.requireTenantId();
    var zone = service.addZone(tenantId, storeId, req);
    return Response.status(201).entity(ApiResponse.ok(...)).build();
  }

  @POST @Path("/staff")
  public Response assignStaff(AssignStaffRequest req) {
    UUID tenantId = ctx.requireTenantId();
    service.assignStaff(tenantId, req);
    return Response.status(201).entity(ApiResponse.ok(...)).build();
  }

  @GET @Path("/staff")
  public ApiResponse<List<StaffResponse>> listStaff() {
    UUID tenantId = ctx.requireTenantId();
    var staff = service.listStaff(tenantId);
    return ApiResponse.ok(staff.stream().map(Mappers::toStaff).toList());
  }
}

@Path("/storefront")
public class StorefrontResource {
  @GET @Path("/stores/{storeId}")
  public ApiResponse<StorePublicResponse> getStore(@PathParam("storeId") UUID storeId) {
    // No tenant context validation here - public endpoint
    var store = service.getStorePublic(storeId);
    return ApiResponse.ok(Mappers.toStorePublic(store));
  }
}
```

### 2.2 Service Layer

```java
@ApplicationScoped
public class TenantService {
  @Inject TenantRepository repo;

  // Onboarding flow
  public Tenant createTenant(UUID ownerUserId, CreateTenantRequest req) {
    // 1. Validate
    if (req.businessName() == null || req.businessName().isBlank()) {
      throw ApiException.badRequest("INVALID_NAME", "Business name required");
    }

    // 2. Create tenant
    UUID tenantId = UUID.randomUUID();
    Instant now = Instant.now();
    var tenant = new Tenant(
        tenantId,
        req.businessName(),
        req.legalName(),
        Tenant.STATUS_ACTIVE,
        null,  // plan_id (optional)
        ownerUserId,
        req.country().toUpperCase(),
        req.currency().toUpperCase(),
        now, now);

    // 3. Publish events (atomic with DB write)
    var tenantEvent = new OutboxRow(
        "TenantCreated",
        "shelfj.tenant.tenant-created",
        tenantId,
        tenantId,
        Events.tenantCreated(tenantId, ownerUserId, req.businessName(), 
                             req.country(), req.currency()));

    // Flow guard: Grant OWNER role to tenant creator
    var roleEvent = new OutboxRow(
        "UserRoleGranted",
        "shelfj.iam.user-role-granted",
        tenantId,
        ownerUserId,
        Events.userRoleGranted(tenantId, ownerUserId, "OWNER"));

    // 4. Persist
    var createdTenant = repo.createTenantWithOutbox(tenant, tenantEvent);
    repo.publishEvent(roleEvent);

    return createdTenant;
  }

  public StoreWithZone createDefaultStore(UUID tenantId, CreateStoreRequest req) {
    // Validate tenant exists
    Tenant tenant = repo.findTenant(tenantId)
        .orElseThrow(() -> ApiException.notFound("TENANT_NOT_FOUND", "Tenant not found"));

    // Create store + default zone (atomic)
    return createStoreInternal(tenantId, req, true);  // isDefault = true
  }

  public Tenant getTenant(UUID tenantId) {
    return repo.findTenant(tenantId)
        .orElseThrow(() -> ApiException.notFound("TENANT_NOT_FOUND", "Tenant not found"));
  }

  public List<Store> listStores(UUID tenantId) {
    return repo.listStores(tenantId);
  }

  public Store getStore(UUID tenantId, UUID storeId) {
    return repo.findStore(tenantId, storeId)
        .orElseThrow(() -> ApiException.notFound("STORE_NOT_FOUND", "Store not found"));
  }
}
```

### 2.3 Repository Layer

```java
@ApplicationScoped
public class TenantRepository extends BaseOutboxRepository {

  public Tenant createTenantWithOutbox(Tenant t, OutboxRow event) {
    return inTx(c -> {
      insertTenant(c, t);
      insertOutbox(c, event);
      return t;
    }, "create tenant");
  }

  public StoreWithZone createStoreWithDefaultZone(
      Store store, Zone defaultZone, OutboxRow storeEvent, OutboxRow zoneEvent) {
    return inTx(c -> {
      // Verify tenant is ACTIVE
      assertTenantActive(c, store.tenantId());
      
      // Insert store
      insertStore(c, store);
      
      // Insert default zone
      insertZone(c, defaultZone);
      
      // Insert events
      insertOutbox(c, storeEvent);
      insertOutbox(c, zoneEvent);
      
      return new StoreWithZone(store, defaultZone);
    }, "create store with default zone");
  }

  public Optional<Tenant> findTenant(UUID tenantId) {
    return query(
      "SELECT id, business_name, legal_name, status, plan_id, owner_user_id, country, currency, created_at, updated_at "
      + "FROM tenants WHERE id = ?",
      ps -> ps.setObject(1, tenantId),
      TenantRepository::mapTenant,
      "find tenant"
    ).stream().findFirst();
  }

  public Optional<Store> findStore(UUID tenantId, UUID storeId) {
    return query(
      "SELECT id, tenant_id, name, code, type, status, is_default, ... "
      + "FROM stores WHERE tenant_id = ? AND id = ?",
      ps -> {
        ps.setObject(1, tenantId);
        ps.setObject(2, storeId);
      },
      TenantRepository::mapStore,
      "find store"
    ).stream().findFirst();
  }

  public List<Store> listStores(UUID tenantId) {
    return query(
      "SELECT ... FROM stores WHERE tenant_id = ? ORDER BY created_at",
      ps -> ps.setObject(1, tenantId),
      TenantRepository::mapStore,
      "list stores"
    );
  }

  public void publishEvent(OutboxRow event) {
    inTx(c -> {
      insertOutbox(c, event);
      return null;
    }, "publish event");
  }
}
```

---

## 3. Service Role & Responsibilities

### 3.1 The Location Model (Core Responsibility)

**Hierarchy:**

```
Tenant (a business)
  owner_user_id: uuid (the creator)
  status: ACTIVE | INACTIVE | DELETED
  country: "IN" | "US" | "UK" | ...
  currency: "INR" | "USD" | "GBP" | ...
  ↓ (1:N relationship)
  
Stores (physical locations)
  tenant_id: uuid (which business)
  type: STORE | WAREHOUSE
  code: "MAIN" | "BRANCH1" | ... (unique within tenant)
  name: "Main Store" | ...
  address: line1, line2, city, state, pincode
  timezone: "Asia/Kolkata"
  business_hours: JSON (e.g., {"monday": "09:00-21:00"})
  is_default: boolean (first store created)
  show_prices: boolean (whether to show on storefront)
  ↓ (1:N relationship)
  
Zones (sub-locations within store)
  tenant_id: uuid (inherited from store)
  store_id: uuid (which store)
  type: DEFAULT | AISLE | SHELF | COLD_ROOM | BACK_STORE
  code: "DEFAULT" | "A1" | "A2" | ... (unique within store)
  name: "Aisle 1" | ...
  status: ACTIVE | INACTIVE
```

**Semantics:**
- Every store automatically gets a `DEFAULT` zone (where stock lives if no other zone specified)
- Zones used by inventory-svc to track where stock physically sits
- Stores track business hours, location, whether to display prices

### 3.2 Staff Assignment

```
StaffAssignment
├─ user_id: UUID (who)
├─ tenant_id: UUID (which business)
├─ store_id: UUID (which store they work in)
├─ role: MANAGER | CASHIER | STOREKEEPER | ... (what they can do in that store)
└─ created_at: TIMESTAMP
```

**Difference from IAM roles:**
- IAM tracks: User + Tenant + Role (e.g., OWNER, STAFF, CUSTOMER)
- Tenant tracks: User + Tenant + Store + Role (more granular: which store they work in)

### 3.3 What Tenant Service Owns

**Database tables:**
- `tenants` — Business entities
- `stores` — Physical locations
- `zones` — Sub-locations (aisles, shelves, etc.)
- `staff_assignments` — Staff to store+role mappings
- `tenant_inventory_config` — Settings for that business (lot control, serial, etc.)
- `outbox` — Event queue

**Events published:**
```
TenantCreated → When business is created
StoreCreated → When store is added
ZoneCreated → When zone is added (even default zone)
StoreStatusChanged → When store activated/deactivated
TenantStatusChanged → When tenant suspended/deleted
StaffAssigned → When staff assigned to store
```

**Events consumed:**
```
UserRoleGranted (from iam-svc) → Another service wants user to have a role
```

### 3.4 Tenant Service Never

- ❌ Creates users (IAM does)
- ❌ Manages inventory (inventory-svc does)
- ❌ Manages products (product-svc does)
- ❌ Stores customer data (customer-svc does)

---

## 4. Technical Architecture

### 4.1 Database Schema

```sql
CREATE TABLE tenants (
  id UUID PRIMARY KEY,
  business_name VARCHAR(255) NOT NULL,
  legal_name VARCHAR(255),
  status VARCHAR(20) DEFAULT 'ACTIVE',  -- ACTIVE, INACTIVE, DELETED
  plan_id VARCHAR(50),  -- Optional: which plan are they on
  owner_user_id UUID NOT NULL,  -- FK to iam_svc.users (no constraint, just track)
  country VARCHAR(2) NOT NULL,  -- "IN", "US", etc.
  currency VARCHAR(3) NOT NULL,  -- "INR", "USD", etc.
  created_at TIMESTAMPTZ DEFAULT now(),
  updated_at TIMESTAMPTZ DEFAULT now()
);
CREATE INDEX idx_tenants_owner_user_id ON tenants(owner_user_id);

CREATE TABLE stores (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL,
  name VARCHAR(255) NOT NULL,
  code VARCHAR(50) NOT NULL,  -- Unique within tenant
  type VARCHAR(20) DEFAULT 'STORE',  -- STORE, WAREHOUSE
  line1 VARCHAR(255),  -- Address
  line2 VARCHAR(255),
  city VARCHAR(100),
  state VARCHAR(100),
  country VARCHAR(2),
  pincode VARCHAR(20),
  geo_lat DECIMAL(10, 8),  -- Latitude
  geo_lng DECIMAL(11, 8),  -- Longitude
  timezone VARCHAR(50) DEFAULT 'UTC',
  business_hours JSONB,  -- {"monday": "09:00-21:00", ...}
  status VARCHAR(20) DEFAULT 'ACTIVE',  -- ACTIVE, INACTIVE
  is_default BOOLEAN DEFAULT false,  -- First store
  show_prices BOOLEAN DEFAULT true,  -- Show on storefront
  created_at TIMESTAMPTZ DEFAULT now(),
  updated_at TIMESTAMPTZ DEFAULT now()
);
CREATE UNIQUE INDEX idx_stores_tenant_code ON stores(tenant_id, code);
CREATE INDEX idx_stores_tenant_id ON stores(tenant_id);

CREATE TABLE zones (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL,
  store_id UUID NOT NULL,
  name VARCHAR(255) NOT NULL,
  code VARCHAR(50) NOT NULL,  -- Unique within store
  type VARCHAR(20) DEFAULT 'AISLE',  -- DEFAULT, AISLE, SHELF, COLD_ROOM, BACK_STORE
  status VARCHAR(20) DEFAULT 'ACTIVE',
  created_at TIMESTAMPTZ DEFAULT now(),
  updated_at TIMESTAMPTZ DEFAULT now()
);
CREATE UNIQUE INDEX idx_zones_store_code ON zones(store_id, code);
CREATE INDEX idx_zones_tenant_id ON zones(tenant_id);
CREATE INDEX idx_zones_store_id ON zones(store_id);

CREATE TABLE staff_assignments (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL,
  user_id UUID NOT NULL,
  store_id UUID NOT NULL,
  role VARCHAR(50) NOT NULL,  -- MANAGER, CASHIER, STOREKEEPER
  created_at TIMESTAMPTZ DEFAULT now()
);
CREATE INDEX idx_staff_tenant_id ON staff_assignments(tenant_id);
CREATE INDEX idx_staff_user_id ON staff_assignments(user_id);
CREATE INDEX idx_staff_store_id ON staff_assignments(store_id);

CREATE TABLE tenant_inventory_config (
  id UUID PRIMARY KEY,
  tenant_id UUID UNIQUE NOT NULL,
  lot_control_enabled BOOLEAN DEFAULT false,
  serial_control_enabled BOOLEAN DEFAULT false,
  grade_control_enabled BOOLEAN DEFAULT false,
  expiry_tracking_enabled BOOLEAN DEFAULT false,
  costing_method VARCHAR(50) DEFAULT 'FIFO',  -- FIFO, LIFO, WEIGHTED_AVG
  default_uom VARCHAR(50),  -- Default unit of measure
  reorder_alert_enabled BOOLEAN DEFAULT true,
  auto_reserve_on_order BOOLEAN DEFAULT true,
  created_at TIMESTAMPTZ DEFAULT now(),
  updated_at TIMESTAMPTZ DEFAULT now()
);
```

### 4.2 Multi-tenancy Enforcement

Every query filters by `tenant_id` first:

```java
// ✓ CORRECT: Filter by tenant_id first
SELECT * FROM stores WHERE tenant_id = ? AND id = ?

// ❌ WRONG: Don't filter by tenant_id
SELECT * FROM stores WHERE id = ?
```

### 4.3 Health Checks

```
GET /health/ready
├─ Database connectivity ✓
├─ Kafka brokers reachable ✓
├─ Config service accessible ✓
└─ Migrations complete ✓
```

---

## 5. API Endpoints

### 5.1 Onboarding Endpoints (Public-ish, auth required)

```
POST /api/tenant-svc/onboarding/tenants
├─ Auth: Bearer token (no tenant claim yet)
├─ Body: { businessName, legalName, country, currency }
├─ Returns: 201 { id, businessName, legalName, ... }
└─ Publishes: TenantCreated, UserRoleGranted

POST /api/tenant-svc/onboarding/stores
├─ Auth: Bearer token
├─ Headers: X-Tenant-Id: {newTenantId}
├─ Body: { name, code, type, line1, city, country, pincode, timezone }
├─ Returns: 201 { id, name, code, ... }
└─ Publishes: StoreCreated, ZoneCreated

GET /api/tenant-svc/onboarding/status
├─ Auth: Bearer token
├─ Headers: X-Tenant-Id: {tenantId}
├─ Returns: 200 { active, hasStore, nextSteps: [...] }
└─ Shows: Onboarding progress
```

### 5.2 Admin Endpoints (Protected, require OWNER/MANAGER role)

```
GET /api/tenant-svc/admin/tenant
├─ Returns: 200 { tenant details }

PUT /api/tenant-svc/admin/tenant
├─ Body: { businessName, legalName }
├─ Returns: 200 { updated tenant }

GET /api/tenant-svc/admin/stores
├─ Returns: 200 { stores: [...] }

POST /api/tenant-svc/admin/stores
├─ Body: { name, code, type, address, timezone }
├─ Returns: 201 { store }

GET /api/tenant-svc/admin/stores/{storeId}
├─ Returns: 200 { store }

PUT /api/tenant-svc/admin/stores/{storeId}
├─ Body: { name, address, ... }
├─ Returns: 200 { updated store }

GET /api/tenant-svc/admin/stores/{storeId}/zones
├─ Returns: 200 { zones: [...] }

POST /api/tenant-svc/admin/stores/{storeId}/zones
├─ Body: { name, code, type }
├─ Returns: 201 { zone }

POST /api/tenant-svc/admin/staff
├─ Body: { userId, storeId, role }
├─ Returns: 201 { assignment }

GET /api/tenant-svc/admin/staff
├─ Returns: 200 { staff: [...] }
```

### 5.3 Public Storefront Endpoints

```
GET /api/tenant-svc/storefront/stores/{storeId}
├─ Auth: No (guest browsing)
├─ Headers: X-Storefront-Tenant: {tenantId}
├─ Returns: 200 { store: { name, address, hours, ... } }
├─ Note: Only public fields (no pricing, inventory details)
└─ Uses showPrices flag to control price visibility
```

---

## 6. Events

### 6.1 Published Events

```
TenantCreated
├─ When: Tenant is first created
├─ Topic: shelfj.tenant.tenant-created
├─ Payload: { tenantId, ownerUserId, businessName, country, currency }
└─ Consumers: iam-svc (create user_tenant record)

StoreCreated
├─ When: New store added to tenant
├─ Topic: shelfj.tenant.store-created
├─ Payload: { tenantId, storeId, code, type, isDefault }
└─ Consumers: inventory-svc (initialize store locations)

ZoneCreated
├─ When: New zone added to store (including default zone)
├─ Topic: shelfj.tenant.zone-created
├─ Payload: { tenantId, storeId, zoneId, code, type }
└─ Consumers: inventory-svc (initialize zone batches)

StoreStatusChanged
├─ When: Store activated/deactivated
├─ Topic: shelfj.tenant.store-status-changed
├─ Payload: { tenantId, storeId, status }
└─ Consumers: Other services that cache store data

TenantStatusChanged
├─ When: Tenant suspended/deleted
├─ Topic: shelfj.tenant.tenant-status-changed
├─ Payload: { tenantId, status }
└─ Consumers: All services (must stop serving deactivated tenant)

UserRoleGranted (PUBLISHED by tenant-svc to iam-svc)
├─ When: User creates tenant (should get OWNER role)
├─ Topic: shelfj.iam.user-role-granted
├─ Payload: { tenantId, userId, role: "OWNER" }
└─ Note: Tenant service publishes this to IAM service
```

### 6.2 Consumed Events

```
UserRoleGranted (from iam-svc)
├─ When: Another service says user should have a role
├─ Action: Update user_roles? (Tenant service doesn't actually need this)
└─ Note: Tenant service doesn't really consume this; it publishes it
```

---

## 7. Integration Examples

### 7.1 Other Services Reading Tenant Data

**Scenario:** Inventory service receives stock, needs to validate storeId belongs to tenant

```java
// inventory-svc
@ApplicationScoped
public class InventoryService {
  @Inject StoreClient storeClient;  // REST call to tenant-svc

  public void receiveStock(UUID tenantId, UUID storeId, ReceiveRequest req) {
    // 1. Validate store exists in this tenant
    Store store = storeClient.getStore(tenantId, storeId);
    if (store == null) {
      throw ApiException.badRequest("INVALID_STORE", "Store not found");
    }

    // 2. Create batch in that store's default zone
    Zone defaultZone = storeClient.getDefaultZone(tenantId, storeId);
    
    // 3. Insert inventory
  }
}
```

### 7.2 Other Services Consuming Zone Created Event

**Scenario:** Inventory service must initialize zone when created

```java
// inventory-svc event consumer
@Incoming("shelfj.tenant.zone-created")
public void onZoneCreated(Message<String> msg) {
  var event = parseJson(msg.getPayload());
  
  // Initialize zone in inventory tracking (if needed)
  // This is idempotent: if zone already exists in inventory, skip
  inventoryRepo.initializeZoneIfNotExists(
      event.tenantId(),
      event.storeId(),
      event.zoneId(),
      event.code());
  
  msg.ack();
}
```

---

## 8. Testing

### 8.1 Unit Tests

```java
@Test
void shouldCreateTenant_whenValidRequest() {
  var req = new CreateTenantRequest("Acme Corp", "Acme Limited", "IN", "INR");
  
  Tenant result = service.createTenant(USER_ID, req);
  
  assertThat(result.id()).isNotNull();
  assertThat(result.businessName()).isEqualTo("Acme Corp");
  assertThat(result.ownerUserId()).isEqualTo(USER_ID);
  assertThat(result.status()).isEqualTo(Tenant.STATUS_ACTIVE);
}

@Test
void shouldCreateStoreWithDefaultZone() {
  createTenant(TENANT_ID);
  var req = new CreateStoreRequest("Main Store", "MAIN", "STORE", ...);
  
  StoreWithZone result = service.createDefaultStore(TENANT_ID, req);
  
  assertThat(result.store().id()).isNotNull();
  assertThat(result.zone().type()).isEqualTo(Zone.TYPE_DEFAULT);
}
```

### 8.2 Integration Tests

```java
@Test
void shouldPublishTenantCreatedEvent() {
  var req = new CreateTenantRequest(...);
  
  Tenant created = service.createTenant(USER_ID, req);
  
  var outbox = repo.findOutboxByEventType("TenantCreated");
  assertThat(outbox).hasSize(1);
  assertThat(outbox.get(0).aggregateId()).isEqualTo(created.id());
}
```

---

## 9. References

- [CLAUDE.md](../../CLAUDE.md) — Multi-tenancy rules
- [docs/onboarding-and-locations.md](../../docs/onboarding-and-locations.md) — Detailed location model
- [OnboardingResource.java](src/main/java/com/shelfj/tenant/api/OnboardingResource.java)
- [AdminResource.java](src/main/java/com/shelfj/tenant/api/AdminResource.java)

---

**Last Updated:** 2026-06-18  
**Service Version:** v1  
**Maintainer:** Platform Team
