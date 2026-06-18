# Service Documentation Template

Use this template for documenting each microservice. Follow the structure exactly for consistency.

```markdown
# {Service Name} (service-name-svc)

**Type:** Business Service | Platform Service  
**Owner:** {Domain}  
**Port (dev):** {Port}  
**Repository:** `services/service-name-svc/` or `platform/service-name/`

---

## Quick Overview

| Aspect | Details |
|--------|---------|
| **Responsibility** | What this service owns |
| **Primary Domain** | Domain it operates in |
| **Key Tables** | Main database tables |
| **Events Published** | Event types it emits |
| **Events Consumed** | Event types it listens to |
| **Sync Dependencies** | Services it calls via REST |
| **Technology** | Helidon MP, Java 21, etc. |

---

## 1. System Flow: How Requests Reach This Service

### 1.1 Request Journey

```
Client (Storefront/Admin/POS)
  ↓
API Gateway (port 8090)
  ├─ Validates Bearer token (JWT)
  ├─ Extracts X-Tenant-Id, X-User-Id, X-Roles
  ├─ Looks up service in Consul
  └─ Forwards to: http://{service-svc}:8080/api/{service}/{path}
    ↓
Service Container (port 8080)
  ├─ TenantContextFilter reads headers
  ├─ Routes to @Path handler
  ├─ Executes business logic
  └─ Returns JSON response
    ↓
Gateway (formats + sends to client)
```

### 1.2 Service Discovery

- **Registry:** Consul (localhost:8500 in dev)
- **Registration:** On startup, service registers itself with unique ID
- **Health Check:** Gateway polls `/health/live` every 10s
- **Deregistration:** On shutdown or 30s of failed checks

### 1.3 Multi-tenancy at Entry

All business endpoints require:
- `Authorization: Bearer {jwt}` (validated by gateway)
- `X-Tenant-Id: {uuid}` (from JWT or request header)
- `X-User-Id: {uuid}` (from JWT)
- `X-Roles: CUSTOMER,OWNER,STAFF` (from JWT)

Result: **TenantContext** (request-scoped) contains authenticated identity.

---

## 2. Request Dispatching

### 2.1 Request Pipeline

```
Incoming HTTP Request
  ↓
[Filter] TenantContextFilter
  ├─ Reads X-Tenant-Id, X-User-Id, X-Roles from headers
  ├─ Validates UUID format
  └─ Populates request-scoped TenantContext
  ↓
[Filter] (future: Rate limit, request logging, etc.)
  ↓
[Resource] @Path handler (api/)
  ├─ Validates input (Bean Validation)
  ├─ Checks authorization (if required)
  └─ Calls service layer
  ↓
[Service] Business logic (service/)
  ├─ Validates business rules
  ├─ Reads/writes repository
  ├─ Publishes events
  └─ Returns domain object
  ↓
[Mapper] DTO conversion
  ├─ Domain → DTO
  └─ Wraps in ApiResponse envelope
  ↓
HTTP Response (200/201/400/403/404/500)
```

### 2.2 Controller Layer (api/)

**Pattern:** Thin controllers, logic in services

```java
@Path("/admin/stores")
@Produces(MediaType.APPLICATION_JSON)
public class AdminResource {
  @Inject TenantService service;
  @Inject TenantContext ctx;

  @GET
  public ApiResponse<List<StoreResponse>> listStores() {
    // 1. Validate tenant context
    UUID tenantId = ctx.requireTenantId();
    
    // 2. Check authorization (if needed)
    // ctx.requireAnyRole("OWNER", "STAFF");
    
    // 3. Delegate to service
    var stores = service.listStores(tenantId);
    
    // 4. Map and return
    return ApiResponse.ok(stores.stream().map(Mappers::toStore).toList());
  }
}
```

**Responsibilities:**
- Parse HTTP method, path, query params
- Validate input (via `@Valid` on DTOs)
- Extract tenant/user context
- Call service layer
- Map domain → DTO
- Wrap response in envelope

### 2.3 Service Layer (service/)

**Pattern:** Business logic, transaction boundaries

```java
@ApplicationScoped
public class StoreService {
  @Inject StoreRepository repo;

  public Store getStore(UUID tenantId, UUID storeId) {
    // 1. Business validation
    // 2. Repository call (read-only)
    return repo.findStore(tenantId, storeId)
        .orElseThrow(() -> ApiException.notFound("STORE_NOT_FOUND", "Store not found"));
  }

