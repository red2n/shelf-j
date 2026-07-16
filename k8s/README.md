# Shelf-J on k3s — storeql.com

Kubernetes manifests to run the whole Shelf-J stack on a single-node k3s cluster, for
the storeql.com production deployment (5-10 tenants, moderate load). This is the k3s
counterpart to `docker-compose.yml` / `docker-compose.prod.yml` + `docs/vps-deployment.md`
— read those first if anything here is unclear about *why* a piece of config exists;
this README only covers what's different for k3s.

## What's deployed vs. what's trimmed

**Deployed:** Postgres, PgBouncer, Consul (dev-agent mode, same as compose), Kafka
(KRaft), Redis, config-svc, gateway, all 12 business services, the Flutter web app,
Prometheus + Grafana + node/postgres/redis exporters, cert-manager-issued TLS on
`app.storeql.com` / `api.storeql.com` / `storeql.com`.

**Not deployed** (trim decision — see the conversation that produced this): pgAdmin,
Kafka UI, Swagger UI (dev-only conveniences, extra exposed surface), Zipkin, Loki,
Tempo, otel-collector (the tracing/log-aggregation stack — Prometheus scrapes every
service's `/metrics` directly, independent of otel-collector, so dropping it does not
affect metrics). Add these back later as their own manifests if you need distributed
tracing or centralized log search — nothing here depends on them.

## Prerequisites

1. **k3s installed** on the VPS:
   ```bash
   curl -sfL https://get.k3s.io | sh -
   # kubeconfig: /etc/rancher/k3s/k3s.yaml (copy to ~/.kube/config or export KUBECONFIG)
   ```
   k3s ships Traefik (ingress) and the `local-path` StorageClass by default — both are
   used as-is below, nothing extra to install for those two.

2. **cert-manager** (not bundled with k3s):
   ```bash
   kubectl apply -f https://github.com/cert-manager/cert-manager/releases/latest/download/cert-manager.yaml
   kubectl -n cert-manager wait --for=condition=Available deployment --all --timeout=120s
   ```

3. **DNS A records** at your registrar/DNS host, pointed at the VPS's public IP —
   *not* at the Squarespace parking nameservers currently set for storeql.com. You'll
   need to either point storeql.com's nameservers at a DNS provider you control (e.g.
   Hostinger's own DNS, Cloudflare, etc.) or manage records wherever the domain's
   nameservers currently resolve, then add:
   | Host | Value |
   |---|---|
   | `storeql.com` | `<vps-public-ip>` |
   | `app.storeql.com` | `<vps-public-ip>` |
   | `api.storeql.com` | `<vps-public-ip>` |

   Verify before deploying: `dig +short app.storeql.com api.storeql.com storeql.com`
   should all return the VPS IP. cert-manager's HTTP-01 challenge (port 80) will fail
   until this propagates.

4. **Firewall:** open 80 and 443 only. Everything else (Postgres, Redis, Consul,
   Grafana, Prometheus) is ClusterIP-only — not reachable outside the cluster network,
   same "internal ops tools via tunnel" posture as `docs/vps-deployment.md` §11 (use
   `kubectl port-forward` instead of an SSH tunnel to reach them from your workstation).

5. **`GHCR` images are public** — `ghcr.io/red2n/shelf-j-*` needs no `imagePullSecret`
   (confirmed: `docker-publish.yml` pushes every service + the web bundle there on
   every merge to `main`).

## Deploy order

```bash
cd k8s

# 1. Namespace + all secrets (generates random passwords; re-run-safe, never overwrites
#    a secret that already exists)
./generate-secrets.sh

# 2. Everything else
kubectl apply -f 00-namespace.yaml
kubectl apply -f 02-configmaps.yaml
kubectl apply -f 10-postgres.yaml -f 11-pgbouncer.yaml -f 12-consul.yaml \
               -f 13-kafka.yaml -f 14-redis.yaml
kubectl apply -f 15-observability.yaml
kubectl apply -f 20-platform-config-svc.yaml
kubectl apply -f 30-business-services.yaml
kubectl apply -f 40-gateway.yaml -f 41-web.yaml
kubectl apply -f 60-ingress.yaml   # after cert-manager is installed (prereq #2)

# 3. Optional: load the existing Grafana dashboard (not templated as a static
#    ConfigMap since it's 587 lines — generated straight from the repo file so it
#    can never drift out of sync)
kubectl -n shelf-j create configmap shelfj-grafana-dashboards \
  --from-file=../infra/grafana/dashboards/
kubectl -n shelf-j rollout restart deployment/grafana

# 4. Once gateway + Postgres are healthy, seed the platform admin
kubectl apply -f 50-bootstrap-job.yaml
kubectl -n shelf-j logs job/shelfj-bootstrap
```

There is deliberately no strict linear ordering enforced between steps 2's manifests —
same as ARCHITECTURE.md §17: services start in any order and gate on their own
readiness probes (`@Retry`/`@CircuitBreaker`/`@Fallback` already handle a dependency
not being up yet). `kubectl apply -f k8s/` (everything at once, still skipping the
bootstrap Job and ingress until their prerequisites are met) works fine too.

