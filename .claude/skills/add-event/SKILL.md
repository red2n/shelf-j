---
name: add-event
description: Add a Kafka domain event to Shelf-J — define the contract, publish via the transactional outbox, and consume idempotently. Use when introducing a new event or wiring a service to react to one.
---

# Add a Kafka event (publish via outbox / consume idempotently)

Use when a service needs to **announce** something happened, or **react** to another service's event. Events are how services stay consistent without sharing a database ([golden rules](../../../CLAUDE.md) #1, #6, #7).

> Context: [ARCHITECTURE §11 how services talk](../../../docs/ARCHITECTURE.md#11-how-services-talk-to-each-other) (sync map + event map) and [§12 key workflows](../../../docs/ARCHITECTURE.md#12-key-workflows) (saga), the publisher/consumer list per service in [docs/API-GUIDE.md](../../../docs/API-GUIDE.md).

## Decide first

1. **Is an event the right tool?** Use an **event** when you're announcing a fact others may care about ("X happened"). Use a **REST call** (`client/`) when you need an answer *now* to continue. Don't use events for request/response.
2. **Name** — `PascalCase`, **past tense**, the thing that happened: `OrderPlaced`, `StockReceived`, `StoreCreated`. Not commands (`CreateOrder` ✗).
3. **Topic** — `shelfj.<domain>.<event>` (e.g. `shelfj.orders.order-placed`).
4. **Owner = publisher.** Only the service that owns the data publishes the event. Confirm publisher + consumers against [docs/API-GUIDE.md](../../../docs/API-GUIDE.md) (events per service) and [ARCHITECTURE §11](../../../docs/ARCHITECTURE.md#11-how-services-talk-to-each-other) (event map).

## Producer side (the service that owns the change)

1. **Define the contract in `shared/events-contract`** (NOT inside the service): a versioned schema (JSON/Avro) + generated POJO. Include: event id (UUID), `tenantId`, occurredAt (UTC), aggregate id (e.g. `orderId`), and only the fields consumers need. Keep it additive/backward-compatible when evolving (new optional fields; never repurpose a field).
2. **Write to the outbox in the same DB transaction** as the state change:
   - the service writes its business rows **and** an `outbox` row (`id, event_type, topic, payload, created_at, published_at NULL`) atomically;
   - this guarantees the event and the data agree — no "saved but never published" or vice-versa.
3. **Drain the outbox** in `messaging/`: a publisher polls unpublished `outbox` rows, sends to Kafka, marks `published_at`. At-least-once delivery (a row may be sent twice — that's why consumers are idempotent).

## Consumer side (each reacting service)

1. **Consume in `messaging/`** via MicroProfile Reactive Messaging (Kafka connector), but **delegate the actual work to `service/`** — no business logic in the consumer.
2. **Be idempotent.** The same event may arrive more than once. Dedupe by **event id** (or a natural business key): keep a `processed_events` table (or unique constraint) and skip if already handled. Processing twice must equal processing once.
3. **Tenant-scope the effect.** Use the `tenantId` from the event payload to write into this service's own tenant-scoped tables.
4. **Build projections, don't reach back.** If you need the publisher's data, keep a **local read-model** updated from its events (CQRS) — never query/join the publisher's database.
5. **Handle poison messages.** On repeated failure, route to a dead-letter topic and alert; don't block the partition forever.

## Saga note (multi-service workflows)

If this event is part of a workflow with compensation (e.g. checkout: reserve → pay → confirm, release on failure), the **coordinator** (usually `order-svc`) owns the saga and the compensating actions. Make sure each step's failure has a defined, tested compensation. See the checkout saga in [ARCHITECTURE §12](../../../docs/ARCHITECTURE.md#12-key-workflows).

## Self-check

- [ ] Past-tense name; topic `shelfj.<domain>.<event>`.
- [ ] Contract in `shared/events-contract`, versioned, additive.
- [ ] Producer writes event to **outbox** in the same tx as the data.
- [ ] Outbox drainer publishes + marks published.
- [ ] Every consumer is **idempotent** (dedupe on event id) and tenant-scoped.
- [ ] Consumers delegate to `service/`, build projections (no cross-DB joins).
- [ ] Saga steps (if any) have tested compensations.
- [ ] Integration test (Testcontainers Kafka) covers publish + consume + idempotent replay.
