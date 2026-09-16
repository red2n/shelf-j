// Script integrity on the pages that lead to payment (PCI DSS v4.0.1 SAQ-A, 6.4.3, 11.6.1): the web
// shell serves an inventory of every script it loads with a reason and a hash for each; every script
// the entry page loads is in it; the bytes served match the hashes; the gateway's monitor, run by a
// platform administrator, finds no drift; a business owner and an anonymous caller are refused.
//
//   k6/run.sh script-integrity-flow      (WEB_URL defaults to http://localhost:8088)
import http from 'k6/http';
import crypto from 'k6/crypto';
import { Counter } from 'k6/metrics';
import { ALL_CHECKS_PASS, call, data, expect, onboardTenant, platformAdmin, truthy } from './lib/shelfj.js';

const WEB = __ENV.WEB_URL || 'http://localhost:8088';
const completed = new Counter('flow_completed');
export const options = {
  vus: 1,
  iterations: 1,
  thresholds: { ...ALL_CHECKS_PASS, flow_completed: ['count==1'] },
  setupTimeout: '3m',
};

const MONITOR = '/admin/security/script-integrity';

export function setup() {
  return { admin: platformAdmin(), tenant: onboardTenant('script-integrity') };
}

export default function ({ admin, tenant }) {
  // ── the inventory the shell serves ───────────────────────────────────────────────────────────────
  const inv = http.get(`${WEB}/script-inventory.json`);
  truthy('[+] the shell serves its script inventory', inv.status === 200, inv.status);
  const inventory = inv.status === 200 ? inv.json() : { scripts: [] };
  const scripts = inventory.scripts || [];
  truthy('[+] ...every script with a reason and a SHA-256', scripts.length >= 3 && scripts.every((s) => s.path && s.reason && /^[0-9a-f]{64}$/.test(s.sha256)), scripts.slice(0, 3));
  truthy('[+] ...and says card entry never happens here', /hosted page/.test(inventory.policy || ''), inventory.policy);
  truthy('[+] the app, its loader and the first-frame script are listed', ['main.dart.js', 'flutter_bootstrap.js', 'first_frame.js'].every((p) => scripts.some((s) => s.path === p)), scripts.map((s) => s.path).slice(0, 8));
  truthy('[+] the renderer is served from the shop, not a CDN', scripts.some((s) => /^canvaskit\//.test(s.path)) && !scripts.some((s) => /:\/\//.test(s.path)), 'canvaskit');

  // ── what the entry page loads ────────────────────────────────────────────────────────────────────
  const page = http.get(`${WEB}/index.html`);
  const loaded = [...String(page.body).matchAll(/<script[^>]*\ssrc=["']([^"']+)["']/gi)].map((m) => m[1].replace(/^\.?\//, ''));
  truthy('[+] the entry page loads scripts', loaded.length >= 2, loaded);
  truthy('[+] ...every one of them named in the inventory', loaded.every((p) => scripts.some((s) => s.path === p)), loaded);
  truthy('[-] ...none from another origin, none inline', !loaded.some((p) => /:\/\//.test(p)) && !/<script>[^<]/.test(String(page.body)), loaded);

  // ── the bytes match ──────────────────────────────────────────────────────────────────────────────
  for (const p of ['first_frame.js', 'flutter_bootstrap.js', 'main.dart.js']) {
    const entry = scripts.find((s) => s.path === p) || {};
    const res = http.get(`${WEB}/${p}`, { responseType: 'binary' });
    const sum = res.status === 200 ? crypto.sha256(res.body, 'hex') : 'unserved';
    truthy(`[+] ${p} served as listed`, sum === entry.sha256, { served: sum, listed: entry.sha256 });
  }

  // ── the gateway's monitor ────────────────────────────────────────────────────────────────────────
  const run = call('POST', `${MONITOR}/check`, { token: admin.token });
  expect(run, '[+] a platform administrator runs the check now', 200);
  const r = data(run) || {};
  truthy('[+] ...the shell was read, its scripts counted, no drift', r.available === true && r.scripts >= 3 && r.clean === true && (r.drift || []).length === 0, r);
  const last = call('GET', MONITOR, { token: admin.token });
  expect(last, '[+] the last report is read back', 200);
  truthy('[+] ...the same report', (data(last) || {}).checkedAt === r.checkedAt, data(last));
  expect(call('GET', MONITOR, { token: tenant.owner.token }), "[-] a business's owner does not read it", 403);
  expect(call('POST', `${MONITOR}/check`, { token: tenant.owner.token }), '[-] nor runs it', 403);
  expect(call('GET', MONITOR, {}), '[abuse] nobody reads it without signing in', 401);

  completed.add(1);
}
