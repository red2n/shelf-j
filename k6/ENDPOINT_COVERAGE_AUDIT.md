# Endpoint Coverage Audit — What's Tested vs What's Outstanding

**Date:** 2026-06-18  
**Baseline:** Extracted from `services/*/api/*Resource.java`  
**Purpose:** Show which endpoints are tested, which are deferred, and which are missing from flow guard.

---

## iam-svc (AuthResource + MeResource)

### Current Implementation
```
@Path("/auth")
  POST /auth/register ✅ TESTED
  POST /auth/login ⚠️ Available (not in flow)
  GET /auth/me ✅ TESTED
  POST /auth/logout ⚠️ Available (not tested — destructive)
  POST /auth/refresh ⚠️ Available (not tested)
  POST /auth/change-password ⚠️ Available (not tested)
```

**Status:** 2/6 endpoints in flow guard test  
**Outstanding:** Login, logout, refresh, change-password (all available, intentionally deferred)

---

## tenant-svc (OnboardingResource + AdminResource)

### Onboarding Path
```
@Path("/onboarding")
  POST /onboarding/tenants ✅ TESTED
  POST /onboarding/stores ✅ TESTED
  GET /onboarding/status ✅ TESTED
```

### Admin Path — Tenant Management
```
@Path("/admin/tenant")
  GET /admin/tenant ✅ TESTED
  PUT /admin/tenant ✅ TESTED
```

### Admin Path — Store Management
```
@Path("/admin/stores")
  POST /admin/stores ⚠️ Alternative to onboarding/stores (not tested)
  GET /admin/stores ✅ TESTED
  GET /admin/stores/{storeId} ✅ TESTED
  PUT /admin/stores/{storeId} ✅ TESTED
  PATCH /admin/stores/{storeId}/status ✅ TESTED
```

### Admin Path — Zone Management
```
@Path("/admin/stores/{storeId}/zones")
  GET /admin/stores/{storeId}/zones ✅ TESTED
  POST /admin/stores/{storeId}/zones ✅ TESTED
  GET /admin/stores/{storeId}/zones/{zoneId} ✅ TESTED
  PUT /admin/stores/{storeId}/zones/{zoneId} ✅ TESTED
  PATCH /admin/stores/{storeId}/zones/{zoneId}/status ✅ TESTED
```

### Admin Path — Staff Management
```
@Path("/admin/staff")
  POST /admin/staff ✅ TESTED
  GET /admin/staff ✅ TESTED
  DELETE /admin/staff/{userId} ⚠️ Available (not tested — destructive)
```

### Config Endpoints (NOT YET TESTED)
```
@Path("/admin/inventory-config")
  GET /admin/inventory-config ❌ NOT TESTED
  PUT /admin/inventory-config ❌ NOT TESTED
```

