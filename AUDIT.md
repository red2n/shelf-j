# Shelf-J — Industry-Standard Deep-Dive Audit

**Scope:** Independent assessment of the *actual* codebase (not the spec docs) against industry standards for **API design** and **UI/UX**.
**Method:** Read the cross-cutting foundation, the security boundary, the largest business service, the Flutter core + representative screens, then verified edge cases with targeted greps.
**Date:** 2026-06-23 · **Branch:** `testcaseimprovement`

> This document supersedes the previous feature-completeness/Oracle-gap audit. It is a *quality* audit: how the system measures up to industry norms, not how many features exist.

---

## Snapshot of what was reviewed

| Area | Reality on disk |
|---|---|
| Backend | 12 Helidon MP services, ~290 endpoint annotations, 257 Java files, 65 Flyway migrations |
| Frontend | Flutter app, 4 shells (admin / POS / storefront / platform), 55 Dart files, Riverpod + go_router + dio |
| Platform | gateway (JWT/CORS/rate-limit/brute-force), Consul, PgBouncer, Kafka outbox, k6 load tests |
| Tests | 31 backend test files (Testcontainers); **0 frontend tests** |

---

## Executive verdict

The system is **well past "design phase"** — it is a working, sizeable platform. The **backend is close to production-grade**; the **frontend is functional and well-structured but roughly one tier below the backend** on industry UI/UX standards. The gap between the two is the headline finding.

**Blocking-for-credible-launch items:** A1 (no versioning), A2 (no OpenAPI), A3 (malformed-UUID → 500), U1 (raw UUIDs in UI), U2 (no server-side pagination in UI), U4 (structured errors discarded).

---

# PART 1 — API / Backend

## Strengths (already at standard) ✅

- **Security boundary is excellent.** `platform/gateway/.../filters/JwtAuthFilter.java` strips client-supplied identity headers *before* stamping verified ones, fails closed, refuses to boot on a weak/short JWT secret, and scopes every storefront/onboarding exception with documented reasoning. Brute-force, rate-limit, CORS, and security-headers filters all exist **with tests**.
- **Consistent contract:** single response envelope (`shared/common-web/.../ApiResponse.java`), stable machine error codes, sanitized 500s that never leak SQL (`GenericExceptionMapper.java`), opaque base64 keyset cursors (`Cursor.java`).
- **Correctness fundamentals:** idempotency keys with replay-returns-original (`OrderService.placeOrder`), transactional outbox with `FOR UPDATE SKIP LOCKED`, idempotent consumers, server-side price enforcement (client `unitPrice` ignored when enforcement on), tenant-from-JWT isolation, `BigDecimal`/`timestamptz`, Testcontainers integration tests.

## Findings (priority order)

### A1 — No API versioning 🔴 (blocking)
There are zero `/v1` path prefixes anywhere. For a multi-tenant SaaS with external storefront/POS clients, the first breaking change breaks every client at once. Introduce a version segment now (cheap today, very expensive later).
- **Fix:** prefix routes with `/v1`; reserve a deprecation/sunset header policy.

### A2 — No OpenAPI / Swagger spec 🔴 (blocking)
No machine-readable contract exists for an explicitly "API-first" platform. Consequences: no generated docs, no generated typed clients (the Flutter app hand-writes every call and re-derives the envelope), no contract tests.
- **Fix:** enable MicroProfile OpenAPI (near-free in Helidon MP); generate the Dart client from the resulting spec.

### A3 — Malformed UUID returns HTTP 500, not 400 🔴 (blocking, real bug)
Service methods call `UUID.fromString(req.storeId())` (e.g. `OrderService.java:65` and dozens of sites) with no guard. A bad UUID throws `IllegalArgumentException`, which falls through to `GenericExceptionMapper` → `500 INTERNAL_ERROR`. This violates golden rule #15 ("reject bad input with 400"), pollutes error budgets/alerting, and is reachable from untrusted input.
- **Fix:** validate at the boundary (declarative `@Valid` + a `UUID` constraint/param converter), or add an `ExceptionMapper<IllegalArgumentException>` → 400. Boundary validation is preferred.

