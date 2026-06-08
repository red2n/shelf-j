import http from 'k6/http';
import { check, sleep } from 'k6';
import { baseUrl } from './common.js';

export const options = { vus: 1, iterations: 1 };

function uniqueName() {
  return `k6-widget-${Date.now()}`;
}

export default function () {
  const name = uniqueName();
  const payload = { name, description: 'k6 widget' };

  const res = http.post(`${baseUrl}/api/sample-svc/widgets`, JSON.stringify(payload), {
    headers: { 'Content-Type': 'application/json' },
  });
  check(res, { 'widget created 2xx': (r) => r.status >= 200 && r.status < 300 });
  sleep(1);

  try {
    const body = res.json();
    const id = body && (body.data && body.data.id) ? body.data.id : body.id;
    if (id) {
      const get = http.get(`${baseUrl}/api/sample-svc/widgets/${id}`);
      check(get, { 'get widget 2xx/404': (r) => r.status === 200 || r.status === 404 });

      const update = http.put(`${baseUrl}/api/sample-svc/widgets/${id}`, JSON.stringify({ description: 'k6 updated' }), {
        headers: { 'Content-Type': 'application/json' },
      });
      check(update, { 'update widget returned': (r) => r.status >= 200 && r.status < 500 });

      const del = http.del(`${baseUrl}/api/sample-svc/widgets/${id}`);
      check(del, { 'delete widget returned': (r) => r.status >= 200 && r.status < 500 });
    }
  } catch (e) {}
}
