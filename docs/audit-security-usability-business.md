# Shelf-J — Security, Usability & Business Requirements Audit

> **Date:** 2026-06-12  
> **Scope:** All services on branch `new`  
> **Standards applied:** OWASP API Security Top 10 (2023) · ASVS 4.0 · Oracle Retail POS R1.3.0 · Oracle Inventory R12.1  
> **Severity key:** 🔴 Critical · 🟠 High · 🟡 Medium · 🟢 Low

---

## Part 1 — Security (OWASP / ASVS)

| ID | Severity | Finding | Affected File(s) | Status |
|---|---|---|---|---|
| SEC-01 | 🔴 Critical→🟠 High | No RBAC on payment tender / refund endpoints — any authenticated user can record a payment or issue a refund | `payment-svc/PaymentResource.java` | ✅ Fixed |
| SEC-02 | 🟠 High | Staff not scoped to assigned store — CASHIER assigned to Store A can ring sales against Store B | `order-svc/OrderService`, `payment-svc/PaymentService` | ❌ Open |
| SEC-03 | 🟡 Medium | Account enumeration: login returns `USER_NOT_FOUND` vs `INVALID_CREDENTIALS` — distinguishable by `code` field | `iam-svc/AuthService.java` | ❌ Open |
| SEC-04 | 🟡 Medium | No security response headers at gateway (missing HSTS, X-Content-Type-Options, X-Frame-Options, Cache-Control on auth) | `gateway/filters/` | ✅ Fixed |
| SEC-05 | 🟡 Medium | Weak password policy — only `@Size(min=8)`, no complexity, no breach-corpus check | `iam-svc/dto/Dtos.java` | ❌ Open |
| SEC-06 | 🟡 Medium | No email verification — accounts active immediately on register | `iam-svc/AuthService.java` | ❌ Open |
| SEC-07 | 🟡 Medium | Refresh token reuse not detected — stolen replayed token not invalidated; no token family tracking | `iam-svc/JwtService.java` | ❌ Open |
| SEC-08 | 🟡 Medium | Infra ports exposed on host in docker-compose (Postgres 5432, Kafka 9092, Redis 6379, Consul 8500, Kafka UI 8081) | `docker-compose.yml` | ✅ Fixed — all bound to 127.0.0.1 |
| SEC-09 | 🟡 Medium | Rate limiting and brute-force counters are in-memory only — bypassed under horizontal scale-out; Redis unused | `gateway/filters/RateLimitFilter`, `BruteForceProtectionService` | ❌ Open |
| SEC-10 | 🟢 Low | No request body size limit at the gateway — unbounded POST bodies can cause OOM | `gateway/ProxyResource.java` | ❌ Open |
| SEC-11 | 🟢 Low | PATCH verb missing from `CorsFilter.ALLOWED_METHODS` — browser PATCH requests fail preflight | `gateway/filters/CorsFilter.java` | ✅ Fixed (was already present from prior session) |

---

## Part 2 — API Usability

| ID | Severity | Finding | Affected File(s) | Status |
|---|---|---|---|---|
| UX-01 | 🟠 High | Cursor pagination only on `GET /orders`; 7+ other collection endpoints return unbounded results | `inventory-svc`, `purchase-svc`, `pricing-svc`, `product-svc` | ❌ Open |
| UX-02 | 🔴 Critical | No product search by name, SKU, or barcode — `GET /catalog/products` accepts no search params; POS barcode scan is non-functional | `product-svc/CatalogResource.java` | ✅ Fixed — added `?q=`, `?sku=`, `?barcode=`; new `GET /catalog/variants/by-barcode/{code}` |
| UX-03 | 🟡 Medium | No OpenAPI / Swagger spec — no `helidon-microprofile-openapi` dependency; no machine-readable contract for integrators | All services | ❌ Open |
| UX-04 | 🟡 Medium | Inconsistent 400 error shape — some endpoints return envelope, others return raw Helidon validation JSON | Multiple | ❌ Open |
| UX-05 | 🟡 Medium | No soft-delete / archive on products or variants — DELETE on a product that appears in historical orders breaks audit trail | `product-svc/AdminResource.java` | ❌ Open |
| UX-06 | 🟡 Medium | `GET /auth/me` returns no store context — POS terminal must make a second round-trip to discover assigned store | `iam-svc/MeResource.java` | ❌ Open |
| UX-07 | 🟡 Medium | No endpoint for MANAGER to list active POS sessions on their own store — sweep is PLATFORM_ADMIN only | `iam-svc/PosSessionResource.java` | ❌ Open |

---

## Part 3 — POS Business Requirements

