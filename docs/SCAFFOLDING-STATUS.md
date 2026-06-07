# Phase 0 Scaffolding — Status & Resume Notes

> Updated 2026-06-07. **Phase 0 is COMPLETE and validated end-to-end (host + docker-compose).**

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
docker compose up -d --build                          # infra + config + sample-svc + gateway
# gateway is published on host port 8090 (8080 may be busy locally; override with GATEWAY_HOST_PORT)
curl -X POST http://localhost:8090/api/sample-svc/widgets \
     -H 'Content-Type: application/json' -H 'X-Tenant-Id: 11111111-1111-1111-1111-111111111111' \
     -d '{"name":"hello"}'
docker compose down
```

## What's DONE and validated ✅ (Phase 0 complete)

| Module | State |
|---|---|
| root `pom.xml`, `.gitignore`, `.env.example` | ✅ |
| `shared/events-contract` | ✅ DomainEvent, BaseEvent, OutboxRecord |
| `shared/common-web` | ✅ ApiResponse, ErrorBody, ApiException(+mapper), TenantContext(+filter), HttpHeaders, Cursor |
| `shared/common-test` | ✅ PostgresSupport (Testcontainers) |
| `services/sample-svc` | ✅ full layered template; Testcontainers IT passing (tenant isolation) |
| `platform/config` (config-svc) | ✅ serves `GET /config/{service}/{profile}`; health |
| `platform/discovery` | ✅ ConsulClient (register/deregister/resolve); sample-svc + gateway use it |
| `platform/gateway` | ✅ routes `/api/{service}/**` via Consul; forwards identity + X-Request-Id |
| `Dockerfile.svc`, `docker-compose.yml`, `infra/prometheus.yml` | ✅ full stack with healthchecks + gating |

**Validated end-to-end (both host-run and docker-compose):**
request → gateway → Consul-discovered sample-svc → Postgres → response, with tenant isolation, request-id propagation, and central config serving. Kafka runs in **KRaft mode** (no Zookeeper). All infra healthy.

**Reactor `mvn clean install -DskipTests` = BUILD SUCCESS (8 modules).**

## Two bugs fixed along the way (see memory)
- **Helidon WebClient query params** must use `.queryParam()`, not `?x=y` in the path (else Consul returns `[]`). → `consul-discovery-gotchas`
- **Consul-in-container can't health-check host-run services** (firewall on docker bridge). Solved by docker-compose service-name networking + `SHELFJ_ADVERTISE_HOST`. → `consul-discovery-gotchas`
- Plus the 4 Helidon traps in `helidon-mp-service-setup` (launcher, catch-all mapper, JSON-B, health paths).

## NEXT: Phase 1 — Back-office services
Build the first real business services (each by copying sample-svc via the `scaffold-service` skill):
**iam-svc → tenant-svc → product-svc → inventory-svc → purchase-svc.**
Exit check: staff logs in → create tenant + store → add product → receive stock (GRN → `GoodsReceived` → `StockReceived`), tenant isolation verified. See README §11 and the `onboard-tenant` skill for the Tenant→Stores→Zones flow.

Consider deleting `sample-svc` once 2–3 real services exist (it's the template; keep it through Phase 1).
