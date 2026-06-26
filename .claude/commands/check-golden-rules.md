# Shelf-J full code-quality gate

Audit every file changed on this branch against **all** mandatory rules. This command is non-negotiable — nothing merges until every BLOCKER is resolved.

---

## Step 1 — collect the diff

```bash
git diff main...HEAD --stat
git diff main...HEAD --name-only
git diff main...HEAD
```

Count changed Java files (`.java`) and Dart files (`.dart`). Store the count — needed for Step 6.

---

## Step 2 — Shelf-J golden rules (CLAUDE.md)

Check each rule against every changed file. Mark each: ✓ pass · ✗ BLOCKER · N/A not applicable.

| # | Rule | What to grep / inspect |
|---|---|---|
| G1 | **No cross-service DB access** | Any SQL referencing a table not owned by this service's schema. Any `EntityManager` or `DataSource` in a `client/` class. |
| G2 | **`tenant_id` from JWT only** | `tenant_id` in `@PathParam`, `@QueryParam`, or request DTO fields. It must only be read from the security context / JWT claim. |
| G3 | **Controllers thin** | `api/` classes may not contain `EntityManager`, `DataSource`, repository calls, or business logic. Only: extract JWT → call service → return envelope. |
| G4 | **DTOs in/out** | No JPA `@Entity` as a return type or parameter on any JAX-RS resource method. |
| G5 | **Money = BigDecimal/NUMERIC** | `double` or `float` for any price, amount, vat, rate, or quantity field → BLOCKER. |
| G6 | **Append-only tables never mutated** | `stock_movements`, `order_status_history`, `payments`, `refunds`, `loyalty_ledger`, `audit_log` — any `UPDATE` or `DELETE` on these → BLOCKER. |
| G7 | **Outbox atomicity** | Any new domain event write must be in the same DB transaction as the state change. Check that the event insert and the state update share a connection / transaction boundary. |
| G8 | **Idempotent consumers** | Any new Kafka consumer must guard against processing the same `eventId` twice (deduplication table or idempotency key check). |
| G9 | **Idempotency-Key on retryable writes** | POST endpoints for checkout, payment capture, stock receipt must accept and honour `Idempotency-Key`. |
| G10 | **Time = UTC / Instant** | `LocalDateTime` without timezone in domain objects or DB columns → BLOCKER. Must be `Instant` / `TIMESTAMPTZ`. |
| G11 | **IDs = UUID** | `long` or `String` as a primary key → BLOCKER. |
| G12 | **Health + metrics + tracing** | New services must expose all three probes; ready probe must check DB + Kafka + config. |
| G13 | **No hardcoded host:port** | Service addresses must come from Consul discovery, not hardcoded strings. |
| G14 | **External config** | No environment-specific values or secrets in code or images. |
| G15 | **Flutter: `showPrices` guard** | Any Flutter widget that displays a price, amount, or currency must check `storefrontShowPricesProvider` (or `configAsync.value?.showPrices`). No unconditional price rendering. |

---

## Step 3 — SQL rules (docs/coding-standards.md §1) — ZERO TOLERANCE

Grep the diff for every SQL string and check all of the following. Any violation is a **BLOCKER**.

### S1 — No `SELECT *`
```bash
git diff main...HEAD | grep -n "SELECT \*"
```
Every column must be named explicitly. `SELECT *` is forbidden in all contexts (inline SQL, prepared statements, named queries).

### S2 — Every `SELECT` must have a `WHERE` clause
Inspect every `SELECT` string in the diff. An unbounded select against any tenant table is both a correctness bug and a performance bomb.

```bash
# Flag any SELECT without WHERE (approximate — review matches manually)
git diff main...HEAD | grep -n "^\+.*SELECT" | grep -iv "WHERE"
```

### S3 — Every `UPDATE` must have a `WHERE` clause
A bare `UPDATE table SET ...` with no `WHERE` is a wildcard update — forbidden unconditionally.

```bash
git diff main...HEAD | grep -n "^\+.*UPDATE" | grep -iv "WHERE"
```

### S4 — Every `DELETE` must have a `WHERE` clause  
A `DELETE FROM table` with no `WHERE` is a wildcard delete — forbidden unconditionally. This is the most destructive possible SQL statement in production.

```bash
git diff main...HEAD | grep -n "^\+.*DELETE" | grep -iv "WHERE"
```