| ID | Severity | Finding | Affected Service(s) | Status |
|---|---|---|---|---|
| POS-01 | 🔴 Critical | No cash management: no till float, cash drop, Z-report, X-report, or cash reconciliation | `order-svc`, `payment-svc` | ✅ Fixed — `till_sessions`, `cash_drops` tables; `POST /admin/cash/till-sessions`, drops, X-report, Z-report |
| POS-02 | 🟠 High | No suspend / park sale — cannot save in-progress basket and recall later | `order-svc` | ❌ Open |
| POS-03 | 🔴 Critical | No barcode scan lookup — `GET /catalog/products?barcode=` does not exist; POS unusable with a scanner | `product-svc/CatalogResource.java` | ✅ Fixed — see UX-02 |
| POS-04 | 🟠 High | No multi-tender split payment — no payment session grouping tenders; no change-due calculation for cash overpayment | `payment-svc` | ❌ Open |
| POS-05 | 🟠 High | No manager override approval flow — any CASHIER can apply full discount without MANAGER PIN/approval | `order-svc` | ❌ Open |
| POS-06 | 🟠 High | No loyalty programme / points at checkout — `loyalty_ledger` referenced in golden rules but no table or service exists | None | ❌ Open |
| POS-07 | 🟢 Low | No customer display / pole display push (WebSocket / SSE) | `order-svc` | ❌ Open |
| POS-08 | 🟡 Medium | No offline mode design or specification for network-unavailable terminals | Architecture | ❌ Open |
| POS-09 | 🟡 Medium | Gift card balance lookup (`GET /gift-cards/{code}`) may be accessible without auth — needs gateway auth verification | `order-svc/GiftCardResource.java` | ❌ Open |
| POS-10 | 🟡 Medium | No age-restricted product flag — no `age_restricted` on variants, no POS prompt, no `age_verified_by` on orders | `product-svc`, `order-svc` | ❌ Open |

---

## Part 4 — Stock Management Business Requirements

| ID | Severity | Finding | Affected Service(s) | Status |
|---|---|---|---|---|
| STK-01 | 🔴 Critical | No Unit of Measure (UOM) model — all quantities are plain numbers; purchase-case vs. sales-each conversion impossible | `product-svc`, `inventory-svc`, `purchase-svc` | ❌ Open |
| STK-02 | 🟠 High | No ATP / backorder — no available-to-promise date, no backorder queue for out-of-stock orders | `inventory-svc`, `order-svc` | ❌ Open |
| STK-03 | 🟠 High | No Return to Vendor (RTV) — no supplier return workflow, no debit memo; damaged PO goods have no path back | `purchase-svc` | ❌ Open |
| STK-04 | 🟠 High | No PO approval workflow — any MANAGER can submit unlimited-value PO; no approval tier, no reject/revision cycle | `purchase-svc` | ❌ Open |
| STK-05 | 🟠 High | No cross-store stock transfer — no inter-branch transfer order, in-transit status, or receiving confirmation | `inventory-svc` | ❌ Open |
| STK-06 | 🟠 High | No FIFO / FEFO picking enforcement — batch with earliest expiry not guaranteed to be reserved first | `inventory-svc` | ❌ Open |
| STK-07 | 🟡 Medium | No stocktake / cycle count workflow — single ad-hoc adjust exists but no formal count session, variance sign-off | `inventory-svc` | ❌ Open |
| STK-08 | 🟡 Medium | No near-expiry batch alert — shortage alert exists in notification-svc but no expiry alert scheduled | `inventory-svc`, `notification-svc` | ❌ Open |
| STK-09 | 🟡 Medium | No min/max replenishment auto-trigger — threshold data exists but nothing fires a draft PO when stock falls below min | `inventory-svc`, `purchase-svc` | ❌ Open |
| STK-10 | 🟡 Medium | Reporting covers only inventory; missing all sales, tender summary, margin, staff performance, and shrinkage reports | `reporting-svc` | ❌ Open |

---

## Priority Fix Order

### Phase A — POS Launch Blockers (fix before any live store can operate)
1. **POS-03 / UX-02** — Barcode + name/SKU search on catalog
2. **SEC-01** — Payment RBAC (CASHIER/MANAGER roles on tender + refund)
3. **POS-01** — Cash management: till float, cash drop, Z-report, X-report
4. **POS-04** — Multi-tender split payment + change-due
5. **SEC-02** — Staff-store scope enforcement (CASHIER locked to their store)

### Phase B — Security Hardening (before public internet exposure)
6. **SEC-03** — Collapse account enumeration error codes
7. **SEC-04** — Security response headers filter at gateway
8. **SEC-11** — Add PATCH to CORS allowed methods
9. **SEC-08** — Bind infra ports to 127.0.0.1 in compose
10. **SEC-09** — Move rate-limit / brute-force counters to Redis

### Phase C — POS Feature Completeness
11. **POS-02** — Suspend / park sale
12. **POS-05** — Manager override approval
13. **POS-10** — Age-restricted product flag + POS prompt
14. **UX-06** — Store context in `/auth/me`
15. **UX-07** — Manager POS session list endpoint

### Phase D — Stock Management
16. **STK-01** — UOM model (foundational; unblocks STK-02, STK-06)
17. **STK-05** — Cross-store transfer
18. **STK-04** — PO approval workflow
19. **STK-03** — RTV workflow
20. **STK-06** — FEFO picking enforcement
