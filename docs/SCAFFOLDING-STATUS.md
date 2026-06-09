# Phase 0 Scaffolding — Status & Resume Notes

> Updated 2026-06-09. **Phase 0 is COMPLETE. Phase 1 back-office services are live.**

## Build & run toolchain (IMPORTANT)

- **JDK 21 required** — build AND run with Temurin 21:
  ```bash
  export JAVA_HOME=/usr/lib/jvm/temurin-21-jdk-amd64
  JAVA_HOME=$JAVA_HOME mvn clean install            # build (machine default java is 25 — too new)
  "$JAVA_HOME/bin/java" -jar <svc>/target/<svc>.jar # run a service directly
  ```
- Helidon MP **4.4.1**, groupId `com.shelfj`, monorepo Maven reactor.

## Run the whole stack (the easy path)

```bash
export JAVA_HOME=/usr/lib/jvm/temurin-21-jdk-amd64
JAVA_HOME=$JAVA_HOME mvn clean install -DskipTests   # build all jars + libs/
docker compose up -d --build                          # infra + config + gateway + business services
# gateway is published on host port 8090 (8080 may be busy locally; override with GATEWAY_HOST_PORT)
curl http://localhost:8090/api/inventory-svc/health/ready
docker compose down
```

## What's DONE and validated ✅

| Module | State |
|---|---|
| root `pom.xml`, `.gitignore`, `.env.example` | ✅ |
| `shared/events-contract` | ✅ DomainEvent, BaseEvent, OutboxRecord |
| `shared/common-web` | ✅ ApiResponse, ErrorBody, ApiException(+mapper), TenantContext(+filter), HttpHeaders, Cursor |
| `shared/common-test` | ✅ PostgresSupport (Testcontainers) |
| `platform/config` (config-svc) | ✅ serves `GET /config/{service}/{profile}`; health |
| `platform/discovery` | ✅ ConsulClient (register/deregister/resolve); used by all business services + gateway |
| `platform/gateway` | ✅ routes `/api/{service}/**` via Consul; forwards identity + X-Request-Id |
| `Dockerfile.svc`, `docker-compose.yml`, `infra/prometheus.yml` | ✅ full stack with healthchecks + gating |
| `services/iam-svc` | ✅ auth, JWT, tenants |
| `services/tenant-svc` | ✅ tenant + store + zone management |
| `services/product-svc` | ✅ products, variants, UOM |
| `services/inventory-svc` | ✅ stock lifecycle + Oracle Inventory gaps #1–#10 |

## Two bugs fixed along the way (see memory)
- **Helidon WebClient query params** must use `.queryParam()`, not `?x=y` in the path (else Consul returns `[]`). → `consul-discovery-gotchas`
- **Consul-in-container can't health-check host-run services** (firewall on docker bridge). Solved by docker-compose service-name networking + `SHELFJ_ADVERTISE_HOST`. → `consul-discovery-gotchas`
- Plus the 4 Helidon traps in `helidon-mp-service-setup` (launcher, catch-all mapper, JSON-B, health paths).

## NEXT: Phase 2 — Commerce services
**pricing-svc → cart-svc → order-svc → payment-svc.**
Exit check: customer browses storefront → adds to cart → checkout → payment captured → inventory deducted, across ONLINE and POS channels.
