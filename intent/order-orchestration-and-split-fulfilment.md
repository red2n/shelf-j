# Order orchestration and split fulfilment across stores

| | |
|---|---|
| **Status** | CONFIRMED |
| **Author** | the readiness review's Omnichannel & fulfilment row · 2026-09-25 |
| **Roadmap** | Readiness Review, "Order orchestration and split fulfilment across stores", Absent (value) |
| **Services** | order-svc owns the routing decision and the split (it already chooses the delivery store); inventory-svc answers where the stock is and holds it per store; tenant-svc owns the stores, their coordinates and the delivery areas; payment-svc takes one payment for the whole checkout |
| **Builds on** | `OrderService.placeOrder` (the delivery-area store for DELIVERY, holds per line at one store), tenant-svc `GET /fulfilment/resolve` and `delivery_areas`, `stores.geo_lat`/`geo_lng`, inventory-svc reservations and `/inventory/availability`, partial fulfilment, `OrderConfirmed`/`OrderFulfilled`, payment-svc `/payments/online`, the storefront cart and checkout |
| **Built in** | — |

## Problem

A shopper orders four things for delivery. The store that covers their postcode has three of them; the fourth sits on a shelf in the next town. Today the order is refused ("some items just sold out") and the sale is lost, although the business holds everything the shopper wants. Nothing looks past the one store the postcode points at, the stores' coordinates are recorded and never read, and a store that is short cannot hand part of an order to another.

## Outcome

At checkout, a delivery order is routed to the stores that can fill it: the delivery-area store first, and what it cannot hold from the nearest other store that can, as few stores as possible. The shopper sees before paying that their order comes in two parts, from which stores, and pays once. Each part is an order of its own at its own store — held, confirmed, picked, fulfilled, reported and refunded exactly as an order is today — and together they are one checkout the shopper can read as one.

## Who and where

- **Personas** ([PRD §2](../PRD.md)): the online shopper; the store staff who pick each part; the manager who reads sales.
- **Channels:** ONLINE, DELIVERY. PICKUP is collected at one store and is not split (see Out).
- **Scope:** per tenant; the stores of the business that sell (type STORE; warehouses per the open question).
- **Roles that can write:** the shopper at checkout; nothing new for staff.
- **Sandbox tenant:** behaves the same.

## Scope

