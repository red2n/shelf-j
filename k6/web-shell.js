// The web app's own responses (12.11, SJ-D60, SJ-D61): every kind of path — the entry page, an SPA
// route, the bundles, a missing file, the manifest, a HEAD, a POST — carries the Content-Security-
// Policy and the security headers; the policy allows no inline or eval'd script and no framing by
// another site; nothing Flutter ships unhashed is cached as immutable, and a revalidation answers 304;
// the page declares its language and has no inline script for the policy to refuse; every icon the
// page and the manifest name exists; and the API proxy and security.txt still pass through.
//
//   k6/run.sh web-shell            (WEB_URL defaults to http://localhost:8088)
import http from 'k6/http';
import { check } from 'k6';
import { ALL_CHECKS_PASS, truthy } from './lib/shelfj.js';

export const options = {
  scenarios: { flow: { executor: 'per-vu-iterations', vus: 1, iterations: 1, maxDuration: '2m' } },
  thresholds: ALL_CHECKS_PASS,
};

const WEB = __ENV.WEB_URL || 'http://localhost:8088';
const API_ORIGIN = __ENV.API_ORIGIN || 'http://localhost:8090';

const header = (res, name) => {
  const key = Object.keys(res.headers).find((k) => k.toLowerCase() === name.toLowerCase());
  return key ? res.headers[key] : undefined;
};
const directives = (csp) =>
  Object.fromEntries(
    String(csp || '')
      .split(';')
      .map((d) => d.trim())
      .filter(Boolean)
      .map((d) => {
        const [name, ...values] = d.split(/\s+/);
        return [name, values];
      })
  );

function headersOn(label, res) {
  const csp = header(res, 'Content-Security-Policy');
  const d = directives(csp);
  check(res, {
    [`[+] ${label}: a Content-Security-Policy`]: () => !!csp,
    [`[+] ${label}: scripts from this origin only, WebAssembly allowed, no eval`]: () =>
      JSON.stringify(d['script-src']) === JSON.stringify(["'self'", "'wasm-unsafe-eval'"]),
    [`[-] ${label}: no inline script allowed`]: () => !(d['script-src'] || []).includes("'unsafe-inline'"),
    [`[-] ${label}: no plugins, no base rewrite, no framing by another site`]: () =>
      (d['object-src'] || []).join() === "'none'" && (d['base-uri'] || []).join() === "'self'" && (d['frame-ancestors'] || []).join() === "'self'",
    [`[+] ${label}: the gateway the bundle calls is reachable`]: () => (d['connect-src'] || []).includes(API_ORIGIN),
    [`[+] ${label}: nosniff`]: () => header(res, 'X-Content-Type-Options') === 'nosniff',
    [`[+] ${label}: same-origin framing only`]: () => header(res, 'X-Frame-Options') === 'SAMEORIGIN',
    [`[+] ${label}: a referrer policy`]: () => header(res, 'Referrer-Policy') === 'strict-origin-when-cross-origin',
    [`[+] ${label}: exactly one Cache-Control`]: () => !String(header(res, 'Cache-Control') || '').includes(', max-age') && !!header(res, 'Cache-Control'),
  });
  return res;
}