  public Store createStore(UUID tenantId, CreateStoreRequest req) {
    // 1. Validate request
    if (req.name() == null || req.name().isBlank()) {
      throw ApiException.badRequest("INVALID_NAME", "Store name required");
    }
    
    // 2. Business logic (check no duplicate code, etc.)
    // 3. Call repository (write + event publishing)
    var event = new OutboxRow(...); // Event for async consumers
    return repo.createStoreWithOutbox(store, event);
  }
}
```

**Responsibilities:**
- Business rule validation
- Service-to-service calls (via clients)
- Orchestrating multiple repository operations
- Publishing events
- Error handling (throw ApiException)

### 2.4 Repository Layer (repo/)

**Pattern:** Database access, transactional outbox

```java
@ApplicationScoped
public class StoreRepository extends BaseOutboxRepository {
  
  // Single entity CRUD (atomic with outbox)
  public Store createStoreWithOutbox(Store s, OutboxRow event) {
    return inTx(c -> {
      insertStore(c, s);
      insertOutbox(c, event);
      return s;
    }, "create store");
  }

  // Reads (no transaction needed)
  public List<Store> listStores(UUID tenantId) {
    return query(
      "SELECT ... FROM stores WHERE tenant_id = ? ORDER BY created_at",
      ps -> ps.setObject(1, tenantId),
      StoreRepository::mapStore,
      "list stores"
    );
  }
}
```

**Responsibilities:**
- SQL execution
- Row ↔ Domain object mapping
- Transaction management (`inTx()`)
- Outbox insertion (for events)
- First-class tenant_id filtering

---

## 3. Service Role & Responsibilities

### 3.1 What This Service Owns

**Database Schema:**
- Tables: `{table1}`, `{table2}`, ...
- No cross-service foreign keys (database-per-service rule)
- Composite index: `tenant_id, ...` on every multi-tenant table

**Entities/Domain Objects:**
- `Entity1`, `Entity2`, ...

**API Endpoints:**
- Public storefront: `/api/{service}/public/...`
- Admin: `/api/{service}/admin/...`
- Staff: `/api/{service}/staff/...`

**Events Published:**
- `EventType1` → topic `shelfj.domain.event-type-1`
- `EventType2` → topic `shelfj.domain.event-type-2`

### 3.2 What This Service Consumes

**Events from Other Services:**
- `OtherServiceEventType` → Consuming logic in messaging/
- Idempotent? Yes/No. Dedup by: `{field}`

**REST Calls to Other Services:**
- GET `/api/other-svc/admin/resource/{id}` → Fetch resource
- Pattern: Sync call with 2s timeout + circuit breaker

### 3.3 Boundaries & Dependencies

```
This Service
├─ Owns: Tables A, B, C
├─ Reads: Tables A, B, C only
├─ Never reads: Other services' tables
├─ Calls (sync REST):
│  ├─ tenant-svc (to get store details)
│  └─ product-svc (to validate product IDs)
└─ Consumes (async Kafka):
   ├─ TenantCreated
   └─ ProductActivated
```

---

## 4. Technical Architecture

### 4.1 Framework & Stack

- **Framework:** Helidon Microprofile
- **Java Version:** 21
- **Build:** Maven
- **Database:** PostgreSQL (Flyway migrations)
- **Async:** Kafka
- **Service Discovery:** Consul
- **Configuration:** Centralized config service

### 4.2 Database Schema

**Tables owned by this service:**

```sql
-- Core entity table (tenant_id first, always)
CREATE TABLE stores (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL,
  name VARCHAR(255) NOT NULL,
  code VARCHAR(50) NOT NULL,
  status VARCHAR(20) DEFAULT 'ACTIVE',
  created_at TIMESTAMPTZ DEFAULT now(),
  updated_at TIMESTAMPTZ DEFAULT now()
);
CREATE UNIQUE INDEX idx_stores_tenant_code ON stores(tenant_id, code);
CREATE INDEX idx_stores_tenant_id ON stores(tenant_id);