**Status:** 13/15 endpoints in flow guard test  
**Outstanding:**
- `POST /admin/stores` — Alternate path to onboarding (redundant)
- `DELETE /admin/staff/{userId}` — Destructive operation
- `GET/PUT /admin/inventory-config` — Inventory org params (Gap #38 from AUDIT.md)

---

## product-svc (CatalogResource + AdminResource)

### Catalog Path (Public/Storefront View)
```
@Path("/catalog")
  GET /catalog/products ✅ TESTED
  GET /catalog/products/{id} ✅ TESTED
  GET /catalog/products/{id}/variants ✅ TESTED
  GET /catalog/categories ⚠️ Available (not tested — low priority)
```

### Admin Path — Brands
```
@Path("/admin/brands")
  POST /admin/brands ✅ TESTED
  GET /admin/brands ✅ TESTED
  GET /admin/brands/{id} ✅ TESTED
  PUT /admin/brands/{id} ✅ TESTED
  DELETE /admin/brands/{id} ⚠️ Available (not tested — destructive)
```

### Admin Path — Categories
```
@Path("/admin/categories")
  POST /admin/categories ✅ TESTED
  GET /admin/categories ✅ TESTED
  GET /admin/categories/{id} ⚠️ Available (not tested)
  PUT /admin/categories/{id} ⚠️ Available (not tested)
  DELETE /admin/categories/{id} ⚠️ Available (not tested — destructive)
```

### Admin Path — Products
```
@Path("/admin/products")
  POST /admin/products ✅ TESTED
  GET /admin/products ✅ TESTED
  GET /admin/products/{id} ✅ TESTED
  PUT /admin/products/{id} ✅ TESTED
  DELETE /admin/products/{id} ⚠️ Available (not tested — destructive)
```

### Admin Path — Variants
```
@Path("/admin/products/{id}/variants")
  POST /admin/products/{id}/variants ✅ TESTED
  GET /admin/products/{id}/variants ✅ TESTED
  GET /admin/products/{id}/variants/{varId} ⚠️ Available (not tested)
  PUT /admin/products/{id}/variants/{varId} ⚠️ Available (not tested)
  DELETE /admin/products/{id}/variants/{varId} ⚠️ Available (not tested — destructive)
```

### Deferred: PIM Admin (NOT TESTED — deliberately deferred per AUDIT.md)
```
@Path("/admin/templates")      — Bulk product templates (~8 endpoints)
@Path("/admin/uom-classes")    — Unit of measure setup (~6 endpoints)
@Path("/admin/uom-conversions")— Lot-level UOM conversions (~4 endpoints)
@Path("/admin/cross-refs")     — Customer item cross-references (~4 endpoints)
@Path("/admin/relationships")  — Substitute/complementary (~4 endpoints)
@Path("/admin/revisions")      — Product revisions & supersession (~8 endpoints)
@Path("/admin/catalog-groups") — Catalog grouping & flexfields (~12 endpoints)
@Path("/admin/attr-groups")    — 18 attribute groups (~18 endpoints)
@Path("/admin/containers")     — Cartonization & container types (~4 endpoints)
@Path("/admin/category-sets")  — Multi-set category model (~8 endpoints)
@Path("/admin/import")         — Bulk CSV/JSON import (~3 endpoints)
```
**Deferred count:** ~82 endpoints

**Status:** 15/20 basic endpoints in flow guard test (75%)  
**Outstanding:**
- `GET/PUT /admin/categories/{id}` — Category detail operations
- `GET/PUT/DELETE /admin/brands/{id}` & variants — Delete operations
- `/admin/products/{id}/variants/{varId}` — Variant details/update/delete
- ~82 PIM endpoints — Deliberately deferred (advanced WMS + templates)

---

## inventory-svc (AdminResource + ReservationResource)

### Admin Path — Inventory Operations
```
@Path("/admin/inventory")
  POST /admin/inventory/receive ✅ TESTED
  POST /admin/inventory/adjust ✅ TESTED
  GET /admin/inventory/levels ✅ TESTED
  GET /admin/inventory/batches ✅ TESTED
  GET /admin/inventory/batches/{id} ✅ TESTED
  GET /admin/inventory/movements ✅ TESTED
  POST /admin/inventory/thresholds ✅ TESTED
  GET /admin/inventory/thresholds ✅ TESTED
```

### Retail Path — Reservations
```
@Path("/inventory/reservations")
  POST /inventory/reservations ✅ TESTED
  GET /inventory/reservations ✅ TESTED
  GET /inventory/reservations/{id} ✅ TESTED
  POST /inventory/reservations/{id}/consume ✅ TESTED
  POST /inventory/reservations/{id}/release ✅ TESTED
```

### Deferred: Advanced WMS (NOT TESTED — deliberately deferred per AUDIT.md)
```
@Path("/admin/inventory/transfers")     — Inter-org/intra-store transfers (~12 endpoints)
@Path("/admin/inventory/move-orders")   — Pick waves & replenishment (~16 endpoints)
@Path("/admin/inventory/cycle-counts")  — Count planning & execution (~12 endpoints)
@Path("/admin/inventory/physical-inv")  — PI reconciliation (~8 endpoints)
@Path("/admin/inventory/abc-analysis")  — ABC classification (~6 endpoints)
@Path("/admin/inventory/safety-stock")  — Safety stock calculation (~4 endpoints)
@Path("/admin/inventory/rop-planning")  — Reorder point planning (~4 endpoints)
@Path("/admin/inventory/kanban")        — Kanban replenishment rules (~8 endpoints)
@Path("/admin/inventory/serials")       — Serial number genealogy (~6 endpoints)
@Path("/admin/inventory/lots")          — Lot genealogy & genealogy (~8 endpoints)
@Path("/admin/inventory/reason-codes")  — Transaction reasons (~6 endpoints)
@Path("/admin/inventory/source-codes")  — Transaction sources (~4 endpoints)
@Path("/admin/inventory/par-levels")    — PAR level replenishment (~4 endpoints)
@Path("/admin/inventory/zone-gl-map")   — Zone-to-GL account mapping (~2 endpoints)
@Path("/admin/inventory/picking-rules") — FIFO/FEFO/LIFO/ZONE_PRIORITY (~4 endpoints)
```
**Deferred count:** ~104 endpoints

**Status:** 13/13 core endpoints ✅ 100%  
**Outstanding:** All ~104 advanced WMS endpoints (deliberately deferred)

---

## Services Not Yet Audited

The following services are **not represented in current k6 tests** (out of scope for flow guard v1):

### order-svc (NOT IN FLOW GUARD)
- Online checkout flow (cart + order creation)
- POS order flow (till + multi-tender)
- Special orders, layaways, gift cards
- Returns & refunds
- POSLog & receipt printing
- Approx. 40+ endpoints

### payment-svc (NOT IN FLOW GUARD)
- Payment processing (online + POS)
- Till sessions & cash management
- Z-reports & reconciliation
- Approx. 30+ endpoints

### customer-svc (NOT IN FLOW GUARD)
- Customer master data
- Addresses & loyalty
- Store credit
- Customer self-service path
- Approx. 25+ endpoints

### purchase-svc (NOT IN FLOW GUARD)
- Purchase requisitions
- Purchase orders
- Goods receipt
- Supplier management
- Intercompany invoicing
- Approx. 35+ endpoints

### pricing-svc (NOT IN FLOW GUARD)
- Price list management
- Dynamic pricing rules
- Promotions & discounts
- VAT/tax configuration
- Approx. 30+ endpoints

### cart-svc (NOT IN FLOW GUARD)
- Shopping cart operations
- Guest & customer carts
- Checkout workflow
- Approx. 15+ endpoints

### notification-svc (NOT IN FLOW GUARD)
- Shortage alerts
- Email/SMS dispatch
- Notification preferences
- Approx. 10+ endpoints

### reporting-svc (NOT IN FLOW GUARD)
- On-hand reporting
- Supply-demand netting
- Movement statistics
- Approx. 12+ endpoints

---

## Summary Table

| Service | Category | Endpoints | Tested | % | Notes |
|---------|----------|-----------|--------|---|-------|
| **iam-svc** | Auth | 6 | 2 | 33% | Basic flow only |
| **tenant-svc** | Org/Location | 15 | 13 | 87% | Config endpoints missing |
| **product-svc** | Catalog | 102 | 15 | 15% | 82 deferred PIM endpoints |
| **inventory-svc** | Core Inventory | 13 | 13 | 100% | 104 deferred WMS endpoints |
| **order-svc** | Commerce | 40+ | 0 | 0% | Out of scope v1 |
| **payment-svc** | Payments | 30+ | 0 | 0% | Out of scope v1 |
| **customer-svc** | CRM | 25+ | 0 | 0% | Out of scope v1 |
| **purchase-svc** | Procurement | 35+ | 0 | 0% | Out of scope v1 |
| **pricing-svc** | Pricing | 30+ | 0 | 0% | Out of scope v1 |
| **cart-svc** | Shopping | 15+ | 0 | 0% | Out of scope v1 |
| **notification-svc** | Alerts | 10+ | 0 | 0% | Out of scope v1 |
| **reporting-svc** | Analytics | 12+ | 0 | 0% | Out of scope v1 |
| | | **345+** | **47** | **14%** | **Focus on happy path** |

---

## Immediate Next Steps

### 1. Complete tenant-svc inventory config (2 endpoints)
Add to flow guard v2:
```javascript
// POST /api/tenant-svc/admin/inventory-config
const cfgRes = put(
  '/api/tenant-svc/admin/inventory-config',
  {
    lotControlEnabled: true,
    serialControlEnabled: false,
    costingMethod: 'FIFO',
    defaultUom: 'EA',
  },
  tenantId,
  userId
);

// GET /api/tenant-svc/admin/inventory-config
const getCfgRes = get('/api/tenant-svc/admin/inventory-config', tenantId, userId);
```

### 2. Add product-svc category/variant detail endpoints (4 endpoints)
```javascript
// GET /api/product-svc/admin/categories/{id}
// PUT /api/product-svc/admin/categories/{id}
// GET /api/product-svc/admin/products/{id}/variants/{varId}
// PUT /api/product-svc/admin/products/{id}/variants/{varId}
```

### 3. Build flow guard v2: Order → Payment → Customer (30+ endpoints)
Would test:
- Cart creation & checkout
- Order placement & fulfillment
- Payment processing
- Customer profile & loyalty

### 4. Build flow guard v3: Advanced Inventory (104 endpoints)
Would test:
- Transfers & move orders
- Cycle counting
- ABC analysis
- Kanban replenishment
- Serial/lot genealogy

---

## Running Individual Service Tests

For now, test each service's CRUD flow separately:

```bash
# Core tenants/stores/zones
k6 run k6/flow-guard-comprehensive.js

# Product catalog
k6 run k6/product-crud.js

# Inventory operations
k6 run k6/inventory-crud.js

# Full stack with concurrent workloads
k6 run k6/full-stack-simulation.js
```

Each will exercise the happy path for that service's primary endpoints.

---

## Validation Checklist for This Release

Before declaring flow guard v1 complete:

- [ ] Run `k6 run k6/flow-guard-comprehensive.js` — 47 checks pass
- [ ] All latencies < 100ms p95
- [ ] No flow_errors in summary
- [ ] Database validation passes: `./k6/db/validate_all.sh`
- [ ] No service errors in logs (grep `ERROR` on docker logs)
- [ ] Can repeat test 3x without data conflicts
