# Comprehensive Service Documentation Index

**Status:** In Progress (3 of 13 services documented)  
**Last Updated:** 2026-06-18  
**Template:** [SERVICE_DOCUMENTATION_TEMPLATE.md](SERVICE_DOCUMENTATION_TEMPLATE.md)

---

## 📚 Documentation Navigation

### ✅ Completed Service Documentation

#### 1. **[Gateway](../platform/gateway/README.md)** — API Entry Point
- **Port:** 8090
- **Role:** Request routing, JWT validation, service discovery, rate limiting
- **Key Topics:**
  - Request journey from client to service
  - Multi-tenancy enforcement at gateway level
  - JWT validation and token claims
  - Service discovery via Consul
  - Rate limiting and CORS
  - Timeout/retry handling
- **Read Time:** 15 min
- **Status:** ✅ Complete (450+ lines)

#### 2. **[IAM Service](../services/iam-svc/README.md)** — Identity & Access Management
- **Port:** 8001
- **Role:** User registration/login, JWT issuance, role management
- **Key Topics:**
  - User registration and authentication flow
  - Password security (BCrypt hashing)
  - Token structure and lifecycle
  - Role management (per-tenant roles)
  - Brute force protection
  - Public vs protected endpoints
- **Read Time:** 15 min
- **Status:** ✅ Complete (400+ lines)

#### 3. **[Tenant Service](../services/tenant-svc/README.md)** — Tenant & Location Management
- **Port:** 8002
- **Role:** Business management, store/zone hierarchy, staff assignment
- **Key Topics:**
  - Tenant creation and onboarding flow
  - Store and zone hierarchy (Tenant → Stores → Zones)
  - Staff assignment per store
  - Multi-tenancy enforcement in queries
  - Event publishing (TenantCreated, StoreCreated, etc.)
  - Integration patterns with other services
- **Read Time:** 20 min
- **Status:** ✅ Complete (450+ lines)

---

### 📋 Planned Service Documentation (In Order of Priority)

#### 4. **Product Service** — Product Catalog Management
- **Port:** 8003
- **Role:** Product catalog, brands, categories, variants, SKU management
- **Key Topics:**
  - Catalog browsing (public) vs admin management
  - Brand and category hierarchy
  - Product variants and SKUs
  - Product status and availability
  - Events: ProductCreated, ProductActivated, VariantAdded
- **Estimated Completion:** Soon
- **Status:** ⏳ Planned

#### 5. **Inventory Service** — Stock Management
- **Port:** 8004
- **Role:** Stock levels, batches, zones, stock movements, thresholds
- **Key Topics:**
  - Receiving stock into zones
  - Stock movement tracking (append-only)
  - Batch management with lot/serial/grade tracking
  - Reorder thresholds and alerts
  - Integration with zones (from tenant-svc)
  - Events: StockReceived, StockAdjusted, LowStockAlert
- **Estimated Completion:** Soon
- **Status:** ⏳ Planned

#### 6. **Pricing Service** — Price Management
- **Port:** 8005
- **Role:** Price lists, price overrides, promotions, discounts, tax handling
- **Key Topics:**
  - Price list creation and maintenance
  - Dynamic price overrides per store
  - Promotion management
  - Price resolution (calculate price for product in store)
  - Tax calculation
  - Events: PriceListCreated, PromotionActivated
- **Estimated Completion:** Soon
- **Status:** ⏳ Planned

#### 7. **Cart Service** — Shopping Cart Management
- **Port:** 8006
- **Role:** Shopping cart creation, item addition, cart persistence
- **Key Topics:**
  - Cart creation and management (guest & authenticated)
  - Add/remove/update item operations
  - Price calculation integration
  - Inventory reservation (via outbox)
  - Cart expiration
  - Events: CartCreated, ItemAdded, CartAbandoned
- **Estimated Completion:** Later
- **Status:** ⏳ Planned