-- Event log (append-only)
CREATE TABLE store_status_history (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL,
  store_id UUID NOT NULL,
  status VARCHAR(20) NOT NULL,
  changed_at TIMESTAMPTZ DEFAULT now()
);
CREATE INDEX idx_store_status_tenant_id ON store_status_history(tenant_id);
```

### 4.3 Health Checks

Service exposes 3 health probes:

```java
// GET /health/started — Service started (runs once)
// GET /health/live — Service is alive (responds quickly)
// GET /health/ready — All dependencies ready (database + Kafka + config)
```

**Ready check verifies:**
- Database connectivity
- Kafka brokers reachable
- Config service accessible
- Migrations have run

### 4.4 Configuration

**External (never in code):**
- `shelfj.db.connection-pool.size` (default 10)
- `shelfj.kafka.broker-list` (required)
- `shelfj.jwt.secret` (required, ≥32 chars)

**Sourced from:** Centralized config service or environment variables

**Never in image:** Secrets, tenant IDs, API keys, database passwords

### 4.5 Logging & Observability

- **Format:** JSON (ELK compatible)
- **Levels:** DEBUG (dev only), INFO (normal), WARN (degraded), ERROR (fail)
- **Tracing:** Distributed tracing headers passed through system
- **Metrics:** Prometheus-compatible `/metrics` endpoint

Example log:
```json
{
  "timestamp": "2026-06-18T12:34:56.789Z",
  "level": "INFO",
  "service": "store-svc",
  "requestId": "abc-123",
  "tenantId": "xyz-456",
  "message": "Store created",
  "storeId": "store-789"
}
```

---

## 5. API Endpoints

### 5.1 Authentication & Authorization

**All endpoints (except public):**
- Require: `Authorization: Bearer {jwt}`
- Extract: `tenantId`, `userId`, `roles` from JWT
- Validate: In gateway (TenantContextFilter)

**Public endpoints:**
- No token required
- Examples: `/public/catalog`, `/public/storefront`

### 5.2 Endpoint Categories

#### Admin Endpoints
```
GET    /api/{service}/admin/{entity}              List all
POST   /api/{service}/admin/{entity}              Create
GET    /api/{service}/admin/{entity}/{id}         Get one
PUT    /api/{service}/admin/{entity}/{id}         Update
DELETE /api/{service}/admin/{entity}/{id}         Delete
PATCH  /api/{service}/admin/{entity}/{id}/status  Patch status
```

#### Staff Endpoints
```
GET    /api/{service}/staff/{entity}              List (filtered for store)
POST   /api/{service}/staff/{entity}              Create (limited actions)
```

#### Storefront Public
```
GET    /api/{service}/public/catalog              Browse products
POST   /api/{service}/public/prices/resolve       Get prices
GET    /api/{service}/public/availability         Check stock
```

### 5.3 Response Envelope

All responses wrapped in standard envelope:

```json
{
  "data": {...},              // Actual response payload
  "error": null,              // null on success, error object on failure
  "meta": {
    "requestId": "abc-123",   // Trace ID
    "nextCursor": "cursor-x"  // For pagination (optional)
  }
}
```

**Error object:**
```json
{
  "code": "RESOURCE_NOT_FOUND",    // Machine-readable code
  "message": "Store not found",    // Human-readable message
  "details": [...]                 // Additional validation errors
}
```

---

## 6. Event-Driven Architecture

### 6.1 Events Published

**Outbox Pattern:** Events written to database + published to Kafka atomically

```java
// In service layer:
var entity = new Store(...);
var event = new OutboxRow(
  "StoreCreated",                      // Event type
  "shelfj.tenant.store-created",       // Kafka topic
  tenantId,                            // tenant_id (partition key)
  storeId,                             // aggregate_id (unique ID)
  json payload                         // Event JSON
);
return repo.createStoreWithOutbox(entity, event);
```

**Topics Published:**
- `shelfj.domain.entity-action` (e.g., `shelfj.tenant.store-created`)

### 6.2 Events Consumed

**Idempotent Consumer Pattern:**

```java
@ApplicationScoped
public class EventConsumer {
  @Incoming("shelfj.tenant.store-created")
  public void onStoreCreated(Message<String> msg) {
    // 1. Parse event JSON
    var event = parseJson(msg.getPayload());
    
    // 2. Idempotency check (is this event already processed?)
    if (repo.eventAlreadyProcessed(event.eventId())) {
      return; // Already handled, skip
    }
    
    // 3. Handle event (e.g., update cache)
    service.handleStoreCreated(event);
    
    // 4. Mark event as processed (atomic with business logic)
    repo.markEventProcessed(event.eventId());
    
    // 5. ACK message
    msg.ack();
  }
}
```

### 6.3 Example Event Flow

```
Scenario: User creates a store
  1. Gateway: POST /api/tenant-svc/admin/stores {name: "Main Store"}
  2. tenant-svc: Creates Store entity + StoreCreated event (outbox)
  3. Kafka Publisher (async): Polls outbox → publishes to Kafka
  4. inventory-svc: Consumes StoreCreated → Initializes default location
  5. notification-svc: Consumes StoreCreated → Sends welcome email
  6. Both idempotent: Can replay event 10x, same result