- **In:**
  - A routing rule, pure and tested: given the lines, the delivery-area store and each candidate store's availability and distance, choose which store fills which line — the delivery-area store first, then the nearest candidate that holds what is left, fewest stores; a line is not split across stores unless no single store holds it (see open questions).
  - inventory-svc answers availability by store for a set of variants in one call (quantities, for the business's own use at checkout, never shown to the shopper).
  - Checkout places the order as an **order group**: one child order per store, each with its own lines, holds, total and store, linked by the group; the shopper's reply names the parts and their stores.
  - Payment for the group once; each child is confirmed from its share (see open questions); cancelling or refunding one part leaves the others.
  - The storefront shows the split before payment ("arrives in 2 parts: 3 items from Leeds, 1 from York") and the order history shows the group with its parts.
  - The cart-svc checkout mismatch the survey found (a DELIVERY order resolved to a different store never marks the cart checked out) is fixed on the way.
- **Out, on purpose:**
  - **Splitting a PICKUP order**: the shopper collects at one store; a short pickup is refused as today (or, later, moved there by transfer — a different row).
  - **Re-routing after payment** (a store that finds it cannot pick): the part is fulfilled short as today; re-routing is its own flow.
  - **Transferring stock to the delivery-area store instead of splitting**: slower, and an existing tool (transfers) a person can use by hand.
  - **Delivery charges per part**, carrier choice, shipment tracking: transport rows.
  - **Geo-polygon delivery areas**: pincode areas stay; distance is straight-line between stores' coordinates.
  - **Showing the shopper stock by store** (buy-online-pick-up-in-store availability): its own row.

## Data and flow

- **Owned by** order-svc: `order_groups` (the checkout: shopper, delivery address, total, currency, status) and `orders.group_id` (null for an order that was never split); each child order keeps its single `store_id`, so every consumer of order events keeps working unchanged.
- **Needs from other services:** availability by store for the lines (inventory-svc, REST, new `POST /inventory/availability/by-store`); the stores with coordinates and the delivery-area store (tenant-svc, REST, `TenantProfiles` for stores, `/fulfilment/resolve` for the area).
- **Events published:** each child's `OrderPlaced`/`OrderConfirmed`/`OrderFulfilled` as today, each carrying its `groupId`; no new consumer is required.
- **Retryable writes** (Idempotency-Key): placing the order — the whole group is created once per key; holds at several stores placed and, on a failure, all released.
- **New error codes:** `ORDER_UNFULFILLABLE` 409 (no combination of stores holds the order); `ORDER_GROUP_NOT_FOUND` 404; payment-svc `PAYMENT_GROUP_AMOUNT_MISMATCH` 400 (if one payment per group).

## Money, time and limits

- **Currency:** the order's, unchanged; each child's total in the same currency; the group total is their sum.
- **Ledger postings:** none new; each child posts as an order does.
- **Dates:** the group's placed instant; each child's as today.
- **Plan limits:** none new.

## Constraints

Database-per-service: order-svc asks inventory-svc and tenant-svc; nothing joins. Every existing consumer assumes one store per order — which is why each part is an order with one store. Holds at several stores must be all-or-nothing from the shopper's point of view. An order never split behaves exactly as today, events included. Money is BigDecimal; a group's parts add up to its total exactly (the last part takes the rounding).

## Open questions

- [x] **What is a part of a split order?** Recommended: a child order per store under an order group — every existing consumer (holds, waves, sales facts, dropship, reporting, store-scoped staff access) keeps its one-store-per-order assumption and keeps working. The alternative is shipments inside one order with a store per line, which keeps one order number but changes every consumer. → **a child order per store under an order group** (the user, 2026-09-25)
- [x] **How is a store chosen for each line?** Recommended: the delivery-area store first; what it cannot hold goes to the nearest other store (straight-line distance) that can hold all of what is left, else the fewest stores; a line is split across stores only when no one store holds it. The alternatives are stock-first (one store that holds everything, however far) or always the fewest stores regardless of distance. → **the delivery-area store first, then the nearest that holds the rest, else the fewest stores; a line split only when it must be** (the user, 2026-09-25)
- [x] **How is a split order paid?** Recommended: once for the whole group, the payment shared across the parts in proportion to their totals (the last part takes the rounding), each part confirmed from its share; a refund or cancellation of one part refunds that part's share. The alternative is a payment per part, which means several card charges for one checkout. → **once for the whole group, shared across the parts by their totals** (the user, 2026-09-25)
- [x] **Do warehouses deliver to shoppers?** Recommended: no — only stores of type STORE fill online orders; a warehouse serves its shops (depot replenishment and cross-docking). The alternative lets a warehouse fill a delivery part directly. → **no: only stores of type STORE fill online orders** (the user, 2026-09-25)

## Acceptance

- [ ] The routing rule: the area store first, the nearest that holds the rest, fewest stores, a line split only when it must be; nothing fits → unfulfillable — pure `RoutingTest`
- [ ] A delivery order the area store can fill alone is one order, exactly as today — `OrderIT`
- [ ] A delivery order the area store cannot fill is placed as a group of child orders at the chosen stores, each with its lines, holds and total; the parts sum to the group — `SplitOrderIT`
- [ ] No combination of stores holds it: `409 ORDER_UNFULFILLABLE`, no hold left anywhere — `SplitOrderIT`
- [ ] The same key places the group once; a hold failing at one store releases the others — `SplitOrderIT`
- [ ] One payment confirms every part from its share; refunding one part leaves the others — `SplitOrderIT`, payment-svc `PaymentIT`
- [ ] Each part's events carry the group and its own store; inventory, waves and reporting see ordinary orders — `SplitOrderIT`, inventory-svc `WaveIT` untouched
- [ ] A PICKUP order is never split; another tenant sees no group — `SplitOrderIT`
- [ ] The cart is marked checked out when the delivery store differs from the cart's — cart-svc test
- [ ] The storefront shows the parts before payment and the history shows the group — widget test
- [ ] Through the gateway: a delivery order split across two stores, paid once, both parts confirmed and fulfilled at their stores — k6 `split-fulfilment-flow`

## Decisions

none yet