export default function () {
  const page = headersOn('the entry page', http.get(`${WEB}/`));
  check(page, {
    '[+] the entry page is never stored': (r) => /no-store/.test(header(r, 'Cache-Control')),
    '[+] the page declares its language': (r) => /<html lang="en-GB">/.test(r.body),
    '[-] the page carries no inline script for the policy to refuse': (r) => !/<script(?![^>]*\bsrc=)[^>]*>/i.test(r.body),
    '[+] the loading message is announced politely': (r) => /id="loading" role="status" aria-live="polite"/.test(r.body),
  });
  headersOn('index.html', http.get(`${WEB}/index.html`));
  const route = headersOn('an SPA route', http.get(`${WEB}/store/accessibility`));
  truthy('[+] an SPA route serves the app', /flutter_bootstrap\.js/.test(route.body));

  const bundle = headersOn('main.dart.js', http.get(`${WEB}/main.dart.js`));
  check(bundle, {
    '[+] the bundle is revalidated, not cached as immutable': (r) => header(r, 'Cache-Control') === 'no-cache',
    '[+] the bundle is JavaScript': (r) => /javascript/.test(header(r, 'Content-Type')),
  });
  const etag = header(bundle, 'ETag');
  const again = http.get(`${WEB}/main.dart.js`, { headers: { 'If-None-Match': etag } });
  truthy('[+] a returning browser revalidates the bundle for a 304', !!etag && again.status === 304, { etag, status: again.status });
  headersOn('a 304', again);
  const bootstrap = headersOn('flutter_bootstrap.js', http.get(`${WEB}/flutter_bootstrap.js`));
  truthy('[+] the bootstrap is revalidated too', header(bootstrap, 'Cache-Control') === 'no-cache');
  const firstFrame = headersOn('first_frame.js', http.get(`${WEB}/first_frame.js`));
  truthy('[+] the first-frame script is a file', firstFrame.status === 200 && /flutter-first-frame/.test(firstFrame.body));
  const canvaskit = http.get(`${WEB}/canvaskit/canvaskit.js`);
  truthy('[+] CanvasKit is served from this origin, not a CDN', canvaskit.status === 200, canvaskit.status);
  truthy('[-] the bootstrap does not send the browser to gstatic for CanvasKit', !/"useLocalCanvasKit":false/.test(bootstrap.body) && /"useLocalCanvasKit":true/.test(bootstrap.body), bootstrap.body.slice(-300));

  const missing = headersOn('a missing file', http.get(`${WEB}/no-such-bundle.js`));
  truthy('[-] a missing bundle file is a 404, never the page served as script', missing.status === 404 && !/flutter_bootstrap/.test(missing.body), missing.status);

  // The customer-facing display (a second window of the till) is a route of the same bundle: the
  // image serves it if some script the inventory lists carries the route and its words.
  const inventory = http.get(`${WEB}/script-inventory.json`);
  const parts = inventory.status === 200 ? (inventory.json().scripts || []).map((x) => x.path).filter((p) => /^main\.dart\.js/.test(p)) : [];
  let displayRoute = false;
  let displayWords = false;
  // The second step of signing in (20.12) ships the same way: the step itself, the set-up a login
  // may be made to do, and the screen where a login manages its own factors.
  const secondStep = { step: false, setUp: false, security: false, passkey: false };
  for (const p of parts) {
    const body = String(http.get(`${WEB}/${p}`).body || '');
    displayRoute = displayRoute || body.includes('/pos/display');
    displayWords = displayWords || body.includes('Container deposit (refundable)');
    secondStep.step = secondStep.step || (body.includes('/auth/mfa/login') && body.includes('One more step'));
    secondStep.setUp = secondStep.setUp || (body.includes('/mfa/setup') && body.includes('Set up a second step'));
    secondStep.security = secondStep.security || (body.includes('/account/security') && body.includes('Keep these recovery codes'));
    secondStep.passkey = secondStep.passkey || body.includes('PublicKeyCredential');
  }
  truthy('[+] the customer display ships in the bundle: its route', displayRoute, parts.length);
  truthy('[+] ...and the rows it shows the customer', displayWords, parts.length);
  truthy('[+] the second step of signing in ships in the bundle', secondStep.step, secondStep);
  truthy('[+] ...with the set-up a login may be made to do', secondStep.setUp, secondStep);
  truthy('[+] ...the screen where a login manages its own factors, recovery codes and all', secondStep.security, secondStep);
  truthy('[+] ...and the browser half of passkeys, which only the web build compiles', secondStep.passkey, secondStep);
  const manifest = headersOn('the manifest', http.get(`${WEB}/manifest.json`));
  let icons = [];
  try {
    icons = JSON.parse(manifest.body).icons || [];
  } catch (e) {
    icons = [];
  }
  truthy('[+] the manifest names its icons, a maskable one among them', icons.length >= 2 && icons.some((i) => i.purpose === 'maskable'), icons.length);
  const linked = [...page.body.matchAll(/<link rel="(?:icon|apple-touch-icon)"[^>]*href="([^"]+)"/g)].map((m) => m[1]);
  truthy('[+] the page links an icon', linked.length > 0, linked);
  for (const src of new Set([...icons.map((i) => i.src), ...linked])) {
    const icon = http.get(`${WEB}/${src}`);
    truthy(`[+] ${src} is served as a PNG`, icon.status === 200 && header(icon, 'Content-Type') === 'image/png' && icon.body.length > 0, icon.status);
  }
  headersOn('a HEAD', http.head(`${WEB}/`));
  const post = headersOn('a POST to the page', http.post(`${WEB}/`, 'x'));
  truthy('[-] the page takes no POST', post.status === 405, post.status);
  const traversal = http.get(`${WEB}/..%2f..%2fetc%2fpasswd`);
  truthy('[-] a traversal reaches nothing', !/root:x:/.test(traversal.body), traversal.status);
  const injected = headersOn('a route with markup in it', http.get(`${WEB}/store/%3Cscript%3Ealert(1)%3C%2Fscript%3E`));
  truthy('[-] markup in the path is not reflected', !/<script>alert\(1\)<\/script>/.test(injected.body));

  const api = http.get(`${WEB}/api/tenant-svc/storefront/stores`, { headers: { 'X-Storefront-Tenant': '01a090ae-611e-7000-8000-000000000000' } });
  truthy('[+] the same-origin API proxy still answers', api.status > 0 && api.status < 500, api.status);
  const sec = http.get(`${WEB}/.well-known/security.txt`);
  truthy('[+] security.txt still passes through', sec.status === 200 || sec.status === 404, sec.status);
  const health = http.get(`${WEB}/healthz`);
  truthy('[+] the health probe answers', health.status === 200 && health.body === 'ok\n', health.status);
}
