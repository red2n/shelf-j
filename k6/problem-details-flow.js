// Machine-readable errors and a current API description: every error leaves as RFC 9457 problem
// details — application/problem+json with a URN type for the stable code, the code in words as the
// title, the HTTP status, the message as detail and the request path as instance — beside the
// envelope members a client read before; and every service, and the gateway, publishes an
// OpenAPI 3.1 description that carries the Problem schema. Errors from an exception mapper (400,
// 404, 409), from a gateway filter (401, 403) and from bean validation are all problems.
//
//   k6/run.sh problem-details-flow
import http from 'k6/http';
import { Counter } from 'k6/metrics';
import { ALL_CHECKS_PASS, BASE, call, data, expect, onboardTenant, sellableVariant, truthy, uniq } from './lib/shelfj.js';

const completed = new Counter('flow_completed');
export const options = {
  vus: 1,
  iterations: 1,
  thresholds: { ...ALL_CHECKS_PASS, flow_completed: ['count==1'] },
  setupTimeout: '3m',
};

const SERVICES = ['iam-svc', 'tenant-svc', 'product-svc', 'inventory-svc', 'pricing-svc', 'cart-svc', 'order-svc', 'payment-svc', 'purchase-svc', 'customer-svc', 'notification-svc', 'reporting-svc'];

export function setup() {
  const tenant = onboardTenant('problem-details');
  const { productId } = sellableVariant(tenant, 'problem widget');
  return { tenant, productId };
}

const contentType = (res) => String(res.headers['Content-Type'] || res.headers['content-type'] || '');

function problem(res, label, status, code) {
  const p = res.json() || {};
  truthy(`${label}: ${status} as application/problem+json`, res.status === status && contentType(res).startsWith('application/problem+json'), { status: res.status, type: contentType(res) });
  truthy(`${label}: type, title, status, detail and instance, and the code beside them`, p.type === `urn:shelfj:problem:${code}` && typeof p.title === 'string' && p.title.length > 0 && p.status === status && typeof p.detail === 'string' && String(p.instance || '').startsWith('/api/') && p.code === code, p);
  truthy(`${label}: the envelope a client read before is still there`, (p.error || {}).code === code && typeof (p.error || {}).message === 'string', p.error);
  return p;
}

export default function ({ tenant, productId }) {
  const owner = tenant.owner.token;

  // ── errors from every producer ───────────────────────────────────────────────────────────────────
  problem(call('GET', `/api/product-svc/admin/products/${uniq()}-not-a-uuid`, { token: owner }), '[-] a path that is not a UUID (mapper)', 400, 'INVALID_UUID');
  problem(call('GET', `/api/product-svc/admin/products/01a090ae-611e-7000-9e1a-0f8a9e565153`, { token: owner }), '[-] a product that does not exist (mapper)', 404, 'PRODUCT_NOT_FOUND');
  problem(call('POST', `/api/product-svc/admin/products/${productId}/launch`, { token: owner }), '[-] a lifecycle move a line cannot make (mapper, 409)', 409, 'PRODUCT_LIFECYCLE_INVALID');
  problem(call('POST', '/api/iam-svc/auth/register', { body: { email: 'not-an-email', password: 'short' } }), '[-] bean validation', 400, 'VALIDATION_FAILED');
  problem(call('GET', '/api/product-svc/admin/products', {}), '[-] no token (gateway filter)', 401, 'UNAUTHORIZED');
  const shopper = data(call('POST', '/api/iam-svc/auth/register', { body: { email: `pd-${uniq()}@example.com`, password: 'a shopper phrase for tests' } }));
  problem(call('GET', '/api/product-svc/admin/products', { token: shopper.accessToken, storefront: tenant.tenantId }), '[-] the wrong role (authorization filter)', 403, 'FORBIDDEN');
  const nowhere = call('GET', '/api/nowhere-svc/things', { token: owner });
  truthy('[-] an unknown service is a problem too', nowhere.status >= 400 && contentType(nowhere).startsWith('application/problem+json') && String((nowhere.json() || {}).type || '').startsWith('urn:shelfj:problem:'), { status: nowhere.status, type: contentType(nowhere) });
  const ok = call('GET', '/api/product-svc/admin/products?limit=1', { token: owner });
  truthy('[+] a response with data is untouched: application/json, no type', ok.status === 200 && contentType(ok).startsWith('application/json') && (ok.json() || {}).type === undefined, contentType(ok));

  // ── the API description ──────────────────────────────────────────────────────────────────────────
  for (const svc of SERVICES) {
    const doc = http.get(`${BASE}/api/${svc}/openapi`, { headers: { Accept: 'application/json' } });
    const body = doc.status === 200 ? doc.json() : {};
    truthy(`[+] ${svc} describes itself in OpenAPI 3.1`, doc.status === 200 && String(body.openapi || '').startsWith('3.1'), { status: doc.status, openapi: body.openapi });
    truthy(`[+] ...with the Problem schema`, !!(((body.components || {}).schemas || {}).Problem), Object.keys((body.components || {}).schemas || {}).slice(0, 5));
  }
  const gw = http.get(`${BASE}/openapi`, { headers: { Accept: 'application/json' } });
  truthy('[+] the gateway describes itself in OpenAPI 3.1', gw.status === 200 && String((gw.json() || {}).openapi || '').startsWith('3.1'), { status: gw.status });

  completed.add(1);
}
