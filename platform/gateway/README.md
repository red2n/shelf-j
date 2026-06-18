# API Gateway Service

**Type:** Platform Service  
**Port (dev):** 8090  
**Repository:** `platform/gateway/`  
**Role:** Single public entry point, JWT validation, request routing, rate limiting

---

## Quick Overview

| Aspect | Details |
|--------|---------|
| **Responsibility** | Route all external requests to business services; validate JWTs; enforce rate limits; manage CORS |
| **Primary Duty** | Security boundary (validate tokens before reaching services) |
| **Key Components** | ProxyResource, JwtAuthFilter, RateLimitFilter, ServiceRegistry client |
| **Events Published** | None (gateway doesn't store data) |
| **Events Consumed** | None |
| **Sync Dependencies** | Consul (service discovery), all business services (for proxying) |
| **Technology** | Helidon MP, Auth0 JWT library, JAX-RS (Jakarta) |

---

## 1. How Requests Reach the Gateway

### 1.1 Client Request Journey

```
Client (Browser/Mobile/POS Device)
  │
  ├─ Sends: GET /api/product-svc/admin/products
  │   Headers: Authorization: Bearer {jwt}
  │            X-Request-Id: {uuid}
  │
  ↓ (TCP port 8090)
  │
API Gateway
  ├─ Step 1: JwtAuthFilter (Priority: AUTHENTICATION - 1)
  │   ├─ Strips any client-supplied X-Tenant-Id, X-User-Id, X-Roles (security)
  │   ├─ Checks if path is public (register/login/refresh)
  │   ├─ If private: Validates Bearer token
  │   ├─ Extracts: subject (userId), tenant claim, roles from JWT
  │   ├─ Sets headers: X-Tenant-Id, X-User-Id, X-Roles
  │   └─ Stores X-Request-Id
  │
  ├─ Step 2: RateLimitFilter (if enabled)
  │   ├─ Checks request count (default: 100/min per IP)
  │   └─ Rejects if over limit (429)
  │
  ├─ Step 3: TenantStatusGate
  │   ├─ Checks if tenant is ACTIVE (if tenant claim present)
  │   └─ Rejects if tenant INACTIVE (403)
  │
  ├─ Step 4: ProxyResource
  │   ├─ Parses: /api/{service}/{path...}
  │   ├─ Looks up in Consul: "product-svc" → 10.0.0.1:8080
  │   ├─ Forwards HTTP request with all headers
  │   └─ Waits for response (2s timeout)
  │
  ├─ Step 5: Response Handler
  │   ├─ Receives response from service
  │   ├─ Copies status + body
  │   ├─ Adds X-Request-Id header to response
  │   └─ Sends to client
  │
  ↓ (TCP port 8090, response)
  │
Client receives: 200 OK with response body
```

### 1.2 Public vs Protected Paths

**Public paths (bypass JWT validation):**
- `POST /api/iam-svc/auth/register` — User signup
- `POST /api/iam-svc/auth/login` — User login
- `POST /api/iam-svc/auth/refresh` — Refresh token
- `GET /api/product-svc/public/catalog/*` — Browse products
- `GET /api/tenant-svc/storefront/*` — Store info (public)

**Protected paths (require Bearer token):**
- Everything else: `/api/{service}/admin/*`, `/api/{service}/staff/*`, etc.

### 1.3 Multi-tenancy at Gateway Level

**Flow Guard:** Tenant context enforced at gateway

1. **User registers (public)** → Get JWT with NO tenant claim
2. **User creates tenant** → Tenant service publishes UserRoleGranted event
3. **User calls admin endpoint** → JWT now has tenant claim
4. **Gateway extracts tenant** → Sets X-Tenant-Id header
5. **Service receives** → TenantContext populated

**Security guarantee:** Gateway STRIPS any client-supplied X-Tenant-Id (prevents spoofing)

---

## 2. Request Dispatching & Routing

### 2.1 Request Pipeline Layers

```java
// Layer 1: Filter (runs before resource handler)
@Provider
@Priority(Priorities.AUTHENTICATION - 1)  // Runs FIRST
public class JwtAuthFilter implements ContainerRequestFilter {
  public void filter(ContainerRequestContext ctx) {
    // 1. Strip client headers (X-Tenant-Id, X-User-Id, X-Roles)
    ctx.getHeaders().remove("X-Tenant-Id");
    
    // 2. Validate Bearer token
    String authHeader = ctx.getHeaderString("Authorization");
    DecodedJWT jwt = verifier.verify(authHeader.substring(7));
    
    // 3. Extract and stamp identity headers
    ctx.getHeaders().putSingle("X-Tenant-Id", jwt.getClaim("tenant").asString());
    ctx.getHeaders().putSingle("X-User-Id", jwt.getSubject());
    ctx.getHeaders().putSingle("X-Roles", String.join(",", jwt.getClaim("roles").asList(String.class)));
  }
}

// Layer 2: Resource (routes request)
@ApplicationScoped
@Path("/api")
public class ProxyResource {
  @GET
  @Path("/{service}/{path: .*}")
  public Response proxyGet(
      @PathParam("service") String service,
      @PathParam("path") String path,
      @Context UriInfo uriInfo,
      @Context HttpHeaders inboundHeaders) {
    
    // 1. Security: Check if service is routable (whitelist)
    if (!ROUTABLE_SERVICES.contains(service)) {
      return 404; // Internal services can't be accessed
    }
    
    // 2. Discovery: Resolve service location from Consul
    ServiceInstance instance = registry.resolve(service)
        .orElseThrow(() -> new ServiceUnavailableException(service));
    
    // 3. Forward: Build upstream request
    var req = webClient
        .get(instance.baseUri() + "/" + path)
        .header("X-Request-Id", newRequestId());  // Generate trace ID
    
    // 4. Propagate: Copy all headers (including identity)
    forward(req, inboundHeaders, "X-Tenant-Id");
    forward(req, inboundHeaders, "X-User-Id");
    forward(req, inboundHeaders, "X-Roles");
    forward(req, inboundHeaders, "X-Request-Id");
    
    // 5. Execute: Call upstream (with timeout + retry logic)
    HttpClientResponse upstream = req.request().await();
    
    // 6. Response: Copy status + body back to client
    return Response.status(upstream.status().code())
        .entity(upstream.as(String.class))
        .build();
  }
}
```

### 2.2 Routing Table

**Format:** `/api/{service}/{path}`

| Service | Base URL | Examples |
|---------|----------|----------|
| `iam-svc` | http://iam-svc:8080 | `/api/iam-svc/auth/register`, `/api/iam-svc/auth/login` |
| `tenant-svc` | http://tenant-svc:8080 | `/api/tenant-svc/admin/tenant`, `/api/tenant-svc/admin/stores` |
| `product-svc` | http://product-svc:8080 | `/api/product-svc/admin/products`, `/api/product-svc/public/catalog` |
| `inventory-svc` | http://inventory-svc:8080 | `/api/inventory-svc/admin/inventory/receive` |
| `pricing-svc` | http://pricing-svc:8080 | `/api/pricing-svc/admin/price-lists` |
| `cart-svc` | http://cart-svc:8080 | `/api/cart-svc/public/cart` |
| `order-svc` | http://order-svc:8080 | `/api/order-svc/public/orders` |
| `payment-svc` | http://payment-svc:8080 | `/api/payment-svc/admin/cash-management` |
| `customer-svc` | http://customer-svc:8080 | `/api/customer-svc/public/profile` |
| `notification-svc` | http://notification-svc:8080 | `/api/notification-svc/admin/notifications` |
| `reporting-svc` | http://reporting-svc:8080 | `/api/reporting-svc/admin/reports` |
| `purchase-svc` | http://purchase-svc:8080 | `/api/purchase-svc/admin/purchase-orders` |

**Service Discovery:** Each service registers with Consul on startup:
```
Service: iam-svc
  ├─ ID: iam-svc-node1
  ├─ Host: 10.0.1.2
  ├─ Port: 8080
  ├─ Health: /health/live
  └─ Ready: /health/ready
```

### 2.3 Error Handling in Gateway

```
Upstream call fails
  ├─ Timeout (>2s) → 504 GATEWAY_TIMEOUT
  ├─ Connection refused → 502 BAD_GATEWAY
  ├─ Invalid JWT → 401 UNAUTHORIZED
  ├─ Service not in Consul → 503 SERVICE_UNAVAILABLE
  ├─ Rate limit exceeded → 429 TOO_MANY_REQUESTS
  └─ Tenant suspended → 403 FORBIDDEN
```

**Example error response:**
```json
{
  "error": {
    "code": "UPSTREAM_TIMEOUT",
    "message": "product-svc did not answer in time"
  },
  "meta": {
    "requestId": "abc-123"
  }
}
```

---

## 3. Gateway Role & Responsibilities

### 3.1 What Gateway Owns

**NOT:** Any business logic, not any data tables, not any events

**YES:** 
- JWT validation (final security check)
- Request routing (finding services in Consul)
- Rate limiting (per IP/tenant)
- Request correlation (X-Request-Id)
- Response formatting (consistency)
- Service discovery (Consul integration)

### 3.2 Golden Rules Enforced by Gateway

1. **Multi-tenancy**: Strips client-supplied X-Tenant-Id (prevents spoofing)
2. **Service isolation**: Routes to specific service based on URL path
3. **Identity validation**: Checks JWT before forwarding to any protected endpoint
4. **Rate limiting**: Enforces request quotas (default 100/min)
5. **Consistency**: All responses formatted in standard envelope

### 3.3 Gateway Never

- ❌ Writes to database (stateless)
- ❌ Publishes events (reads only)
- ❌ Consumes events (reads only)
- ❌ Calls other services (except to proxy client request)
- ❌ Stores request data (memory-only, discarded after response)

---

## 4. Technical Architecture

### 4.1 Components

```
ProxyResource
├─ Handles all HTTP methods: GET, POST, PUT, PATCH, DELETE
├─ Routes to ServiceRegistry to find upstream
├─ Uses WebClient to make outbound calls
└─ Copies responses back to client

JwtAuthFilter
├─ Validates Bearer tokens (Auth0 JWT library)
├─ Extracts userId, tenantId, roles from JWT claims
├─ Stamps identity headers before forwarding
└─ Strips client-supplied headers (security)

RateLimitFilter (optional)
├─ Tracks requests per IP (or per tenant if configured)
├─ Rejects requests over limit (429)
└─ Allows whitelist (e.g., internal monitoring)

TenantStatusGate
├─ Checks if tenant is ACTIVE when tenant claim exists
├─ Rejects inactive tenants (403 FORBIDDEN)
└─ Prevents deactivated businesses from operating

ServiceRegistry (Consul client)
├─ Polls Consul every 30s for healthy service instances
├─ Maintains in-memory cache of service locations
├─ Removes unhealthy instances (failed health checks)
└─ Throws ServiceUnavailableException if no healthy instance
```

### 4.2 Configuration

**Gateway configuration (`shelfj.gateway.*`):**

```properties
# Service discovery
shelfj.consul.host=localhost
shelfj.consul.port=8500

# Rate limiting
shelfj.gateway.rate-limit.enabled=true
shelfj.gateway.rate-limit.requests-per-minute=100

# Allowed services (whitelist)
shelfj.gateway.routable-services=iam-svc,tenant-svc,product-svc,...

# Upstream timeouts
shelfj.gateway.upstream.connect-timeout-seconds=2
shelfj.gateway.upstream.read-timeout-seconds=10

# CORS (browser access)
shelfj.gateway.cors.allowed-origins=http://localhost:3000,https://app.example.com

# JWT validation
shelfj.jwt.secret={required, >=32 chars}
shelfj.jwt.issuer=shelfj

# Brute force protection
shelfj.gateway.brute-force.enabled=true
shelfj.gateway.brute-force.max-failures=5
shelfj.gateway.brute-force.block-minutes=15
shelfj.gateway.brute-force.login-path=/auth/login
```

### 4.3 Health Checks

```
GET /health/started
  → OK: Gateway JVM started (quick)

GET /health/live
  → OK: Gateway responding (quick)

GET /health/ready
  → OK: Consul accessible + JWT config loaded
     FAIL: Can't reach Consul or missing JWT secret
```

---

## 5. API Behavior

### 5.1 Headers Forwarded to Services

**From client (preserved):**
- `X-Request-Id` — Trace ID (generated if missing)
- `X-Idempotency-Key` — For retryable requests (passed through)

**Stamped by gateway (cannot be spoofed):**
- `X-Tenant-Id` — From JWT tenant claim (or storefront header for guest paths)
- `X-User-Id` — From JWT subject
- `X-Roles` — From JWT roles claim

**Stripped by gateway (security):**
- Client-supplied `X-Tenant-Id` (overwritten by JWT value)
- Client-supplied `X-User-Id` (overwritten by JWT value)
- Client-supplied `X-Roles` (overwritten by JWT value)

### 5.2 Request Timeout Behavior

```
Client request arrives
  ↓
Gateway calls upstream service
  ├─ No response after 2s → Timeout exception
  │   └─ Return: 504 GATEWAY_TIMEOUT
  │
  ├─ Response after 0.5s → Forward response immediately
  │   └─ Return: Upstream status code
  │
  └─ Service returns 5xx → Forward as-is
      └─ Return: Same 5xx status
```

### 5.3 Response Format

**All responses wrapped in standard envelope:**

```json
{
  "data": { /* business response */ },
  "error": null,  // null on success
  "meta": {
    "requestId": "abc-123",
    "nextCursor": "token"  // Optional, for pagination
  }
}
```

**Error response:**
```json
{
  "data": null,
  "error": {
    "code": "UPSTREAM_TIMEOUT",
    "message": "product-svc did not answer in time"
  },
  "meta": {
    "requestId": "abc-123"
  }
}
```

---

## 6. Service Discovery Integration

### 6.1 How Services Register

**On startup, service sends:**
```
PUT /v1/agent/service/register
{
  "ID": "iam-svc-node1",
  "Name": "iam-svc",
  "Address": "10.0.1.2",
  "Port": 8080,
  "Check": {
    "HTTP": "http://10.0.1.2:8080/health/live",
    "Interval": "10s",
    "Timeout": "2s"
  }
}
```

**Gateway polls Consul:**
```
GET /v1/catalog/service/iam-svc
{
  "ServiceID": "iam-svc-node1",
  "ServiceAddress": "10.0.1.2",
  "ServicePort": 8080,
  "ServiceChecks": [...]
}
```

### 6.2 Failover Behavior

```
Gateway wants to call product-svc
  ├─ Consul lists 3 instances
  │  ├─ Instance 1: Healthy ✓
  │  ├─ Instance 2: Failed check ✗
  │  └─ Instance 3: Healthy ✓
  │
  ├─ Gateway picks Instance 1 (round-robin among healthy)
  │
  └─ If Instance 1 times out:
     ├─ Mark as temporarily unhealthy
     ├─ Try Instance 3 next (fallback)
     └─ If all fail, return 503 SERVICE_UNAVAILABLE
```

---

## 7. JWT Token Structure

### 7.1 Token Claims

```json
{
  "iss": "shelfj",              // Issuer (validated by gateway)
  "sub": "user-id-uuid",        // User ID (from register/login)
  "tenant": "tenant-id-uuid",   // Tenant ID (null for new users)
  "roles": ["CUSTOMER", "OWNER"],  // User roles for that tenant
  "iat": 1687000000,            // Issued at
  "exp": 1687003600             // Expires (1 hour)
}
```

### 7.2 Token Lifecycle

```
1. User calls POST /auth/register
   ├─ iam-svc creates user
   └─ Returns JWT (no tenant claim yet)

2. User calls POST /onboarding/tenants
   ├─ tenant-svc creates tenant
   ├─ Publishes UserRoleGranted event
   └─ Client still has old JWT (no tenant yet)

3. User calls POST /auth/refresh
   ├─ iam-svc checks if UserRoleGranted event was processed
   ├─ Returns NEW JWT with tenant + OWNER role
   └─ Client now has full access

4. User calls GET /admin/stores
   ├─ Gateway validates NEW JWT
   ├─ Extracts tenant claim → X-Tenant-Id
   ├─ Forward to tenant-svc
   └─ Works!
```

---

## 8. Rate Limiting

### 8.1 How It Works

**Configuration:**
```
Max: 100 requests per minute
Key: IP address (or X-Tenant-Id if behind reverse proxy)
Storage: In-memory (resetting at minute boundary)
```

**Behavior:**
```
Request 1-100 from IP 192.168.1.1
  → All pass (200)

Request 101 from IP 192.168.1.1
  → Rejected (429 Too Many Requests)

After minute boundary (X:00-X:59)
  → Counter resets
  → Can request again
```

### 8.2 Whitelist

```
Not rate limited:
  - GET /health/* (monitoring)
  - Platform admin endpoints (if configured)
```

---

## 9. CORS Support

### 9.1 Browser Access

**Configuration:**
```
shelfj.gateway.cors.allowed-origins=
  http://localhost:3000,
  https://storefront.example.com,
  https://admin.example.com
```

**Behavior:**
```
Browser request from https://storefront.example.com
  ├─ Pre-flight: OPTIONS /api/product-svc/catalog
  │   └─ Gateway returns: Access-Control-Allow-Origin: https://storefront.example.com
  ├─ Actual: GET /api/product-svc/catalog
  │   └─ Browser allows request (pre-flight passed)
  └─ Response: Includes CORS headers
```

---

## 10. Common Tasks

### 10.1 Adding a New Service to Gateway

1. **Service registers in Consul** (automatic on startup)
2. **Gateway discovers it** (polls Consul, caches result)
3. **Add to whitelist** (if not already there):
   ```properties
   shelfj.gateway.routable-services=iam-svc,...,new-svc
   ```
4. **Test routing:**
   ```bash
   curl http://localhost:8090/api/new-svc/health
   ```

### 10.2 Debugging Routing Issues

**Q: Request returns 503 SERVICE_UNAVAILABLE**
```
A: Service not healthy in Consul
   1. Check service is running: docker ps | grep new-svc
   2. Check health endpoint: curl http://localhost:8081/health/ready
   3. Check registration: curl http://consul:8500/v1/catalog/service/new-svc
```

**Q: Request returns 504 GATEWAY_TIMEOUT**
```
A: Service taking >2 seconds
   1. Check service logs for slow queries
   2. Check database connectivity
   3. Increase timeout in config (careful!)
```

**Q: Request returns 401 UNAUTHORIZED**
```
A: JWT validation failed
   1. Client not sending Authorization header
   2. Token expired
   3. JWT signature invalid (wrong secret)
   4. Check JWT at jwt.io
```

---

## 11. Monitoring & Observability

### 11.1 Metrics Exposed

```
GET /metrics

# Prometheus format
gateway_requests_total{method="GET",path="/api/iam-svc/auth/register",status="200"} 42
gateway_request_duration_ms{path="/api/product-svc/catalog",quantile="0.95"} 15.2
gateway_upstream_timeout_total 3
gateway_rate_limit_exceeded_total 5
```

### 11.2 Logging

```json
{
  "timestamp": "2026-06-18T12:34:56.789Z",
  "level": "INFO",
  "logger": "gateway",
  "requestId": "abc-123",
  "message": "Request proxied",
  "method": "GET",
  "path": "/api/product-svc/admin/products",
  "service": "product-svc",
  "upstreamLatencyMs": 42,
  "status": 200
}
```

---

## 12. Security Considerations

### 12.1 Threat Model

| Threat | Gateway Defense |
|--------|-----------------|
| Spoofed JWT | Validates signature (HMAC256) + verifies issuer |
| Invalid tenant claim | Strips client-supplied X-Tenant-Id, uses JWT value only |
| Brute force login | Rate limits + account lock after N failures |
| Slow attacks | 2s upstream timeout prevents hanging requests |
| Unauthorized service access | Whitelist of routable services (internal services not exposed) |
| Cross-tenant access | Services validate tenant context from X-Tenant-Id header |

### 12.2 TLS/HTTPS

**In production:**
- Client → Gateway: HTTPS (TLS 1.2+)
- Gateway → Services: HTTP (internal, no TLS needed if on same network)

**Configuration:**
```
Server TLS (client-facing)
  server.ssl.port=8443
  server.ssl.keystore=/etc/secrets/keystore.jks
  server.ssl.keystore-password={from-secret}

Redirect HTTP→HTTPS
  All requests to :8090 redirected to :8443
```

---

## 13. Deployment

### 13.1 Container

```dockerfile
FROM eclipse-temurin:21-jdk-alpine
COPY target/gateway.jar /app/gateway.jar
EXPOSE 8090
HEALTHCHECK --interval=10s CMD curl http://localhost:8090/health/live
CMD ["java", "-jar", "/app/gateway.jar"]
```

### 13.2 Kubernetes Manifest

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: gateway
spec:
  replicas: 2  # High availability
  selector:
    matchLabels:
      app: gateway
  template:
    metadata:
      labels:
        app: gateway
    spec:
      containers:
      - name: gateway
        image: shelfj/gateway:1.0
        ports:
        - containerPort: 8090
        env:
        - name: shelfj.consul.host
          value: consul
        - name: shelfj.jwt.secret
          valueFrom:
            secretKeyRef:
              name: app-secrets
              key: jwt-secret
        livenessProbe:
          httpGet:
            path: /health/live
            port: 8090
          initialDelaySeconds: 30
        readinessProbe:
          httpGet:
            path: /health/ready
            port: 8090
          initialDelaySeconds: 30
```

---

## 14. Troubleshooting

| Error | Cause | Fix |
|-------|-------|-----|
| `503 SERVICE_UNAVAILABLE` | Service not in Consul or unhealthy | Check `docker ps`, `docker logs service-name` |
| `504 GATEWAY_TIMEOUT` | Service slow (>2s) | Check service CPU/memory; increase timeout if needed |
| `401 UNAUTHORIZED` | Invalid/missing JWT | Client must send `Authorization: Bearer {jwt}` |
| `403 FORBIDDEN` (with TENANT_INACTIVE) | Tenant deactivated | Check `tenants.status` in database |
| `429 TOO_MANY_REQUESTS` | Rate limit exceeded | Wait for minute to reset or whitelist IP |
| `502 BAD_GATEWAY` | Service returned error | Check service logs for actual error |
| `CORS error from browser` | Origin not in whitelist | Add origin to `shelfj.gateway.cors.allowed-origins` |

---

## 15. References

- [../CLAUDE.md](../../CLAUDE.md) — Architecture rules
- [../README.md](../../README.md) — System overview
- [JwtAuthFilter.java](src/main/java/com/shelfj/gateway/filters/JwtAuthFilter.java) — Token validation
- [ProxyResource.java](src/main/java/com/shelfj/gateway/ProxyResource.java) — Request routing
- [docker-compose.yml](../../docker-compose.yml) — Local dev setup

---

**Last Updated:** 2026-06-18  
**Service Version:** v1  
**Maintainer:** Platform Team
