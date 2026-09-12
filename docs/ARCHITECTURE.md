# Shelf-J — Architecture & Engineering Reference

> **Read this first if you're building or changing code.** This is the single source of truth for *how* Shelf-J is built — the concepts, the rules, the architecture, and a per-service map of what actually exists in the repo today. For *what* Shelf-J does as a product (features, personas, workflows), read [README.md](../README.md) instead — it's the product deep dive and doesn't assume any of the technical background below. Companion docs: [PRD.md](../PRD.md) (what & why, roadmap), [docs/API-GUIDE.md](API-GUIDE.md) (full REST surface by business capability), [docs/UI-GUIDE.md](UI-GUIDE.md) (full UI surface by persona/screen).

**Shelf-J** is a **multi-tenant SaaS stock & store management platform** that also lets **customers buy products** — online (storefront) and in-store (POS) — built as **strict microservices** on **Helidon MP (Java 21)**, behind an **API gateway**, with **Consul** discovery, a **central config service**, and **Kafka** events.

## Status

This is **not** a design-phase repo — it's a working platform. Backend: **12 business services + gateway/discovery/config**, ~290 REST endpoints, 70+ Flyway migrations, full outbox/event pipeline. Frontend: one Flutter app with **4 shells** (storefront, POS, admin console, platform/super-admin). Everything runs together via `docker-compose.yml` with a real observability stack (Prometheus/Grafana/Zipkin/Tempo/Loki). Two independent deep-dive audits ([AUDIT.md](../AUDIT.md), [fable-finding.md](../fable-finding.md)) found the codebase **mature and well-hardened**, with prior findings fixed and tracked — see [§18 Security posture](#18-security--hardening-posture).

## Table of contents

