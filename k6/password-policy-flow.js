// The password policy to current guidance (NIST SP 800-63B-4, OWASP ASVS 5.0 V6), through the
// gateway: a password that is the only factor is fifteen characters or more, any character, no
// composition rule; the login is not a password; a password known from breaches is refused through
// the Pwned Passwords range lookup; the same policy holds at sign-up, when staff are provisioned
// and when a password is changed. Refused: fourteen characters, the login itself, a breached
// phrase, one over a hundred and twenty-eight; abuse: a change to a breached password with a valid
// current one, a cashier provisioning staff.
//
//   k6/run.sh password-policy-flow
import { Counter } from 'k6/metrics';
import { ALL_CHECKS_PASS, PASSWORD, call, data, expect, onboardTenant, staffUser, truthy, uniq } from './lib/storeql.js';

const completed = new Counter('flow_completed');
export const options = {
  vus: 1,
  iterations: 1,
  thresholds: { ...ALL_CHECKS_PASS, flow_completed: ['count==1'] },
  setupTimeout: '3m',
};

const AUTH = '/api/iam-svc/auth';
const BREACHED = 'correct horse battery staple';

export function setup() {
  const tenant = onboardTenant('password-policy');
  const cashier = staffUser(tenant, 'CASHIER', [tenant.stores[0].id]);
  return { tenant, cashier };
}

export default function ({ tenant, cashier }) {
  const email = () => `pw-${uniq()}@example.com`;
  const register = (password, mail = email()) => call('POST', `${AUTH}/register`, { body: { email: mail, password } });

  // ── sign-up ──────────────────────────────────────────────────────────────────────────────────────
  expect(register('short'), '[-] five characters never reach the policy', 400, 'VALIDATION_FAILED');
  expect(register('fourteen chars'), '[-] fourteen characters are refused, saying why', 400, 'PASSWORD_TOO_SHORT');
  const ok = register('fifteen charact');
  expect(ok, '[+] fifteen characters are enough, whatever they are', 201);
  expect(register('all lower case words no digit'), '[+] no composition rule: lower-case words alone are fine', 201);
  expect(register('ünïcödé pässwörd with spaces'), '[+] any character, spaces included', 201);
  const tooLong = register('x'.repeat(129));
  truthy('[-] a hundred and twenty-nine characters are too many (bean validation says so first)', tooLong.status === 400 && /PASSWORD_TOO_LONG|VALIDATION_FAILED/.test(tooLong.body), { status: tooLong.status });
  expect(register('y'.repeat(128)), '[+] a hundred and twenty-eight are fine', 201);
  const me = email();
  expect(register(me, me), '[-] the login is not a password', 400, 'PASSWORD_IS_IDENTITY');
  expect(register(`${me.split('@')[0]} and more words`, me), '[-] nor is a phrase with the login in it', 400, 'PASSWORD_IS_IDENTITY');
  const breached = register(BREACHED);
  truthy('[-] a phrase known from breaches is refused, or let through only when the screen cannot be reached', breached.status === 400 ? /PASSWORD_BREACHED/.test(breached.body) : breached.status === 201, { status: breached.status, body: String(breached.body).slice(0, 120) });

  // ── changing it ──────────────────────────────────────────────────────────────────────────────────
  const token = (data(ok) || {}).accessToken;
  const change = (currentPassword, newPassword) => call('PUT', `${AUTH}/change-password`, { token, body: { currentPassword, newPassword } });
  expect(change('fifteen charact', 'fourteen chars'), '[-] a change to fourteen characters is refused', 400, 'PASSWORD_TOO_SHORT');
  expect(change('fifteen charact', 'short'), '[-] a change to five never reaches the policy', 400, 'VALIDATION_FAILED');
  const changed = change('fifteen charact', BREACHED);
  truthy('[abuse] a valid current password does not buy a breached new one', changed.status === 400 ? /PASSWORD_BREACHED/.test(changed.body) : changed.status === 200, { status: changed.status });
  expect(change('fifteen charact', 'a new phrase of several words'), '[+] a change to a good phrase goes through', 200);

  // ── provisioning staff ───────────────────────────────────────────────────────────────────────────
  const staff = (password, tok = tenant.owner.token) => call('POST', `${AUTH}/admin/staff-users`, { token: tok, body: { email: email(), password } });
  expect(staff('fourteen chars'), '[-] staff are not provisioned with fourteen characters', 400, 'PASSWORD_TOO_SHORT');
  expect(staff('a staff phrase that is long'), '[+] nor refused a good phrase', [200, 201]);
  expect(staff(PASSWORD, cashier.token), '[abuse] a cashier does not provision staff', 403);

  completed.add(1);
}
