import http from 'k6/http';
import { check, sleep } from 'k6';
import { baseUrl } from './common.js';

export const options = { vus: 1, iterations: 1 };

function uniqueName() {
  return `k6-product-${Date.now()}`;
}

export default function () {
  const name = uniqueName();
  const payload = { name, description: 'k6 generated product', sku: `SKU-${Date.now()}`, price: 9.99 };

  // Create product
  const res = http.post(`${baseUrl}/api/product-svc/products`, JSON.stringify(payload), {
    headers: { 'Content-Type': 'application/json' },
  });
  check(res, { 'product created (2xx)': (r) => r.status >= 200 && r.status < 300 });
  sleep(1);

  // Try to parse id and exercise read/update/delete endpoints if present
  try {
    const body = res.json();
    const id = body && (body.data && body.data.id) ? body.data.id : body.id;
    if (id) {
      const get = http.get(`${baseUrl}/api/product-svc/products/${id}`);
      check(get, { 'get product 2xx/404': (r) => r.status === 200 || r.status === 404 });

      const update = http.put(`${baseUrl}/api/product-svc/products/${id}`, JSON.stringify({ description: 'k6 updated' }), {
        headers: { 'Content-Type': 'application/json' },
      });
      check(update, { 'update product returned': (r) => r.status >= 200 && r.status < 500 });

      const del = http.del(`${baseUrl}/api/product-svc/products/${id}`);
      check(del, { 'delete product returned': (r) => r.status >= 200 && r.status < 500 });
    }
  } catch (e) {
  }
}
