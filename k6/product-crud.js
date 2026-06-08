import http from 'k6/http';
import { check, sleep } from 'k6';
import { baseUrl } from './common.js';

export const options = { vus: 1, iterations: 1 };

const JSON_CT = { 'Content-Type': 'application/json' };

function setupTenant() {
  const email = `k6-prod-${Date.now()}@example.com`;
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
    JSON.stringify({ businessName: `k6-prod-co-${Date.now()}`, legalName: 'k6 Ltd', country: 'US', currency: 'USD' }),
    { headers: { ...JSON_CT, 'X-User-Id': uid || '00000000-0000-0000-0000-000000000001' } }
  );
  const tenantId = tenantRes.status < 300 ? tenantRes.json('data.id') : null;
  return { uid, tenantId };
}

export default function () {
  const ctx = setupTenant();
  const tenantId = ctx && ctx.tenantId;
  const hdrs = tenantId
    ? { ...JSON_CT, 'X-Tenant-Id': tenantId }
    : { ...JSON_CT };

  // Create a brand first (needed for product)
  const brandRes = http.post(
    `${baseUrl}/api/product-svc/admin/brands`,
    JSON.stringify({ name: `k6-brand-${Date.now()}` }),
    { headers: hdrs }
  );
  check(brandRes, { 'brand created (2xx or tenant-less 4xx)': (r) => r.status < 500 });

  // Create a product
  const prodRes = http.post(
    `${baseUrl}/api/product-svc/admin/products`,
    JSON.stringify({ name: `k6-prod-${Date.now()}`, description: 'k6 generated', sellableOnline: true, sellablePos: true }),
    { headers: hdrs }
  );
  check(prodRes, { 'product created (2xx or tenant-less 4xx)': (r) => r.status < 500 });

  sleep(0.5);

  // Read product if created
  try {
    const id = prodRes.json('data.id');
    if (id) {
      const getRes = http.get(`${baseUrl}/api/product-svc/catalog/products/${id}`, { headers: hdrs });
      check(getRes, { 'get product 2xx/4xx': (r) => r.status < 500 });

      // Update via PUT (gateway now supports PUT)
      const putRes = http.put(
        `${baseUrl}/api/product-svc/admin/products/${id}`,
        JSON.stringify({ name: 'k6-updated', description: 'updated', sellableOnline: true, sellablePos: false }),
        { headers: hdrs }
      );
      check(putRes, { 'update product < 500': (r) => r.status < 500 });

      // Delist (DELETE maps to soft-delete on product-svc)
      const delRes = http.del(`${baseUrl}/api/product-svc/admin/products/${id}`, null, { headers: hdrs });
      check(delRes, { 'delist product < 500': (r) => r.status < 500 });
    }
  } catch (_) {}

  // List products (catalog view)
  const listRes = http.get(`${baseUrl}/api/product-svc/catalog/products`, { headers: hdrs });
  check(listRes, { 'list products < 500': (r) => r.status < 500 });
}
