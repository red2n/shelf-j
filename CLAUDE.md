# CLAUDE.md

This file is auto-loaded into every Claude Code session for this repository. It gives an AI agent the context and rules needed to work on StoreQL correctly. **Read it fully before making changes.**

> **Deep docs:** [PRD.md](PRD.md) = what & why · [README.md](README.md) = the product deep dive (features, personas, workflows) · [docs/API-GUIDE.md](docs/API-GUIDE.md) = full API surface by business capability · [docs/UI-GUIDE.md](docs/UI-GUIDE.md) = full UI surface by persona/screen · [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) = how (concepts, per-service catalog, conventions) · [docs/onboarding-and-locations.md](docs/onboarding-and-locations.md) = tenant onboarding + location model · [docs/coding-standards.md](docs/coding-standards.md) = SQL rules + SOLID rules (enforced on every change) · [docs/RELEASE-PROCESS.md](docs/RELEASE-PROCESS.md) = version tags, what fires on a tag push, GHCR retention. When detail is needed, open those. This file is the fast briefing + the hard rules.

---

## What this project is

**StoreQL** — a **multi-tenant SaaS** stock & store management platform that also lets **customers buy products** (public online storefront **and** in-store POS). Built as **strict microservices** on **Helidon MP (Java 21)**, behind an **API gateway**, with **service discovery (Consul)**, **centralized config**, and **Kafka** events. Architecture patterns borrowed from [red2n/home](https://github.com/red2n/home) (which is Spring Cloud) but **re-implemented in Helidon MP**.

**Status:** not a design-phase repo — a working platform (12 business services + gateway/discovery/config, ~290 REST endpoints, a 4-shell Flutter frontend). See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) Status section for specifics. Follow the templates and rules below exactly when changing or extending it.

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