### A4 — Boundary validation has gaps (finding corrected on deeper review) 🟠 ✅ addressed
**Correction:** the original finding ("validation is imperative, not declarative") was overstated. The codebase *does* validate declaratively — request DTOs carry Bean Validation constraints (`@NotBlank/@NotNull/@Positive/...`) and resources enforce them at the boundary via `Validations.validate(req)` (a deliberate wrapper used instead of JAX-RS `@Valid`, because Helidon's `ConstraintViolation` mapper leaks internals). Remaining `if (...)` checks in service layers are mostly legitimate **cross-field/conditional** rules (e.g. delivery address required only when `fulfilmentType=DELIVERY`) that don't map to simple field annotations.

The genuine gaps a repo-wide scan found, now fixed:
- `iam-svc` `BootstrapResource.createPlatformAdmin` accepted a body but never called `Validations.validate` — its `@NotBlank/@Size(min=8)` password constraints were dead, and a null body NPE'd into a 500. **Fixed.**
- `tenant-svc` `UpsertInventoryConfigRequest.costingMethod` was a free string stored verbatim (`"BANANA"` would persist). Added `@Pattern(FIFO|AVERAGE|STANDARD)` + `@Size` on `defaultUom`, and the resource now validates the (optional) body. **Fixed + unit test.**

The 171 mutating endpoints vs 122 `validate` calls is mostly legitimate — bodyless POSTs (confirm/cancel/fulfil) and DELETEs need no body validation.

### A5 — God-files that violate the repo's own SOLID rules 🟠
- `inventory-svc/.../repo/InventoryRepository.java` — **3,988 lines**
- `product-svc/.../repo/ProductRepository.java` — 2,137 lines
- `inventory-svc/.../service/InventoryService.java` — 1,871 lines
- single `Dtos.java` / `Domain.java` bundles per service

These are hard to test and review and contradict `docs/coding-standards.md`, which is enforced on every other change.
- **Fix:** split by aggregate (e.g. batches / movements / planning / reservations repositories); break the DTO/domain bundles into per-aggregate files.

---

# PART 2 — UI / UX (Flutter)

Good bones: Riverpod + go_router + dio, responsive `LayoutBuilder` wide/narrow layouts, Material 3, dark mode, a token-refresh interceptor (`core/network/api_client.dart`), solid empty/loading/error/retry states, and a genuinely nice two-pane POS till with barcode scan + hold/resume (`features/pos/cart_screen.dart`). Measured against industry UX standards, the following hold it back.

### U1 — Raw UUIDs leak to end users 🔴 (worst offense, blocking)
The admin inventory table shows "Variant ID" as a truncated monospace UUID; the search box is literally **"Search variant ID…"** (`inventory_screen.dart:96`); the Receive Stock dialog tells staff to **"paste the variant UUID"** (`inventory_screen.dart:604`). UUIDs appear across ~8 admin screens. No human knows a UUID — this is an internal identifier bleeding onto the primary work surface.
- **Fix:** show product name + SKU everywhere user-facing; keep UUIDs internal to providers/requests.

### U2 — UI ignores the pagination the API was built for 🔴 (blocking at scale)
14 screens fetch the **entire** collection and `.where()`-filter client-side in Dart; search is in-memory substring matching. The backend has cursor pagination and server-side filtering — the frontend uses neither. At 10k SKUs this loads everything into memory on every visit.
- **Fix:** wire `nextCursor`/`after` + server-side search; infinite-scroll or paged tables.

### U3 — "AdaptiveNavShell" is not adaptive 🟠
Primary navigation is hidden behind a hamburger drawer in **every** viewport — the class comment states it "stays hidden in every view" (`shared/widgets/adaptive_nav_shell.dart:15`). On a wide admin/POS screen the Material 3 standard is a persistent `NavigationRail`/expanded drawer. Hiding nav adds a click to every navigation and hurts discoverability.
- **Fix:** breakpoint-driven `NavigationRail` (expanded on desktop, drawer on mobile).

### U4 — Structured errors are discarded 🔴 (blocking)
The backend returns `{ error: { code, message } }`, and the frontend even **models** it (`core/network/api_response.dart:27`) — but screens do `e.toString().contains('404')` (`inventory_screen.dart:503`, `cart_screen.dart:58`). So `INVENTORY_INSUFFICIENT_STOCK` surfaces as "Could not receive stock: DioException…". The contract exists end-to-end and is thrown away at the last step, then re-implemented per screen.
- **Fix:** one error interceptor/mapper that reads `error.code`/`error.message`; map codes → friendly copy centrally.

### U5 — No accessibility 🟠
Zero `Semantics`/`semanticLabel` in the entire app. Icon-only buttons, status conveyed by color alone (green "OK" / red "Low", and `Colors.green.shade700` hardcoded — fails colorblind users **and** dark-mode contrast since it bypasses `colorScheme`), 11–12px monospace table text. WCAG fail; unusual for commercial SaaS.

### U6 — No internationalization 🟠
Every string is hardcoded English; currency is a hardcoded `'$'` prefix though orders are multi-currency; money uses `toStringAsFixed(2)` instead of locale-aware `NumberFormat`; dates are free-text `"YYYY-MM-DD"` fields instead of date pickers. A platform meant to sell internationally needs `intl` + locale formatting from the start.

### U7 — No design system / tokens 🟠
`core/theme.dart` is ~33 lines — one seed color, no typography/spacing scale, no semantic color tokens. Spacing is magic numbers (`fromLTRB(24,24,24,0)`, `SizedBox(width: 220)`); ad-hoc `Colors.green/orange` are scattered through widgets. Hard to keep consistent as it grows.

### U8 — Zero frontend tests 🟠
0 widget/unit tests vs. 31 backend test files. No regression safety net on the UI.

---

# Prioritized remediation roadmap

| # | Item | Layer | Severity | Effort | Status |
|---|---|---|---|---|---|
| A3 | Malformed UUID → 400 (boundary validation) | API | 🔴 | S | ✅ Done |
| A1 | `/v1` API versioning | API | 🔴 | S | ✅ Done |
| A2 | OpenAPI spec + generated Dart client | API | 🔴 | M | 🟡 Partial — specs served; Dart client gen pending |
| U4 | Consume structured `error.code` centrally | UI | 🔴 | S | 🟡 Core done — `api_error.dart` helper + 2 screens; roll-out pending |
| U1 | Replace user-facing UUIDs with name/SKU | UI | 🔴 | M | ✅ Done — inventory, orders, pricing (+ backend enabler); store-name resolution pending |
| U2 | Server-side pagination + search in UI | UI | 🔴 | L | 🟡 Orders list done (cursor infinite scroll); roll to other lists pending |
| U3 | True adaptive navigation (NavigationRail) | UI | 🟠 | S | ✅ Done — rail on wide, drawer on phones |
| A4 | Close boundary-validation gaps | API | 🟠 | S | ✅ Done — finding corrected; 2 real holes fixed |
| U5 | Accessibility (Semantics, non-color status, font sizes) | UI | 🟠 | M | 🟡 Template done — semantic colors + Semantics labels + tooltips; roll-out pending |
| U6 | i18n + locale-aware money/date formatting | UI | 🟠 | L | 🟡 Pipeline live (gen-l10n + login migrated, en/pl); remaining-string extraction ongoing |
| A5 | Split god-files per aggregate | API | 🟠 | L | 🟡 In progress — 2 repos extracted (−319 lines); pattern proven |
| U7 | Design tokens (typography/spacing/semantic colors) | UI | 🟠 | M | 🟡 Tokens established (StatusColors ext + spacing scale); magic-number sweep pending |
| U8 | Frontend widget/unit tests | UI | 🟠 | L | 🟡 Harness + 23 tests (new code covered); broaden coverage pending |

---

## Implementation log

### A3 — malformed UUID → 400 (done)
- Added `shared/common-web/.../UuidParseExceptionMapper.java`: an `ExtendedExceptionMapper` that claims an `IllegalArgumentException` **only** when `java.util.UUID.fromString` is in its stack, returning `400 INVALID_UUID`. Genuine internal `IllegalArgumentException`s still fall through to `GenericExceptionMapper` (500). JAX-RS mappers run only on HTTP threads, so Kafka-consumer parse failures keep their poison-pill/DLT handling. This single backstop fixes the bug for all ~157 sites + future ones.
- Converted order-svc's critical path (`api/` + `service/`, orders/layaways/parked-sales/POS-stock) from raw `UUID.fromString` to `Parsing.uuid(value, field)` for **field-named** messages. Left the cursor-decode and messaging/repo (internal) sites to the backstop.
- Tests: new `UuidParseExceptionMapperTest` (3) + full order-svc suite (28, incl. Testcontainers `OrderIT`) green.
- **Follow-up:** roll `Parsing.uuid` across the other 11 services' boundaries for field-named messages (optional — correctness already covered by the backstop).

### A1 — `/v1` API versioning (done, gateway alias approach)
- `platform/gateway/.../ProxyResource.java`: added `Route.of(service, path)` which peels an optional `v\d+` segment, so `/api/v1/{service}/...` routes to the same upstream as the unversioned `/api/{service}/...`. Business services stay version-agnostic.
- `JwtAuthFilter.normalize()`: collapses an optional version segment so public/storefront/onboarding whitelists match both forms (security exact-match preserved).
- New `ApiVersionDeprecationFilter`: stamps `Deprecation: true` + `Link: </api/v1>; rel="successor-version"` on the unversioned alias (RFC 8594).
- Tests: new `ProxyRouteTest` (5) + 2 added `JwtAuthFilterTest` v1 cases; full gateway suite (37) green.
- **Follow-up:** migrate Flutter `ApiConstants.baseUrl` from `/api` to `/api/v1` when ready (non-breaking until the alias is retired).

### A2 — OpenAPI (specs served; client-gen pending)
- Added `helidon-microprofile-openapi` to all 14 server modules (12 services + gateway + config). Each now serves its generated contract at `/openapi`. Verified: feature jars (helidon-microprofile-openapi 4.4.1, smallrye-open-api 3.3.4 + JAX-RS scanner) on the runtime classpath; full reactor `install` green.
- **Follow-up:** set per-service OpenAPI `info.title`/`info.version` via MicroProfile Config (`mp.openapi.extensions.smallrye.info.*`); generate the typed Dart client from the spec and replace hand-written calls in the Flutter app.

### A4 — boundary-validation gaps (done; finding corrected)
- Repo-wide scan confirmed declarative validation is the norm (DTO constraints + `Validations.validate`). Two real holes fixed: `iam-svc` `BootstrapResource` now validates its body (dead password constraints + null-body 500); `tenant-svc` `costingMethod` now constrained via `@Pattern` (was stored verbatim).
- Tests: new `InventoryConfigValidationTest` (2) + iam-svc (12) and tenant-svc (12) suites green, incl. Testcontainers `AuthIT`/`OnboardingIT`.
- **Follow-up:** none critical; remaining service-layer `if` checks are legitimate cross-field rules.

### A5 — split god-files (started; pattern proven)
- Extracted two cohesive clusters from `InventoryRepository` into new sibling repos (both `extends BaseJdbcRepository`, matching the existing `SerialRepository` injection pattern):
  - `ReferenceDataRepository` — transaction reason codes, source types, zone→GL mappings.
  - `PlanningConfigRepository` — lot-specific UOM conversions, replenishment PAR-level configs.
  - `InventoryRepository`: **3,988 → 3,669 lines** (−319); 13 call sites rewired in `InventoryService`.
- Behavior-preserving: 32 inventory tests green after each step, incl. the 27-test Testcontainers `InventoryIT` (real DB exercises the moved CRUD).
- Note: order-modifier updates (#28) were left in place — they reuse `mapRopPlan`/`mapKanbanCard` shared with the still-resident ROP/Kanban domains.
- **Coupling map for the remaining extractions** (do each as its own verified slice):
  - *Clean / low-risk (only `inTx`/`query` + own mappers):* PAR levels, order modifiers, lot UOM conversions, expiry-alert query — same recipe as this slice.
  - *Medium (own helpers + outbox, but self-contained):* move orders, transfer orders, cycle counting, ABC, kanban, costing, physical inventory — extract into per-aggregate repos extending `BaseOutboxRepository`.
  - *Coupled (leave for last / keep shared):* the FIFO/reservation "internals" (`availableForUpdate`, batch deduction, `checkThresholdTx`) and the picking-rules `resolve`/`preview` paths read shared batch mappers — these stay in (or alongside) the core `InventoryRepository`.
- Same treatment applies to `ProductRepository` (2,137 lines) and the single `Dtos.java`/`Domain.java` bundles.

**A5 next clean cluster:** the remaining medium-risk per-aggregate repos (move/transfer orders, cycle counting, ABC, kanban, costing).

---

## Implementation log — UI

### U4 — consume structured `error.code` (core done)
- Added `frontends/shelf-app/lib/core/network/api_error.dart`: `apiErrorOf(e)` (pulls the `{code,message}` envelope off a `DioException`), `apiErrorCode(e)`, and `friendlyError(e, {fallback})` (prefers the server's safe `message`, then a network-error line, then a fallback — never surfaces a raw `DioException`).
- Converted the POS cart screen and the admin inventory receive dialog from `e.toString().contains('404')` to the structured helper (branching on codes like `PRODUCT_NOT_FOUND` / `INVALID_UUID`), plus the POS park/no-sale snackbars.
- Verified with `flutter analyze` (0 new issues; toolchain at `~/flutter/bin`).
- **Follow-up:** roll `friendlyError`/`apiErrorCode` across the remaining ~12 screens' `$e` snackbars (mechanical).

### U1 — replace user-facing UUIDs with name/SKU (inventory screen done)
**Backend enabler (built + tested):** added an authenticated, tenant-scoped batch resolve endpoint `GET /admin/products/variants/resolve?ids=a,b,c` (max 200) → `[{variantId, productName, sku, ...}]` on product-svc.
- `ProductRepository.findVariantsByIds` (mirrors the by-barcode JOIN; `WHERE tenant_id=? AND id=ANY(?)`; resolves all statuses so admin sees inactive variants too), service `resolveVariants`, resource on `AdminResource` (so tenant comes from JWT, not the storefront header — it's an admin call). Malformed ids → 400 via `Parsing.uuid`; `/admin` is role-gated (403 without a role).
- New `CatalogIT` test: resolves name+SKU, asserts tenant isolation, and 400 on a bad id. Full product-svc suite green (9 tests).

**Frontend (reusable):** added a reusable `variantLabelsProvider` family (keyed by a sorted/de-duped id csv via `variantIdsKey`, so overlapping resolves share cache) plus `variantDisplayName`/`variantSku` helpers. Wired into three screens that previously showed truncated variant UUIDs:
- **Inventory** Levels tab → "Product" column + SKU; search matches name/SKU/id.
- **Orders** return dialog line rows → product name + SKU instead of monospace UUID.
- **Pricing** price-list item dialog → product name + SKU.
- `flutter analyze` clean across the app (16 issues, 0 errors/0 warnings — one fewer than baseline).
- **Follow-up:** resolve **store** UUIDs to store names the same way (a few screens show short store ids), and apply to any remaining admin lists. Note: `#abc12345` order/layaway short-refs are intentional human-friendly references, not UUID leaks.

### U2 — server-side pagination (orders list done)
- New `providers/orders_pagination.dart`: a `StateNotifierProvider.family` keyed by `OrdersFilter(channel, status)` that fetches one page at a time via the API's `?after=&limit=` cursor (the backend already returns `meta.nextCursor`), accumulating rows. State carries `isLoadingInitial / isLoadingMore / nextCursor / error`.
- Rewired the admin **orders** screen: channel **and** status filters are now sent to the server (status was previously a client-side `.where`), a `ScrollController` triggers `loadMore()` within 300px of the bottom, and a footer spinner shows while the next page loads. Deleted the old "fetch 50, filter in Dart" `ordersProvider`.
- `flutter analyze` clean (16 issues, 0 errors/0 warnings).
- **Follow-up:** apply the same pattern to the other full-list screens (customers, products, inventory levels — the latter needs a paginated backend endpoint; today `/admin/inventory/levels` returns everything).

### U3 — adaptive navigation (done)
- Reworked the shared `AdaptiveNavShell` (used by all four shells: admin / POS / storefront / platform) from "drawer hidden at every width" to responsive: at ≥ 800px a **persistent `NavigationRail`** sits beside the content (labels under icons by default; the app-bar icon expands it to labelled, scroll-safe via `SingleChildScrollView`+`IntrinsicHeight`); below 800px it keeps the existing `NavigationDrawer`. Public API unchanged, so every shell adopts it for free.
- `flutter analyze` clean (16 issues, 0 errors/0 warnings).

### U8 — frontend tests (harness + first suite)
- Stood up the `flutter test` harness (was **0** tests) with **23 passing tests** across 6 files, covering the logic added this session:
  - `core/format_test.dart` — `AppFormat.money` (GBP symbol/grouping/decimals, defaults, other currencies) + `AppFormat.date` (UK format, fallthrough). Uses `initializeDateFormatting` so intl works in a plain test.
  - `core/api_error_test.dart` — `apiErrorOf`/`apiErrorCode`/`friendlyError` (envelope extraction, server message, network line, non-Dio fallback).
  - `features/variant_labels_test.dart` — `variantIdsKey` (sort/dedupe/blank-drop), `variantDisplayName`/`variantSku` (resolved + short-UUID fallback).
  - `features/orders_pagination_test.dart` — `OrdersFilter` value-equality (stable family key), `OrdersPage.hasMore`/`copyWith`.
  - `widgets/adaptive_nav_shell_test.dart` — pumps the shell at 1200px (asserts `NavigationRail`) and 500px (asserts none → drawer), locking the U3 behaviour.
  - `l10n/localization_test.dart` — en resolves base strings, pl resolves translations, an untranslated locale (ro) falls back to English.
- **Follow-up:** broaden into golden/widget tests for the main screens and provider integration tests; wire `flutter test` into CI alongside the backend Maven suites.

### U7 — design tokens (foundations established)
- **Semantic colour tokens** promoted from static helpers to a proper `StatusColors` `ThemeExtension` registered in both light/dark themes, read contextually via `context.status.success/warning`. Migrated the U5 call sites (inventory OK/material-status, POS in-stock/stock-dot) onto it — no more manual `Brightness` plumbing or ad-hoc `Colors.green/orange`.
- **Spacing scale**: new `core/spacing.dart` — `AppSpacing` (4-pt scale `xs…xxl`, plus `pagePadding`/`cardPadding`) and a `Gap` widget — replacing magic numbers. Applied to the inventory screen's page paddings as the template.
- `flutter analyze` clean (16 issues, 0 errors/0 warnings).
- **Follow-up:** sweep the remaining magic-number `EdgeInsets`/`SizedBox` onto `AppSpacing` app-wide; consider a `_StatusPill` widget (shared by inventory/orders/POS) and tightening the typography scale. The 11–12px table fonts (also a U5 item) get fixed in the same pass.

### U6 — internationalization (infra + formatting done; string ARB pending)
- **UK-first locale strategy.** Added `flutter_localizations` + `intl`. `core/l10n/app_locales.dart` declares **en_GB as the default/fallback** plus the largest non-English-speaking UK communities (2021 England & Wales census "main language other than English"): **Polish, Romanian, Punjabi, Urdu, Bengali, Gujarati, Arabic** — all verified to ship with `flutter_localizations`. `main.dart` wires the `Global*Localizations` delegates, `supportedLocales`, and a `localeResolutionCallback` that honours the device language among these and falls back to en_GB. **Urdu/Arabic resolve to RTL automatically** (Flutter mirrors the layout) — so the whole app already flips for those users, and Material widgets (date pickers, dialogs) are localized for all eight.
- **Locale-aware money/date.** `core/format.dart` (`AppFormat.money/date/dateTime`) replaces the hardcoded `'$'` prefix and bare `toStringAsFixed(2)`/`substring` formatting — GBP default, correct symbol/grouping/decimals (e.g. `£1,234.50`), locale-driven dates. Applied to the orders list (totals + timestamps) and the inventory receive dialog (`'$ '` → `'£ '`).
- `flutter analyze` clean (16 issues, 0 errors/0 warnings).
- **ARB translation pipeline (now live).** Enabled `gen-l10n` (`l10n.yaml`, `generate: true`); ARB files under `lib/l10n/` for all eight locales — `app_en.arb` (template) and `app_pl.arb` fully translated, the other six as stubs that fall back to the English template per-key until translated. `AppLocalizations` is generated into `lib/l10n/gen/` and wired as the first `localizationsDelegate`. **Migrated the login screen end-to-end** as the reference vertical slice (subtitle, field labels, validators, buttons, mode toggle, and the friendly auth-error messages) — it renders in Polish on a `pl` device today.
- **Follow-up:** extend ARB coverage screen-by-screen (the rest still use inline English; same recipe — add keys to `app_en.arb`, run `flutter gen-l10n`, swap literals for `AppLocalizations.of(context)`), then commission translations for pl/ro/pa/ur/bn/gu/ar. Note: `lib/l10n/gen/` is generated — either commit it or gitignore + regenerate on build. Continue rolling `AppFormat` across remaining money/date displays.

### U5 — accessibility (template slice done)
- **Semantic status colors** added to `AppTheme` (`success`/`warning` that adapt to `Brightness`), replacing hardcoded `Colors.green`/`Colors.green.shade700`/`Colors.orange` that bypassed the colour scheme and lost contrast in dark mode. Applied in the inventory Levels table + material-status chip and the POS catalog stock indicators.
- **Non-colour-only status:** the inventory stock cell is wrapped in `Semantics(label: 'Low stock' / 'Stock OK')` so screen readers announce state (previously colour-only); the POS stock dot keeps its `Tooltip` (which carries the same label).
- **Labelled controls:** added tooltips/semantic labels to the previously icon-only refresh buttons.
- `flutter analyze` clean (16 issues, 0 errors/0 warnings).
- **Follow-up (roll-out):** apply `AppTheme.success/warning` + `Semantics` to the remaining status indicators app-wide; add `semanticLabel`/tooltips to the other icon-only buttons; bump the 11–12px monospace table text toward the 14px minimum. A reusable `_StatusPill` widget would DRY this up (overlaps with U7 design tokens).

---

## Reference
- Backend security boundary: `platform/gateway/src/main/java/com/shelfj/gateway/filters/JwtAuthFilter.java`
- Response envelope / errors: `shared/common-web/src/main/java/com/shelfj/web/`
- Largest service example: `services/order-svc/src/main/java/com/shelfj/order/service/OrderService.java`
- Flutter core: `frontends/shelf-app/lib/core/`
