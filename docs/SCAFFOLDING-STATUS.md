# Phase 0 Scaffolding — Status & Resume Notes

> Working handoff doc. Updated 2026-06-06 end of session. Pick up here next time.

## Build & run toolchain (IMPORTANT)

- **JDK 21 required** — build AND run with Temurin 21:
  ```bash
  export JAVA_HOME=/usr/lib/jvm/temurin-21-jdk-amd64
  JAVA_HOME=$JAVA_HOME mvn clean install            # build
  "$JAVA_HOME/bin/java" -jar <svc>/target/<svc>.jar # run (NOT bare `java` — machine default is JDK 25)
  ```
- Helidon MP **4.4.1**, groupId `com.shelfj`, monorepo Maven reactor.

## What's DONE and green ✅

| Module | State |
|---|---|
| root `pom.xml` (parent reactor) | ✅ Helidon 4.4.1 parent, BOMs, JDK21 |
| `.gitignore`, `.env.example` | ✅ |
| `shared/events-contract` | ✅ builds — `DomainEvent`, `BaseEvent`, `OutboxRecord` |
| `shared/common-web` | ✅ builds — `ApiResponse`, `ErrorBody`, `ApiException`, `ApiExceptionMapper`, `TenantContext`(+filter), `HttpHeaders`, `Cursor` |
| `shared/common-test` | ✅ builds — `PostgresSupport` (Testcontainers helper) |
| `services/sample-svc` | ✅ **VALIDATED end-to-end** against real Postgres + Testcontainers IT passing |

**`mvn clean install -Dmaven.test.skip=true` on the active reactor = BUILD SUCCESS.**

### sample-svc — proven behavior (ran against a live Postgres container)
- `/health` 200 + `database:UP` when DB live; 503 when DB down (readiness gating works → services start in any order)
- Flyway migration runs on boot (creates `widgets`)
- POST/GET `/widgets` round-trip through DB, response envelope + requestId correct
- **Tenant isolation proven**: tenant B cannot see tenant A's rows
- Tests: `WidgetIT` (2 tests) pass via `@HelidonTest` + Testcontainers

## What's IN PROGRESS 🚧

**`platform/config` (config-svc)** — ~70% done, currently **commented out** of the reactor `<modules>` (so it doesn't affect the main build). Files written:
- `platform/config/pom.xml` ✅
- `src/main/java/com/shelfj/config/ConfigResource.java` ✅ (`GET /config/{service}/{profile}` → JSON from `config-repo/<service>-<profile>.properties`)
- `src/main/java/com/shelfj/config/LivenessCheck.java` ✅ (liveness + readiness)
- `src/main/resources/META-INF/beans.xml` ✅
- `src/main/resources/META-INF/microprofile-config.properties` ✅ (port 8888)

**TODO to finish config-svc:**
1. Add sample bundled config files under `platform/config/src/main/resources/config-repo/` (e.g. `sample-svc-dev.properties`) so it returns something out of the box.
2. Uncomment `<module>platform/config</module>` in root `pom.xml`.
3. Build + boot it; verify `GET http://localhost:8888/config/sample-svc/dev` returns JSON and `/health` is 200.

## What's NOT STARTED ⬜ (remaining Phase 0)

1. **`platform/discovery`** — NOT a Helidon app. Plan: thin module with a shared `ConsulClient` helper (register/deregister/lookup via Consul HTTP API using Helidon WebClient) that sample-svc + gateway reuse. (Consul itself runs as a docker-compose container, not Java.) The registration logic already exists inline in `sample-svc/.../ConsulRegistration.java` — extract/generalize it here.
2. **`platform/gateway`** — Helidon MP edge service: routes `/api/{service}/**` → upstream resolved via Consul, validates JWT, injects `X-Tenant-Id`/`X-User-Id`/`X-Roles` + `X-Request-Id` downstream, rate-limit, CORS. (Reuse the route table in README §8.1.)
3. **`docker-compose.yml`** — postgres, kafka+zookeeper, consul, redis, zipkin, prometheus, grafana, WITH healthchecks + `depends_on: condition: service_healthy` (see README §12.1). Then the app services.
4. **Phase 0 exit check**: request through gateway → discovered sample-svc → config from config-svc → trace in Zipkin → health/metrics green.
5. Update `README.md` / `CLAUDE.md` with the JDK-21 toolchain note + a "scaffolding status" pointer to this file.

## Known Helidon gotchas (already solved — see memory `helidon-mp-service-setup`)
1. `mainClass` = `io.helidon.Main` (NOT a custom `Server.create()` main) — else health/metrics 404.
2. Do NOT register a catch-all `ExceptionMapper<Throwable>`/`<Exception>` — it shadows `/health` & `/metrics`. Keep only `ApiExceptionMapper`.
3. Slim `helidon-microprofile-core` lacks JSON-B — add `jersey-media-json-binding:3.1.11` + `yasson:3.0.4`.
4. Health is at `/health`, `/health/live`, `/health/ready` (root), not `/observe/health`.