#### 8. **Order Service** — Order Management
- **Port:** 8007
- **Role:** Order creation, order history, order status tracking (online & POS)
- **Key Topics:**
  - Order placement (ONLINE channel & POS channel)
  - Order status tracking
  - Fulfillment types (HOME_DELIVERY, PICKUP, etc.)
  - Channel-sharing (same flow for online & POS)
  - Order cancellation
  - Parked sales (POS holding)
  - Events: OrderCreated, OrderConfirmed, OrderCancelled
- **Estimated Completion:** Later
- **Status:** ⏳ Planned

#### 9. **Payment Service** — Payment Processing
- **Port:** 8008
- **Role:** Payment capture, reconciliation, cash management
- **Key Topics:**
  - Payment method support (card, cash, wallet, etc.)
  - Idempotency for payment capture
  - Cash management and reconciliation
  - Refund handling
  - Payment gateway integration
  - Events: PaymentCaptured, PaymentRefunded, CashCounted
- **Estimated Completion:** Later
- **Status:** ⏳ Planned

#### 10. **Purchase Service** — Procurement Management
- **Port:** 8009
- **Role:** Purchase order creation, goods receipt, supplier management
- **Key Topics:**
  - Purchase order creation and tracking
  - Goods receipt matching
  - Supplier management
  - Intercompany invoicing
  - Three-way matching (PO, receipt, invoice)
  - Events: PurchaseOrderCreated, GoodsReceived
- **Estimated Completion:** Later
- **Status:** ⏳ Planned

#### 11. **Customer Service** — Customer Profile Management
- **Port:** 8010
- **Role:** Customer profiles, loyalty programs, customer data
- **Key Topics:**
  - Customer account management
  - Loyalty program tracking
  - Customer preferences
  - Address management
  - Order history integration
  - Events: CustomerRegistered, LoyaltyPointsEarned
- **Estimated Completion:** Later
- **Status:** ⏳ Planned

#### 12. **Notification Service** — Email/SMS Notifications
- **Port:** 8011
- **Role:** Send emails, SMS, push notifications
- **Key Topics:**
  - Event-driven notifications
  - Email template management
  - Notification history
  - Retry logic for failed sends
  - Consumers of: OrderCreated, PaymentCaptured, UserRegistered, etc.
  - No direct API endpoints (purely async)
- **Estimated Completion:** Later
- **Status:** ⏳ Planned

#### 13. **Reporting Service** — Analytics & Reports
- **Port:** 8012
- **Role:** Sales reports, inventory analytics, customer insights
- **Key Topics:**
  - Report generation (sales, inventory, customer)
  - Time-series data aggregation
  - Dashboard support
  - Data warehouse approach
  - Consumers of: OrderCreated, StockAdjusted, PaymentCaptured, etc.
  - No direct API endpoints (analytics only)
- **Estimated Completion:** Later
- **Status:** ⏳ Planned

---

## 🗺️ How to Use This Documentation

### For System Understanding
1. **Start:** Read [../CLAUDE.md](../CLAUDE.md) for golden rules
2. **Then:** Read [../README.md](../README.md) for architecture overview
3. **Then:** Read this index to understand what each service does
4. **Deep Dive:** Read individual service READMEs in order of interest

### For New Developers
1. **Get context:** [SERVICE_DOCUMENTATION_TEMPLATE.md](SERVICE_DOCUMENTATION_TEMPLATE.md)
2. **Study:** Gateway → IAM → Tenant (the core flow)
3. **Understand:** Product → Inventory (the stock/catalog model)
4. **Learn:** Order → Payment (the commerce flow)
5. **Extend:** Add your service following the same template

### For Adding New Features
1. **Find:** Which service owns this feature?
2. **Read:** That service's README section 2 (Request Dispatching)
3. **Understand:** The layer structure (Resource → Service → Repository)
4. **Look at:** Similar endpoints in that service
5. **Follow:** The pattern exactly

### For Debugging Issues
1. **Symptom:** Service returns 404 / 403 / 500
2. **Check:** That service's README section "Troubleshooting"
3. **Verify:** Request headers (X-Tenant-Id, Authorization)
4. **Read:** "Common Tasks" section for patterns

