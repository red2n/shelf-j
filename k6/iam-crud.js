// iam-svc: sign-up, sign-in, token rotation, password and account lifecycle, staff provisioning and
// POS sessions — each with the refusals that keep them safe.
//
//   k6/run.sh iam-crud
import {
  ALL_CHECKS_PASS,
  PASSWORD,
  call,
  claims,
  data,
  expect,
  login,
  onboardTenant,
  platformAdmin,
  register,
  truthy,
  uniq,
} from './lib/shelfj.js';

export const options = { vus: 1, iterations: 1, thresholds: ALL_CHECKS_PASS };

const UNKNOWN = '01a0b000-0000-7000-8000-000000000000';

export function setup() {
  return { admin: platformAdmin(), tenant: onboardTenant('iam', { stores: 1 }), rival: onboardTenant('iam-rival', { stores: 1 }) };
}

export default function ({ admin, tenant, rival }) {
  // ── sign-up and sign-in ─────────────────────────────────────────────────────
  const email = `iam-${uniq()}@k6.shelfj.test`;
  expect(call('POST', '/api/iam-svc/auth/register', { body: { email, password: 'short' } }), '[-] register: password too short', 400, 'VALIDATION_FAILED');
  expect(call('POST', '/api/iam-svc/auth/register', { body: { email: 'nope', password: PASSWORD } }), '[-] register: not an email', 400, 'VALIDATION_FAILED');
  const reg = call('POST', '/api/iam-svc/auth/register', { body: { email, password: PASSWORD } });
  expect(reg, '[+] register', 201);
  expect(call('POST', '/api/iam-svc/auth/register', { body: { email: email.toUpperCase(), password: PASSWORD } }), '[-] register: email taken (any case)', 409);
  const user = { email, password: PASSWORD, token: data(reg).accessToken, refreshToken: data(reg).refreshToken };
  truthy('[+] a sign-up is a CUSTOMER', (claims(user.token).roles || []).includes('CUSTOMER'), claims(user.token));

  expect(login(user), '[+] login', 200);
  expect(call('POST', '/api/iam-svc/auth/login', { body: { email, password: 'Wrong-Passw0rd!' } }), '[-] login: wrong password', 401, 'INVALID_CREDENTIALS');
  expect(call('POST', '/api/iam-svc/auth/login', { body: { email: `nobody-${uniq()}@k6.shelfj.test`, password: PASSWORD } }), '[-] login: unknown user', 401, 'INVALID_CREDENTIALS');
  expect(call('POST', '/api/iam-svc/auth/platform-login', { body: { email, password: PASSWORD } }), '[-] platform-login: a customer is not a platform admin', [401, 403]);
  expect(call('POST', '/api/iam-svc/auth/login', { body: { email: admin.email, password: admin.password } }), '[-] login: platform admin must use platform-login', 401);
  expect(
    call('POST', '/api/iam-svc/bootstrap/admin', { body: { email: `second-admin-${uniq()}@k6.shelfj.test`, password: PASSWORD } }),
    '[-] bootstrap: only once per deployment',
    409
  );

  const me = call('GET', '/api/iam-svc/auth/me', { token: user.token });
  expect(me, '[+] who am I', 200);
  truthy('[+] who am I: my email and roles', data(me).email === email && (data(me).roles || []).includes('CUSTOMER'), data(me));
  expect(call('GET', '/api/iam-svc/auth/me'), '[-] who am I: no token', 401);
  expect(call('GET', '/api/iam-svc/auth/me', { token: `${user.token}x` }), '[-] who am I: tampered token', 401);

  // ── refresh rotation and theft detection ────────────────────────────────────
  const spent = user.refreshToken;
  const rotated = call('POST', '/api/iam-svc/auth/refresh', { body: { refreshToken: spent } });
  expect(rotated, '[+] refresh rotates the token pair', 200);
  const fresh = data(rotated).refreshToken;
  truthy('[+] refresh returns a new refresh token', fresh && fresh !== spent);
  expect(call('POST', '/api/iam-svc/auth/refresh', { body: { refreshToken: spent } }), '[-] refresh: a spent token is refused', 401, 'INVALID_REFRESH');
  expect(call('POST', '/api/iam-svc/auth/refresh', { body: { refreshToken: fresh } }), '[-] refresh: replaying a spent token revoked the whole family', 401, 'INVALID_REFRESH');
  expect(call('POST', '/api/iam-svc/auth/refresh', { body: {} }), '[-] refresh: token required', 400);

  // ── logout ──────────────────────────────────────────────────────────────────
  const session = data(login(user));
  expect(call('POST', '/api/iam-svc/auth/logout', { token: session.accessToken, body: { refreshToken: session.refreshToken } }), '[+] logout', 200);
  expect(call('POST', '/api/iam-svc/auth/refresh', { body: { refreshToken: session.refreshToken } }), '[-] refresh after logout', 401);

  // ── password change ─────────────────────────────────────────────────────────
  const beforeChange = data(login(user));
  const newPassword = 'K6-Changed-Passw0rd!';
  expect(
    call('PUT', '/api/iam-svc/auth/change-password', { token: beforeChange.accessToken, body: { currentPassword: 'Wrong-Passw0rd!', newPassword } }),
    '[-] change password: current password wrong',
    401
  );
  expect(
    call('PUT', '/api/iam-svc/auth/change-password', { token: beforeChange.accessToken, body: { currentPassword: PASSWORD, newPassword: 'short' } }),
    '[-] change password: new password too short',
    400
  );
  expect(
    call('PUT', '/api/iam-svc/auth/change-password', { token: beforeChange.accessToken, body: { currentPassword: PASSWORD, newPassword } }),
    '[+] change password',
    200
  );
  expect(login(user), '[-] old password no longer logs in', 401);
  expect(call('POST', '/api/iam-svc/auth/refresh', { body: { refreshToken: beforeChange.refreshToken } }), '[-] tokens issued before the change are revoked', 401);
  user.password = newPassword;
  expect(login(user), '[+] new password logs in', 200);

  // ── account deletion (customers only) ───────────────────────────────────────
  const leaving = register('iam-leaving');
  expect(call('POST', '/api/iam-svc/auth/delete-account', { token: leaving.token, body: { password: 'Wrong-Passw0rd!' } }), '[-] delete account: password asked again', 401);
  expect(call('POST', '/api/iam-svc/auth/delete-account', { token: leaving.token, body: { password: PASSWORD } }), '[+] delete account', 200);
  expect(login(leaving), '[-] a deleted account cannot log in', 401);
  expect(
    call('POST', '/api/iam-svc/auth/delete-account', { token: tenant.owner.token, body: { password: PASSWORD } }),
    '[-] delete account: staff accounts are not deleted here',
    403
  );

  // ── staff provisioning ──────────────────────────────────────────────────────
  const staffEmail = `iam-staff-${uniq()}@k6.shelfj.test`;
  expect(
    call('POST', '/api/iam-svc/auth/admin/staff-users', { token: user.token, body: { email: staffEmail, password: PASSWORD } }),
    '[-] provision staff: a customer cannot',
    403
  );
  const provisioned = call('POST', '/api/iam-svc/auth/admin/staff-users', { token: tenant.owner.token, body: { email: staffEmail, password: PASSWORD } });
  expect(provisioned, '[+] provision a staff login', 200);
  truthy('[+] provisioned login has an id', data(provisioned).userId, data(provisioned));
  expect(
    call('POST', '/api/iam-svc/auth/admin/staff-users', { token: tenant.owner.token, body: { email: staffEmail, password: 'short' } }),
    '[-] provision staff: password too short',
    400
  );

  // ── POS sessions ────────────────────────────────────────────────────────────
  const t = tenant.owner.token;
  const storeId = tenant.stores[0].id;
  expect(call('POST', '/api/iam-svc/auth/pos/sessions', { token: t, body: { idleTimeoutSeconds: 300 } }), '[-] POS session: store required', 400);
  expect(
    call('POST', '/api/iam-svc/auth/pos/sessions', { token: t, body: { storeId, idleTimeoutSeconds: 10 } }),
    '[-] POS session: idle timeout below a minute',
    400,
    'POS_SESSION_INVALID_TIMEOUT'
  );
  expect(
    call('POST', '/api/iam-svc/auth/pos/sessions', { token: user.token, body: { storeId, idleTimeoutSeconds: 300 } }),
    '[-] POS session: a customer cannot open one',
    [401, 403]
  );
  const started = call('POST', '/api/iam-svc/auth/pos/sessions', { token: t, body: { storeId, idleTimeoutSeconds: 300 } });
  expect(started, '[+] POS session opens', 201);
  const sessionId = data(started).id;
  truthy('[+] POS session is ACTIVE with the timeout asked for', data(started).status === 'ACTIVE' && data(started).idleTimeoutSeconds === 300, data(started));
  expect(call('PUT', `/api/iam-svc/auth/pos/sessions/${sessionId}/activity`, { token: t }), '[+] POS session activity', 204);
  const active = call('GET', '/api/iam-svc/auth/pos/sessions', { token: t });
  expect(active, '[+] list active POS sessions', 200);
  truthy('[+] the open session is listed', (data(active) || []).some((s) => s.id === sessionId), data(active));
  truthy('[-] a rival does not see it', !(data(call('GET', '/api/iam-svc/auth/pos/sessions', { token: rival.owner.token })) || []).some((s) => s.id === sessionId));
  expect(call('PUT', `/api/iam-svc/auth/pos/sessions/${sessionId}/activity`, { token: rival.owner.token }), '[-] a rival cannot touch it', 404);
  expect(call('DELETE', `/api/iam-svc/auth/pos/sessions/${sessionId}`, { token: rival.owner.token }), '[-] a rival cannot end it', 404);
  expect(call('DELETE', `/api/iam-svc/auth/pos/sessions/${sessionId}`, { token: t }), '[+] POS session ends', 204);
  expect(call('PUT', `/api/iam-svc/auth/pos/sessions/${sessionId}/activity`, { token: t }), '[-] activity on an ended session', 409, 'POS_SESSION_NOT_ACTIVE');
  expect(call('PUT', `/api/iam-svc/auth/pos/sessions/${UNKNOWN}/activity`, { token: t }), '[-] activity on an unknown session', 404, 'POS_SESSION_NOT_FOUND');
  expect(call('POST', '/api/iam-svc/auth/pos/sessions/sweep', { token: t }), '[-] idle sweep: owners cannot run it', 403);
  expect(call('POST', '/api/iam-svc/auth/pos/sessions/sweep', { token: admin.token }), '[+] idle sweep: platform admin', 200);
}