### S5 — Tenant filter is the FIRST `WHERE` condition
On every query against a tenant-owned table, `tenant_id = ?` must be the **first** predicate. Verify by reading the full SQL string in context.

### S6 — Queries on large tables must have a LIMIT
Any `SELECT` that lists multiple rows without a `LIMIT` → WARN (BLOCKER if no pagination at all).

### S7 — No N+1 queries
A SQL call inside a loop (`for`, `while`, `stream().map(...)`) → BLOCKER. Batch or join instead.

---

## Step 4 — SOLID principles (docs/coding-standards.md §2) — strictly enforced

Read every changed `.java` file in full and check each principle.

### SOLID-SRP — Single Responsibility
Each class has exactly one reason to change. Check:
- `api/` Resource: contains ONLY deserialization → service call → response. No logic, no SQL.
- `service/` Service: ONLY orchestrates domain rules. No HTTP concerns, no SQL strings.
- `repo/` Repository: ONLY SQL in, domain objects out. No business rules.
- `messaging/` Consumer: ONLY poll loop + dispatch. No JSON parsing, no service calls inline.
- `messaging/` Handler: ONLY processes one event payload. No Kafka lifecycle.

**Violation signal:** A class whose name ends in `Consumer` that contains `service.` calls outside a handler delegate → BLOCKER.

### SOLID-OCP — Open/Closed
- Topic names must come from `@ConfigProperty`, not hardcoded string constants.
- `if/else` or `switch` on domain status strings inside `service/` or `repo/` → WARN (should be pushed to the domain object or a strategy).

```bash
git diff main...HEAD | grep -n 'private static final String TOPIC'
```

### SOLID-LSP — Liskov Substitution
Any new implementation of an interface must honour the full contract. Check that no implementation returns `null` or an empty result in a non-exceptional case without explicit documentation.

### SOLID-ISP — Interface Segregation
New interfaces must be narrow: 1–3 related methods. If a new interface has more than 5 methods → WARN, flag for splitting.

```bash
# Count methods in new interfaces
git diff main...HEAD | grep -c "^\+.*throws\|^\+.*default\|^\+.*public [a-zA-Z]"
```

### SOLID-DIP — Dependency Inversion
- Fields must be injected (`@Inject`), not instantiated with `new` inside business classes.
- `new` is only allowed inside CDI `@Produces` factory beans and Kafka bootstrap classes.

```bash
git diff main...HEAD | grep -n "^\+.*= new " | grep -v "@Produces\|test\|Test"
```

---

## Step 5 — Java style rules (docs/coding-standards.md §3)

| Rule | Check |
|---|---|
| No `SELECT *` | covered in S1 |
| No `double`/`float` for money | covered in G5 |
| No `LocalDateTime` in domain | covered in G10 |
| No `long`/`String` PKs | covered in G11 |
| No explanatory comments (WHAT) | Flag multi-line comments that describe what the code does rather than why — WARN |
| No paragraph-length Javadoc on obvious methods | WARN |
| Kafka consumer `@ApplicationScoped` beans must have `@Observes @Initialized(ApplicationScoped.class)` eager-init hook | BLOCKER if missing |

---

## Step 6 — Run `scripts/duplo.sh` (major changesets)

Run duplo if **10 or more Java files** changed, or if any new service directory was added:

```bash
scripts/duplo.sh
```

Report the Results summary line. If duplication exceeds **10%** → BLOCKER. Between 5–10% → WARN. Under 5% → ✓.

If duplo is not installed, report:
```
WARN: duplo not found at ./tools/duplo — install from https://github.com/dlidstrom/Duplo/releases
      to enforce the <10% duplication threshold on major changesets.
```

---

## Output format

For every finding, use exactly this format:

```
[BLOCKER|WARN|NOTE] <file>:<line> — <rule-id>: <short rule name>
  Found:  <quote the exact offending code>
  Why:    <one sentence explaining the violation>
  Fix:    <concrete, actionable suggestion>
```

Then a summary table:

```
── Summary ──────────────────────────────────────────────────────
 BLOCKERS : N   (must fix before merge)
 WARNINGS : N   (should fix; document if deferred)
 NOTES    : N   (optional cleanup)
 Duplo    : X%  duplication  [✓ / WARN / BLOCKER]
─────────────────────────────────────────────────────────────────
```

If there are zero BLOCKERs, say so explicitly. Do not omit the summary table even on a clean diff.
