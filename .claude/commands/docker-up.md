# Start the Shelf-J Docker Compose stack

Bring up all infrastructure services (Postgres, PgBouncer, Kafka, Consul, Redis, Zipkin, Prometheus, Grafana, Loki, Tempo) and any already-built business services.

```bash
docker compose up -d $ARGUMENTS
```

Run from the repo root. If `$ARGUMENTS` is empty, starts everything. Pass specific service names to start a subset, e.g. `/docker-up postgres kafka consul`.

After the command, check readiness:
```bash
docker compose ps --format "table {{.Name}}\t{{.Status}}\t{{.Ports}}"
```

Report which services are healthy/unhealthy. If any are unhealthy, run `docker compose logs --tail=20 <service>` and summarise why.

**Standard startup order reminder (runtime, not build order):**
infra (postgres, kafka, consul, redis) → platform (config, gateway) → business services (in any order — they gate on readiness via probes).