```

---

## 7. Interservice Communication

### 7.1 Synchronous Calls (REST)

**When:** Need immediate answer (user waiting)  
**How:** Service-to-service REST with retry + circuit breaker

```java
@ApplicationScoped
public class StoreService {
  @Inject TenantClient tenantClient;

  public Store getStoreWithTenant(UUID tenantId, UUID storeId) {
    // 1. Get store (local)
    Store store = repo.findStore(tenantId, storeId)
        .orElseThrow(...);
    
    // 2. Get tenant details (remote, with retry)
    Tenant tenant = tenantClient.getTenant(tenantId);
    
    return store; // tenant enriched separately in mapper
  }
}
```

**Client Pattern:**

```java
@ApplicationScoped
public class TenantClient {
  @Inject ServiceRegistry registry;
  @Inject WebClient http;

  @Retry(maxRetries = 2, delay = 100)
  @CircuitBreaker(successThreshold = 5, failureThreshold = 3)
  @Timeout(2000)
  public Tenant getTenant(UUID tenantId) {
    var instance = registry.resolve("tenant-svc")
        .orElseThrow(() -> new ServiceUnavailableException("tenant-svc down"));
    
    var response = http.get(instance.baseUri() + "/api/tenant-svc/admin/tenant")
        .header("X-Tenant-Id", tenantId.toString())
        .request(String.class)
        .await();
    
    return parseTenantJson(response);
  }
}
```

### 7.2 Asynchronous Communication (Events)

**When:** Downstream doesn't need immediate confirmation

**How:** Publish event to Kafka → Other services listen

```java
// Publishing service publishes once and moves on
var event = new OutboxRow("StoreCreated", "shelfj.tenant.store-created", ...);
repo.insertOutbox(event);
// Response sent to client immediately

// Consuming services process async (may take seconds)
// If they fail, they retry indefinitely (no loss)
```

### 7.3 Service Dependency Map

```
This Service
├─ Calls (sync):
│  ├─ tenant-svc/admin/tenant
│  └─ product-svc/admin/variants
├─ Publishes (async):
│  ├─ {eventName}
│  └─ {eventName}
└─ Consumes (async):
   ├─ {otherServiceEvent}
   └─ {otherServiceEvent}
```

---

## 8. Error Handling & Resilience

### 8.1 Error Codes

**Client errors (4xx):**
```
400 BAD_REQUEST — Invalid input (e.g., missing required field)
401 UNAUTHORIZED — Missing/invalid Bearer token
403 FORBIDDEN — Lacks required role
404 RESOURCE_NOT_FOUND — Entity doesn't exist
409 CONFLICT — Duplicate code/email
```

**Server errors (5xx):**
```
500 INTERNAL_SERVER_ERROR — Unexpected error (logs full stack)
502 UPSTREAM_ERROR — Called service returned error
503 SERVICE_UNAVAILABLE — Service not registered in Consul
504 UPSTREAM_TIMEOUT — Service took >2s to respond
```

### 8.2 Resilience Patterns

**Retry:** REST calls with exponential backoff (max 2 retries)  
**Circuit Breaker:** Open after 3 failures, retry after 60s  
**Timeout:** 2s hard timeout on all sync calls  
**Fallback:** Return cached value or error (service decides)  

---

## 9. Testing Strategy

### 9.1 Unit Tests

**Location:** `src/test/java/com/shelfj/{service}/`

```java
@Test
void shouldCreateStore_whenValidRequest() {
  // 1. Setup
  var req = new CreateStoreRequest("Main Store", "MAIN", ...);
  
  // 2. Execute
  Store result = service.createStore(TENANT_ID, req);
  
  // 3. Assert
  assertThat(result.name()).isEqualTo("Main Store");
  assertThat(result.tenantId()).isEqualTo(TENANT_ID);
}
```

### 9.2 Integration Tests

**Location:** `src/test/java/com/shelfj/{service}/`  
**Pattern:** Testcontainers (Postgres + Kafka)

```java
@Test
@Testcontainers
void shouldPublishStoreCreatedEvent() {
  // 1. Setup real database
  var tenant = new Tenant(TENANT_ID, ...);
  repo.insertTenant(tenant);
  
  // 2. Create store (publishes event)
  Store store = service.createStore(TENANT_ID, req);
  
  // 3. Verify outbox has event
  var outbox = repo.findOutboxByAggregate(store.id());
  assertThat(outbox).hasSize(1);
  assertThat(outbox.get(0).eventType()).isEqualTo("StoreCreated");
}
```

**Never Mock:** Database, Kafka (use real containers)

---

## 10. Deployment & Startup

### 10.1 Startup Sequence

1. **Container starts** → Java process begins
2. **@PostConstruct methods run** → Initialize beans, connect to DB
3. **GET /health/started** → Service reports ready (quick check)
4. **Migrations run** → Flyway executes pending V*.sql files
5. **GET /health/ready** → Service tests all dependencies (DB + Kafka)
6. **Service registers in Consul** → Gateway can now route to it
7. **Ready for traffic** → First request routed by gateway

### 10.2 Configuration on Startup

**From environment/config service:**
```yaml
shelfj:
  db:
    url: jdbc:postgresql://postgres:5432/shelfj
    user: shelfj
    password: {from-secret}
  kafka:
    broker-list: kafka:9092
  jwt:
    secret: {from-secret}
