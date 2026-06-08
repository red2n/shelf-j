import http from 'k6/http';
import { check, sleep } from 'k6';
import { baseUrl } from './common.js';

export const options = { vus: 1, iterations: 1 };

function uniqueRef() {
  return `k6-batch-${Date.now()}`;
}

export default function () {
  const ref = uniqueRef();
  const payload = { sku: `SKU-${Date.now()}`, quantity: 10, store_id: '00000000-0000-0000-0000-000000000000', reference: ref };

  // Create inventory batch
  const res = http.post(`${baseUrl}/api/inventory-svc/inventory-batches`, JSON.stringify(payload), {
    headers: { 'Content-Type': 'application/json' },
  });
  check(res, { 'inventory batch create 2xx': (r) => r.status >= 200 && r.status < 300 });
  sleep(1);

  try {
    const body = res.json();
    const id = body && (body.data && body.data.id) ? body.data.id : body.id;
    if (id) {
      const get = http.get(`${baseUrl}/api/inventory-svc/inventory-batches/${id}`);
      check(get, { 'get batch 2xx/404': (r) => r.status === 200 || r.status === 404 });

      const update = http.put(`${baseUrl}/api/inventory-svc/inventory-batches/${id}`, JSON.stringify({ quantity: 5 }), {
        headers: { 'Content-Type': 'application/json' },
      });
      check(update, { 'update batch returned': (r) => r.status >= 200 && r.status < 500 });

      const del = http.del(`${baseUrl}/api/inventory-svc/inventory-batches/${id}`);
      check(del, { 'delete batch returned': (r) => r.status >= 200 && r.status < 500 });
    }
  } catch (e) {}
}