1. [What Shelf-J does](#1-what-shelf-j-does)
2. [Core concepts](#2-core-concepts)
3. [System architecture](#3-system-architecture)
4. [Technology stack](#4-technology-stack)
5. [Repository layout](#5-repository-layout)
6. [The golden rules](#6-the-golden-rules)
7. [Anatomy of one service](#7-anatomy-of-one-service)
8. [Platform services](#8-platform-services)
9. [Shared modules](#9-shared-modules)
10. [The business services](#10-the-business-services)
11. [How services talk to each other](#11-how-services-talk-to-each-other)
12. [Key workflows](#12-key-workflows)
13. [The frontend (shelf-app)](#13-the-frontend-shelf-app)
14. [Cross-cutting conventions](#14-cross-cutting-conventions)
15. [Local development](#15-local-development)
16. [Testing & quality gates](#16-testing--quality-gates)
17. [Production deployment & startup ordering](#17-production-deployment--startup-ordering)
18. [Security & hardening posture](#18-security--hardening-posture)
19. [Definition of Done](#19-definition-of-done)
20. [Glossary](#20-glossary)

---

## 1. What Shelf-J does

Imagine a business that owns one or more shops. With Shelf-J it can:

- track **what stock it has and where** — down to the batch/lot, serial number, and shelf zone (inventory),
- **buy stock from suppliers** and receive it against purchase orders (procurement),
- **sell at the counter** with a full POS (cash register, till management, receipts, layaway, gift cards),
- **sell the same catalog online** on a public storefront customers browse and check out on,
- **plan replenishment** (reorder points, kanban, ABC analysis, safety stock),
- handle **UK-style VAT**, promotions, and multi-price-list pricing,
- run **loyalty** and **store credit** for repeat customers,
- see **reports** across stores of what sold, what's low, and what's in transit.

It's **multi-tenant**: many separate businesses (tenants) share the platform, but each only ever sees its own data. And it's built as **many small services** instead of one big program — each owns one capability and its own database, and they cooperate over REST + Kafka behind a single public **gateway**.

> For the full product tour (personas, feature-by-feature walkthrough, business workflows in plain language), see [README.md](../README.md). The rest of this document is the engineering "how," not the product "what."

### The location model: Tenant → Store → Zone

```
Tenant (a business)
  └── Store / Warehouse   (physical site: address, geo, hours, type STORE|WAREHOUSE)
        └── Zone          (aisle/rack/cold-room/back-store — where stock physically sits)
```

- `tenant-svc` owns this hierarchy exclusively. Every other service references `store_id`/`zone_id` via API or events — **never** a DB join.
- Every tenant gets a default store on onboarding; every store gets a default zone. Inventory, pricing, orders, and POS are always scoped to a `(store_id, zone_id)`.
- Full flow: [docs/onboarding-and-locations.md](onboarding-and-locations.md).

---

## 2. Core concepts

You don't need prior microservices experience, but these ideas are load-bearing everywhere in the codebase:

- **Microservice** — a small independent program owning one business capability and its own database. `inventory-svc` never reads `payment-svc`'s tables; if it needs payment data it calls the API or consumes an event.
- **API Gateway** — the single public entry point (`platform/gateway`). Browsers/apps never call a service directly; they call the gateway, which authenticates, rate-limits, and routes.
- **Service discovery (Consul)** — services register themselves on boot; callers resolve `pricing-svc` → a live address at call time instead of hardcoding `host:port`.
- **Centralized config** — settings live in a config service, not baked into images; secrets come from the environment/secret store at deploy time.
- **Events & Kafka** — two ways services talk: **REST** ("I need the answer now") and **events** ("something happened, react if you care"). Every service publishes state changes via a transactional **outbox** (DB write + event write are atomic) so a crash between the two is impossible.
- **Saga** — a workflow spread across services (e.g. checkout = quote → reserve stock → *pay* → confirm). If a later step fails, earlier steps are **compensated** (e.g. release the stock reservation). `order-svc` coordinates placement (quote + reserve) synchronously; payment is client-initiated against payment-svc and order-svc confirms on the resulting `PaymentCaptured` event (choreography, not a call) — see [§12](#12-key-workflows).
- **Multi-tenancy** — `tenant_id` always comes from the verified JWT, never the request body/query/path. Every tenant-owned query filters by `tenant_id` first.

---

## 3. System architecture

```
                     Storefront · POS · Admin Console · Platform Console
                          (one Flutter app, 4 shells — see §13)
                                          │
                                          ▼
                     ┌───────────────────────────────────────┐
                     │   GATEWAY  :8090  (platform/gateway)   │
                     │  CORS → RateLimit(Redis) → BruteForce  │
                     │  → JwtAuth → TenantStatusGate → Proxy  │
                     └───────────────────┬─────────────────────┘
                                          │ Consul-resolved, circuit-broken
        ┌─────────────────────────────────┼──────────────────────────────────┐
        │                                 ▼                                  │
        │            12 BUSINESS SERVICES (Helidon MP, one per box)          │
        │                                                                    │
        │  iam-svc    tenant-svc   product-svc   inventory-svc   pricing-svc │
        │  cart-svc   order-svc    payment-svc   purchase-svc   customer-svc │
        │  notification-svc        reporting-svc                            │
        │                                                                    │
        │  each: own Postgres schema · REST + Kafka outbox/consumers ·      │
        │  3 health probes · Consul registration · Flyway migrations        │
        └───────────────────────┬───────────────────────┬────────────────────┘
                                 │                        │
                    ┌────────────▼───────────┐  ┌─────────▼──────────────────┐
                    │   PLATFORM SERVICES     │  │   DATA / MESSAGING          │
                    │  discovery (Consul)     │  │  Postgres (per-service DB, │
                    │  config    (:8888)      │  │    via PgBouncer pool)     │
                    └─────────────────────────┘  │  Kafka (KRaft, outbox topics│
                                                  │    shelfj.<domain>.<event>)│
                                                  │  Redis (rate-limit, cache) │
                                                  └─────────────────────────────┘
                                                              │
                                          ┌───────────────────▼──────────────────┐
                                          │           OBSERVABILITY               │
                                          │ OTel Collector → Zipkin + Tempo(trace)│
                                          │             → Loki (logs, via Promtail)│
                                          │ Prometheus (+ node/postgres/redis      │
                                          │   exporters) → Grafana (dashboards)   │
                                          └───────────────────────────────────────┘
```

Dev-only tooling that rides along in `docker-compose.yml`: **kafka-ui** (browse topics), **swagger-ui** (aggregated per-service OpenAPI), **pgAdmin**, and a one-shot **bootstrap** container that proves the whole stack is functional by creating the first platform admin through the gateway.

---

## 4. Technology stack

| Layer | Tech | Notes |
|---|---|---|
| Language / runtime | **Java 21**, Helidon MP 4.x | MicroProfile (CDI/JAX-RS) style, not Helidon SE |
| Build | **Maven**, multi-module reactor | one parent `pom.xml`, one module per service |
| Database | **PostgreSQL**, one schema per service, pooled via **PgBouncer** | Flyway 10.20.1, HikariCP 6.2.1 |
| Messaging | **Apache Kafka (KRaft mode, no ZooKeeper)** | transactional outbox pattern on every publisher |
| Cache / rate-limit store | **Redis** (Lettuce client) | gateway rate-limit + brute-force counters, cart cache |
| Discovery | **Consul** | self-register + health-checked deregistration |
| Config | Custom **config service** | non-secret `.properties`, per-service + profile overlay |
| Tracing | **OpenTelemetry → Zipkin + Tempo** | via `otel-collector` |
| Logs | **Promtail → Loki** | container logs shipped from Docker |
| Metrics | **Prometheus** (+ node/postgres/redis exporters) **→ Grafana** | MicroProfile Metrics per service |
| AuthN | **JWT (Auth0 lib)**, Argon2id password hashing | gateway strips/re-stamps identity headers |
| Testing | **JUnit 5**, **Testcontainers** (real Postgres+Kafka), **ArchUnit** | quality gates: SpotBugs, PMD, fmt-maven-plugin |
| Frontend | **Flutter** (web deployed; Android/iOS targets present), **Riverpod 2.x**, `go_router`, `dio` | one codebase, 4 shells — see §13 |
| Containers | **Docker / docker-compose** (dev), Kubernetes (production target) | health-check-gated startup |

---

## 5. Repository layout

```
shelf-j/
├── pom.xml                      # parent: Java 21, Helidon BOM, quality plugins
├── docker-compose.yml           # full local stack (see §15 for the port map)
├── docker-compose.prod.yml      # prod overlay: fail-fast secrets, no dev tooling
├── Dockerfile.svc / Dockerfile.web
├── PRD.md  README.md  CLAUDE.md AUDIT.md  fable-finding.md
│
├── platform/
│   ├── gateway/                 # single public entry point
│   ├── discovery/                # Consul registration/lookup wrapper
│   └── config/                   # centralized non-secret config server
│
├── services/                    # 12 business microservices, one Maven module each
│   ├── iam-svc/ tenant-svc/ product-svc/ inventory-svc/ pricing-svc/
│   ├── cart-svc/ order-svc/ payment-svc/ purchase-svc/ customer-svc/
│   └── notification-svc/ reporting-svc/
│
├── shared/                      # CONTRACTS + infra glue only — no business logic
│   ├── common-ids/               # Ids.newId(): time-ordered UUIDv7 ids
│   ├── events-contract/          # BaseEvent/DomainEvent/OutboxRecord
│   ├── common-web/               # response envelope, error mapper, tenant context
│   ├── common-service/           # DataSource/Flyway/Consul/health/outbox/Kafka base classes
│   └── common-test/               # Testcontainers + ArchUnit rule helpers
│
├── frontends/shelf-app/         # one Flutter app: storefront + POS + admin + platform
├── docs/                        # ARCHITECTURE.md, API-GUIDE.md, UI-GUIDE.md, onboarding-and-locations.md, coding-standards.md
├── infra/                       # postgres role bootstrap SQL, etc.
├── k6/                          # load tests (see §16)
├── scripts/                     # redeploy.sh, run-web.sh, duplo.sh, ghcr-prune.sh
└── .claude/skills/               # scaffold-service, add-endpoint, add-event, onboard-tenant, ...
```

**Rule:** `shared/` may contain DTOs, event contracts, filters, and generic infra plumbing — **never** business rules. If you're putting "how inventory works" into `shared/`, stop — it belongs in `inventory-svc`.

---

## 6. The golden rules

Non-negotiable, apply to **every** service:

1. **Database-per-service.** No cross-service SQL joins. Need foreign data → call the owning service (REST) or consume its events.
2. **Gateway is the only public door.** Business services are never exposed to the internet directly.
3. **`tenant_id` from JWT only.** Never trust it from the request body/query/path. Filter every tenant query by it first.
4. **Discover, don't hardcode.** Resolve other services via Consul; no hardcoded `host:port`.
5. **Config is external.** No env-specific values or secrets in code/images.
6. **Publish events via the transactional outbox** — the DB write and the event write are atomic.
7. **Event consumers are idempotent.** Same event twice = same effect as once.
8. **Append-only stays append-only:** `stock_movements`, `order_status_history`, `payments`, `refunds`, `loyalty_ledger`, `audit_log`, etc.
9. **Controllers (`api/`) are thin.** Logic lives in `service/`. No DB calls in resource classes.
10. **DTOs are the contract.** Never expose JPA/domain entities over HTTP.
11. **Idempotency-Key** on retryable writes (checkout, payment capture, stock receipt, cash movements).
12. **Health + metrics + tracing** on every service (3 probes: started/live/ready; ready checks DB+Kafka+config).
13. **Money is `BigDecimal` / `NUMERIC`** — never floating point.
14. **Time is UTC** (`timestamptz`); convert at the UI edge only.
15. **Validate every input** at the boundary (Bean Validation); reject bad input with `400`.

Full SQL-safety and SOLID rules (enforced on every change): [docs/coding-standards.md](coding-standards.md) — no `SELECT *`, mandatory `WHERE` on every mutating query with `tenant_id` first, strict single-responsibility layering, interfaces over concretes for DIP, etc.

---

## 7. Anatomy of one service

Every business service has the same internal shape:

```
<service>/
├── pom.xml
└── src/main/java/com/shelfj/<service>/
    ├── api/         # JAX-RS resources — THIN: map HTTP ↔ DTO, call service/, nothing else
    ├── dto/          # request/response contracts
    ├── service/      # business logic, transactions, orchestration
    ├── domain/       # entities = database tables
    ├── repo/         # persistence, always tenant-filtered
    ├── messaging/     # Kafka producers (outbox) + consumers (idempotent)
    ├── client/        # typed REST clients to other services, via discovery, with timeout/retry/breaker
    ├── mapper/        # entity ↔ DTO
    └── config/        # CDI producers, config beans
    resources/
    ├── META-INF/microprofile-config.properties
    └── db/migration/  # Flyway: V1__init.sql, V2__...
```

Data flow for a write: `HTTP → api/ (validate DTO) → service/ (logic + repo save + outbox row, same tx) → return DTO`, then a background publisher drains the outbox to Kafka.

---

## 8. Platform services

### `platform/gateway` — the only public door
Single `ProxyResource` (`/api/{service}/{path}`) in front of a Consul-resolved allowlist of the 12 routable services (internal-only services like `config`/`discovery` are never reachable through it). Request pipeline, in priority order:

1. **CorsFilter** — answers preflight; only emits CORS headers if an allowed-origins list is configured.
2. **RateLimitFilter** — Redis-backed fixed-window counter (default 100 req/min), shared across gateway replicas so round-robin can't bypass it.
3. **BruteForceFilter** — login-path-specific Redis counter (default 5 failures / 15 min block).
4. **JwtAuthFilter** — strips any client-supplied `X-Tenant-Id`/`X-User-Id`/`X-User-Email`/`X-Roles`/`X-Store-Ids`, validates the Bearer JWT, and **re-stamps** identity headers only from verified claims. What actually reaches a service is `ProxyResource.FORWARDED_HEADERS` — a stamped header not on that list is dropped (SJ-D46), and a test holds the two together. Whitelists genuinely public paths (register/login/refresh, storefront catalog reads).
5. **TenantStatusGate** — rejects with `403` if the resolved tenant isn't `ACTIVE`.
6. **ProxyResource** — forwards `Idempotency-Key`, generates `X-Request-Id`, per-upstream circuit breaker, faithfully forwards `Content-Type` and raw bytes (so binary bodies like product images round-trip intact).

> **Adding a new service?** The allowlist is `shelfj.gateway.routable-services`, which hard-defaults to the 12 current services (`GatewayConfig`). A new service must be added to that config value or the gateway rejects every `/api/<new-svc>/...` call — even if the service is healthy and registered in Consul. Registering with discovery is **not** enough.

### `platform/discovery` — Consul wrapper
`ConsulClient implements ServiceRegistry` (a 1-method interface — the DIP seam so the gateway never depends on Consul directly). Handles self-register with an HTTP health check, deregistration, and a 3s in-memory TTL cache on `healthyInstances()` lookups (avoids hammering Consul on every proxied request, including caching "nothing found" to avoid retry storms when a service is down).

### `platform/config` — centralized non-secret config
`GET /config/{service}/{profile}` (guarded by a shared `X-Config-Token`) merges `{service}.properties` (base) with `{service}-{profile}.properties` (overlay) from a config-repo directory. Strict name validation (`[A-Za-z0-9_-]{1,64}`) blocks path traversal. Explicitly **not** for secrets — those come from the environment/secret store at deploy time.

Every service pulls from it at startup via `common-service`'s **`ConfigServiceConfigSource`** (a MicroProfile `ConfigSource`, active when `shelfj.config.url` is set). Fetched values layer at **ordinal 150** — above the service's baked `META-INF/microprofile-config.properties` (100) but below env vars (300) / system properties (400) — so config-svc overrides image defaults while deploy-time env still wins. If config-svc is unreachable or has no entry for the service, it degrades to the local defaults, so services still start in any order.

For a service named `<service>` (e.g. `iam-svc`) and a profile `<profile>` (default `default`), the config repo stores `<service>.properties` plus an optional `<service>-<profile>.properties` overlay — see the files under `platform/config/src/main/resources/config-repo/` for the live examples.

---

## 9. Shared modules

| Module | Purpose |
|---|---|
| `common-ids` | `Ids.newId()`: every id the platform mints — primary keys, event ids, outbox rows, request ids. `Ids.derived(source, name)` for deterministic keys, `Ids.shortRef(id)` for handles people read. UUIDv7, so inserts append to B-tree indexes instead of scattering; no dependencies, so every other module can use it. |
| `events-contract` | Event envelope types only: `BaseEvent`, `DomainEvent`, `EventPayload`, `OutboxRecord`. Actual event names are per-service string constants — no business logic here. |
| `common-web` | `ApiResponse`/`ErrorBody`/`ErrorCodes`, exception mappers (generic + UUID-parse), `TenantContext`/`TenantContextFilter`, `AdminAuthorizationFilter`, `Cursor` (pagination), `Validations`. |
| `common-service` | Reusable infra: `DataSourceProducer`, `FlywayRunner`, `ConsulRegistrar`, `HealthChecks`, `BaseJdbcRepository`, the outbox pattern (`BaseOutboxRepository`/`OutboxPublisher`/`OutboxStore`), `BaseKafkaConsumer`/`KafkaConsumerRegistry`, `RedisClientProducer`, shared tenant/store status-change projection consumers, and `db/migration/afterMigrate.sql` — run after every service's migrations, it fails `flyway migrate` if a column generates its own uuid (`FlywayRunner` logs that as a warning). |
| `common-test` | Testcontainers helpers (`PostgresSupport`, `RedisSupport`) and `ShelfJArchRules` (ArchUnit rules enforcing the layering above). `PostgresSupport.stop()` fails the test class if any column defaults to a uuid generator or any table's `id` holds a row that is not UUIDv7. |

---

## 10. The business services

Every service publishes via its own transactional **outbox** table to Kafka topics named `shelfj.<service>.<event>`, and most consumers extend the shared `BaseKafkaConsumer` family. Endpoint lists below are representative, not exhaustive — for the full endpoint-by-endpoint catalog, see [docs/API-GUIDE.md](API-GUIDE.md).

### iam-svc — Identity & Access
Staff/customer auth, JWT issuance, and POS cashier session lifecycle.
- **API:** `/auth` register, login, platform-login, refresh, logout, change-password, `/auth/me`; `/auth/pos/sessions` start/touch/end/list + `/sweep` (idle-timeout force-expire); `/bootstrap/admin` (initial platform admin).
- **Tables:** `users`, `roles`, `user_roles`, `refresh_tokens`, `otp_codes`, `audit_log`, `pos_sessions`, `tenant_status`/`store_status` (local projections).
- **Events:** publishes `UserRegistered`; consumes `TenantCreated`, `StaffAssigned`, `TenantStatusChanged`, `StoreStatusChanged`.
- **Notable:** platform-admin / tenant-admin / staff role model; POS idle-timeout sweep revokes stale sessions. Logout also best-effort disconnects the caller's live MQTT device-push session (`MqttSessionRevoker`, `DELETE /api/v5/clients/{clientId}` on EMQX) — closes the gap where JWT-based MQTT auth is otherwise only checked at CONNECT time, so a logged-out user would keep receiving push until the token's natural expiry. `clientId = mqtt-{tenantId}-{userId}`, the same scheme the frontend's live-push connection uses (see notification-svc's MQTT section below and frontends/shelf-app's `live_alerts_provider.dart`).

### tenant-svc — Tenants, Stores, Zones, Staff
Owns the Tenant→Store→Zone hierarchy and tenant onboarding (see §1).
- **API:** `/onboarding` self-serve signup + tenant/store creation + status checklist; `/admin` tenant/store/zone CRUD + status, staff assign/list/remove, inventory-config; `/platform` cross-tenant list + suspend/reactivate; `/storefront` public config/store lookup.
- **Tables:** `tenants`, `stores`, `zones`, `staff_assignments`, `tenant_inventory_config`.
- **Events:** publishes `TenantCreated`, `TenantStatusChanged`, `StoreCreated`, `StoreStatusChanged`, `ZoneCreated`, `StaffAssigned`, `UserRoleGranted`.
- **Notable:** source of truth for store/zone data every other service projects locally.

### product-svc — Product Catalog (PIM)
Product/variant master data and storefront catalog browsing.
- **API:** `/admin` brands/categories/products (+images, per-store assortment, variants), UoM classes/conversions, item templates, item revisions, cross-references, catalog groups, container types, attribute groups, category sets, CSV import; `/catalog` (public) search/list/detail, barcode scan lookup.
- **Tables:** `products`, `product_variants`, `brands`, `categories`, `product_images`, `product_stores`, `uom_*`, `item_templates`, `item_revisions`, `item_cross_references`, `catalog_groups`, `container_types`, `item_attribute_groups`, `category_sets`.
- **Events:** publishes `ProductCreated`, `ProductUpdated`, `ProductDelisted`, `VariantCreated`, `ItemTemplateCreated`, `ItemRevisionCreated`.
- **Notable:** full PIM feature set — supplier cross-references, item versioning, packaging/container hierarchy, configurable attribute groups.

### inventory-svc — Stock, Batches, Planning (largest service)
Single source of truth for stock: levels, reservations, batches/lots, serials, and advanced planning.
- **API:** `/admin/inventory` receive, adjust, levels, batches, movements, thresholds, planning run/suggestions, serials, transfers, move-orders, ABC analysis, safety-stock, lot-genealogy, cycle-counts, physical-inventories, costing-methods, accounting-periods, kanban-cards, reorder-point plans, picking-rules; `/admin/inventory/food-safety` (staff: points, records, corrective actions) and `/admin/food-safety` (management: points setup, check types, reviews); `/admin/inventory/recalls` (staff: list, active list for the till, store actions, releasing a checked pack) and `/admin/recalls` (management: open, close, cancel); `/inventory/reservations` hold/consume/release; `/inventory/availability` (storefront read).
- **Tables:** `inventory_batches`, `stock_movements` (append-only), `reservations`, `serial_numbers`, `transfer_orders`, `move_orders`, `safety_stock_params`, `abc_assignments`, `lot_genealogy`, `cycle_count_headers`, `physical_inventories`, `costing_methods`, `accounting_periods`, `kanban_cards`, `reorder_point_plans`, `picking_rules`, `fs_check_types`, `fs_monitoring_points`, `fs_check_records` / `fs_corrective_actions` / `fs_reviews` / `fs_point_status_changes` (append-only), `fs_overdue_alerts`, `recalls`, `recall_items` / `recall_batches` / `recall_batch_releases` / `recall_store_actions` (append-only), and more.
- **Events:** publishes `StockReceived`, `StockReserved`, `StockReleased`, `StockDeducted`, `StockAdjusted`, `StockBelowThreshold`, `ReplenishmentSuggested`, `TransferOrderShipped/Received`, `CycleCountAdjusted`, `KanbanTriggered`, `FoodSafetyCheckFailed`, `FoodSafetyCheckOverdue`, `RecallOpened`, and more; consumes `GoodsReceived` (purchase-svc), `OrderFulfilled`/`OrderReturned`/`OrderCancelled` (order-svc).
- **Notable:** FIFO/expiry-ordered deduction with row locking; costing methods & accounting-period close; lot genealogy; serial tracking; kanban/ROP replenishment; cycle counts & physical inventory; ABC analysis; zone-based picking with GL account mapping.

### pricing-svc — Prices, Promotions, VAT
Price resolution, promotions, and UK-style VAT computation/reporting.
- **API:** `/prices/resolve` (+batch), `/price-lists` (+items), `/admin/price-overrides`, `/promotions`, `/vat-rates`, `/product-vat-categories`, `/customer-vat-status`, `/tax-transactions`, `/vat-return` (HMRC MTD boxes 1-9).
- **Tables:** `price_lists`, `price_list_items`, `price_overrides`, `promotions`, `vat_rates`, `product_vat_categories`, `customer_vat_status`, `tax_transactions`.
- **Events:** publishes `PriceChanged`, `PromotionActivated`.
- **Notable:** VAT Notice 700 s.17-style return, customer VAT-exemption status, per-product VAT category, time-bounded PERCENT/FLAT promotions.

### cart-svc — Storefront Cart
Server-side shopping cart for the online channel: session-scoped and customer carts, with merge-on-login. Every call requires a verified token (cart paths are **not** on the gateway's public storefront whitelist); the current Flutter storefront keeps its pre-checkout cart on-device and does not call this service.
- **API:** `/cart` create/get, `/cart/items` add/update/remove, `/cart/merge`.
- **Tables:** `carts`, `cart_items`, local `tenant_status`/`store_status` projections.
- **Events:** consumes `OrderPlaced` (close cart), `TenantStatusChanged`/`StoreStatusChanged` (**flow-guard**: rejects cart mutations early if the tenant/store is suspended).

### order-svc — Orders & Checkout (saga coordinator)
The transaction/sales-journal service for **both** channels: online orders/returns and POS parked sales, layaway, gift cards, special orders, receipts.
- **API:** `/orders` create/confirm/cancel/fulfil/void/returns; `/layaways` create/deposit/complete/cancel; `/gift-cards` issue/reload/redeem/transactions; `/pos/parked-sales`, `/pos/no-sale`; `/admin/special-orders`; `/admin/pos-log`; `/admin/pos/stock-positions`; `/admin/orders/{id}/receipts` (e-journal, print/email).
- **Tables:** `orders`, `order_items`, `order_status_history` (append-only), `returns`, `layaways`, `gift_cards`, `gift_card_transactions`, `parked_sales`, `special_orders`, `pos_log_entries`, `order_receipts`, `idempotency_keys`.
- **Events:** publishes `OrderPlaced`, `OrderConfirmed`, `OrderCancelled`, `OrderFulfilled`, `OrderReturned`, `OrderVoided`, `LayawayCreated/Completed/Cancelled`; consumes `StockReceived`/`StockDeducted`/`StockAdjusted` (POS stock-position projection), `PaymentCaptured`/`PaymentFailed`/`PaymentRefunded`, tenant/store status.
- **Notable:** POS and online share the **same endpoints** — only `channel`/`fulfilment_type` differ. Idempotency-Key on checkout. See [§12 checkout saga](#12-key-workflows).

### payment-svc — Payments & Cash Management
Payment capture/refund plus till sessions, cash drawer movements, and end-of-day reporting.
- **API:** `/payments` capture, online, by-order, refunds; `/admin/cash/till-sessions` open/drops/x-report/close; `/admin/cash` movements (pay-in/pay-out), z-report.
- **Tables:** `payment_tenders`, `refund_tenders`, `till_sessions`, `cash_drops`, `cash_movements`, `z_reports`.
- **Events:** publishes `PaymentCaptured`, `PaymentFailed`, `PaymentRefunded`.
- **Notable:** payment methods CASH/CARD/UPI/WALLET/GIFT_CARD/VOUCHER/STORE_CREDIT; X-report (mid-shift) vs Z-report (end-of-day close); idempotent cash-movement recording.

### purchase-svc — Procurement
Suppliers, purchase orders, goods receipts, and finance-adjacent intercompany invoicing.
- **API:** `/suppliers`; `/purchase-orders` create/submit/lines; `/goods-receipts`; `/intercompany-invoices` (+settle); `/nominal-ledger` (read-only double-entry view).
- **Tables:** `suppliers`, `purchase_orders`, `purchase_order_lines`, `goods_receipts`, `intercompany_invoices`, `nominal_ledger_entries`.
- **Events:** publishes `PurchaseOrderCreated`, `GoodsReceived`, `IntercompanyInvoiceRaised`.
- **Notable:** FRS 102/UK GAAP-style double-entry nominal ledger; intercompany AR/AP invoicing for inter-org transfers.

### customer-svc — Customers, Loyalty, Store Credit
Customer profiles, addresses, and two append-only ledgers.
- **API:** `/customers` CRUD (+anonymize-on-delete), addresses CRUD; `/{id}/loyalty` earn/redeem/adjust/ledger; `/{id}/store-credit` issue/redeem.
- **Tables:** `customers`, `customer_addresses`, `loyalty_accounts`, `loyalty_ledger` (append-only), `store_credit_accounts`, `store_credit_ledger` (append-only).
- **Events:** publishes `CustomerRegistered`, `LoyaltyEarned/Redeemed/Adjusted`, `StoreCreditIssued/Redeemed`; consumes `OrderConfirmed` (auto-accrues loyalty points, deduped by event id).
- **Notable:** GDPR-style anonymize-on-delete; both ledgers are auditable balances, never mutable counters.

### notification-svc — Alerting
Thin fan-in service: consumes events, records notifications, and exposes read feeds. Delivery channel is pluggable (`shelfj.notification.channel`): in-app log by default, an optional SMTP email channel (`SmtpChannel`), or an optional MQTT channel (`MqttChannel`, HiveMQ MQTT Client) for device-facing push — POS terminals, kiosk/back-store displays, the platform console — topic-scoped `shelfj/notifications/{tenantId}/{recipient}`. No SMS yet — see PRD open questions.

**MQTT broker (EMQX, not Mosquitto):** every client — a tenant's own device *and* notification-svc's own publisher connection — authenticates with a shelfj platform JWT (same HS256 secret/issuer iam-svc signs with) as the MQTT password; the broker's `verify_claims` config ties the connecting username to that JWT's `tenant` claim so it can't be spoofed, and file-based ACL then scopes a tenant client's subscribe to exactly its own topic subtree (`infra/emqx.conf`, `infra/emqx-acl.conf`). A device already holding a login session reuses that JWT directly — no separate credential-issuing endpoint. Logout force-disconnects that session rather than waiting for the JWT to expire — see iam-svc's `MqttSessionRevoker` above. **This auth/ACL config was authored without a broker available to test against; `MqttAclIT` (Testcontainers) is the actual verification of it — run it before relying on this in anything beyond local dev.**
- **API:** `/admin/notifications/shortage-alerts` (filter by store/variant, paginated); `/admin/notifications` (in-app notification feed, newest first).
- **Tables:** `shortage_alerts`, `notification_log`.
- **Events:** consumes `StockBelowThreshold` (shortage alert), `OrderConfirmed` (order-confirmation notice), `UserRegistered` (welcome notice); publishes nothing.

### reporting-svc — Cross-Store Analytics (CQRS read model)
Pure projection service built by consuming inventory and sales events.
- **API:** inventory — `/admin/reports/inventory/on-hand`, `/supply-demand` (nets against open in-transit supply), `/movement-stats` (bucketed daily/weekly/monthly); sales — `/admin/reports/sales/summary` (gross/refunded/net revenue + order count per currency), `/admin/reports/sales/by-day` (daily revenue buckets).
- **Tables:** `inventory_projection`, `movement_events`, `open_supply_lines`, `sales_facts`.
- **Events:** consumes `StockReceived`, `StockDeducted`, `StockAdjusted`, `TransferOrderShipped/Received` (stock projections) and `OrderConfirmed`, `PaymentRefunded` (sales projection, net of refunds); publishes nothing.
- **Notable:** no writes of its own beyond reacting to other services' Kafka streams.

---

## 11. How services talk to each other

**Rule of thumb:** need an answer right now to continue → **REST**, via a discovery-resolved client with timeout/retry/circuit-breaker. Just announcing something happened → **event** via Kafka.

**Sync call map (who calls whom):**
```
order-svc    ──REST──►  pricing-svc, inventory-svc, payment-svc
cart-svc     ──REST──►  pricing-svc, inventory-svc, product-svc
product-svc  ──REST──►  inventory-svc   (stock flag on product page)
purchase-svc ──REST──►  product-svc     (validate variant)
tenant-svc   ──REST──►  iam-svc         (verify user on staff assignment)
```

**Event map (who publishes → who reacts), representative:**

| Event | Publisher | Consumers |
|---|---|---|
| `TenantCreated` / `TenantStatusChanged` | tenant-svc | iam-svc, cart-svc, order-svc (status projections) |
| `StoreCreated` / `StoreStatusChanged` | tenant-svc | iam-svc, cart-svc, order-svc |
| `GoodsReceived` | purchase-svc | inventory-svc, reporting-svc |
| `StockReceived` / `StockDeducted` / `StockAdjusted` | inventory-svc | reporting-svc, order-svc (POS stock-position projection) |
| `StockBelowThreshold` | inventory-svc | notification-svc |
| `OrderPlaced` / `OrderConfirmed` | order-svc | inventory-svc, customer-svc, cart-svc, reporting-svc, notification-svc |
| `OrderCancelled` / `OrderReturned` | order-svc | inventory-svc, payment-svc, reporting-svc |
| `PaymentCaptured` / `PaymentFailed` / `PaymentRefunded` | payment-svc | order-svc, reporting-svc (refunds net against sales) |
| `UserRegistered` | iam-svc | notification-svc (welcome notice) |

**Reliability requirements on every call:** REST calls carry a timeout, retries with backoff, and a circuit breaker (Helidon MP Fault Tolerance). Events are at-least-once with idempotent consumers, published via the outbox, and tracked via `processed_events` dedupe tables.

---

## 12. Key workflows

**Online checkout (storefront):**
```
Browse (public catalog) → add to cart → view live price (pricing-svc) → POST /orders (Idempotency-Key)
  order-svc on placement: quote (pricing-svc) → reserve stock (inventory-svc) → order PENDING
  client then captures payment directly against payment-svc (pay-now = /payments/online)
  payment-svc emits PaymentCaptured → order-svc confirms the order (once tenders cover the total)
  async on confirm: inventory deducts held stock; customer-svc accrues loyalty; reporting records the sale
  payment fails → PaymentFailed → order CANCELLED (reservation released)
  never paid → PendingOrderSweeper cancels the stranded PENDING order after a TTL (releases the hold)
```
> **Note on the "saga".** order-svc orchestrates the *placement* half (quote + reserve) synchronously,
> but payment is **client-initiated** against payment-svc and order-svc reacts to `PaymentCaptured`/
> `PaymentFailed` events — it does not call payment-svc itself. The stranded-order sweeper is the
> backstop for a client that places an order and then never pays.

**POS sale (cashier):**
```
Cashier opens POS session (iam-svc) → opens till (payment-svc) → scans barcode (product-svc)
  → price resolved for this store (pricing-svc) → [same order-svc saga, channel=POS]
  → tender screen: cash/card/UPI/wallet/gift-card, split-tender supported (payment-svc)
  → receipt generated + printed/emailed (order-svc) → till closed, Z-report (payment-svc)
```
Held sales can be **parked** and later **resumed** (with a discard-confirmation for unsynced changes); a sale can also be converted to a **layaway** with deposits, or issued as a **gift card**.

**Stock receipt (procurement):**
```
purchase-svc: create PO → submit to supplier → record goods receipt (batch, expiry, cost)
  → GoodsReceived event → inventory-svc creates inventory_batches rows
  → reporting-svc updates its projection
Stock is deducted FIFO (earliest expiry / oldest arrival first), rows locked FOR UPDATE.
```

**Replenishment planning:**
```
Admin sets thresholds/safety-stock/kanban cards → inventory-svc planning engine runs
  → below-threshold triggers StockBelowThreshold → notification-svc raises a shortage alert
  → suggestions can be turned into a purchase-svc PO
```

---

## 13. The frontend (shelf-app)

One Flutter codebase at `frontends/shelf-app/` (Riverpod 2.x, `go_router`, `dio`) serves **four shells** behind one `MaterialApp.router`, gated by JWT-derived roles:

| Shell | Route prefix | Who | What it does |
|---|---|---|---|
| **Storefront** | `/store/*` (always public) | guest or any signed-in role | Browse/search catalog, product detail, cart & checkout (with duplicate-order guard + pickup contact-phone capture), order history — respects a tenant's "show prices" catalog mode. |
| **POS** | `/pos/*` | cashier or admin | Build a sale (scan/add items), park/resume held sales, split-tender across cash/card/UPI/wallet/gift-card/store-credit, till open/close, print receipts (an "email receipt" is currently only logged, not sent). |
| **Admin console** | `/admin/*` | tenant admin | Catalog (incl. CSV bulk import), inventory, pricing, stores, staff, procurement, orders across channels, customers, reports, dashboard. |
| **Platform console** | `/platform/*` | platform admin (separate login) | Cross-tenant view; suspend/reactivate tenants. |

New admins/owners without a `tenantId` claim are routed into a 2-step **onboarding wizard** (business details → first store) before landing in the admin console.

For the full screen-by-screen, persona-by-persona tour of what's actually on each of these four shells, see [docs/UI-GUIDE.md](UI-GUIDE.md).

**Auth & networking:**
- `lib/core/auth/`: JWT decoded client-side to populate roles/tenant; tokens in `flutter_secure_storage`.
- `lib/core/network/api_client.dart`: single `dio` instance, `AuthInterceptor` attaches the bearer token and does **single-flight token refresh** — concurrent 401s share one in-flight `/auth/refresh` call instead of racing/dropping requests.
- `lib/core/network/api_response.dart`/`api_error.dart` mirror the backend's `{ data, error, meta }` envelope with structured error extraction (not string-matching).

**State management:** `AsyncNotifier` for auth/single-shot state; `StateNotifier` + `.family` cursor-pagination providers (`customers_pagination.dart`, `orders_pagination.dart`, `products_pagination.dart`) for infinite-scroll admin lists.

**Localization:** 8 languages (`en_GB` default) — English, Arabic, Bengali, Gujarati, Punjabi, Polish, Romanian, Urdu; Arabic/Urdu resolve RTL automatically.

**Targets:** web (the deployed target, served via nginx on port 8088), plus Android/iOS project scaffolding. Flutter 3.44.2 in CI, matched to local SDK.

---

## 14. Cross-cutting conventions

- **Response envelope:** `{ "data": ..., "error": { "code", "message" } | null, "meta": { "requestId", "nextCursor" } }`.
- **Errors:** correct HTTP codes (`400` validation, `401`/`403` auth, `404`, `409` conflict, `422` business rule, `500` unexpected); stable machine `code`; never leak stack traces or SQL.
- **Pagination:** cursor only (`?after=&limit=`, default 20 / max 100, opaque base64 keyset cursor). No page numbers.
- **Naming:** REST paths = plural kebab nouns (`/purchase-orders`); JSON = `camelCase`; DB columns = `snake_case`; events = `PascalCase` past tense (`OrderPlaced`); Kafka topics = `shelfj.<domain>.<event>`.
- **IDs:** UUIDv7 is the only uuid version Shelf-J stores. v7 ids lead with a millisecond timestamp, so new rows append to the right-hand edge of each index rather than splitting random pages.
  - **Minting:** in the service, with `Ids.newId()` (`shared/common-ids`). A key that must come out the same on every redelivery — a dedupe id per event line — is `Ids.derived(eventId, name)`: the event's timestamp plus a hash. Seed rows in migrations carry literal v7 ids.
  - **Never:** `UUID.randomUUID()` (v4), `UUID.nameUUIDFromBytes()` (v3), `gen_random_uuid()` or `uuid_generate_v4()` in SQL, or a column `DEFAULT` that fills in a uuid — every `INSERT` names `id` and binds `Ids.newId()`. The one set-based insert that cannot (`demand_history`'s `INSERT … SELECT … GROUP BY`) calls inventory's `uuid_v7()` function; switch it to Postgres 18's `uuidv7()` on upgrade.
  - **Enforced by:** PMD rules `UseTimeOrderedIds` and `NoDatabaseMintedIds`; `PostgresSupport.stop()`, which fails an integration test class if any column defaults to a uuid generator or any table's `id` holds another version; and the Flyway `afterMigrate` check in `common-service`, which fails `flyway migrate` for the same column defaults. `FlywayRunner` logs a failed migration as a warning and keeps the service running, so CI — not startup — is what stops a violation.
  - **Handles for people** (order numbers, batch-number suffixes) come from the end of the id with `Ids.shortRef(id)` / Dart `shortRef(id)` — the first characters are the clock.
  - The id's timestamp is visible to anyone holding it: never use an id as a secret or as the business time (keep `created_at`).
- **Auth:** gateway validates the JWT once and re-stamps identity headers; services read `tenant_id`/`userId`/`roles` from those headers, never from the request body.
- **Idempotency:** `Idempotency-Key` header on checkout/payment-capture/stock-receipt/cash-movement writes; the gateway forwards it verbatim; the service stores processed keys and replays the original response.
- **Health:** `/health/started`, `/health/live`, `/health/ready` (ready checks real DB/Kafka/config reachability) + `/metrics` (Prometheus) on every service.
- **Migrations:** Flyway only (`V<n>__desc.sql`); never manual DDL in prod.
- **Tests:** unit for `service/` logic + Testcontainers integration for the core flow. Not done without it.

---

## 15. Local development

**Prerequisites:** JDK 21 (Temurin), Maven 3.9+, Docker + Docker Compose.

```bash
cp .env.example .env && nano .env    # set real secrets (dev defaults work out of the box too)
export JAVA_HOME=/usr/lib/jvm/temurin-21-jdk-amd64
mvn clean install -DskipTests        # build all service JARs
docker compose up -d --build         # infra → platform → business services → gateway, health-gated
curl http://localhost:8090/api/inventory-svc/health/ready
```

**Ports exposed to the host:**

| Port | Service | Port | Service |
|---|---|---|---|
| 8088 | shelf-app (web UI) | 3100 | Grafana |
| 8090 | gateway (public API) | 8500 | Consul UI |
| 5555 | pgAdmin | 9411 | Zipkin |
| 8081 | kafka-ui | 9090 | Prometheus |
| 8082 | swagger-ui (aggregated OpenAPI) | 5432 | Postgres (direct SQL) |
| 6379 | Redis (redis-cli) | | |

Internal-only (not published to the host): `otel-collector`, `loki`, `tempo`, node/postgres/redis exporters, `pgbouncer`, and each service's own port 8080 (reached only via the gateway or the Docker network).

`docker compose down` to stop. Rebuild-and-redeploy in one shot: `scripts/redeploy.sh`. Run the Flutter web app against the dockerized gateway: `scripts/run-web.sh` (port 40015).

---

## 16. Testing & quality gates

- **Backend:** JUnit 5 unit tests per service; Testcontainers integration tests (real Postgres + Kafka) for repos, messaging, and saga happy paths; `ArchUnit` rules (`ShelfJArchRules` in `common-test`) enforce the layering in §7; SpotBugs + PMD + `fmt-maven-plugin` run as part of `mvn clean install`.
- **End-to-end and load tests (`k6/`, see [k6/README.md](../k6/README.md)):** every suite drives the dockerized stack through the gateway with real JWTs, and every functional suite requires all of its checks to pass. `k6/run.sh` runs them (waiting until each service is routable, reading the platform-admin login from `.env`) and fails if any suite fails. The **flow guard** has two suites: `flow-guard-comprehensive.js` (onboarding → first sale, each step refused when too early, by the wrong role or against another tenant) and `flow-guard-runtime.js` (a suspended tenant or closed store stops the storefront, checkout, staff login/refresh, POS sessions, carts and orders — for that tenant or store only — and reopening restores them; another tenant's store id is never operational). Per-service `*-crud.js` suites cover positive and negative cases for each service; `gateway-smoke-it.js`, `gateway-login-protection.js` (brute-force lockout) and `gateway-rate-limit-stress.js` cover the gateway; `multi-tenant-retail.js` and `full-stack-simulation.js` are the concurrent load runs (`k6/run.sh --load`). `k6/db/validate_all.sh` runs `psql` checks afterwards, including that every stored id is v7. Shared helpers live in `k6/lib/shelfj.js`. Always run these against the dockerized stack, never a raw JVM process.
- **Frontend:** widget/unit tests under `frontends/shelf-app/test/` covering auth interceptor refresh, envelope/error mapping, cursor pagination, held-sale resume, catalog show-price mode, checkout duplicate-order guard, localization, and the adaptive nav shell.
- **Duplication:** `scripts/duplo.sh` runs the Duplo duplicate-code finder over the Java sources.
- **Skills** (`.claude/skills/`): `scaffold-service`, `add-endpoint`, `add-event`, `onboard-tenant`, `check-golden-rules` — use these for consistency when extending the system; a dedicated `shelf-j-reviewer` agent enforces the golden rules + SQL/SOLID rules + duplo on major changesets.

**CI (`.github/workflows/`):** `ci.yml` builds+tests the full reactor on every push/PR to main; `docker-publish.yml` builds and pushes every service image to GHCR on push to main / version tags (with a cleanup job pruning old images); `release.yml` publishes JARs to GitHub Packages and as release assets on `v*` tags.

---

## 17. Production deployment & startup ordering

- The `docker-compose.yml` port map (§15) is **local-dev only**. In production every service listens on the **same port (8080)**; addressing is by **k8s DNS + Consul**, never `host:port`.
- **Do not order individual services at startup** — the dependency graph is a mesh (order-svc needs pricing+inventory+payment; cart-svc needs pricing+inventory+product; etc.), so no linear order works. Services start in **any order** and gate on readiness: `/health/started` (booting grace period), `/health/live` (restart if failing), `/health/ready` (stop routing traffic if failing — must check real DB/Kafka/config reachability), backed by `@Retry`/`@CircuitBreaker`/`@Fallback`.
- Ordering exists only between **stages**, enforced by readiness gates, never between individual business services:
  ```
  infra (Postgres/Kafka/Consul/Redis) → platform (config → discovery → gateway)
    → DB migrations (run-once Jobs, never inside app boot)
    → all 12 business services in parallel → frontends
  ```
- Rollouts are independent per service (rolling update by default; blue-green/canary for risky changes) — never a synchronized "restart everything." `docker-compose.prod.yml` additionally fails fast (`${VAR:?...}`) if any datastore secret is left at its dev default.
- Running Shelf-J on a bare VPS instead of Kubernetes? See [docs/vps-deployment.md](vps-deployment.md) for the full single-box production playbook (domain, TLS, backups).

---

## 18. Security & hardening posture

Two independent deep-dive audits have been run against the *actual code* (not the spec docs): [AUDIT.md](../AUDIT.md) (2026-06-23, API/UI industry-standards review) and [fable-finding.md](../fable-finding.md) (2026-07-02, security/correctness first-principles review). Both concluded the system is **well past design phase** and, on the backend, **close to production-grade**:

- No SQL injection — every query is a bound `PreparedStatement`.
- `tenant_id` only ever comes from the gateway-verified JWT; identity headers are stripped and re-stamped, never trusted from the client.
- Money paths (refund caps, layaway overpayment, return-quantity caps) are enforced inside locked transactions, not check-then-act — no concurrent double-spend.
- Inventory reserve/deduct locks rows `FOR UPDATE`; checkout is idempotent end-to-end (replay returns the original result, never double-charges or double-deducts).
- Refresh tokens are opaque, hashed at rest, rotated, with reuse-triggers-family-revocation; passwords use Argon2id with timing-equalized responses against account enumeration.
- Production datastore secrets fail fast instead of silently falling back to repo-public dev defaults (`docker-compose.prod.yml`).
- Prior findings from both audits are tracked with fix status inline in those documents (most are fixed; anything deferred carries a documented rationale) — check them before assuming a known gap is still open.

The frontend is functional and structured but was flagged as roughly one tier below the backend on UI/UX polish at audit time — see AUDIT.md Part 2 for the current list. Known open items worth checking before relying on them: API versioning (no `/v1` prefix yet) and machine-readable OpenAPI generation are tracked in AUDIT.md as not-yet-done.

---

## 19. Definition of Done

A service isn't finished until:

- [ ] Own Postgres schema via Flyway migrations; no access to any other service's database.
- [ ] REST endpoints behind the gateway; DTOs in/out, never domain entities.
- [ ] Input validated at the boundary; correct HTTP codes; standard error envelope.
- [ ] `tenant_id` read from the JWT-derived header; every tenant query filters by it first.
- [ ] Registers with Consul on startup; resolves other services via discovery.
- [ ] Reads config from the config service/env; no secrets in code or image.
- [ ] Publishes events via the outbox after commit; consumers are idempotent.
- [ ] Append-only tables are truly append-only.
- [ ] Money = `BigDecimal`/`NUMERIC`; timestamps = UTC `timestamptz`.
- [ ] `/health/started`, `/health/live`, `/health/ready` (readiness checks real deps) + `/metrics`.
- [ ] Starts in any order — survives dependencies being absent at boot (stays not-ready, doesn't crash-loop).
- [ ] Synchronous calls have timeout + retry + circuit breaker + fallback.
- [ ] Unit tests for logic + Testcontainers integration test for the core flow.
- [ ] Builds clean with `mvn clean install`; containerizes; starts in docker-compose.
- [ ] Follows the [golden rules](#6-the-golden-rules) and [coding standards](coding-standards.md).

---

## 20. Glossary

| Term | Meaning |
|---|---|
| **Tenant** | One business using the platform; data isolated from other tenants. |
| **Store / Warehouse** | A physical site owned by a tenant; has an address, geo, hours, type. |
| **Zone** | A sub-location inside a store (aisle/rack/cold-room) where stock physically sits. |
| **Gateway** | The single public entry point that authenticates, rate-limits, and routes. |
| **Consul / discovery** | The registry where services announce and find each other. |
| **Outbox** | A DB table where events are written in the same transaction as the data, then drained to Kafka — guarantees the event and the data agree. |
| **Idempotent** | Doing the same operation twice has the same effect as once. |
| **Saga** | A multi-service workflow with compensation if a later step fails. |
| **Reservation** | Temporarily holding stock during checkout so two buyers can't claim the same unit. |
| **FIFO** | Sell/deduct the oldest (or soonest-expiring) batch first. |
| **GRN** | Goods Receipt Note — record of stock actually received from a supplier. |
| **Layaway** | A sale reserved against deposits, completed once fully paid. |
| **Parked sale** | A POS sale held mid-transaction, resumable later. |
| **Till / X-report / Z-report** | Cash drawer session; X = mid-shift summary, Z = end-of-day close. |
| **VAT MTD return** | UK HMRC Making Tax Digital VAT return (boxes 1-9), computed by pricing-svc. |
| **CQRS / projection** | A read-optimized copy of data built from events (used by reporting-svc, status-gate consumers). |
| **JWT** | Signed token proving who the caller is and what they may do. |
| **DTO** | Data Transfer Object — the API's data shape, separate from DB entities. |
| **Helidon MP** | The MicroProfile-based Java framework every service is built with. |
| **Liveness / Readiness / Startup probe** | Health checks answering "alive?", "can serve traffic now?", "finished booting?" respectively. |
| **Rolling / blue-green / canary** | Zero-downtime deploy strategies, applied per service. |

---

*Companion documents: [README.md](../README.md) (product deep dive) · [docs/API-GUIDE.md](API-GUIDE.md) (API surface by business capability) · [docs/UI-GUIDE.md](UI-GUIDE.md) (UI surface by persona/screen) · [PRD.md](../PRD.md) (product requirements & roadmap) · [docs/onboarding-and-locations.md](onboarding-and-locations.md) · [docs/coding-standards.md](coding-standards.md) · [AUDIT.md](../AUDIT.md) · [fable-finding.md](../fable-finding.md) · [CLAUDE.md](../CLAUDE.md) (AI agent briefing).*