Retrieve the generated platform admin password:
```bash
kubectl -n shelf-j get secret shelfj-platform-admin -o jsonpath='{.data.PLATFORM_ADMIN_PASSWORD}' | base64 -d; echo
```

## Verify

```bash
kubectl -n shelf-j get pods                       # everything Running/Ready
kubectl -n shelf-j get certificate                 # 3 certs, READY=True (can take ~1 min)
curl https://api.storeql.com/api/iam-svc/health/ready
curl -I https://app.storeql.com/healthz
curl -I https://storeql.com                        # 301 -> https://app.storeql.com
```

Platform admin login: `https://app.storeql.com/#/platform/login`.

## Decisions made building this (context for later changes)

- **JVM heap / container memory sizing is UNCHANGED from `docker-compose.yml`'s own
  defaults** (e.g. iam-svc `-Xmx512m` / 700Mi limit), not doubled per `.env.example`'s
  "32 GB VPS full load" guidance. Total limits across the stack come to roughly 25-27
  GiB on this 32 GiB box; requests (what's actually reserved for scheduling) are far
  lower (~13-15 GiB). At "moderate load, 5-10 tenants" the extra headroom is worth
  more as room to **horizontally scale a hot service later** (bump `replicas:` on
  order-svc/gateway under real traffic) than as bigger heaps per single replica. If
  you observe real memory pressure (not just idle usage) under load-testing, doubling
  the `JAVA_TOOL_OPTIONS` Xmx values and mem limits per `.env.example`'s guidance is
  the documented next step.
- **Postgres tuning was bumped one step** (`shared_buffers=1GB`, `effective_cache_size=4GB`,
  vs. the dev file's 512MB/2GB) since a bigger shared Postgres cache helps every tenant
  simultaneously, unlike per-JVM heaps.
- **Flyway migrations run inline at each service's boot**, exactly like
  `docker-compose.yml` and the live storeql.com VPS deployment today — there is no
  standalone "migrate as a separate Job" entry point in this codebase yet (the
  `FlywayRunner` class in `shared/common-service` explicitly documents this as a
  known gap vs. the aspirational model in `ARCHITECTURE.md` §17). Safe at
  `replicas: 1` everywhere (no concurrent-migration race) — re-verify before ever
  scaling a business service beyond 1 replica.
- **`SHELFJ_ADVERTISE_HOST` must equal each Deployment's k8s Service name.** Confirmed
  by reading `ConsulRegistrar`/`ConsulClient`: every service registers in Consul with
  `Address = SHELFJ_ADVERTISE_HOST`, and every peer (gateway included) resolves others
  by looking them up in Consul and connecting to that stored address directly — not
  via a fresh k8s DNS lookup. Get this wrong for any one service and nothing else can
  reach it, even though k8s DNS itself would resolve the name fine.
- **Consul stays in `-dev` mode** (in-memory, single node, no ACLs) — this is not a
  regression vs. today: `docker-compose.prod.yml`'s storeql.com overlay doesn't harden
  Consul either. Redesigning that is a separate, later piece of work.
- **PgBouncer's `DATABASES` points at the plain hostname `postgres`.** docker-compose.yml
  works around a pgbouncer/Docker-specific DNS bug (c-ares chokes on Docker's `search .`
  resolv.conf) with a pinned IP + `extra_hosts`. Kubernetes' resolv.conf is a normal
  search-domain list, so that specific bug shouldn't recur — but if pgbouncer logs
  `server DNS lookup failed (bad-af)` on `postgres`, add a `hostAliases` entry in
  `11-pgbouncer.yaml` pointing `postgres` at the `postgres` Service's ClusterIP as the
  fallback.
- **`shelf-j-web`'s API base is baked in at CI build time**, not runtime-configurable.
  Two things both work correctly regardless of what's baked in: if `SHELFJ_API_BASE`
  was never set as a GitHub Actions repo variable, the published image bakes the
  relative default `/api` — the bundle's nginx (`infra/nginx-spa.conf`) then proxies
  same-origin `/api/` calls straight to the `gateway` k8s Service, which works fine
  inside the cluster network exactly like it does in docker-compose. If you do set
  `SHELFJ_API_BASE=https://api.storeql.com/api` as a GitHub Actions variable (per
  `docs/vps-deployment.md` §4) and rebuild, the browser instead calls `api.storeql.com`
  directly. Either is fine; you don't strictly need to touch the GitHub setting for
  this to work in k3s.
- **`order-svc`'s `SHELFJ_ORDER_PRICING_ENFORCE` is set to `"true"`** (documented hard
  requirement for production in `docs/vps-deployment.md` §5). `SHELFJ_ORDER_RESERVE_ENFORCE`
  is left at `"false"` — flip it to `"true"` once you've actually seeded inventory for
  your tenants, or every online checkout will 409 on stock.
