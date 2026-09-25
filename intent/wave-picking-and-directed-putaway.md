# Wave picking and directed putaway strategy

| | |
|---|---|
| **Status** | BUILT |
| **Author** | the readiness review's Supply chain, warehouse & logistics row · 2026-09-25 |
| **Roadmap** | Readiness Review, "Wave picking and directed putaway strategy", Absent (value) |
| **Services** | inventory-svc owns the data · order-svc says which orders are confirmed (`OrderConfirmed`) and fulfils them when a wave is picked (a new event, if confirmed below) · tenant-svc owns the zones, referenced never joined |
| **Builds on** | `reservations` (the holds a confirmed online order has on the shelf), `picking_rules` and their zone priorities (Zone picking rules with priority resolution), `inventory_batches.zone_id`, `move_orders` (intra-store zone moves), `GoodsReceived` and transfer receipts (stock arriving with no zone), the Inventory screen |
| **Built in** | the commit that closes this page (see git log: "feat(inventory,order,app): ten orders, one walk")

## Problem

A store fulfilling online orders picks them one at a time: a person opens an order, walks the aisles for its three lines, comes back, opens the next order and walks the same aisles again. Ten orders are ten walks. Nothing tells the picker which batch to take, so the oldest date is not always the one that leaves, and nothing records what was actually picked short. On the way in, a delivery is booked into the store with no zone at all: the batch exists but nobody knows which aisle or cold room it sits in until someone raises a move order by hand, so the expiring-batches view, the recall and the shelf-gap report all point at "somewhere in the store".

## Outcome

A storekeeper gathers the orders waiting at their store into one wave and gets one pick list, ordered by the walk through the zones and directed to the batch the picking rule chooses (oldest date first, or the rule the business set), with each line saying which orders it serves. They confirm what they picked, short or in full; the stock leaves from exactly those batches and the orders are fulfilled without a second screen. On the way in, goods that arrive with no zone are placed where the business said they go — a rule per product, or the store's default — or, when nothing says, land on a short putaway list for a person to place with one tap, so every batch has a zone from the day it arrives.

## Who and where

- **Personas** ([PRD §2](../PRD.md)): the storekeeper and the store manager; the online shopper sees only that their order was fulfilled sooner.
- **Channels:** back-office (Inventory screen); the orders picked are ONLINE ones for PICKUP or DELIVERY. A till sale (INSTORE) is never waved: it leaves the shelf the moment it is paid.
- **Scope:** per store. A wave is built for one store from the orders confirmed for that store; putaway rules are per store, with a store default.
- **Roles that can write:** whoever holds `stock.transfer` (storekeepers and managers by default; a cashier never): building and picking a wave and placing stock is the same warehouse work as a move order. Putaway rules: OWNER, MANAGER.
- **Sandbox tenant:** behaves the same.

## Scope

