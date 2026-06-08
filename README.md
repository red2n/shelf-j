# Shelf-J — Developer & AI Build Guide

> **Read this first.** This document is the single source of truth for building Shelf-J. It is written so that **a developer (or an AI coding agent) who knows nothing about this project can build any service correctly and consistently.** The companion [PRD.md](PRD.md) explains *what* we are building and *why*; this README explains *how* — the concepts, the rules, and the per-service blueprints.

---

## Table of contents

1. [What we are building (in plain words)](#1-what-we-are-building-in-plain-words)
2. [Concepts you must understand before writing code](#2-concepts-you-must-understand-before-writing-code)
3. [The golden rules (an AI agent MUST follow these)](#3-the-golden-rules-an-ai-agent-must-follow-these)
4. [Technology stack & why each piece exists](#4-technology-stack--why-each-piece-exists)
5. [Repository layout](#5-repository-layout)
6. [Anatomy of one service (the template every service copies)](#6-anatomy-of-one-service-the-template-every-service-copies)
7. [Cross-cutting conventions](#7-cross-cutting-conventions)
8. [The platform services (gateway, discovery, config)](#8-the-platform-services-gateway-discovery-config)
9. [The business services — full catalog](#9-the-business-services--full-catalog)
10. [How services talk to each other](#10-how-services-talk-to-each-other)
11. [Build order & milestones](#11-build-order--milestones)
12. [Local development](#12-local-development)
13. [Production deployment & startup ordering](#13-production-deployment--startup-ordering)
14. [Definition of Done for any service](#14-definition-of-done-for-any-service)
15. [Glossary](#15-glossary)

---

## 1. What we are building (in plain words)

Shelf-J is a **stock and store management platform** that also lets **customers buy products online**.

Imagine a business that owns one or more shops. With Shelf-J they can:

- keep track of **what products they have and how much stock is on the shelf** (inventory),
- **buy more stock from suppliers** (purchasing),
- **sell products at the counter** with a cash register screen (POS — Point of Sale),
- **sell the same products on a public website** where any customer can browse and order (storefront),
- see **reports** of what sold and what's running low.

It is **multi-tenant**: many separate businesses use the same platform, but each business only ever sees its own data.

It is built as **many small programs (microservices)** instead of one big program. Each small program does one job (e.g. "inventory", "payments") and they cooperate over the network. A single front door called the **gateway** is the only thing the outside world talks to.

If you have never built microservices: that's fine. Section 2 teaches the ideas. Section 6 gives you a fill-in-the-blanks template. Section 9 tells you exactly what each service must do.

---

## 2. Concepts you must understand before writing code

You do **not** need prior microservices experience, but you must understand these eight ideas. Each is short.

### 2.1 Microservice
A microservice is a **small, independent program** that owns **one business capability** and its **own database**. It can be built, deployed, and scaled on its own. In Shelf-J, "inventory-svc" is one microservice; "payment-svc" is another. They never share a database.

### 2.2 Why "strict" microservices
"Strict" here means we enforce real boundaries:
- **Database-per-service** — `inventory-svc` cannot read `payment-svc`'s tables. If it needs payment data, it asks over the network or listens for an event.
- **No shared code that contains business logic** — only *contracts* (shapes of data) are shared.

This keeps services truly independent. The most common beginner mistake is to "just join the tables" — **we never do that here.**

### 2.3 API Gateway
The **gateway** is the single public entry point. The browser/app never calls a service directly. It calls the gateway, and the gateway forwards the request to the right internal service. The gateway also handles things every service would otherwise repeat: checking the login token, blocking abusive traffic (rate limiting), adding a request id, CORS, and TLS.

Think of it as the **reception desk** of an office building: visitors talk to reception, reception routes them to the right room.

### 2.4 Service discovery
Services don't have fixed addresses (we may run 1 copy or 10 copies of each). When `order-svc` wants to call `pricing-svc`, it asks the **discovery** system "where is pricing-svc right now?" and gets a healthy address back. We use **Consul** for this. Every service **registers itself** with discovery when it starts.

Think of it as a **phone directory** that updates itself.

### 2.5 Centralized configuration
Settings (database URLs, Kafka address, feature toggles) are **not** hardcoded in the program or baked into the container image. Instead a **config** service serves them, and each service pulls its settings at startup. This means we can change a setting in one place without rebuilding anything.

### 2.6 Events & Kafka (asynchronous communication)
Two ways services communicate:
- **Synchronous (REST):** "I'll wait for your answer." `order-svc` asks `pricing-svc` for a price and waits. Used when you need the answer *right now*.
- **Asynchronous (events via Kafka):** "I'll announce that something happened; whoever cares can react later." When an order is placed, `order-svc` publishes an `OrderPlaced` **event** to **Kafka** (a message bus). `notification-svc`, `customer-svc`, and `reporting-svc` each **consume** it and react independently. The order service does not wait for them.

**Event** = a record of *something that already happened*, named in the past tense (`OrderPlaced`, `StockReceived`). This is how services stay consistent without sharing a database.

### 2.7 Saga (a workflow spread across services)
Some operations touch several services. **Checkout** = resolve price + reserve stock + take payment + confirm order. No single database transaction can cover all of them (they're in different services). A **saga** is a sequence of steps where, if a later step fails, earlier steps are **compensated** (undone). Example: if payment fails, we **release the stock reservation**. `order-svc` is the saga coordinator for checkout.

### 2.8 Multi-tenancy & tenant isolation
Every row of business data belongs to a **tenant** (a business). The `tenant_id` is taken from the **logged-in user's token (JWT)** — never from what the request body claims. Every database query filters by `tenant_id`. This guarantees Business A can never see Business B's data.

> If you understand 2.1–2.8, you can build any service in this project.

---

## 3. The golden rules (an AI agent MUST follow these)

These are non-negotiable. Apply them to **every** service, **every** time. Violating one is a bug even if the code "works".

1. **Database-per-service.** Never connect a service to another service's schema. Never write a SQL join across service boundaries. Need foreign data? Call the owning service (REST) or consume its events.
2. **The gateway is the only public door.** Business services are never exposed to the internet directly. They trust that traffic arriving from the gateway is already authenticated.
3. **`tenant_id` comes from the JWT, never from the request.** Read it from the authenticated principal. Never trust `tenant_id` in the body, query, or path. Every query on tenant-owned data filters by `tenant_id` **as the first condition**.
4. **Discover, don't hardcode.** To call another service, resolve it via discovery (Consul). No hardcoded host:port of another service in application code.
5. **Config is external.** No environment-specific value (DB URL, Kafka broker, secret) baked into code or image. Pull from config service / environment. Secrets never committed to git.
6. **State changes others care about are published as events.** After you successfully change your own data, publish the corresponding past-tense event. Use the transactional **outbox** pattern so the DB write and the event are atomic.
7. **All event consumers are idempotent.** The same event may arrive twice. Processing it twice must have the same effect as once (dedupe by event id / business key).
8. **Append-only ledgers stay append-only.** `stock_movements`, `order_status_history`, `payments`, `audit_log` are insert-only. Never UPDATE or DELETE rows; record a new compensating row instead.
9. **Controllers are thin.** JAX-RS resource classes do request/response mapping only. All business logic lives in the service layer. No database calls in resource classes.
10. **DTOs are the contract — never expose JPA entities over HTTP.** Map entities to DTOs. This keeps the API stable as the database evolves.
11. **Every write that matters is auditable and idempotent at the edge.** Payment capture, order placement, and stock movements accept an idempotency key so retries don't double-charge or double-deduct.
12. **Every service ships health, metrics, and tracing.** No service is "done" without `/health`, `/metrics`, and propagated trace context (see §13).
13. **Money is never a floating-point number.** Use `BigDecimal` (Java) and `NUMERIC` (Postgres) for all amounts and quantities that require exactness. Store currency explicitly.
14. **Time is UTC.** Store timestamps as `timestamptz` in UTC. Convert to local time only at the UI edge.
15. **Validate every input at the boundary.** Use Bean Validation (`@NotNull`, `@Positive`, etc.) on DTOs. Reject bad input with `400`, never let it reach the database.

---

## 4. Technology stack & why each piece exists

| Piece | What it is | Why we use it |
|---|---|---|
| **Java 21** | Programming language/runtime | Matches the reference repo; modern, stable. |
| **Helidon MP 4.x** | Microservice framework (MicroProfile) | Gives us REST (JAX-RS), config, health, metrics, OpenAPI, JWT auth, fault tolerance, and Kafka messaging out of the box. |
| **Maven (multi-module)** | Build tool | One parent project, each service a module — same as the reference repo. |
| **PostgreSQL 16** | Relational database | One database/schema **per service**. Reliable, supports `NUMERIC`, `timestamptz`, JSON. |
| **Flyway** | Database migration tool | Versioned, repeatable schema changes checked into git. |
| **Apache Kafka** | Event/message bus | Asynchronous events between services (the backbone of consistency). |
| **Consul** | Service discovery | The "phone directory" — services register and find each other. |
| **Config service** | Centralized configuration | One place for all settings. |
| **Redis** | In-memory cache/store | Fast storage for carts, sessions, hot catalog reads. |
| **OpenTelemetry + Zipkin** | Distributed tracing | Follow one request across many services (debugging). |
| **Prometheus + Grafana** | Metrics + dashboards | See health, throughput, latency of every service. |
| **Docker + docker-compose** | Containers / local orchestration | Run the whole system on your laptop with one command. |
| **Kubernetes** (later) | Production orchestration | Scale and run services in production. |

> **Helidon MP vs SE:** we use **MP (MicroProfile)** — the annotation/CDI style, closest to the Spring experience the reference repo used. Do not mix in Helidon SE patterns.

---

## 5. Repository layout

```
shelf-j/
├── pom.xml                      # PARENT pom: Java 21, Helidon BOM, shared plugins/versions
├── docker-compose.yml           # postgres, kafka, zookeeper, consul, redis, zipkin, prometheus, grafana
├── PRD.md                       # what & why
├── README.md                    # this file — how
│
├── platform/                    # the three infrastructure services
│   ├── gateway/                 # single public entry point
│   ├── discovery/               # Consul bootstrap / registration support
│   └── config/                  # centralized configuration server
│
├── services/                    # the business microservices (one module each)
│   ├── iam-svc/                 # identity, login, JWT, roles
│   ├── tenant-svc/              # tenants, stores, staff, feature flags
│   ├── product-svc/             # product catalog
│   ├── inventory-svc/           # stock, batches, movements
│   ├── pricing-svc/             # prices, promotions, tax
│   ├── cart-svc/                # storefront shopping cart
│   ├── order-svc/               # orders for online + POS (saga coordinator)
│   ├── payment-svc/             # payments & refunds
│   ├── purchase-svc/            # suppliers, purchase orders, goods receipt
│   ├── customer-svc/            # customer profiles, loyalty
│   ├── notification-svc/        # email / SMS / push
│   └── reporting-svc/           # analytics & reports (read models)
│
├── shared/                      # shared CONTRACTS only — no business logic
│   ├── events-contract/         # event schemas (JSON/Avro) + generated POJOs
│   ├── common-web/              # shared JAX-RS filters: error envelope, request-id, tenant context
│   └── common-test/             # Testcontainers helpers, fixtures
│
└── frontends/
    ├── admin-console/           # web app for owner/manager/staff
    ├── storefront/              # public customer web app
    └── pos/                     # in-store point-of-sale web app
```

**Rule:** `shared/` may contain DTOs, event schemas, filters, and helpers — but **never** business rules or database access. If you find yourself putting "how inventory works" into `shared/`, stop: it belongs in `inventory-svc`.

---

## 6. Anatomy of one service (the template every service copies)

Every business service has the **same internal shape**. Copy this for each new service.

```
<service>/
├── pom.xml
└── src/
    ├── main/
    │   ├── java/com/shelfj/<service>/
    │   │   ├── api/            # JAX-RS resources (REST endpoints) — THIN
    │   │   ├── dto/            # request/response objects (the API contract)
    │   │   ├── service/        # business logic — the brain of the service
    │   │   ├── domain/         # JPA entities (database tables)
    │   │   ├── repo/           # persistence (queries)
    │   │   ├── messaging/      # Kafka producers + consumers
    │   │   ├── client/         # typed REST clients to OTHER services (via discovery)
    │   │   ├── mapper/         # entity ↔ DTO conversion
    │   │   └── config/         # config injection, beans
    │   └── resources/
    │       ├── META-INF/microprofile-config.properties   # local/default config
    │       └── db/migration/                              # Flyway: V1__init.sql, V2__...
    └── test/
        └── java/com/shelfj/<service>/                     # unit + integration tests
```

**The layers, top to bottom:**

| Layer | Responsibility | Must NOT |
|---|---|---|
| `api/` | Receive HTTP, validate DTO, call `service/`, return DTO. | contain business logic or DB calls |
| `dto/` | Define the shape of requests/responses. | contain logic; expose JPA entities |
| `service/` | All business rules, transactions, orchestration. | talk HTTP directly (use `client/`) |
| `domain/` | JPA entities = database tables. | leak out over HTTP (map to DTO) |
| `repo/` | Database queries (always tenant-filtered). | be called from `api/` |
| `messaging/` | Publish events (after commit) & consume events (idempotently). | contain business rules (delegate to `service/`) |
| `client/` | Call other services via discovery, with timeouts + retries + fallbacks. | hardcode hostnames |
| `mapper/` | Convert entity ↔ DTO. | — |

**Data flow for a write request:**
```
HTTP → api/ (validate DTO) → service/ (logic + repo/ save + outbox) → return DTO
                                   └─ later: messaging/ publishes event from outbox → Kafka
```

---

## 7. Cross-cutting conventions

These apply identically across all services so the codebase feels like one team wrote it.

### 7.1 API response envelope
Every JSON response uses one shape (defined once in `common-web`):

```json
{
  "data":  { ... },              // the payload, or null on error
  "error": null,                  // or { "code": "...", "message": "...", "details": [...] }
  "meta":  { "requestId": "…", "nextCursor": "…" }   // pagination/trace info
}
```

### 7.2 Errors
- Use proper HTTP status codes: `400` validation, `401` not authenticated, `403` not allowed, `404` not found, `409` conflict, `422` business-rule violation, `500` unexpected.
- Error body always carries a stable machine-readable `code` (e.g. `INVENTORY_INSUFFICIENT_STOCK`).
- Never leak stack traces or SQL to the client.

### 7.3 Pagination
- **Cursor-based only**: `?after=<cursor>&limit=<n>`. No page numbers.
- Default `limit` 20, max 100.

### 7.4 Naming
- REST paths: plural nouns, kebab-case — `/products`, `/purchase-orders`, `/stock-movements`.
- JSON fields: `camelCase`. Database columns: `snake_case`. Java: standard `CamelCase`/`camelCase`.
- Events: `PascalCase` past tense — `OrderPlaced`, `StockReceived`.
- Kafka topics: `shelfj.<domain>.<event>` lower kebab/dot — e.g. `shelfj.orders.order-placed`.

### 7.5 Identifiers
- Primary keys are **UUID** (`uuid` column type), generated by the service.
- Every tenant-owned table has `tenant_id UUID NOT NULL` and a composite index starting with `tenant_id`.

### 7.6 Auth
- Gateway validates the JWT and forwards identity (claims) downstream.
- Services read `tenant_id`, `userId`, `roles` from the verified token (MicroProfile JWT).
- Authorization: coarse role checks at the gateway, fine-grained checks in the service.

### 7.7 Idempotency
- Mutating endpoints that can be retried (checkout, payment capture, stock receipt) accept an `Idempotency-Key` header; the service stores processed keys and returns the prior result on replay.

### 7.8 Health, metrics, tracing (every service)
- **Three health probes** (MicroProfile Health) — these are what let services start in **any order** in production (see §13):
  - `GET /health/started` — has the JVM finished booting? (grace period for slow cold starts)
  - `GET /health/live` — is the process alive/not deadlocked? (failing → orchestrator **restarts** it)
  - `GET /health/ready` — can it serve traffic *right now*? **Must check its real dependencies** (database reachable, Kafka reachable, config loaded). Failing → orchestrator routes **no traffic** to it but does **not** kill it.
- `GET /metrics` (Prometheus format, MicroProfile Metrics).
- Trace context propagated; `X-Request-Id` flows from the gateway through every hop and into events.

> **Why this matters:** in production we never order service startup. A service whose DB or Kafka isn't up yet simply reports **not ready** and retries — see §13 for the full model.

### 7.9 Database rules
- Migrations only via Flyway (`V<n>__description.sql`), never manual ALTERs in prod.
- Money/quantity = `NUMERIC`. Timestamps = `timestamptz` (UTC).
- Append-only tables never updated/deleted.
- Every query on tenant data filters `tenant_id` first.

### 7.10 Testing
- **Unit tests** for service-layer logic.
- **Integration tests** with **Testcontainers** (real Postgres + Kafka in a container) for repos, messaging, and the happy-path saga.
- A service is not done if its core flow has no integration test (§13).

---

## 8. The platform services (gateway, discovery, config)

These three are the scaffolding. Build them in **Phase 0** before any business service.

### 8.1 `platform/gateway`
**Role:** the only public entry point. Every external request enters here.

**Responsibilities:**
- **Routing:** map a public path to an internal service resolved via discovery. e.g. `/api/products/**` → `product-svc`, `/api/orders/**` → `order-svc`.
- **Authentication:** validate the JWT once; reject anonymous calls to protected routes; allow public routes (storefront browse, login, register).
- **Authorization (coarse):** block by role where appropriate before traffic reaches a service.
- **Rate limiting:** protect against abuse (stricter on `/auth`, login).
- **Request id:** generate/propagate `X-Request-Id` for tracing.
- **CORS & TLS termination.**
- **Header hygiene:** strip internal headers from inbound, add identity headers for downstream.

**Public route map (initial):**

| Public path | Routed to | Auth |
|---|---|---|
| `/api/auth/**` | iam-svc | public (login/register), rate-limited |
| `/api/catalog/**` | product-svc | public read |
| `/api/cart/**` | cart-svc | public/guest allowed |
| `/api/checkout/**`, `/api/orders/**` | order-svc | customer or staff |
| `/api/payments/**` | payment-svc | customer or staff |
| `/api/admin/tenants/**` | tenant-svc | OWNER/PLATFORM_ADMIN |
| `/api/admin/products/**` | product-svc | OWNER/MANAGER |
| `/api/admin/inventory/**` | inventory-svc | MANAGER/STOREKEEPER |
| `/api/admin/pricing/**` | pricing-svc | OWNER/MANAGER |
| `/api/admin/purchases/**` | purchase-svc | MANAGER/STOREKEEPER |
| `/api/admin/customers/**` | customer-svc | OWNER/MANAGER |
| `/api/admin/reports/**` | reporting-svc | OWNER/MANAGER |

### 8.2 `platform/discovery`
**Role:** service registry (Consul).

**Responsibilities:**
- Run Consul (containerized).
- Each business service **registers** on startup with its name + address + health check, and **deregisters** on shutdown.
- Gateway and services **look up** healthy instances by name.

**Convention:** service name in discovery = module name (`product-svc`, `order-svc`, …).

### 8.3 `platform/config`
**Role:** centralized configuration server.

**Responsibilities:**
- Serve per-service, per-environment configuration from a backing store (Git repo or Consul KV).
- Services read config at startup via MicroProfile Config; non-secret values can hot-refresh.
- **Secrets** (DB passwords, payment keys) come from environment/secret store injected at deploy — **never committed**.

**Config naming:** `<service>-<profile>.properties` (e.g. `order-svc-prod.properties`).

---

## 9. The business services — full catalog

> For each service below: **Purpose**, **Owns (tables)**, **Key REST endpoints**, **Events published**, **Events consumed**, **Talks to (sync)**, **Special rules**. An AI agent building a service should treat its subsection as the spec.

Standard roles referenced: `PLATFORM_ADMIN`, `OWNER`, `MANAGER`, `STOREKEEPER`, `CASHIER`, `CUSTOMER`.

---

### 9.1 iam-svc — Identity & Access Management
**Purpose:** Who is allowed in, and as whom. Handles **both** staff and customers. Issues and validates JWTs.

**Owns (tables):**
- `users` (id, tenant_id?, email, phone, password_hash, status, type[STAFF|CUSTOMER])
- `roles` (id, name)
- `user_roles` (user_id, role_id, store_id?)
- `refresh_tokens` (id, user_id, token_hash, expires_at, revoked)
- `otp_codes` (id, target, code_hash, expires_at, consumed)
- `audit_log` (append-only)

> Note: customers are global-ish (can shop any tenant's storefront if multi-storefront), staff belong to a tenant. Decide tenant scoping per `type`.

**Key REST endpoints:**
- `POST /auth/register` — customer self-signup
- `POST /auth/login` — email/phone + password → access + refresh JWT
- `POST /auth/otp/request` / `POST /auth/otp/verify` — OTP login
- `POST /auth/refresh` — rotate refresh token → new access token
- `POST /auth/logout` — revoke refresh token
- `GET  /auth/me` — current principal
- `POST /admin/users` — staff creation (OWNER/MANAGER), assign roles/stores

**Events published:** `UserRegistered`, `UserLoggedIn`, `StaffInvited`
**Events consumed:** `TenantCreated` (to seed an owner account)
**Talks to (sync):** notification-svc (send OTP/welcome) — or via event
**Special rules:** passwords hashed (Argon2/bcrypt); JWT signed (RS256) with keys from config; refresh-token rotation; rate-limit login + OTP hard.

---

### 9.2 tenant-svc — Tenants, Stores, Staff, Features
**Purpose:** The business structure. A tenant (business) owns stores/warehouses and staff, and has a plan that toggles features.

**Owns (tables):**
- `tenants` (id, name, status, plan_id, created_at)
- `stores` (id, tenant_id, name, type[STORE|WAREHOUSE], address, geo, hours, status)
- `staff_assignments` (id, tenant_id, user_id, store_id, role)
- `plans` (id, name, limits json)
- `feature_flags` (id, tenant_id, feature_key, enabled)

**Key REST endpoints:**
- `POST /admin/tenants` — create tenant (PLATFORM_ADMIN)
- `GET  /admin/tenants/{id}` — tenant detail
- `POST /admin/stores` — create store under tenant
- `GET  /admin/stores` — list stores (tenant-scoped)
- `POST /admin/staff` — assign staff to store
- `GET  /admin/features` / `PUT /admin/features/{key}` — toggle feature

**Events published:** `TenantCreated`, `StoreCreated`, `FeatureToggled`, `StaffAssigned`
**Events consumed:** —
**Talks to (sync):** iam-svc (verify user exists when assigning staff)
**Special rules:** feature flags drive what other services allow; plan limits (e.g. max stores) enforced here.

---

### 9.3 product-svc — Product Catalog
**Purpose:** What can be sold. Products, variants, categories, brands, attributes, barcodes, images. The catalog the storefront browses.

**Owns (tables):**
- `products` (id, tenant_id, name, description, brand_id, category_id, status, sellable_online bool, sellable_pos bool)
- `product_variants` (id, tenant_id, product_id, sku, barcode, attributes json, unit)
- `categories` (id, tenant_id, parent_id, name) — tree
- `brands` (id, tenant_id, name)
- `product_media` (id, tenant_id, product_id, url, position)

**Key REST endpoints:**
- **Public:** `GET /catalog/products` (filter: category, search, in-stock), `GET /catalog/products/{id}`
- **Admin:** `POST /admin/products`, `PUT /admin/products/{id}`, `POST /admin/products/{id}/variants`, `POST /admin/categories`, `POST /admin/brands`, media upload

**Events published:** `ProductCreated`, `ProductUpdated`, `ProductDelisted`, `VariantCreated`
**Events consumed:** `LowStock` / `StockReceived` (optional, to cache an "available" flag for fast browse) — or query inventory at read time
**Talks to (sync):** inventory-svc (to show stock availability on product pages) — read-time, with fallback if inventory is down
**Special rules:** only `sellable_online=true` products appear on the storefront; `sellable_pos=true` appear in POS lookup.

---

### 9.4 inventory-svc — Stock, Batches, Movements
**Purpose:** **The single source of truth for stock.** How much of each variant exists, in which batch, where, and every movement in/out. Reserves stock during checkout.

**Owns (tables):**
- `inventory_batches` (id, tenant_id, store_id, variant_id, batch_no, received_qty, remaining_qty, cost_price, expiry_date, created_at)
- `stock_movements` (id, tenant_id, store_id, variant_id, batch_id, type[RECEIVE|SALE|ADJUST|TRANSFER|RETURN|RESERVE|RELEASE], qty, ref_type, ref_id, created_at) — **append-only**
- `reservations` (id, tenant_id, store_id, variant_id, qty, order_id, status[HELD|CONSUMED|RELEASED], expires_at)

**Key REST endpoints:**
- **Admin:** `GET /admin/inventory/levels` (per store/variant), `POST /admin/inventory/adjust`, `GET /admin/inventory/movements`, `POST /admin/inventory/transfer`
- **Internal (from order-svc):** `POST /inventory/reservations` (hold stock), `POST /inventory/reservations/{id}/consume`, `POST /inventory/reservations/{id}/release`

**Events published:** `StockReceived`, `StockReserved`, `StockDeducted`, `StockReleased`, `LowStock`, `StockExpiringSoon`, `StockAdjusted`
**Events consumed:** `GoodsReceived` (from purchase-svc → create batches), `OrderConfirmed` (consume reservation), `OrderCancelled` (release reservation), `OrderReturned` (add stock back)
**Talks to (sync):** — (mostly event-driven; serves reservation API)
**Special rules:**
- **FIFO deduction:** always `ORDER BY expiry_date ASC NULLS LAST, created_at ASC` and lock rows (`FOR UPDATE`).
- `remaining_qty` is the only mutable quantity field; everything else recorded via a new `stock_movements` row.
- Reservations **expire** (timeout) and auto-release so abandoned carts free stock.
- Low-stock threshold per variant/store → emit `LowStock`.

---

### 9.5 pricing-svc — Prices, Promotions, Tax
**Purpose:** What a product costs to the buyer, including promotions and tax. Resolves the final price at checkout. Online and POS prices may differ.

**Owns (tables):**
- `price_lists` (id, tenant_id, store_id?, channel[ONLINE|POS|ALL], currency)
- `prices` (id, tenant_id, price_list_id, variant_id, amount, effective_from, effective_to)
- `promotions` (id, tenant_id, type[PERCENT|FLAT], value, scope, min_qty, starts_at, ends_at, active)
- `tax_rates` (id, tenant_id, name, rate, category)

**Key REST endpoints:**
- **Public:** `GET /catalog/prices?variantId=&channel=` — display price
- **Internal (from order-svc/cart-svc):** `POST /pricing/quote` — given items+channel+store, return per-line net, discount, tax, and totals
- **Admin:** `POST /admin/pricing/prices`, `POST /admin/pricing/promotions`, `POST /admin/pricing/tax-rates`

**Events published:** `PriceChanged`, `PromotionActivated`, `PromotionDeactivated`
**Events consumed:** —
**Talks to (sync):** — (called by others)
**Special rules:** price resolution is deterministic and **idempotent**; promotion stacking rules defined explicitly; all math in `BigDecimal`; tax breakdown itemized (GST-slab-ready).

---

### 9.6 cart-svc — Storefront Shopping Cart
**Purpose:** The customer's shopping cart on the public storefront. Works for guests and logged-in customers. Hands the cart to checkout.

**Owns (tables):**
- `carts` (id, tenant_id, customer_id?, session_id?, status[ACTIVE|CHECKED_OUT|ABANDONED], updated_at) — often cached in Redis
- `cart_items` (id, cart_id, variant_id, qty, added_at)

**Key REST endpoints:**
- `POST /cart` — create/get cart (guest by session, or by customer)
- `POST /cart/items` — add item
- `PUT  /cart/items/{id}` — change qty
- `DELETE /cart/items/{id}` — remove
- `GET  /cart` — view cart with live prices (calls pricing-svc) and availability (calls inventory-svc)
- `POST /cart/merge` — merge guest cart into customer cart on login

**Events published:** `CartCheckedOut` (hand-off to order-svc) — or order-svc reads the cart directly at checkout
**Events consumed:** `OrderPlaced` (mark cart CHECKED_OUT)
**Talks to (sync):** pricing-svc (live prices), inventory-svc (availability), product-svc (item display)
**Special rules:** carts are ephemeral (Redis + periodic persistence ok); abandoned carts expire; never reserve stock in the cart — reservation happens at checkout in order-svc.

---

### 9.7 order-svc — Orders & Checkout (Saga Coordinator)
**Purpose:** **The heart of commerce.** Creates and tracks orders for **both** the online storefront and the in-store POS. Coordinates the **checkout saga**: price → reserve stock → pay → confirm. Handles returns.

**Owns (tables):**
- `orders` (id, tenant_id, store_id, customer_id?, channel[ONLINE|POS], status, fulfilment_type[PICKUP|DELIVERY|IMMEDIATE], totals, currency, created_at)
- `order_items` (id, order_id, variant_id, qty, unit_price, discount, tax, line_total)
- `order_status_history` (id, order_id, from_status, to_status, reason, at) — **append-only**
- `returns` (id, order_id, items json, reason, status, created_at)
- `outbox` (id, event_type, payload, created_at, published_at) — transactional outbox
- `idempotency_keys` (key, response, created_at)

**Order status lifecycle:**
```
CREATED → PENDING_PAYMENT → CONFIRMED → FULFILLED → COMPLETED
                       └→ CANCELLED (compensations run)
CONFIRMED/COMPLETED → RETURNED (partial/full)
```

**Key REST endpoints:**
- `POST /checkout` — body: cartId or line items, channel, store, fulfilment, payment method, `Idempotency-Key` header. Runs the saga; returns the order.
- `GET  /orders/{id}` — order detail
- `GET  /orders` — customer's orders / store's orders (tenant + role scoped)
- `POST /orders/{id}/cancel`
- `POST /orders/{id}/returns` — initiate return/refund (POS or storefront)

**The checkout saga (must implement with compensation):**
```
1. Quote      → pricing-svc.POST /pricing/quote          (sync)   [fail → 422, no side effects]
2. Reserve    → inventory-svc.POST /inventory/reservations(sync)   [fail → release nothing, 409 out-of-stock]
3. Pay        → payment-svc.POST /payments/capture        (sync)   [fail → COMPENSATE: release reservation, CANCELLED]
4. Confirm    → set CONFIRMED, write status history, write outbox event OrderPlaced/OrderConfirmed
5. (async) inventory-svc consumes OrderConfirmed → reservation CONSUMED → StockDeducted
6. (async) notification, customer, reporting react to OrderPlaced
```

**Events published:** `OrderPlaced`, `OrderConfirmed`, `OrderCancelled`, `OrderFulfilled`, `OrderReturned` (via **outbox**)
**Events consumed:** `PaymentCaptured` / `PaymentFailed` (if async payment), `StockReserved`/`StockReleased` acks
**Talks to (sync):** pricing-svc, inventory-svc, payment-svc
**Special rules:**
- **Idempotency-Key on `/checkout`** — replays return the same order, never double-charge.
- **Outbox pattern** — `OrderPlaced` is written to `outbox` in the same DB transaction as the order; a publisher drains it to Kafka.
- POS and online go through the **same** endpoint; only `channel` and `fulfilment_type` differ.
- Compensations are mandatory and tested.

---

### 9.8 payment-svc — Payments & Refunds
**Purpose:** Take money and give it back. Online via gateways (Razorpay/Stripe); POS via cash/card. Idempotent and auditable.

**Owns (tables):**
- `payment_intents` (id, tenant_id, order_id, amount, currency, method, provider, status, idempotency_key)
- `payments` (id, tenant_id, order_id, amount, status[CAPTURED|FAILED], provider_ref, created_at) — **append-only**
- `refunds` (id, tenant_id, payment_id, amount, status, provider_ref, created_at) — **append-only**

**Key REST endpoints:**
- `POST /payments/capture` — body: orderId, amount, method, `Idempotency-Key`. For online → create+capture provider intent; for POS cash/card → record. Returns result.
- `POST /payments/{id}/refund` — issue refund
- `POST /payments/webhook/{provider}` — provider callbacks (verify signature, reconcile)
- `GET  /payments/order/{orderId}` — payments for an order

**Events published:** `PaymentCaptured`, `PaymentFailed`, `RefundIssued`
**Events consumed:** `OrderReturned` (trigger refund), `OrderCancelled` (void/refund if already captured)
**Talks to (sync):** external payment providers
**Special rules:** **idempotency mandatory** (same key never charges twice); verify webhook signatures; never store raw card data (PCI — use provider tokens); all amounts `BigDecimal`/`NUMERIC`.

---

### 9.9 purchase-svc — Procurement
**Purpose:** Buying stock from suppliers. Suppliers, purchase orders, and goods receipt (GRN) that creates inventory batches.

**Owns (tables):**
- `suppliers` (id, tenant_id, name, contact, terms, status)
- `purchase_orders` (id, tenant_id, store_id, supplier_id, status[DRAFT|SENT|PARTIAL|RECEIVED|CANCELLED], expected_at)
- `purchase_order_lines` (id, po_id, variant_id, qty_ordered, qty_received, unit_cost)
- `goods_receipts` (id, tenant_id, po_id, received_at, received_by)
- `goods_receipt_lines` (id, grn_id, variant_id, qty, batch_no, expiry_date, unit_cost)

**Key REST endpoints:**
- `POST /admin/suppliers`, `GET /admin/suppliers`
- `POST /admin/purchase-orders`, `GET /admin/purchase-orders`, `PUT /admin/purchase-orders/{id}`
- `POST /admin/purchase-orders/{id}/receive` — record GRN (qty, batch, expiry, cost)

**Events published:** `SupplierCreated`, `PurchaseOrderCreated`, `GoodsReceived`
**Events consumed:** —
**Talks to (sync):** product-svc (validate variants on a PO)
**Special rules:** `GoodsReceived` is what tells inventory-svc to create batches — purchase-svc never writes inventory tables itself (database-per-service). PO status moves to `PARTIAL`/`RECEIVED` based on received quantities.

---

### 9.10 customer-svc — Customer Profiles & Loyalty
**Purpose:** Everything about the buyer: profile, addresses, loyalty points, and a read-only view of their order history (built from events).

**Owns (tables):**
- `customers` (id, tenant_id?, user_id, name, email, phone, created_at)
- `addresses` (id, customer_id, type[SHIP|BILL], line1, city, state, pincode, is_default)
- `loyalty_ledger` (id, tenant_id, customer_id, points, reason, ref_order_id, created_at) — **append-only**
- `customer_order_history` (projection built from order events: order_id, total, status, at)

**Key REST endpoints:**
- `GET /customers/me` / `PUT /customers/me` — profile
- `POST /customers/me/addresses`, `GET /customers/me/addresses`
- `GET /customers/me/orders` — order history (from projection)
- `GET /customers/me/loyalty` — points balance + ledger
- `POST /customers/me/loyalty/redeem` — redeem points (emits event for pricing/order)

**Events published:** `CustomerProfileUpdated`, `LoyaltyAccrued`, `LoyaltyRedeemed`
**Events consumed:** `UserRegistered` (create profile), `OrderPlaced`/`OrderConfirmed` (accrue loyalty + update history)
**Talks to (sync):** —
**Special rules:** loyalty ledger append-only; order history is a **projection** (read model) — customer-svc never reads order-svc's tables, it builds its own copy from events.

---

### 9.11 notification-svc — Outbound Messaging
**Purpose:** Send emails, SMS, and push notifications in reaction to events. Purely a consumer + outbound integrator.

**Owns (tables):**
- `templates` (id, tenant_id?, key, channel, subject, body)
- `notification_log` (id, tenant_id?, to, channel, template_key, status, provider_ref, created_at)

**Key REST endpoints:**
- `POST /admin/notifications/templates` — manage templates
- `GET  /admin/notifications/log` — delivery log
- (mostly no public endpoints — it's event-driven)

**Events published:** — (it's a sink)
**Events consumed:** `UserRegistered` (welcome), `OtpRequested` (send code), `OrderPlaced`/`OrderConfirmed`/`OrderFulfilled` (notify customer), `LowStock`/`StockExpiringSoon` (notify staff)
**Talks to (sync):** external email (SMTP/SES), SMS (MSG91/Twilio), push (FCM)
**Special rules:** idempotent (don't send the same notification twice for a redelivered event); retries with backoff; per-tenant template overrides.

---

### 9.12 reporting-svc — Analytics & Reports (Read Model / CQRS)
**Purpose:** Answer business questions: what sold, what's running low, inventory value, tax summary. Builds **read-optimized projections** from events. Never writes to other services.

**Owns (tables):**
- `sales_facts` (tenant_id, store_id, channel, variant_id, qty, revenue, tax, at) — built from order/payment events
- `inventory_valuation` (tenant_id, store_id, variant_id, qty, cost_value, at) — from inventory events
- `low_stock_view`, `tax_summary` — denormalized projections

**Key REST endpoints:**
- `GET /admin/reports/sales?from=&to=&store=&channel=`
- `GET /admin/reports/inventory-valuation`
- `GET /admin/reports/low-stock`
- `GET /admin/reports/tax-summary`
- `GET /admin/reports/{name}/export` — CSV

**Events published:** —
**Events consumed:** `OrderConfirmed`, `PaymentCaptured`, `StockReceived`, `StockDeducted`, `StockAdjusted`, `GoodsReceived` (build all projections)
**Talks to (sync):** —
**Special rules:** **read-only and event-sourced** — reporting-svc is a pure consumer; it tolerates eventual consistency (numbers may lag a moment); heavy queries never touch transactional services.

---

## 10. How services talk to each other

### 10.1 Two channels, one rule of thumb
- **Need an answer right now to continue?** → **REST** (synchronous), via a discovery-resolved client in `client/`, always with a **timeout**, **retry**, and **fallback** (Fault Tolerance).
- **Just announcing something happened?** → **Event** (asynchronous) to **Kafka**, via `messaging/`.

### 10.2 Synchronous call map (who calls whom)

```
order-svc    ──REST──► pricing-svc      (quote)
order-svc    ──REST──► inventory-svc    (reserve / consume / release)
order-svc    ──REST──► payment-svc      (capture / refund)
cart-svc     ──REST──► pricing-svc      (live prices)
cart-svc     ──REST──► inventory-svc    (availability)
cart-svc     ──REST──► product-svc      (item display)
product-svc  ──REST──► inventory-svc    (stock flag on product page)
purchase-svc ──REST──► product-svc      (validate variant)
tenant-svc   ──REST──► iam-svc          (verify user when assigning staff)
```

### 10.3 Event map (who publishes → who reacts)

| Event | Publisher | Consumers |
|---|---|---|
| `UserRegistered` | iam-svc | customer-svc, notification-svc |
| `TenantCreated` | tenant-svc | iam-svc |
| `GoodsReceived` | purchase-svc | inventory-svc, reporting-svc |
| `StockReceived` | inventory-svc | reporting-svc, product-svc(opt) |
| `LowStock` / `StockExpiringSoon` | inventory-svc | notification-svc |
| `OrderPlaced` | order-svc | notification-svc, customer-svc, cart-svc, reporting-svc |
| `OrderConfirmed` | order-svc | inventory-svc, customer-svc, reporting-svc |
| `OrderCancelled` | order-svc | inventory-svc, payment-svc |
| `OrderReturned` | order-svc | inventory-svc, payment-svc, reporting-svc |
| `PaymentCaptured` | payment-svc | order-svc(opt), reporting-svc |
| `PaymentFailed` | payment-svc | order-svc |
| `LoyaltyAccrued` | customer-svc | notification-svc(opt) |

### 10.4 Reliability requirements on every call
- **REST:** timeout (e.g. 2s), limited retries with backoff, circuit breaker, and a sensible fallback (e.g. show product without live stock if inventory is briefly down). Helidon MP Fault Tolerance annotations.
- **Events:** at-least-once delivery; **idempotent consumers**; transactional **outbox** on the publish side; dead-letter topic for poison messages.

---

## 11. Build order & milestones

> **Build order ≠ runtime startup order.** This section is about the sequence in which **you, the developer, implement** the system (you must build the gateway before the services that sit behind it). It is **not** how services boot in production — there, services start in **any** order and gate on readiness (see §13). Don't confuse the two.

Build in this order. Do not start a phase until the previous one's exit check passes. (Mirrors PRD §10.)

| Phase | Build | Exit check |
|---|---|---|
| **0 — Foundation** | parent pom, service template, `shared/*`, **gateway**, **discovery**, **config**, docker-compose (postgres, kafka, consul, redis, zipkin, prometheus, grafana). One dummy service. | A request through the **gateway** reaches a **discovered** dummy service, config pulled **centrally**, trace visible in **Zipkin**, `/health` + `/metrics` green. |
| **1 — Back office** | iam-svc, tenant-svc, product-svc, inventory-svc, purchase-svc | Staff logs in → creates tenant + store → adds product → receives stock (GRN). `GoodsReceived → StockReceived` flows; inventory reflects it; tenant isolation proven. |
| **2 — Commerce core** | pricing-svc, cart-svc, order-svc, payment-svc | Customer completes an **online** order AND cashier completes a **POS** sale; both deduct the **same** inventory via the checkout saga; payment captured; **compensation works** when payment fails. |
| **3 — Experience & ops** | customer-svc, notification-svc, reporting-svc + frontends | Order confirmation sent; loyalty accrues; sales & inventory reports populate from events. |
| **4 — Hardening** | rate limits, circuit breakers, outbox everywhere, k8s manifests, load + security tests | Resilience and scale validated. |

---

## 12. Local development

> **Phase 0 is implemented.** The commands below work today against the scaffolded platform (gateway, discovery, config) + `sample-svc`. See [docs/SCAFFOLDING-STATUS.md](docs/SCAFFOLDING-STATUS.md) for current build status.

**Prerequisites:** **JDK 21** (Temurin), Maven 3.9+, Docker + Docker Compose.

> ⚠️ **Build & run with JDK 21**, not a newer JDK. Helidon 4 targets Java 21; set `JAVA_HOME` explicitly if your machine default differs:
> ```bash
> export JAVA_HOME=/usr/lib/jvm/temurin-21-jdk-amd64
> ```

**Fastest path — whole stack via compose:**
```bash
JAVA_HOME=$JAVA_HOME mvn clean install -DskipTests   # build jars + libs/ (Kafka runs in KRaft mode, no Zookeeper)
docker compose up -d --build                          # infra + config + sample-svc + gateway, readiness-gated
# Gateway is published on host port 8090 (8080 may be taken locally; override via GATEWAY_HOST_PORT).
curl -X POST http://localhost:8090/api/sample-svc/widgets \
  -H 'Content-Type: application/json' \
  -H 'X-Tenant-Id: 11111111-1111-1111-1111-111111111111' -d '{"name":"hello"}'
docker compose down
```

**1 — Start infrastructure**
```bash
docker compose up -d        # postgres, kafka, zookeeper, consul, redis, zipkin, prometheus, grafana
```

**2 — Build everything**
```bash
mvn clean install           # builds parent + all modules, runs tests
```

**3 — Run a single service** (each is a runnable Helidon app)
```bash
cd services/product-svc
mvn helidon:dev             # or: java -jar target/product-svc.jar
```

**3.1 — Build a native image**
```bash
export GRAALVM_HOME=/path/to/graalvm
export JAVA_HOME=$GRAALVM_HOME
cd services/product-svc
mvn -Pnative clean package
```
The resulting native binary will be in `target/product-svc`.

**4 — Run the gateway** (after at least one service + discovery are up)
```bash
cd platform/gateway
mvn helidon:dev
```

**5 — Verify the platform**
```bash
curl http://localhost:8080/health        # gateway health
curl http://localhost:8500/v1/agent/services   # Consul: registered services
# open Zipkin    http://localhost:9411
# open Grafana   http://localhost:3000
```

**Ports:** see PRD §8 (gateway 8080, config 8888, consul 8500, services 8001–8012, postgres 5432, kafka 9092, redis 6379, zipkin 9411, prometheus 9090, grafana 3000). Remember these per-service ports are a **local-dev convenience only** — in production every service listens on the same internal port; see §13.

### 12.1 GitHub Actions CI and release

This repository is configured with GitHub Actions for automated build and release.

- `ci.yml` runs on push and pull request events targeting `main` and `master`.
- `release.yml` runs when a tag matching `v*` is pushed.
- The release workflow builds the full Maven reactor and uploads generated module JARs as GitHub release assets.

To publish a release:
```bash
git tag v0.1.0
git push origin v0.1.0
```

### 12.2 Make docker-compose model readiness gating (not a race)

Even locally, don't let services start before Postgres/Kafka are actually accepting connections. Use **healthchecks** + `depends_on: condition: service_healthy` so compose waits for a dependency to be *healthy*, not merely *started*. This mirrors (in miniature) the production readiness gates in §13.

```yaml
services:
  postgres:
    image: postgres:16
    environment:
      POSTGRES_DB: shelfj
      POSTGRES_USER: shelfj
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD}
    healthcheck:                                   # "is Postgres actually ready?"
      test: ["CMD-SHELL", "pg_isready -U shelfj"]
      interval: 5s
      timeout: 3s
      retries: 10

  kafka:
    image: bitnami/kafka:latest
    healthcheck:                                   # "is the broker up?"
      test: ["CMD-SHELL", "kafka-topics.sh --bootstrap-server localhost:9092 --list || exit 1"]
      interval: 10s
      timeout: 5s
      retries: 10

  consul:
    image: hashicorp/consul:latest
    healthcheck:
      test: ["CMD", "consul", "members"]
      interval: 5s
      timeout: 3s
      retries: 10

  # ---- platform & business services wait for healthy infra ----
  config:
    build: ./platform/config
    depends_on:
      consul: { condition: service_healthy }

  product-svc:
    build: ./services/product-svc
    depends_on:
      postgres: { condition: service_healthy }     # don't boot until DB is READY
      kafka:    { condition: service_healthy }      # ...and Kafka is READY
      consul:   { condition: service_healthy }
      config:   { condition: service_started }
    healthcheck:                                   # expose the service's own readiness
      test: ["CMD-SHELL", "curl -fsS http://localhost:8080/health/ready || exit 1"]
      interval: 10s
      timeout: 5s
      retries: 12
      start_period: 30s                            # JVM cold-start grace (≈ startup probe)
```

> **Note:** `depends_on` only controls *start ordering between containers*. It is **not** a substitute for the service's own retry logic — a service must still survive its dependency disappearing *after* startup. Compose gating handles the cold boot; `@Retry`/`@CircuitBreaker` (§7.8, §13) handle everything after.

---

## 13. Production deployment & startup ordering

> **You asked: "the services start in an order like +1 — what's the industry standard?"** Short answer: **the `+1` port sequence is a local-dev convention, not a deploy order, and in production you do not order individual services at all.** This section is the standard.

### 13.1 The `8001…8012` ports are dev-only

Those sequential ports exist so all services can run on one laptop without colliding. In production this disappears:

| | Local dev (docker-compose) | Production (Kubernetes) |
|---|---|---|
| **Ports** | unique per service `8001…8012` | **every service on the same `containerPort` (8080)** — no collision because each pod has its own network namespace |
| **Addressing** | `localhost:<port>` | **k8s DNS** (`order-svc.shelfj.svc.cluster.local`) + Consul discovery — callers never use raw `host:port` |
| **Instances** | 1 each | **N replicas**, autoscaled (HPA) |

**Do not** carry the per-service `+1` ports into production manifests.

### 13.2 The real principle: don't order services — gate them on readiness

In a live cluster, services crash, restart, scale, and redeploy independently and constantly. You can **never guarantee** `pricing-svc` is up before `order-svc`. And the dependency graph is a **mesh, not a line** (see §10): `order-svc` needs pricing + inventory + payment; `cart-svc` needs pricing + inventory + product; etc. **No single linear order can satisfy a mesh.**

So the industry standard is: **every service starts in any order and becomes *ready* only when its dependencies are reachable.** The tools that replace ordering:

| Probe / mechanism | Question | Failing action | Endpoint / annotation |
|---|---|---|---|
| **Startup probe** | booted yet? | wait (don't kill during cold start) | `GET /health/started` |
| **Liveness probe** | alive? | **restart** the pod | `GET /health/live` |
| **Readiness probe** | can serve *now*? (DB+Kafka+config ok) | **stop routing traffic** (don't kill) | `GET /health/ready` |
| **Retry + backoff** | dep not up yet | keep retrying | Helidon MP `@Retry` |
| **Circuit breaker + fallback** | dep staying down | fail fast / degrade gracefully | Helidon MP `@CircuitBreaker` + `@Fallback` |

Result: **all 12 business services deploy in parallel.** Each flaps to *not ready* until its dependencies appear, then flips to *ready*; Kubernetes only sends traffic to ready instances. No human-defined sequence.

### 13.3 What DOES have an order: stages, enforced by readiness gates

Ordering exists only between **coarse infrastructure stages** (CD pipeline steps / Helm hooks), never between individual business services:

```
STAGE 0  Stateful infra      PostgreSQL · Kafka(+ZK/KRaft) · Consul · Redis · Zipkin · Prometheus
            └ gate: each passes its own healthcheck
STAGE 1  Platform            config → discovery → gateway
            └ gate: config answers before apps read settings
STAGE 2  DB migrations        Flyway as run-once Jobs / Helm pre-upgrade hooks   ← NOT inside app startup
            └ gate: migration Job succeeds
STAGE 3  Business services    iam · tenant · product · inventory · pricing · cart ·
                              order · payment · purchase · customer · notification · reporting
            └ DEPLOYED IN PARALLEL — each readiness-gated on its own DB+Kafka+config
STAGE 4  Frontends            storefront · admin-console · pos
```

- **Migrations are run-once `Job`s (or `pre-install`/`pre-upgrade` Helm hooks), never run inside a service's own boot** — otherwise N replicas race to migrate the same database. Gate STAGE 3 on the Job succeeding.
- A business service starting before its DB/Kafka exists is **fine** — it stays *not ready* and retries.

### 13.4 Rollouts

- Each service is **independently versioned and deployed** via **rolling update** (default), or **blue-green / canary** for risky changes — never a synchronized "restart everything."
- Config & secrets come from the config service + **k8s Secrets / Vault**, injected at deploy — never baked into images.

> **One-line takeaway:** *Deploy infra → platform → migrations → all business services in parallel → frontends. Within the business tier there is no order — readiness probes, retries, and circuit breakers make a startup sequence unnecessary. The `+1` ports are just so it runs on your laptop.*

---

## 14. Definition of Done for any service

A service is **not finished** until **all** of these are true. An AI agent should self-check against this list before declaring a service complete.

- [ ] Its own PostgreSQL schema, created via **Flyway** migrations (no manual DDL).
- [ ] No access to any other service's database.
- [ ] REST endpoints exposed under the gateway; **DTOs** in/out (never JPA entities).
- [ ] Input **validated** at the boundary; correct HTTP status codes; standard **error envelope**.
- [ ] `tenant_id` read from JWT; every tenant query filtered by it first.
- [ ] Registers with **discovery** on startup; resolves other services via discovery.
- [ ] Reads config from **config service / env**; no secrets in code or image.
- [ ] Publishes its events via **outbox** after commit; consumers are **idempotent**.
- [ ] Append-only tables are truly append-only.
- [ ] Money = `BigDecimal`/`NUMERIC`; timestamps = UTC `timestamptz`.
- [ ] `/health/started`, `/health/live`, `/health/ready`, `/metrics` present; **readiness probe checks real dependencies** (DB + Kafka + config); trace context + `X-Request-Id` propagated.
- [ ] **Starts in any order** — survives its DB/Kafka/dependencies being absent at boot (stays *not ready* and retries, does not crash-loop).
- [ ] Synchronous calls have timeout + retry + circuit breaker + fallback.
- [ ] **Unit tests** for logic + **integration test** (Testcontainers) for the core flow.
- [ ] Builds clean with `mvn clean install`; containerizes; starts in docker-compose.
- [ ] Follows the [golden rules](#3-the-golden-rules-an-ai-agent-must-follow-these).

---

## 15. Glossary

| Term | Meaning |
|---|---|
| **Microservice** | Small independent program owning one capability and its own DB. |
| **Gateway** | The single public entry point that routes to internal services. |
| **Service discovery / Consul** | The registry where services announce and find each other. |
| **Config service** | Central place serving each service's settings. |
| **Event** | A past-tense record that something happened (`OrderPlaced`), sent over Kafka. |
| **Kafka** | The message bus carrying events between services. |
| **Topic** | A named stream in Kafka where events of one kind are published. |
| **Producer / Consumer** | A service that publishes / reads events. |
| **Saga** | A multi-service workflow with compensation if a step fails. |
| **Compensation** | Undoing earlier saga steps (e.g. release reserved stock) when a later step fails. |
| **Outbox** | A DB table where events are written in the same transaction as the data, then drained to Kafka — guarantees the event and the data agree. |
| **Idempotent** | Doing the same operation twice has the same effect as once. |
| **Idempotency-Key** | A header used to safely retry a write without duplicating it. |
| **Reservation** | Temporarily holding stock during checkout so two buyers can't claim the same unit. |
| **FIFO** | First-In-First-Out — sell/deduct the oldest (or soonest-expiring) batch first. |
| **GRN** | Goods Receipt Note — the record of stock actually received from a supplier. |
| **Tenant** | One business using the platform; its data is isolated from others. |
| **Multi-tenant** | Many businesses share the platform but never see each other's data. |
| **JWT** | A signed token proving who the caller is and what they may do. |
| **DTO** | Data Transfer Object — the shape of data over the API (separate from DB entities). |
| **JPA / Hibernate** | The Java way to map objects to database tables. |
| **Flyway** | Tool that applies versioned SQL migrations. |
| **CQRS / read model / projection** | Building a separate, read-optimized copy of data (used by reporting-svc and customer history) from events. |
| **Helidon MP** | The MicroProfile-based Java framework we build each service with. |
| **POS** | Point of Sale — the in-store checkout/cash-register screen. |
| **Storefront** | The public website where customers browse and buy. |
| **Liveness probe** | Health check: "is the process alive?" Failing → orchestrator restarts the pod. |
| **Readiness probe** | Health check: "can it serve traffic *now*?" (deps reachable). Failing → no traffic routed, but pod not killed. |
| **Startup probe** | Health check giving a slow-booting process grace time before liveness applies. |
| **Kubernetes (k8s)** | Production orchestrator that runs/scales containers and routes traffic only to *ready* pods. |
| **Pod** | The smallest deployable unit in Kubernetes — one (or few) containers with their own network namespace. |
| **HPA** | Horizontal Pod Autoscaler — adds/removes replicas based on load (CPU, latency, queue lag). |
| **Rolling update / blue-green / canary** | Strategies to deploy a new version with zero downtime, one service at a time. |
| **Migration Job** | A run-once task (k8s `Job` / Helm hook) that applies Flyway migrations *before* services start — never inside app boot. |
| **Readiness gate / stage** | A deploy checkpoint: a stage proceeds only when the previous stage is healthy (infra → platform → migrations → services). |

---

*Companion document: [PRD.md](PRD.md) — product requirements, architecture rationale, roadmap, open decisions.*