```

**Validated:** If required config missing, startup fails (safe to deploy)

### 10.3 Graceful Shutdown

1. Stop accepting new connections (readiness probe fails)
2. Wait for in-flight requests to complete (max 30s)
3. Close database connections
4. Deregister from Consul
5. Exit

---

## 11. Common Tasks

### 11.1 Adding a New Endpoint

1. **Decide:** Admin, Staff, or Public?
2. **Create resource method:**
   ```java
   @POST
   @Path("/admin/stores")
   public Response createStore(CreateStoreRequest req) {
     Validations.validate(req);
     var store = service.createStore(ctx.requireTenantId(), req);
     return Response.status(201).entity(ApiResponse.ok(...)).build();
   }
   ```
3. **Create service method** (with business logic)
4. **Create repository method** (with outbox if needed)
5. **Add test** (unit + integration)
6. **Update docs** (this file)

### 11.2 Adding a New Event

1. **Add event method to Events.java:**
   ```java
   static String storeStatusChanged(UUID tenantId, UUID storeId, String status) {
     return "{"eventType":"StoreStatusChanged", ...}"
   }
   ```
2. **Publish from service:**
   ```java
   var event = new OutboxRow("StoreStatusChanged", "shelfj.domain.store-status-changed", ...);
   repo.updateStoreStatusWithOutbox(tenantId, storeId, status, event);
   ```
3. **Create consumer in another service** (if needed)
4. **Add integration test** (verify event published)
5. **Update Kafka topic routing** (in docker-compose)

### 11.3 Adding a Sync Call to Another Service

1. **Create client class:**
   ```java
   @ApplicationScoped
   public class ProductClient {
     @Inject ServiceRegistry registry;
     
     @Retry(maxRetries = 2)
     @CircuitBreaker(...)
     @Timeout(2000)
     public Product getProduct(UUID tenantId, UUID productId) { ... }
   }
   ```
2. **Inject in service:**
   ```java
   @Inject ProductClient productClient;
   ```
3. **Call with error handling:**
   ```java
   try {
     Product product = productClient.getProduct(tenantId, productId);
   } catch (ServiceUnavailableException e) {
     throw ApiException.serverError("PRODUCT_SERVICE_DOWN", "Unable to fetch product");
   }
   ```

---

## 12. Troubleshooting

| Symptom | Cause | Fix |
|---------|-------|-----|
| `401 UNAUTHORIZED` | Missing Bearer token | Client must send `Authorization: Bearer {jwt}` |
| `403 FORBIDDEN` | Lacks required role | Check JWT roles; may need to login again |
| `404 RESOURCE_NOT_FOUND` | Resource doesn't exist | Verify ID is correct; may be from different tenant |
| `502 UPSTREAM_ERROR` | Called service failed | Check other service logs; may be down |
| `504 UPSTREAM_TIMEOUT` | Service slow | Check service CPU/memory; increase timeout if consistent |
| `500 INTERNAL_SERVER_ERROR` | Unexpected error | Check service logs for full stack trace |
| `Database migration failed` | Schema already exists | Verify migration hasn't run twice |

---

## 13. References

- [CLAUDE.md](../../CLAUDE.md) — Golden rules
- [README.md](../../README.md) — System overview
- [docs/coding-standards.md](../coding-standards.md) — SQL + SOLID rules
- [docs/onboarding-and-locations.md](../onboarding-and-locations.md) — Tenant + store model
- [FLOW_GUARD_TEST_GUIDE.md](../../k6/FLOW_GUARD_TEST_GUIDE.md) — How endpoints are tested

---

**Last Updated:** 2026-06-18  
**Service Version:** v1  
**Maintainer:** Platform Team
```