These are the condensed form of [ARCHITECTURE §6](docs/ARCHITECTURE.md#6-the-golden-rules). Violating one is a bug even if the code runs. **SQL and SOLID rules are in [docs/coding-standards.md](docs/coding-standards.md) and must be applied to every change.**

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
**Business services** (`services/`): `iam-svc`, `tenant-svc`, `product-svc`, `inventory-svc`, `pricing-svc`, `cart-svc`, `order-svc`, `payment-svc`, `purchase-svc`, `customer-svc`, `notification-svc`, `reporting-svc`. Full endpoint catalog: [docs/API-GUIDE.md](docs/API-GUIDE.md). Owned tables/events per service: [ARCHITECTURE §10](docs/ARCHITECTURE.md#10-the-business-services).

**Channel-sharing rule:** online checkout and in-store POS both go through **the same `order-svc`** — only `channel` (`ONLINE`/`POS`) and `fulfilment_type` differ, so inventory/payments/reporting behave identically across channels.

---

## Repository layout

```
storeql/
├── pom.xml                  # parent: Java 21, Helidon BOM
├── docker-compose.yml       # postgres, kafka, consul, redis, zipkin, prometheus, grafana (with healthchecks)
├── platform/                # gateway, discovery, config
├── services/                # the 12 business microservices (one Maven module each)
├── shared/                  # contracts + shared infra, NO business logic: common-ids (`Ids.newId()`), events-contract, common-web, common-service (DataSource/Flyway/Consul/outbox/health base — reuse it, never re-implement), common-test, einvoice (EN 16931 UBL/CII/Factur-X read, write and rules)
├── frontends/               # storeql-app: ONE Flutter app with four shells (storefront, admin, POS, platform console)
├── intent/                  # one page per feature, before code and kept after: problem, scope, open questions, acceptance, decisions (TEMPLATE.md)
├── docs/                    # ARCHITECTURE.md, API-GUIDE.md, UI-GUIDE.md, onboarding-and-locations.md, coding-standards.md, …
├── PRD.md  README.md  CLAUDE.md
└── .claude/skills/          # invokable skills (capture-intent, scaffold-service, add-endpoint, add-event, onboard-tenant)
```

Per-service internal shape (copy for each): `api/ dto/ service/ domain/ repo/ messaging/ client/ mapper/ config/` + `resources/db/migration/` (Flyway). See [ARCHITECTURE §7](docs/ARCHITECTURE.md#7-anatomy-of-one-service).

---

## Conventions cheat-sheet (full list: [ARCHITECTURE §14](docs/ARCHITECTURE.md#14-cross-cutting-conventions))

- **Response envelope:** `{ "data": ..., "error": ..., "meta": { "requestId", "nextCursor" } }` for data.
- **Errors:** correct HTTP codes; stable machine `code` (e.g. `INVENTORY_INSUFFICIENT_STOCK`); never leak stack/SQL. On the wire every error is **RFC 9457 problem details** (`application/problem+json`: `type` = `urn:storeql:problem:CODE`, `title`, `status`, `detail`, `instance`, plus `code`, `details`, `requestId` and the legacy `error`/`meta` members) — produced once by `ProblemResponseFilter` in common-web from any `ApiResponse.error(...)`, so services keep throwing `ApiException` and never build problems by hand. API descriptions are OpenAPI 3.1 (`mp.openapi.extensions.smallrye.openapi=3.1.0`, static files `openapi: 3.1.0`, a shared `Problem` schema).
- **Second factors (20.12):** sign-in may answer `mfaRequired` (no tokens; answer at `POST /auth/mfa/login`) or `mfaEnrolmentRequired` (a `scope: mfa-enrol` token the gateway confines to `/auth/mfa/**`). Anything that signs in — a client, a test, a k6 helper — must handle both; the platform administrator always has a factor (`PLATFORM_ADMIN_TOTP_SECRET`; k6: `platformAdmin()`/`totp()` in the lib). A wrong second-factor answer counts as a failed sign-in at the gateway's brute-force filter.
- **Tokens:** access tokens are RS256 under a rotating key that only iam-svc holds (`signing_keys`, private halves sealed under `storeql.jwt.secret`, which no other service is given); each names its key (`kid`) and the public halves are at `GET /auth/.well-known/jwks.json`. The gateway (`SigningKeySet`) and the MQTT broker verify against that set — never hand a service a signing secret, and never verify without pinning RS256 and looking the key up by `kid`.
- **Release supply chain (22.10):** every published image is built with SBOM + max-mode provenance and gets, by digest, a CycloneDX SBOM attestation, a SLSA provenance attestation and a keyless cosign signature; a release carries the reactor SBOM (`scripts/sbom.sh`), checksums and jar provenance. `scripts/supply-chain-check.py` (CI job `supply-chain`) fails the build if a workflow edit drops any of it — when adding a published image, add it to the publish matrix, the cleanup matrix **and** `scripts/verify-release.sh`. Details: [docs/RELEASE-PROCESS.md](docs/RELEASE-PROCESS.md).
- **Backups and restore:** the `backup` compose service / `16-backup.yaml` CronJob (image `infra/backup`) dumps Postgres nightly with a same-snapshot manifest, encrypted to `STOREQL_BACKUP_RECIPIENT` (age), and Postgres archives WAL for point-in-time recovery. **Anything that changes the schema in a way a restore could trip on is proved by `scripts/backup-drill.sh`**, which restores a real backup and recovers to a moment against the running stack; `scripts/backup-selftest.sh` is its CI form. A function used in a CHECK must bind its references at creation (`BEGIN ATOMIC`), because `pg_restore` runs with an empty search path. Details: [docs/BACKUP-AND-RESTORE.md](docs/BACKUP-AND-RESTORE.md).
- **Dependencies (22.11):** never pin a library version in a module when the parent (ours or Helidon's) manages it — raise it once in the parent pom, through Helidon's `version.lib.*` property where there is one, with the advisory named beside it. `scripts/vuln-scan.sh deps` must pass before a push (it fails at High); an exception is a dated, reasoned entry in `security/vulnerability-exceptions.yaml`, never a loosened threshold.
- **Plan limits (21.8):** a plan's allowance is enforced by the service that **owns the thing counted**, never by tenant-svc on its behalf — stores and staff in tenant-svc from its own tables, products in product-svc through `Entitlements` (a cached read of `/admin/tenant/plan/limits`); API requests a minute (`requests.per-minute`) at the gateway, the one door, through `TenantRateLimitFilter`; megabytes of product images (`images.mb.max`) in product-svc and of supplier e-invoice documents (`documents.mb.max`) in purchase-svc through `Entitlements.requireBytesWithin`, measured as the write would leave them (21.11). Adding an entitlement key means adding the refusal that enforces it: `Plans.CATALOGUE` is the list, and a key outside it is refused rather than promised. Limits fail **open** (no plan, or allowances unreadable → unrestricted), unlike spend authority, which fails closed.
- **API versions and the sandbox (22.8):** every route is `/api/v1/{service}/…`; the unversioned alias is deprecated and dated (`Deprecation`/`Sunset`/`Link` on every answer, `410` from the sunset), a version nobody published is `404 API_VERSION_UNKNOWN`, and `GET /api/versions` says so publicly — the policy is `ApiVersions` in the gateway and four `storeql.gateway.api.*` keys; a breaking change is a new version, never a change to `v1`. A business's sandbox is a **tenant of its own** (`mode: SANDBOX`, `sandboxOf`), owned by tenant-svc and announced in `TenantCreated`; every service that must behave differently there reads `TenantProfiles.Profile.sandbox()` — never a flag on a request — and today that is notification-svc (nothing leaves but in-app, logged `SUPPRESSED`) and payment-svc (MANUAL provider). Keys for it start `sqk_test_`; a token for it has `amr: [sandbox]` and no refresh. Details: [docs/API-VERSIONING.md](docs/API-VERSIONING.md).
- **Accounting connectors (17.9):** purchase-svc pushes every ledger journal to the business's package (Xero, QuickBooks Online, Sage, or `SIMULATED`) through `client.accounting.AccountingPackage` drivers — the only code that knows a package's URL, headers or JSON; `service/` stays HTTP-free. One journal → one journal in the package, once (the unique pair on `accounting_syncs`); tokens sealed under `storeql.accounting.secrets-key`; a push that may have landed on a package with no idempotency key and got no answer is `UNCERTAIN` and waits for a person, never the clock. Adding a package means a driver with a stub-backed test of its exact request shape, a row in `Accounting.CATALOGUE`, and the `ck_accounting_provider` check. Details: [docs/ACCOUNTING-CONNECTORS.md](docs/ACCOUNTING-CONNECTORS.md).
- **UI theme (23 Sep 2026):** `frontends/storeql-app/lib/core/theme.dart` mirrors the **Claude design system** (artifact `2507697b-5dab-4a2e-8ac9-2a27b0d8b4fb`, brand book at `project/README.md`) one-to-one — every Material 3 role written out for both brightnesses, "warm and quiet". Build screens from the roles and the tokens (`AppRadius`, `AppSpacing`, `context.status.*` with its `*Container` pairs, `context.channelAccent` in POS); never a hard-coded colour or `BorderRadius.circular(<number>)` in a feature (the four exceptions are listed in [docs/UI-GUIDE.md](docs/UI-GUIDE.md) §7.1). `test/core/theme_contrast_test.dart` measures every text pair at 4.5:1 in both themes and fails a palette change that breaks one. Shared states: `EmptyState`, `ErrorView`, `LoadingView`.
- **Exchange rates (03.x):** a business keeps one home currency (`TenantProfiles`) and its own dated, append-only rate table in tenant-svc (`GET /admin/tenant/fx-rates`, home units per one unit of the other currency); every service reads it through common-service's cached `FxRates` and converts only through `Fx` (rounded to the target's minor units). A price is **shown** in another currency (`displayCurrency` → `display`), never charged in it; a foreign-currency spend ceiling is **translated** at the business's rate and **fails closed** without one; the platform fetches no rates and guesses none.
- **Price zones and repricing (03.x):** a `price_zones` row groups the stores that price alike (`price_zone_stores`, a store in **one zone at most**; store ids are tenant-svc's, checked through `TenantProfiles.stores`, never joined); `price_lists.zone_id` binds a list to a zone and `PricingRepository.resolveBasePrice(…, storeId)` prefers the store's zone's list over the tenant-wide one and never reaches another zone's — **pass the store** wherever a price is resolved. Competitor sightings (`competitor_prices`) are append-only and in the home currency; a repricing rule **proposes** (`Repricing`, pure: undercut only, floor as a share of the current price because pricing-svc holds no cost, `.99` reached only by rounding down) and a person **applies** — a proposal is decided once, and the apply writes the price through `PricingRepository.writePriceListItemTx` on the same transaction so the `PriceChanged` event and price history are never skipped.
- **Consignment stock ownership:** a batch says whose it is (`inventory_batches.ownership` OWNED|CONSIGNMENT + `owner_supplier_id`, purchase-svc's supplier referenced never joined) and ownership rides with the stock (`Provenance.Drawn`, lot splits, transfers); the valuation's `value` is the business's own and consignment is reported apart. A purchase order carries `ownership` too: a consignment receipt tells inventory-svc (`GoodsReceived.ownership/supplierId`) and **posts nothing** — the debt arises when `deductBatches` draws a SALE from a consignment batch and publishes `ConsignmentStockSold` on the deduction's own transaction; purchase-svc records each once (`consignment_sales`, Dr 5010 / Cr 2100) and `POST /admin/consignment/settlements` gathers a period's sales into a statement, a sale settled once. Never invoice a consignment order on receipt (`PURCHASE_CONSIGNMENT_NOT_INVOICED`). **Dropship** is stock the business never holds: purchase-svc's `dropship_arrangements` (one live per variant) publish `VariantSourcingChanged`, inventory-svc keeps it in `variant_sourcing` and answers available/`dropship: true` with no batch, places a `fulfilment: DROPSHIP` hold that draws nothing and deducts nothing on fulfilment; `OrderConfirmed` carries `fulfilmentType`/`deliveryAddress`/recipient so purchase-svc can raise one DRAFT `source: DROPSHIP` order per supplier (`salesOrderId`, `shipTo`), never received (`PURCHASE_DROPSHIP_NOT_RECEIVED`) but `dropship-delivered` (Dr 5020 / Cr 2109).
- **Bonded and duty-suspended stock:** a batch says whether its duty is paid (`inventory_batches.duty_status` DUTY_PAID|DUTY_SUSPENDED); duty-suspended stock arrives only into a store with a live `bond_approvals` row (`INVENTORY_STORE_NOT_BONDED`), counts in `onHand` and `inBond` but never `available`, is never drawn by a hold or a sale (`deductBatches` filters by move type: BOND_RELEASE draws suspended, ADJUST either, everything else duty-paid), and is valued at cost without the duty with `dutyPotential` beside it. The platform derives no duty: management sets `excise_duty_rates` per variant in the home currency; `BondService.release` draws suspended batches as `BOND_RELEASE`, makes a duty-paid batch per draw (`Provenance.released`, same lot and cost), writes `bond_releases` and publishes `DutyReleased` on the same transaction; purchase-svc records each once (`duty_releases`, Dr 5030 / Cr 2140 dated the release). A purchase order carries `duty_status` and `GoodsReceived.dutyStatus`.
- **Fresh yield and butchery loss:** a `yield_templates` row says what a primal breaks into (`yield_template_outputs`: expected share of the input, cost share defaulting to it, optional shelf life); `YieldService.record` → `YieldRepository.record` on one transaction draws the primal as `MoveType.YIELD` (`deductBatches`, oldest first; a consignment draw is `INVENTORY_YIELD_INPUT_NOT_OWNED`, too little `INVENTORY_YIELD_INSUFFICIENT_INPUT` 422), makes a batch per cut under the primal's lot (`TRANSFORM` genealogy link; cost = `Yield.apportion`, pure, two decimals; the loss carries no cost), writes `yield_runs`/`yield_run_outputs` and announces `StockAdjusted` (the primal), `StockReceived` (each cut) and `YieldRecorded`. Quantities stay in the primal's unit; more out than in is refused (`INVENTORY_YIELD_OUTPUT_EXCEEDS_INPUT`). The loss is not shrinkage: it is reported against expected by `GET /admin/inventory/yield/runs`.
- **Wave picking and directed putaway** ([intent](intent/wave-picking-and-directed-putaway.md)): inventory-svc projects confirmed ONLINE PICKUP/DELIVERY orders from `OrderConfirmed` into `awaiting_orders` (cleared by `OrderFulfilled`/`OrderCancelled`; a till sale is never waved); `Waves.plan` (pure) allocates the waiting lines to batches in the resolved picking rule's order and walks the zones by the rule's priorities; `WaveRepository.complete` deducts exactly the picked batches as SALE movements against each order, reduces the holds, writes `wave_picked_lines` and publishes `WavePicked` on one transaction, and order-svc's `WavePickedHandler` fulfils each order for the picked quantities once per event (partial where short). **The `OrderFulfilled` that follows must not deduct twice:** `OrderEventHandler` asks `WaveService.revenueOnlyIfPickedByWave` first (revenue recorded, the pick acknowledged, nothing drawn). Every `insertBatch` with no zone runs `PutawayRepository.directTx`: a `putaway_rules` match (product, else store default) sets the zone at once; none raises a `putaway_tasks` row for a person. Waves, picks and placements need `stock.transfer`; rules need management.
- **Depot / DC replenishment** ([intent](intent/depot-dc-replenishment.md)): only a store of type `WAREHOUSE` serves, and only shops (tenant-svc refuses any other type; inventory-svc reads types through `TenantProfiles.Stores.isWarehouse`, never joined). inventory-svc owns the network (`serving_relationships`, one warehouse per shop; `serving_exceptions`, products a shop buys direct) and `NetworkService.run` proposes one DRAFT transfer per shop by pure `DcReplenishment` (reorder point + lead + cover; fair share when short, remainder to the least cover), a person releasing each (`POST /transfers/{id}/release`). **purchase-svc must never buy for a shop a warehouse serves**: its proposal reads `GET /admin/inventory/network/sourcing` first, keeps only a served shop's direct products, and buys for a warehouse on its shops' demand, less what is committed to them.
- **Supplier lead times and scorecards:** `purchase_orders.submitted_at` is stamped when an order becomes SUBMITTED (submit or approval) and `suppliers.lead_time_days` is the quote; `PurchaseRepository.createGoodsReceipt` records a `supplier_deliveries` fact on the receipt's transaction through `SupplierPerformanceRepository.recordDeliveryTx` (promise = the order's `expected_delivery`, else `submitted_at` + quote, else none; lead days never negative; late days null without a promise). The card is pure `SupplierScorecard` (on time 40, fill 30, quality 20, invoice accuracy 10, renormalised over the parts known; `pct` of nothing is null, never zero; grades A/B/C/D at 90/75/60) over `GET /suppliers/scorecards`, `/suppliers/{id}/scorecard`, `/suppliers/{id}/deliveries` (management; period defaults to the last 90 days, `PURCHASE_PERIOD_INVALID`). Fill counts only orders that reached their end (RECEIVED or CLOSED); a part-received order is not yet a fill rate.
- **RFQ and sourcing:** `rfqs`/`rfq_lines`/`rfq_suppliers`/`rfq_quote_lines`/`rfq_awards` (purchase-svc, series `rfq_series`); quotes are recorded by the buyer in the supplier's currency and compared by pure `Rfq.compare` at home through `FxRates` (a price with no rate is shown, never lowest; a partial or untranslatable bid is shown, never ranked); each bid carries the supplier's scorecard grade. `RfqService.award` gives each line to a supplier who priced it (`PURCHASE_RFQ_NOT_QUOTED`), raises one DRAFT `PO_SOURCE_RFQ` order per supplier at the quoted prices (orders first, the award row last, so a lost race leaves cancellable drafts, never an award without orders), and a request is awarded once (`PURCHASE_RFQ_NOT_ISSUED`) and not cancelled after (`PURCHASE_RFQ_CLOSED`). Writes need OWNER/MANAGER/STOREKEEPER.
- **Pagination:** cursor only (`?after=&limit=`), default 20 / max 100. No page numbers.
- **Naming:** REST paths = plural kebab nouns (`/purchase-orders`); JSON = `camelCase`; DB columns = `snake_case`; events = `PascalCase` past tense (`OrderPlaced`); Kafka topics = `storeql.<domain>.<event>`.
- **IDs:** RFC 9562 UUIDv7 only — minted, accepted and stored. Mint in the service with `Ids.newId()` (`shared/common-ids`); deterministic keys with `Ids.derived(eventId, name)`; **read every id with `Ids.parse(text)`** (canonical form, version 7, RFC variant — never `UUID.fromString`, which takes any version and `1-1-1-1-1`). Never `UUID.randomUUID()`/`nameUUIDFromBytes()`/`new UUID(…)`, never `gen_random_uuid()` in SQL, never a column `DEFAULT` that fills in a uuid — every `INSERT` binds its `id`. An id of another version is `400 INVALID_UUID` wherever it arrives (path, query, header, body — `common-web` does it for every service), and an `Idempotency-Key` must be a UUIDv7 too (`400 IDEMPOTENCY_KEY_INVALID`, header or body; normalised to lowercase; the app uses `core/ids.dart`, k6 `newKey()`) — including a key a service makes for itself or sends another service, which is `Ids.derived(id, "step")`, never `"prefix:" + id`. Enforced in layers: PMD (`UseTimeOrderedIds`, `NoDatabaseMintedIds`, `ParseIdsAsV7`) on main code, ArchUnit `IDS_ARE_V7` on tests too, a database `CHECK` on every uuid column and every `idempotency_key` column (common-service `afterMigrate__uuid_v7_everywhere.sql`), and the integration-test audit (`PostgresSupport.stop()`: every uuid column v7, every one and every key column guarded) ([ARCHITECTURE §14](docs/ARCHITECTURE.md#14-cross-cutting-conventions)).
- **Migrations:** Flyway only (`V<n>__desc.sql`); never manual DDL in prod.
- **Tests:** unit for `service/` logic + Testcontainers integration for the core flow (Postgres + Kafka). Not done without it. End-to-end: `k6/run.sh` against the dockerized stack — the two **flow-guard** suites (`flow-guard-comprehensive`, `flow-guard-runtime`) must stay green after any change to onboarding, tenant/store status, authorization, carts, orders or POS sessions ([k6/README.md](k6/README.md)).

---

## Production deployment & startup ordering (the thing that surprises people)

- The per-service ports `8001…8012` are a **LOCAL-DEV convenience only**. In production every service listens on the **same port (8080)**; addressing is by **k8s DNS + Consul**, never `host:port`. Don't put `+1` ports in prod manifests.
- **Do NOT order individual services at startup.** The dependency graph is a mesh; no linear order works. Instead services **start in any order and gate on readiness** (probes + `@Retry` + `@CircuitBreaker`).
- Ordering exists only between **stages**: `infra → platform (config/discovery/gateway) → DB migrations (run-once Jobs) → all business services in parallel → frontends`.
- Full model: [ARCHITECTURE §17](docs/ARCHITECTURE.md#17-production-deployment--startup-ordering) and [PRD §9](PRD.md).

---

## Build order (for the developer, ≠ runtime order)

Phase 0 foundation (gateway/discovery/config + template) → Phase 1 back-office (iam, tenant, product, inventory, purchase) → Phase 2 commerce (pricing, cart, order, payment) → Phase 3 experience (customer, notification, reporting + frontends) → Phase 4 hardening. Each phase has an exit check — see [PRD §10](PRD.md).

---

## How to do common tasks (skills)

Invokable skills live in `.claude/skills/`. Prefer them for consistency:

| Skill | Use when |
|---|---|
| `capture-intent` | Starting a roadmap item or any new feature: writes `intent/<slug>.md`, gets its open questions answered before code, keeps its Decisions while building. |
| `scaffold-service` | Creating a brand-new business microservice (sets up module, layers, Flyway, health, config, registration). |
| `add-endpoint` | Adding a REST endpoint to an existing service (enforces layering, envelope, validation, tenant rule). |
| `add-event` | Adding a Kafka event (producer via outbox + idempotent consumer + contract in `events-contract`). |
| `onboard-tenant` | Implementing/walking the client onboarding + Tenant→Stores→Zones location-mapping flow. |

**Intent before code.** A roadmap item or new feature starts with `capture-intent`. No code is written until its `intent/<slug>.md` is `CONFIRMED`, meaning the user has answered every open question. Before changing an area, run `grep -ril "<table|event|screen>" intent/` and read what comes back: its **Decisions** and **Out, on purpose** hold the "why" the code doesn't show. Bug fixes and small follow-ups don't get a page; they start from a failing test.

---

## Definition of Done (before declaring any service complete)

Self-check against [ARCHITECTURE §19](docs/ARCHITECTURE.md#19-definition-of-done). Highlights: own schema via Flyway · no cross-service DB access · DTOs in/out · tenant filtering · discovery registration · external config · outbox + idempotent consumers · 3 health probes (ready checks deps) · starts in any order · sync calls have timeout/retry/breaker/fallback · unit + Testcontainers tests · builds with `mvn clean install` · runs in docker-compose.

For a feature, also: its `intent/<slug>.md` is `BUILT`, every Acceptance line is ticked with the test that proves it, and its Decisions are recorded.

---

## When unsure

- **Where does X live / who owns this data?** → [docs/API-GUIDE.md](docs/API-GUIDE.md) (full endpoint catalog) or [ARCHITECTURE §10](docs/ARCHITECTURE.md#10-the-business-services) (owned tables/events summary).
- **How do services talk for this flow?** → [ARCHITECTURE §11](docs/ARCHITECTURE.md#11-how-services-talk-to-each-other) (sync map + event map) and [§12](docs/ARCHITECTURE.md#12-key-workflows) (checkout saga).
- **Onboarding / stores / zones / delivery?** → [docs/onboarding-and-locations.md](docs/onboarding-and-locations.md).
- **SQL or SOLID rule question?** → [docs/coding-standards.md](docs/coding-standards.md).
- **Cutting a release / tagging a version?** → [docs/RELEASE-PROCESS.md](docs/RELEASE-PROCESS.md).
- **Why is a feature shaped this way, or what was left out on purpose?** → its page in [intent/](intent/) (Decisions; Scope → Out, on purpose).
- **A decision isn't settled?** → [PRD §11 open questions](PRD.md), or the feature's intent page under Open questions. Don't silently guess on those; surface them.