- **In:**
  - inventory-svc keeps which online orders are confirmed and not yet fulfilled at each store, from order-svc's `OrderConfirmed` (with their lines), cleared by `OrderFulfilled` and `OrderCancelled`.
  - Build a wave for a store from those orders (all waiting, or the ones named); the pick list is one line per zone, batch and product with the quantity and the orders it serves, in the walk order the picking rule's zone priorities give (zone code order when the rule names none), directed by the resolved picking rule (FEFO, FIFO, grade, zone priority).
  - Confirm the picks line by line, short or in full; complete the wave. Completing deducts the stock from exactly the batches confirmed (SALE movements against each order, consuming the orders' holds) and announces the wave as picked. Cancel a wave: nothing moved, the orders wait for the next one.
  - Putaway rules: a zone per product per store, and a default zone per store. Stock arriving with no zone — a purchase receipt, a transfer received, a manual receipt that named none — is placed by the rule; when no rule matches, a putaway task is raised for the batch with the store default suggested, and a person confirms the zone (or another) with one tap.
  - The Inventory screen's tab for both: waves to build and pick, tasks to place, rules to set.
- **Out, on purpose:**
  - Handheld scanners, printed labels and voice picking: devices are their own rows. The pick list is the screen.
  - Picking for POS or in-store orders: they leave the shelf at the till.
  - Picking move orders or transfers in a wave: a move order already picks itself; waves are for customer orders.
  - Capacity-aware slotting (how much fits in a bay) and re-slotting by velocity: the planogram's `shelf_targets` know capacity per fixture, not per zone, and a fixture has no zone; this is a later row.
  - Packing, cartonisation, courier labels and dispatch: fulfilment ends at "picked and fulfilled"; delivery is Transport and route planning, its own row.
  - Reserving a specific batch at checkout so the wave cannot be surprised: a hold stays what it is (a quantity); the wave directs at build time and re-checks at completion.

## Data and flow

- **Owned by** inventory-svc: `awaiting_orders` and `awaiting_order_lines` (a projection of confirmed online orders per store, cleared when fulfilled or cancelled); `pick_waves` (store, status OPEN → PICKED → COMPLETED | CANCELLED, who and when), `pick_wave_lines` (zone, batch, variant, directed qty, picked qty, walk order) and `pick_wave_allocations` (which order each line serves and how much, append-only once the wave completes); `wave_picked_orders` (an order deducted by a wave, so its later `OrderFulfilled` deducts nothing twice); `putaway_rules` (store, variant or default → zone) and `putaway_tasks` (batch, suggested zone, status OPEN | PLACED). `stock_movements` stays append-only: a wave's deduction is SALE movements against the order, as a fulfilment's is; a placement is not a quantity movement: it sets the batch's zone and records the task's outcome, nothing in `stock_movements`.
- **Needs from other services:** `OrderConfirmed`, `OrderFulfilled`, `OrderCancelled` from order-svc (events, already consumed for other reasons); zone ids from tenant-svc (referenced, never joined; validated as ids only, as a manual receipt's zone is today); the picking rule resolution is inventory-svc's own.
- **Events published:** `WavePicked` `{waveId, storeId, orders: [{orderId, lines: [{variantId, qty}]}]}` on the wave's completion transaction, for order-svc to fulfil those orders (in full, or the picked lines when short — see the open questions). `StockDeducted` per order as today.
- **Retryable writes** (Idempotency-Key): building a wave (`POST /admin/inventory/waves`), so a retried build never makes two waves of the same orders.
- **New error codes:** `INVENTORY_WAVE_NOT_FOUND` 404; `INVENTORY_WAVE_NOTHING_TO_PICK` 409 (no order is waiting at the store, or the named ones are not); `INVENTORY_WAVE_ORDER_IN_ANOTHER_WAVE` 409; `INVENTORY_WAVE_NOT_OPEN` 409 (confirming or completing a wave that is not being picked); `INVENTORY_WAVE_PICK_EXCEEDS_LINE` 400 (more confirmed than directed); `INVENTORY_WAVE_LINE_UNKNOWN` 400; `INVENTORY_PUTAWAY_TASK_NOT_FOUND` 404; `INVENTORY_PUTAWAY_TASK_PLACED` 409; `INVENTORY_PUTAWAY_ZONE_REQUIRED` 400 (no rule, no default, and no zone named). Store scope applies as everywhere on inventory routes (`403 STORE_ACCESS_DENIED`).

## Money, time and limits

- **Currency:** none; nothing here is priced.
- **Ledger postings:** none new; the deduction a wave makes is the same SALE deduction a fulfilment makes.
- **Dates:** when a wave was built, picked and completed, and when a task was raised and placed, all UTC.
- **Plan limits:** none.

## Constraints

Database-per-service: the orders come in through events and go out through one; the zone is an id. Event consumers idempotent: an `OrderConfirmed` redelivered projects once, a `WavePicked` redelivered fulfils once (order-svc's existing fulfil is idempotent on a fulfilled order), an `OrderFulfilled` for an order a wave already deducted deducts nothing more. Append-only stays append-only: `stock_movements` and the wave's allocations once completed. A wave that is never completed holds nothing new: the orders' holds are what they were, so cancelling a wave changes no stock. The picking rule's resolution (`GET /admin/inventory/picking-rules/resolve`) is reused, not reimplemented; consignment and bonded batches are drawn by the same `deductBatches` rules a sale uses (never bonded, consignment announced as sold).

## Open questions

- [x] When a wave is completed, are the orders fulfilled by the platform, or does a person still press Fulfil on each? Recommended: the platform fulfils them — inventory-svc publishes `WavePicked` and order-svc fulfils each order it names, exactly as the Fulfil button does; a wave is the fulfilment. → **the platform fulfils them** (the user, 2026-09-25)
- [x] When a line is picked short, what happens to the order? Recommended: the order is fulfilled for what was picked (order-svc's partial fulfilment) and the shortfall stays waiting for the next wave; a shopper gets most of their order today rather than none. The alternative is to hold the whole order back until everything is there. → **fulfil what was picked; the shortfall waits** (the user, 2026-09-25)
- [x] When stock arrives with no zone and a rule matches, is it placed silently or does a person confirm? Recommended: placed by the rule at once (that is what "directed" means), with the placement visible on the batch; only stock with no matching rule and no store default waits on the putaway list. The alternative is a task for every arrival, confirmed with a tap. → **placed by the rule at once** (the user, 2026-09-25)

## Acceptance

- [x] Two confirmed online orders at a store are projected as waiting with their lines, listed by when order-svc confirmed them (the event's `occurredAt`) whatever order the confirmations arrive in; a redelivered `OrderConfirmed` projects once; a till sale and another store's order are not this store's; a cancelled or hand-fulfilled order leaves the list — `WaveIT.confirmedOrdersWaitAtTheStoreOnce`
- [x] A wave built from them has one line per zone, batch and product in the walk order the picking rule gives, directed FEFO, each line naming the orders it serves; nothing to pick is refused `409 INVENTORY_WAVE_NOTHING_TO_PICK`; an order already in an open wave is refused `409 INVENTORY_WAVE_ORDER_IN_ANOTHER_WAVE`; cancelled, nothing moved and the orders wait again — `WaveIT.aWaveIsOneWalkThroughTheZonesDirectedByTheRule`, pure `WavesTest` (3) for the allocation, the walk and the short-pick share
- [x] Confirming the picks and completing the wave deducts exactly the confirmed batches as SALE movements against each order, reduces the holds by as much, publishes `WavePicked` with the picked quantities, leaves a short pick's remainder waiting, and a later `OrderFulfilled` for those lines records revenue and deducts nothing more while a hand fulfilment still deducts; completed once `409 INVENTORY_WAVE_NOT_OPEN`; more than directed `400 INVENTORY_WAVE_PICK_EXCEEDS_LINE` — `WaveIT.completingAWaveDeductsWhatWasPickedConsumesTheHoldsAndTellsOrderSvcOnce`
- [x] order-svc fulfils the orders a `WavePicked` names for the picked quantities, once per order per event; a refused order is skipped and the rest fulfilled; a malformed event touches nothing — `WavePickedHandlerTest` (3, order-svc)
- [x] A cashier cannot build a wave (`403`); a keeper of another store cannot build one for this store (`403 STORE_ACCESS_DENIED`); another tenant sees no waves and reads none by id — `WaveIT.wavesAreTheStoresOwn`
- [x] A purchase receipt with a matching putaway rule lands in the rule's zone, one with only the store default lands there, a manual receipt keeps the zone it named; with no rule the batch waits on the putaway list, placing needs a zone (`400 INVENTORY_PUTAWAY_ZONE_REQUIRED`), a cashier may not place, a storekeeper places once (`409 INVENTORY_PUTAWAY_TASK_PLACED`), another tenant sees no rule — `PutawayIT.stockArrivingWithNoZoneIsDirectedByTheRuleOrWaitsToBePlaced`
- [x] The Inventory tab lists the orders waiting and the waves, builds a wave with an idempotency key, walks a wave's lines under their zone names, saves the picks and completes, places a batch from the putaway list, and shows a cashier no buttons — `inventory_waves_test` (5)
- [x] Through the gateway: two paid delivery orders waiting in order, a wave walked aisle A then the cold room with the old apples shared, picked one short and completed, order-svc fulfilling one order in full and one in part from the wave alone, the fulfilments deducting nothing twice; a rule placing pears on arrival, apples waiting and placed once — k6 `wave-flow` (20)

## Decisions

- **The projection is inventory-svc's own, from `OrderConfirmed`.** A hold alone cannot tell a confirmed order from one still paying, so the waiting list is built from order-svc's confirmations (ONLINE, PICKUP or DELIVERY only) and reduced by fulfilments and cancellations. Rule 1 kept: no call into order-svc to ask.
- **A wave deducts the batch it directed, and the fulfilment that follows deducts nothing.** Letting `OrderFulfilled` re-run FEFO after the picker took a different batch would make the books disagree with the shelf. So completion draws exactly the confirmed batches, remembers each order line in `wave_picked_lines`, and the fulfilment handler asks that table first: a match records the revenue (gross margin still needs it) and acknowledges the pick; no match runs the ordinary path. Matching is by order, product and exact quantity, which is what order-svc fulfils per wave.
- **A short pick reduces the hold rather than consuming it.** The remainder of the line stays held for the order and stays on the waiting list; it goes in the next wave. A hold emptied is consumed.
- **Orders are served earliest-confirmed first**, both when allocating a batch and when sharing out a short pick, so the customer who ordered first is the one not short-changed.
- **The walk order is the picking rule's zone priorities; other zones follow by id; stock with no zone walks last.** Zone codes live in tenant-svc and are not joined; a business that wants a particular walk states it on a ZONE_PRIORITY rule.
- **Putaway is a hook on every batch insert, and a rule places at once.** Directed means placed: a matching rule (product, else store default) sets the zone inside the arrival's transaction, whatever brought the batch in; only a batch with no rule waits for a person. A placement changes no quantity, so it is not a stock movement.
- **An order waits from when order-svc confirmed it, not from when inventory-svc heard.** Two confirmations ride different partitions and arrive in either order, and the first k6 run listed the second shopper first for that reason. `OrderConfirmed` now carries `occurredAt`, stamped on the confirmation's own transaction (as every event built on `EventPayload.base` already does), and the projection keeps it, so earliest-confirmed first means the customer who paid first, whatever the consumer's luck.
- **`WavePicked` names quantities, not batches.** order-svc fulfils lines; which batch left is inventory-svc's business and is already written in its movements.
- **A wave build is idempotent on its key**, because the button is easy to press twice and two waves of the same orders would be refused anyway; the same key returns the same wave. The key is looked up *before* anything else is judged: the first k6 run answered a retried build with "nothing to pick" because the check came after the waiting list had emptied, which is exactly the retry the key exists for.