---

## 🔄 Cross-Service Request Flow

### Example: Customer Places an Order

```
Client
  ├─ POST /api/cart-svc/items                     (add item to cart)
  │   └─ cart-svc: Store item, calls product-svc for prices
  │
  ├─ POST /api/order-svc/orders (from cart)       (place order)
  │   ├─ order-svc: Create order
  │   ├─ Publish: OrderCreated event
  │   └─ Inventory-svc consumes: Reserve stock
  │
  ├─ POST /api/payment-svc/payments (online)      (pay)
  │   ├─ payment-svc: Capture payment
  │   ├─ Publish: PaymentCaptured event
  │   └─ notification-svc: Send receipt email
  │
  └─ GET /api/order-svc/orders/mine               (view order)
      └─ order-svc: Return order details

Services involved: cart, product, order, inventory, payment, notification
Events published: OrderCreated, PaymentCaptured, StockReserved, etc.
```

---

## 📊 Service Dependency Map

```
┌─────────────────────────────────────────────────────────────┐
│                    API Gateway (8090)                        │
│        (JWT validation, routing, rate limiting)              │
└────────────────────────────┬────────────────────────────────┘
                             │
        ┌────────────────────┼────────────────────┐
        │                    │                    │
        ▼                    ▼                    ▼
    ┌────────┐          ┌────────┐          ┌────────┐
    │ IAM    │          │Tenant  │          │Product │
    │ (8001) │          │ (8002) │          │ (8003) │
    └────┬───┘          └────┬───┘          └─────┬──┘
         │                   │                    │
         │ (role mgmt)       │ (tenant/store)     │ (catalog)
         │                   │                    │
        ├───────────────────┴─────────────────────┤
        │                                         │
        ▼                                         ▼
    ┌────────┐     ┌────────┐                ┌──────────┐
    │Inventory      │Pricing │                │Cart (6)  │
    │(8004) │      │(8005)  │                └─────┬────┘
    └──┬─────┘      └─────┬──┘                     │
       │                  │                        │
       └──────────────────┼────────────┐          │
                          │            │          │
                          ▼            ▼          ▼
                      ┌────────┐  ┌────────┐  ┌──────────┐
                      │Order   │  │Payment │  │Notification
                      │(8007)  │  │(8008)  │  │(8011)
                      └────┬───┘  └─────┬──┘  └──────────┘
                           │            │
                           └────────┬───┘
                                    │
                                    ▼
                            ┌──────────────┐
                            │ Reporting    │
                            │ (8012)       │
                            └──────────────┘

Legend:
─────▶ Synchronous call (REST)
· · ▶ Asynchronous (Kafka event)
```

---

## 🔍 Finding Answers to Common Questions

| Question | Answer Location |
|----------|-----------------|
| "How do requests flow through the system?" | [Gateway README](../platform/gateway/README.md) Section 1 |
| "How does multi-tenancy work?" | [CLAUDE.md](../CLAUDE.md) + [Tenant README](../services/tenant-svc/README.md) |
| "How do I register a user?" | [IAM README](../services/iam-svc/README.md) Section 2 + API Endpoints |
| "How does authentication work?" | [IAM README](../services/iam-svc/README.md) Section 4 (JWT) |
| "How do I create a store?" | [Tenant README](../services/tenant-svc/README.md) Section 1 + API Endpoints |
| "How do I add a product?" | [Product README](../services/product-svc/README.md) (coming soon) |
| "How does inventory tracking work?" | [Inventory README](../services/inventory-svc/README.md) (coming soon) |
| "How do orders work?" | [Order README](../services/order-svc/README.md) (coming soon) |
| "How does payment processing work?" | [Payment README](../services/payment-svc/README.md) (coming soon) |
| "How do services talk to each other?" | [CLAUDE.md](../CLAUDE.md) Section 10 + individual service READMEs |
| "What events are published?" | Individual service README Section 6 (Events) |
| "How do I debug a 403 error?" | Service README Section "Troubleshooting" |
| "What's the database schema?" | Service README Section 4 (Technical Architecture) |
| "How do I add a new endpoint?" | Service README Section 11 (Common Tasks) |

