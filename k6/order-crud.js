import http from 'k6/http';
import { check } from 'k6';
import { baseUrl } from './common.js';

export const options = { vus: 1, iterations: 1 };

const JSON_CT = { 'Content-Type': 'application/json' };
const FAKE_UUID = '01a090ae-611e-7001-a690-2682e4afcb55';

function setupTenant() {
  const email = `k6-order-${Date.now()}@example.com`;
  const regRes = http.post(
    `${baseUrl}/api/iam-svc/auth/register`,
    JSON.stringify({ email, password: 'TestPass1!' }),
    { headers: JSON_CT }
  );
  if (regRes.status < 200 || regRes.status >= 300) return null;
  let uid = null;
  try {
    const token = regRes.json('data.accessToken');
    uid = JSON.parse(atob(token.split('.')[1])).sub;
  } catch (_) {}

  const tenantRes = http.post(
    `${baseUrl}/api/tenant-svc/onboarding/tenants`,
    JSON.stringify({ businessName: `k6-order-co-${Date.now()}`, legalName: 'k6 Ltd', country: 'GB', currency: 'GBP' }),
    { headers: { ...JSON_CT, 'X-User-Id': uid || FAKE_UUID } }
  );
  const tenantId = tenantRes.status < 300 ? tenantRes.json('data.id') : null;
  return { uid, tenantId };
}

