// Health probes, against the running stack: the separation a pod's life depends on.
//
// Liveness is the only probe whose failure kills the container, so it must ask one question — is
// this process wedged? — and never reach a dependency. Readiness may reach the database, because
// failing it only takes the replica out of rotation. The aggregate /health holds every check and is
// therefore the one endpoint no liveness probe may be pointed at: point it there and a slow
// database becomes a restart storm, with every replacement pod hitting the same slow database.
//
// This suite proves the split is real rather than documented: /health/live carries no database
// check on a business service or on the gateway, /health/ready carries one, /health carries the lot.
// It also drives the deep check, /admin/health, which makes a real round trip and prints the pool's
// own figures — the one way to tell "the database is down" from "the pool is empty and the database
// is fine", which look identical from a probe and want opposite remedies.
// Refused: the probes are not published to the internet, and the deep check is management's alone.
//
//   k6/run.sh health-probes
import { Counter } from 'k6/metrics';
import { ALL_CHECKS_PASS, call, data, expect, onboardTenant, staffUser, truthy, uniq } from './lib/shelfj.js';

const completed = new Counter('flow_completed');
export const options = {
  vus: 1,
  iterations: 1,
  thresholds: { ...ALL_CHECKS_PASS, flow_completed: ['count==1'] },
  setupTimeout: '4m',
};

// One business service stands for all twelve: they answer these three endpoints from the same
// shared code, so what is true of one is true of the rest.
const SVC = '/api/product-svc';

/** The names of the checks in a MicroProfile health document. */
function checkNames(res) {
  try {
    return (JSON.parse(res.body).checks || []).map((c) => c.name);
  } catch (e) {
    return [`unreadable: ${String(res.body).slice(0, 120)}`];
  }
}

function status(res) {
  try {
    return JSON.parse(res.body).status;
  } catch (e) {
    return null;
  }
}

export default function () {
  const tag = uniq().toUpperCase().slice(0, 8);
  const shop = onboardTenant(`health-${tag}`, { country: 'GB', currency: 'GBP' });
  const owner = shop.owner.token;
  const probe = (path) => call('GET', `${SVC}${path}`, { token: owner });

  // ── liveness: the probe that kills the pod touches nothing ───────────────────────────────────────
  const live = probe('/health/live');
  expect(live, '[+] a service answers its liveness probe', 200);
  truthy('[+] and it is UP', status(live) === 'UP', live.body);
  const liveChecks = checkNames(live);
  truthy('[+] liveness carries no database check — a slow database must not kill the pod', !liveChecks.includes('database'), liveChecks);
  truthy('[+] nor any other dependency: one check, the process itself', liveChecks.length === 1 && liveChecks[0] === 'product-svc', liveChecks);

  // ── readiness: the probe that may reach the database, because failing it only stops traffic ──────
  const ready = probe('/health/ready');
  expect(ready, '[+] and its readiness probe', 200);
  const readyChecks = checkNames(ready);
  truthy('[+] readiness does check the database', readyChecks.includes('database'), readyChecks);
  truthy('[+] and the event consumers', readyChecks.includes('kafka-consumers'), readyChecks);
  truthy('[+] readiness does not repeat the liveness check', !readyChecks.includes('product-svc'), readyChecks);

  // ── startup: what holds liveness off while the process boots ─────────────────────────────────────
  expect(probe('/health/started'), '[+] and its startup probe', 200);

  // ── the aggregate: everything, which is why no liveness probe may be pointed at it ───────────────
  const all = probe('/health');
  expect(all, '[+] the aggregate answers too, for a dashboard', 200);
  const allChecks = checkNames(all);
  truthy('[+] the aggregate holds the database check, so it is not a liveness endpoint', allChecks.includes('database'), allChecks);
  truthy('[+] it is the union of the three, not one of them', allChecks.length > liveChecks.length && allChecks.includes('product-svc'), allChecks);

  // ── the gateway keeps the same split, though it has no database of its own ───────────────────────
  const gwLive = call('GET', '/health/live');
  expect(gwLive, '[+] the gateway answers liveness without a token — it is not behind itself', 200);
  truthy('[+] and carries no dependency either', !checkNames(gwLive).includes('database'), checkNames(gwLive));
  expect(call('GET', '/health/ready'), '[+] and readiness', 200);
  expect(call('GET', '/health/started'), '[+] and startup', 200);

  // ── the deep check: a real round trip, and the pool's own figures ────────────────────────────────
  const deep = call('GET', `${SVC}/admin/health`, { token: owner });
  expect(deep, '[+] management opens the deep check', 200);
  const body = data(deep);
  truthy('[+] it names the service it speaks for', body.service === 'product-svc', body.service);
  truthy('[+] and reports it up', body.up === true, body);
  const db = (body.dependencies || []).find((d) => d.name === 'database');
  truthy('[+] the database answered a query made just now', !!db && db.up === true, db);
  truthy('[+] timed, so a slow database is visible as slow rather than as broken', !!db && typeof db.millis === 'number' && db.millis >= 0, db);
  truthy('[+] the consumers are reported beside it', (body.dependencies || []).some((d) => d.name === 'kafka-consumers'), body.dependencies);
  truthy('[+] with the pool\'s own figures, which is what tells an empty pool from a dead database', !!body.pool && body.pool.max > 0 && body.pool.waiting === 0, body.pool);
  truthy('[+] and what readiness is currently saying, so the two can be compared', typeof body.probeVerdict === 'string' && body.probeVerdict.length > 0, body.probeVerdict);

  // ── who may ask ──────────────────────────────────────────────────────────────────────────────────
  const cashier = staffUser(shop, 'CASHIER', [shop.stores[0].id]);
  expect(call('GET', `${SVC}/admin/health`, { token: cashier.token }), '[abuse] a cashier does not read the inside of the service', 403);
  expect(call('GET', `${SVC}/admin/health`), '[-] nor does anybody without a token', 401);
  expect(call('GET', `${SVC}/health/live`), '[abuse] and the probes are not published to the internet', 401);
  expect(call('GET', `${SVC}/health/ready`), '[abuse] readiness least of all — it says whether the database is up', 401);

  completed.add(1);
}
