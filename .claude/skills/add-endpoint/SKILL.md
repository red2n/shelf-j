---
name: add-endpoint
description: Add a REST endpoint to an existing Shelf-J service following the layered architecture, response envelope, validation, tenant-isolation, pagination, and idempotency rules. Use when adding or changing an API endpoint.
---

# Add a REST endpoint to a Shelf-J service

Use when adding/modifying a REST endpoint so it stays consistent with every other endpoint in the platform.

> Context: [README §7 conventions](../../../README.md#7-cross-cutting-conventions), the service's spec in [README §9](../../../README.md#9-the-business-services--full-catalog), [golden rules](../../../CLAUDE.md).

## Before writing code — confirm

1. **Right service?** The endpoint must live in the service that **owns** the data (README §9 ownership). If it needs another service's data, call that service (`client/`), don't add a table here.
2. **Path & method** — plural kebab noun (`/purchase-orders`), correct verb. Admin vs public/internal route (it must map to a gateway route in [README §8.1](../../../README.md#81-platformgateway)).
3. **Auth & roles** — which roles may call it. Public storefront reads vs staff admin.
4. **Tenant-scoped?** Almost always yes for business data.

## Steps (top-down through the layers)

1. **DTOs (`dto/`)** — define request and response as records. Add **Bean Validation** (`@NotNull`, `@Positive`, `@Size`, `@Email`, …). **Never** accept or return JPA entities. **Never** include `tenant_id` as a settable request field — it comes from the JWT.

2. **Resource (`api/`)** — add the JAX-RS method. Keep it **thin**:
   - read `tenant_id`/`userId`/`roles` from the **verified JWT** (via the tenant-context filter), never from body/path/query;
   - validate the DTO (let Bean Validation throw → mapped to `400`);
   - enforce role/authorization (coarse may be at gateway; fine-grained here);
   - delegate to `service/`;
   - return the standard envelope `{data, error, meta}`.
   - **No business logic, no DB calls here.**

3. **Service (`service/`)** — implement the logic and the transaction:
   - all reads/writes via `repo/`, always filtering `tenant_id` **first** on tenant data;
   - for writes others care about, write to **outbox** in the same transaction (the event is published by `messaging/`);
   - for retryable writes (anything that creates money movement or stock movement, e.g. checkout/capture/receive), honor an **`Idempotency-Key`** header: store processed keys, return the prior result on replay;
   - business-rule violations → `422` with a stable `code`; not-found → `404`; conflicts → `409`.

4. **Repo (`repo/`)** — queries enumerate the columns you need (no `SELECT *`), filter `tenant_id` first, and for stock deduction use FIFO ordering + row locks where relevant.

5. **Cross-service reads** — if you need data this service doesn't own, call the owner via a `client/` method that has **timeout + `@Retry` + `@CircuitBreaker` + `@Fallback`**. Never join across service DBs.

6. **Pagination** — list endpoints are **cursor-based** (`?after=&limit=`), default 20 / max 100; put `nextCursor` in `meta`. No page numbers.

7. **OpenAPI** — annotate so the generated OpenAPI is accurate (this is the contract for frontends/clients).

8. **Tests** — add/extend a unit test for the new logic and, for a non-trivial flow, a Testcontainers integration test (including the tenant-isolation assertion: a caller from tenant A cannot read/affect tenant B's row).

## Quick self-check

- [ ] `tenant_id` from JWT only; query filters it first.
- [ ] DTOs in/out (no entities); inputs validated.
- [ ] Envelope + correct status codes + stable error `code`.
- [ ] Cursor pagination on lists.
- [ ] Cross-service data via resilient `client/`, not a DB join.
- [ ] Idempotency-Key honored on retryable money/stock writes.
- [ ] Events (if any) via outbox; consumers idempotent.
- [ ] Tests incl. tenant isolation.
