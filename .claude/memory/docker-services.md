---
name: docker-services
description: Docker Compose service names, local ports, and health check paths for the Shelf-J stack. Use when running docker commands or diagnosing startup issues.
metadata:
  type: reference
---

# Shelf-J Docker Compose — service reference

## Infrastructure

| Service | Local port | Health |
|---|---|---|
| `postgres` | 5432 | `pg_isready` |
| `pgbouncer` | 6432 | TCP check |
| `kafka` | 9092 (ext), 29092 (internal) | topic list |
| `zookeeper` | 2181 | — |
| `consul` | 8500 (UI + API) | `GET /v1/health/state/any` |
| `redis` | 6379 | `redis-cli ping` |

## Observability

| Service | Local port | UI path |
|---|---|---|
| `zipkin` | 9411 | `/zipkin` |
| `prometheus` | 9090 | `/graph` |
| `grafana` | 3000 | `/` (admin/admin) |
| `loki` | 3100 | — |
| `tempo` | 3200, 4317 (OTLP) | — |
| `pgadmin` | 5050 | `/` |

## Business service local ports (dev only — prod all use 8080)

| Service | Local port |
|---|---|
| `iam-svc` | 8001 |
| `tenant-svc` | 8002 |
| `product-svc` | 8003 |
| `inventory-svc` | 8004 |
| `pricing-svc` | 8005 |
| `cart-svc` | 8006 |
| `order-svc` | 8007 |
| `payment-svc` | 8008 |
| `purchase-svc` | 8009 |
| `customer-svc` | 8010 |
| `notification-svc` | 8011 |
| `reporting-svc` | 8012 |
| `gateway` | 8080 |

## Common DB connection

```
jdbc:postgresql://localhost:5432/shelfj
User: shelfj  Password: shelfj_dev_change_me
```

Each service uses its own schema (e.g. `iam`, `tenant`, `inventory`).