export default function () {
  const ctx = setupTenant();
  const tenantId = ctx && ctx.tenantId;
  const hdrs = tenantId ? { ...JSON_CT, 'X-Tenant-Id': tenantId, 'X-Roles': 'OWNER' } : { ...JSON_CT };
  const noTenant = { ...JSON_CT };

  // ── Core orders (#46: tax-exempt flag) ───────────────────────────────────

  const orderRes = http.post(
    `${baseUrl}/api/order-svc/orders`,
    JSON.stringify({
      storeId: FAKE_UUID,
      channel: 'POS',
      items: [{ variantId: FAKE_UUID, qty: 2, unitPrice: 9.99 }],
      currency: 'GBP',
      taxExempt: true,
      exemptReason: 'Charity purchase'
    }),
    { headers: hdrs }
  );
  check(orderRes, { '[+] place order with tax-exempt 201': (r) => r.status === 201 });
  check(orderRes, { '[+] order response has taxExempt field': (r) => r.json('data.taxExempt') === true });
  const orderId = orderRes.status === 201 ? orderRes.json('data.id') : null;

  // [-] Order missing channel → 400
  check(
    http.post(
      `${baseUrl}/api/order-svc/orders`,
      JSON.stringify({ storeId: FAKE_UUID, items: [{ variantId: FAKE_UUID, qty: 1, unitPrice: 5.00 }] }),
      { headers: hdrs }
    ),
    { '[-] place order missing channel 400': (r) => r.status === 400 }
  );

  // [-] No tenant → 401
  check(
    http.post(
      `${baseUrl}/api/order-svc/orders`,
      JSON.stringify({ storeId: FAKE_UUID, channel: 'POS', items: [{ variantId: FAKE_UUID, qty: 1, unitPrice: 5.00 }] }),
      { headers: noTenant }
    ),
    { '[-] place order no tenant 401': (r) => r.status === 401 || r.status === 400 }
  );

  // ── Gap #43: POSLog ───────────────────────────────────────────────────────

  if (orderId) {
    const posLogRes = http.post(
      `${baseUrl}/api/order-svc/admin/pos-log/orders/${orderId}`,
      null,
      { headers: hdrs }
    );
    check(posLogRes, { '[+] record pos log entry 201': (r) => r.status === 201 });

    check(
      http.get(`${baseUrl}/api/order-svc/admin/pos-log`, { headers: hdrs }),
      { '[+] list pos log 200': (r) => r.status === 200 }
    );

    check(
      http.get(`${baseUrl}/api/order-svc/admin/pos-log/orders/${orderId}`, { headers: hdrs }),
      { '[+] get pos log by order 200': (r) => r.status === 200 }
    );

    // [-] Duplicate POSLog entry for same order → 409
    check(
      http.post(
        `${baseUrl}/api/order-svc/admin/pos-log/orders/${orderId}`,
        null,
        { headers: hdrs }
      ),
      { '[-] duplicate pos log entry 409': (r) => r.status === 409 }
    );
  }

  // ── Gap #44: Receipts ─────────────────────────────────────────────────────

  if (orderId) {
    const rcptRes = http.post(
      `${baseUrl}/api/order-svc/admin/orders/${orderId}/receipts`,
      JSON.stringify({ receiptType: 'PRINT', printCount: 1 }),
      { headers: hdrs }
    );
    check(rcptRes, { '[+] generate receipt 201': (r) => r.status === 201 });

    check(
      http.post(
        `${baseUrl}/api/order-svc/admin/orders/${orderId}/receipts`,
        JSON.stringify({ receiptType: 'EMAIL', emailedTo: 'customer@example.com' }),
        { headers: hdrs }
      ),
      { '[+] generate email receipt 201': (r) => r.status === 201 }
    );

    check(
      http.get(`${baseUrl}/api/order-svc/admin/orders/${orderId}/receipts`, { headers: hdrs }),
      { '[+] list receipts 200': (r) => r.status === 200 }
    );

    // [-] Email receipt without emailedTo → 400
    check(
      http.post(
        `${baseUrl}/api/order-svc/admin/orders/${orderId}/receipts`,
        JSON.stringify({ receiptType: 'EMAIL' }),
        { headers: hdrs }
      ),
      { '[-] email receipt missing emailedTo 400': (r) => r.status === 400 }
    );

    // [-] Receipt for unknown order → 404
    check(
      http.post(
        `${baseUrl}/api/order-svc/admin/orders/01a090ae-611e-7000-9e1a-0f8a9e565153/receipts`,
        JSON.stringify({ receiptType: 'PRINT' }),
        { headers: hdrs }
      ),
      { '[-] receipt unknown order 404': (r) => r.status === 404 }
    );
  }

  // ── Gap #42: Special Orders ───────────────────────────────────────────────

  const soRes = http.post(
    `${baseUrl}/api/order-svc/admin/special-orders`,
    JSON.stringify({
      storeId: FAKE_UUID,
      customerName: 'Jane Doe',
      customerEmail: 'jane@example.com',
      deliveryAddress: '123 High Street, London, W1A 1AA',
      requestedDeliveryDate: '2026-07-01',
      items: [{ variantId: FAKE_UUID, qty: 1, unitPrice: 49.99 }],
      currency: 'GBP'
    }),
    { headers: hdrs }
  );
  check(soRes, { '[+] create special order 201': (r) => r.status === 201 });
  const soId = soRes.status === 201 ? soRes.json('data.id') : null;

  check(
    http.get(`${baseUrl}/api/order-svc/admin/special-orders`, { headers: hdrs }),
    { '[+] list special orders 200': (r) => r.status === 200 }
  );

  if (soId) {
    check(
      http.get(`${baseUrl}/api/order-svc/admin/special-orders/${soId}`, { headers: hdrs }),
      { '[+] get special order 200': (r) => r.status === 200 }
    );

    check(
      http.post(`${baseUrl}/api/order-svc/admin/special-orders/${soId}/confirm`, null, { headers: hdrs }),
      { '[+] confirm special order 200': (r) => r.status === 200 }
    );

    check(
      http.post(`${baseUrl}/api/order-svc/admin/special-orders/${soId}/fulfil`, null, { headers: hdrs }),
      { '[+] fulfil special order 200': (r) => r.status === 200 }
    );

    // [-] Can't cancel fulfilled special order → 409
    check(
      http.post(`${baseUrl}/api/order-svc/admin/special-orders/${soId}/cancel`, null, { headers: hdrs }),
      { '[-] cancel fulfilled special order 409': (r) => r.status === 409 }
    );
  }

  // [-] Special order missing storeId → 400
  check(
    http.post(
      `${baseUrl}/api/order-svc/admin/special-orders`,
      JSON.stringify({ items: [{ variantId: FAKE_UUID, qty: 1, unitPrice: 9.99 }] }),
      { headers: hdrs }
    ),
    { '[-] special order missing storeId 400': (r) => r.status === 400 }
  );

  // [-] No tenant → 401
  check(
    http.post(
      `${baseUrl}/api/order-svc/admin/special-orders`,
      JSON.stringify({ storeId: FAKE_UUID, items: [{ variantId: FAKE_UUID, qty: 1, unitPrice: 9.99 }] }),
      { headers: noTenant }
    ),
    { '[-] special order no auth 403': (r) => r.status === 403 }
  );

  // [-] Unknown special order → 404
  check(
    http.get(
      `${baseUrl}/api/order-svc/admin/special-orders/01a090ae-611e-7000-9e1a-0f8a9e565153`,
      { headers: hdrs }
    ),
    { '[-] get unknown special order 404': (r) => r.status === 404 }
  );
}