---

## 📈 Documentation Coverage

| Service | Status | Location | Lines | Sections |
|---------|--------|----------|-------|----------|
| **Gateway** | ✅ Complete | [README.md](../platform/gateway/README.md) | 450+ | 15 |
| **IAM** | ✅ Complete | [README.md](../services/iam-svc/README.md) | 400+ | 13 |
| **Tenant** | ✅ Complete | [README.md](../services/tenant-svc/README.md) | 450+ | 13 |
| **Product** | ⏳ Planned | [README.md](../services/product-svc/README.md) | — | — |
| **Inventory** | ⏳ Planned | [README.md](../services/inventory-svc/README.md) | — | — |
| **Pricing** | ⏳ Planned | [README.md](../services/pricing-svc/README.md) | — | — |
| **Cart** | ⏳ Planned | [README.md](../services/cart-svc/README.md) | — | — |
| **Order** | ⏳ Planned | [README.md](../services/order-svc/README.md) | — | — |
| **Payment** | ⏳ Planned | [README.md](../services/payment-svc/README.md) | — | — |
| **Purchase** | ⏳ Planned | [README.md](../services/purchase-svc/README.md) | — | — |
| **Customer** | ⏳ Planned | [README.md](../services/customer-svc/README.md) | — | — |
| **Notification** | ⏳ Planned | [README.md](../services/notification-svc/README.md) | — | — |
| **Reporting** | ⏳ Planned | [README.md](../services/reporting-svc/README.md) | — | — |
| **TOTAL** | 23% | — | 1,300+ | 41 |

**Target:** 13 services × 400 lines × 13 sections = ~70,000 lines of comprehensive documentation

---

## 🚀 Next Steps

### Immediate (This Week)
- [ ] Complete Product Service README
- [ ] Complete Inventory Service README
- [ ] Complete Pricing Service README

### Short Term (Next Week)
- [ ] Complete Cart → Payment Services READMEs (commerce flow)
- [ ] Complete Purchase Service README (procurement)
- [ ] Complete Customer Service README

### Medium Term
- [ ] Complete Notification Service README
- [ ] Complete Reporting Service README
- [ ] Add quick-reference diagrams for each service
- [ ] Add database schema diagrams (ERD)
- [ ] Add API endpoint summaries (one-pager per service)

### Long Term
- [ ] Create developer onboarding guide using this documentation
- [ ] Create runbooks for common operational tasks
- [ ] Create decision trees for "where does feature X live?"
- [ ] Create video walkthroughs of key flows

---

## 💡 Standards Maintained

All service documentation follows:
- **Template:** [SERVICE_DOCUMENTATION_TEMPLATE.md](SERVICE_DOCUMENTATION_TEMPLATE.md)
- **Structure:** 
  1. Quick Overview (table)
  2. How Requests Reach Service
  3. Request Dispatching
  4. Service Role & Responsibilities
  5. Technical Architecture
  6. API Endpoints
  7. Event-Driven Architecture
  8. Interservice Communication
  9. Error Handling & Resilience
  10. Testing Strategy
  11. Common Tasks
  12. Troubleshooting
  13. References
- **Length:** 400-500 lines per service
- **Audience:** New developers, architects, operators
- **Goal:** Complete understanding without reading source code

---

## 📞 Questions?

- **How do I...?** → Check Service README's "Common Tasks" section
- **Why does X...?** → Check [CLAUDE.md](../CLAUDE.md) and [README.md](../README.md)
- **Where is X...?** → Use "Finding Answers" table above
- **Report unclear documentation?** → File issue with section number

---

**Last Updated:** 2026-06-18  
**Maintained By:** Platform Team  
**Total Words:** ~1,300+ (completed) / ~70,000+ (target)
