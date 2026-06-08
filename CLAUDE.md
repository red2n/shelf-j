# CLAUDE.md

This file is auto-loaded into every Claude Code session for this repository. It gives an AI agent the context and rules needed to work on Shelf-J correctly. **Read it fully before making changes.**

> **Deep docs:** [PRD.md](PRD.md) = what & why · [README.md](README.md) = how (concepts, per-service catalog, conventions) · [docs/onboarding-and-locations.md](docs/onboarding-and-locations.md) = tenant onboarding + location model · [docs/coding-standards.md](docs/coding-standards.md) = SQL rules + SOLID rules (enforced on every change). When detail is needed, open those. This file is the fast briefing + the hard rules.

---

## What this project is

**Shelf-J** — a **multi-tenant SaaS** stock & store management platform that also lets **customers buy products** (public online storefront **and** in-store POS). Built as **strict microservices** on **Helidon MP (Java 21)**, behind an **API gateway**, with **service discovery (Consul)**, **centralized config**, and **Kafka** events. Architecture patterns borrowed from [red2n/home](https://github.com/red2n/home) (which is Spring Cloud) but **re-implemented in Helidon MP**.

**Status:** design phase. The repo currently contains specs only (PRD, README, this file, skills). No service code or git history yet. When you scaffold code, follow the templates and rules below exactly.

---

## The two ideas to keep front-of-mind

### 1. Multi-tenancy (every line of business data is tenant-scoped)
- A **tenant** = one business (client) on the platform. Tenants never see each other's data.
- **`tenant_id` ALWAYS comes from the authenticated JWT — never from the request body, query, or path.**
- Every query on tenant-owned data filters by `tenant_id` **as the first condition**. Every tenant table has `tenant_id UUID NOT NULL` + a composite index starting with `tenant_id`.

### 2. The location model: **Tenant → Stores → Zones**
This is the spatial backbone. (Full design: [docs/onboarding-and-locations.md](docs/onboarding-and-locations.md).)

```
Tenant (a business)
  └── Store / Warehouse        (physical site: address + geo lat/lng + hours + type)
        └── Zone / Aisle       (internal sub-location for where stock physically sits)
```

- A **tenant** owns one or more **stores** (type `STORE` or `WAREHOUSE`).
- A **store** has a postal address, geo coordinates, business hours, and status.
- A **store** contains **zones** (aisles/racks/cold-room/back-store) used to pin where a batch of stock lives.
- **Inventory, pricing, orders, and POS are scoped to a store.** Stock lives in a `(store, zone)`. The storefront and POS always operate in the context of a specific store.
- Ownership of this data: **tenant-svc** owns tenants/stores/zones. **inventory-svc** references `store_id` + `zone_id` on batches but does **not** own the store/zone tables (database-per-service — it gets them via API/events, never a join).

---

## The golden rules (non-negotiable — apply to EVERY service)

These are the condensed form of [README §3](README.md#3-the-golden-rules-an-ai-agent-must-follow-these). Violating one is a bug even if the code runs. **SQL and SOLID rules are in [docs/coding-standards.md](docs/coding-standards.md) and must be applied to every change.**

1. **Database-per-service.** No service reads another service's tables. No cross-service SQL joins. Need foreign data → call the owning service (REST) or consume its events.
2. **Gateway is the only public door.** Business services are never exposed to the internet directly.
3. **`tenant_id` from JWT only.** Never trust it from the request. Filter every tenant query by it first.
4. **Discover, don't hardcode.** Resolve other services via Consul; no hardcoded `host:port` in app code.
5. **Config is external.** No env-specific values or secrets in code/images.
6. **Publish events for state others care about**, via the transactional **outbox** (event + DB write are atomic).
7. **Event consumers are idempotent.** Same event twice = same effect as once.
8. **Append-only stays append-only:** `stock_movements`, `order_status_history`, `payments`, `refunds`, `loyalty_ledger`, `audit_log`.
9. **Controllers (`api/`) are thin.** Logic lives in `service/`. No DB calls in resource classes.
10. **DTOs are the contract.** Never expose JPA entities over HTTP.
11. **Idempotency-Key** on retryable writes (checkout, payment capture, stock receipt).
12. **Health + metrics + tracing** on every service (3 probes: started/live/ready; ready checks DB+Kafka+config).
13. **Money is `BigDecimal` / `NUMERIC`** — never floating point. Store currency explicitly.
14. **Time is UTC** (`timestamptz`); convert at the UI edge only.
15. **Validate every input** at the boundary (Bean Validation); reject bad input with `400`.

---

## Architecture at a glance

```
Storefront / Admin / POS  ──►  API GATEWAY (Helidon MP)  ──►  business services
                                  │ authN, rate-limit, routing      (each: own Postgres schema)
                                  ├── discovery: Consul
                                  └── config: central config service
                            services talk: REST (sync, need answer now) + Kafka events (async, announce)
```

**Platform services** (`platform/`): `gateway`, `discovery` (Consul), `config`.
**Business services** (`services/`): `iam-svc`, `tenant-svc`, `product-svc`, `inventory-svc`, `pricing-svc`, `cart-svc`, `order-svc`, `payment-svc`, `purchase-svc`, `customer-svc`, `notification-svc`, `reporting-svc`. Each one's full spec (owns, endpoints, events) is in [README §9](README.md#9-the-business-services--full-catalog).

**Channel-sharing rule:** online checkout and in-store POS both go through **the same `order-svc`** — only `channel` (`ONLINE`/`POS`) and `fulfilment_type` differ, so inventory/payments/reporting behave identically across channels.

---

## Repository layout (target)

```
shelf-j/
├── pom.xml                  # parent: Java 21, Helidon BOM
├── docker-compose.yml       # postgres, kafka, consul, redis, zipkin, prometheus, grafana (with healthchecks)
├── platform/                # gateway, discovery, config
├── services/                # the 12 business microservices (one Maven module each)
├── shared/                  # CONTRACTS ONLY: events-contract, common-web, common-test (no business logic)
├── frontends/               # storefront, admin-console, pos
├── docs/                    # onboarding-and-locations.md, etc.
├── PRD.md  README.md  CLAUDE.md
└── .claude/skills/          # invokable skills (scaffold-service, add-endpoint, add-event, onboard-tenant)
```

Per-service internal shape (copy for each): `api/ dto/ service/ domain/ repo/ messaging/ client/ mapper/ config/` + `resources/db/migration/` (Flyway). See [README §6](README.md#6-anatomy-of-one-service-the-template-every-service-copies).

---

## Conventions cheat-sheet (full list: [README §7](README.md#7-cross-cutting-conventions))

- **Response envelope:** `{ "data": ..., "error": ..., "meta": { "requestId", "nextCursor" } }`.
- **Errors:** correct HTTP codes; stable machine `code` (e.g. `INVENTORY_INSUFFICIENT_STOCK`); never leak stack/SQL.
- **Pagination:** cursor only (`?after=&limit=`), default 20 / max 100. No page numbers.
- **Naming:** REST paths = plural kebab nouns (`/purchase-orders`); JSON = `camelCase`; DB columns = `snake_case`; events = `PascalCase` past tense (`OrderPlaced`); Kafka topics = `shelfj.<domain>.<event>`.
- **IDs:** UUID primary keys, service-generated.
- **Migrations:** Flyway only (`V<n>__desc.sql`); never manual DDL in prod.
- **Tests:** unit for `service/` logic + Testcontainers integration for the core flow (Postgres + Kafka). Not done without it.

---

## Production deployment & startup ordering (the thing that surprises people)

- The per-service ports `8001…8012` are a **LOCAL-DEV convenience only**. In production every service listens on the **same port (8080)**; addressing is by **k8s DNS + Consul**, never `host:port`. Don't put `+1` ports in prod manifests.
- **Do NOT order individual services at startup.** The dependency graph is a mesh; no linear order works. Instead services **start in any order and gate on readiness** (probes + `@Retry` + `@CircuitBreaker`).
- Ordering exists only between **stages**: `infra → platform (config/discovery/gateway) → DB migrations (run-once Jobs) → all business services in parallel → frontends`.
- Full model: [README §13](README.md#13-production-deployment--startup-ordering) and [PRD §9](PRD.md).

---

## Build order (for the developer, ≠ runtime order)

Phase 0 foundation (gateway/discovery/config + template) → Phase 1 back-office (iam, tenant, product, inventory, purchase) → Phase 2 commerce (pricing, cart, order, payment) → Phase 3 experience (customer, notification, reporting + frontends) → Phase 4 hardening. Each phase has an exit check — see [README §11](README.md#11-build-order--milestones).

---

## How to do common tasks (skills)

Invokable skills live in `.claude/skills/`. Prefer them for consistency:

| Skill | Use when |
|---|---|
| `scaffold-service` | Creating a brand-new business microservice (sets up module, layers, Flyway, health, config, registration). |
| `add-endpoint` | Adding a REST endpoint to an existing service (enforces layering, envelope, validation, tenant rule). |
| `add-event` | Adding a Kafka event (producer via outbox + idempotent consumer + contract in `events-contract`). |
| `onboard-tenant` | Implementing/walking the client onboarding + Tenant→Stores→Zones location-mapping flow. |

---

## Definition of Done (before declaring any service complete)

Self-check against [README §14](README.md#14-definition-of-done-for-any-service). Highlights: own schema via Flyway · no cross-service DB access · DTOs in/out · tenant filtering · discovery registration · external config · outbox + idempotent consumers · 3 health probes (ready checks deps) · starts in any order · sync calls have timeout/retry/breaker/fallback · unit + Testcontainers tests · builds with `mvn clean install` · runs in docker-compose.

---

## When unsure

- **Where does X live / who owns this data?** → [README §9](README.md#9-the-business-services--full-catalog) (per-service ownership table).
- **How do services talk for this flow?** → [README §10](README.md#10-how-services-talk-to-each-other) (sync map + event map + checkout saga).
- **Onboarding / stores / zones / delivery?** → [docs/onboarding-and-locations.md](docs/onboarding-and-locations.md).
- **SQL or SOLID rule question?** → [docs/coding-standards.md](docs/coding-standards.md).
- **A decision isn't settled?** → [PRD §11 open questions](PRD.md). Don't silently guess on those; surface them.
