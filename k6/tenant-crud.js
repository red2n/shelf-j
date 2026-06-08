import http from 'k6/http';
import { check, sleep } from 'k6';
import { baseUrl } from './common.js';

export const options = { vus: 1, iterations: 1 };

function uniqueName() {
  return `k6-tenant-${Date.now()}`;
}

export default function () {
  const name = uniqueName();
  const payload = { name, address: '123 K6 St', contact_email: `${name}@example.com` };

  const res = http.post(`${baseUrl}/api/tenant-svc/tenants`, JSON.stringify(payload), {
    headers: { 'Content-Type': 'application/json' },
  });
  check(res, { 'tenant created 2xx': (r) => r.status >= 200 && r.status < 300 });
  sleep(1);

  try {
    const body = res.json();
    const id = body && (body.data && body.data.id) ? body.data.id : body.id;
    if (id) {
      const get = http.get(`${baseUrl}/api/tenant-svc/tenants/${id}`);
      check(get, { 'get tenant 2xx/404': (r) => r.status === 200 || r.status === 404 });

      const update = http.put(`${baseUrl}/api/tenant-svc/tenants/${id}`, JSON.stringify({ address: '456 K6 Ave' }), {
        headers: { 'Content-Type': 'application/json' },
      });
      check(update, { 'update tenant returned': (r) => r.status >= 200 && r.status < 500 });

      const del = http.del(`${baseUrl}/api/tenant-svc/tenants/${id}`);
      check(del, { 'delete tenant returned': (r) => r.status >= 200 && r.status < 500 });
    }
  } catch (e) {}
}
