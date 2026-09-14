// Security incident, breach and exploited-vulnerability reporting (21.15), end to end through the
// gateway: the security.txt a researcher finds, the statutory clocks an incident runs, the notices
// that reach only the businesses affected, and the wrong caller, the wrong input and the rush at
// every step.
//
//   k6/run.sh security-incidents
import { group } from 'k6';
import http from 'k6/http';
import {
  ALL_CHECKS_PASS,
  BASE,
  call,
  data,
  expect,
  must,
  onboardTenant,
  platformAdmin,
  staffUser,
  truthy,
} from './lib/shelfj.js';

export const options = {
  scenarios: { flow: { executor: 'per-vu-iterations', vus: 1, iterations: 1, maxDuration: '5m' } },
  setupTimeout: '3m',
  thresholds: ALL_CHECKS_PASS,
  batch: 20,
  batchPerHost: 20,
};

const WEB = __ENV.WEB_URL || 'http://localhost:8088';
const INCIDENTS = '/api/tenant-svc/platform/security-incidents';
const NOTICES = '/api/tenant-svc/admin/tenant/security-notices';
const HOUR = 3600 * 1000;

export function setup() {
  const admin = platformAdmin();
  const tenant = onboardTenant('security');
  const rival = onboardTenant('security-rival');
  const cashier = staffUser(tenant, 'CASHIER', [tenant.stores[0].id]);
  return { admin: admin.token, tenant, rival, cashier };
}

const ago = (ms) => new Date(Date.now() - ms).toISOString();
const stage = (incident, name) => (incident.stages || []).find((s) => s.stage === name) || {};
const sameInstant = (a, b) => Boolean(a) && Boolean(b) && Date.parse(a) === Date.parse(b);

export default function ({ admin, tenant, rival, cashier }) {
  const owner = tenant.owner.token;
  const open = (body, token = admin) => call('POST', INCIDENTS, { token, body });
  const record = (id, body, token = admin) => call('POST', `${INCIDENTS}/${id}/events`, { token, body });
  const tell = (id, message, token = admin) => call('POST', `${INCIDENTS}/${id}/notices`, { token, body: { message } });
  const incident = (kind, awareAt, tenantIds) =>
    must(open({ kind, title: `k6 ${kind}`, summary: 'Found by the k6 security-incidents suite', awareAt, tenantIds }), 201, `open ${kind}`);

  group('1 security.txt: where a researcher reports a vulnerability', () => {
    for (const [where, url] of [['the gateway', `${BASE}/.well-known/security.txt`], ['the web app', `${WEB}/.well-known/security.txt`]]) {
      const res = http.get(url, { tags: { name: `GET security.txt via ${where}` } });
      expect(res, `${where} serves it without a token`, 200);
      const body = String(res.body || '');
      truthy(`${where}: plain text`, String(res.headers['Content-Type'] || '').startsWith('text/plain'), res.headers);
      truthy(`${where}: a contact that is a link, a mailbox or a phone`, /^Contact: (mailto:|https:\/\/|tel:)\S+$/m.test(body), body);
      const expires = (body.match(/^Expires: (\S+)$/m) || [])[1];
      truthy(`${where}: an expiry in the future and no more than a year out (RFC 9116 §2.5.5)`, expires && Date.parse(expires) > Date.now() && Date.parse(expires) <= Date.now() + 366 * 24 * HOUR, expires);
      truthy(`${where}: cached, not re-read on every hit`, /max-age=\d+/.test(String(res.headers['Cache-Control'] || '')), res.headers);
    }
    expect(http.post(`${BASE}/.well-known/security.txt`, 'x'), 'nothing writes to it', [401, 404, 405]);
    expect(http.get(`${BASE}/.well-known/../api/tenant-svc/platform/security-incidents`), 'its public path opens nothing else', [400, 401, 404]);
  });

  group('2 an exploited vulnerability: 24 hours, 72 hours, 14 days after a measure', () => {
    const awareAt = ago(2 * HOUR);
    const i = incident('EXPLOITED_VULNERABILITY', awareAt, [tenant.tenantId]);
    truthy('the early warning is due 24 hours after awareness', sameInstant(stage(i, 'EARLY_WARNING').dueAt, new Date(Date.parse(awareAt) + 24 * HOUR).toISOString()), stage(i, 'EARLY_WARNING'));
    truthy('the notification 72 hours after', sameInstant(stage(i, 'NOTIFICATION').dueAt, new Date(Date.parse(awareAt) + 72 * HOUR).toISOString()), stage(i, 'NOTIFICATION'));
    truthy('the final report waits on a corrective measure', stage(i, 'FINAL_REPORT').state === 'WAITING' && !stage(i, 'FINAL_REPORT').dueAt, stage(i, 'FINAL_REPORT'));
    truthy('each stage cites the regulation', /2024\/2847/.test(stage(i, 'EARLY_WARNING').citation || ''), stage(i, 'EARLY_WARNING'));

    const warned = record(i.id, { kind: 'EARLY_WARNING_SENT', occurredAt: ago(HOUR), reference: 'SRP-K6-1' });
    expect(warned, 'the early warning is recorded', 201);
    truthy('and shows done with its reference', stage(data(warned), 'EARLY_WARNING').state === 'DONE' && (data(warned).events || []).some((e) => e.reference === 'SRP-K6-1'), data(warned));
    expect(record(i.id, { kind: 'EARLY_WARNING_SENT' }), 'recorded once only', 409, 'INCIDENT_STAGE_ALREADY_RECORDED');
    expect(record(i.id, { kind: 'FINAL_REPORT_SENT' }), 'no final report before the notification', 409, 'INCIDENT_FINAL_REPORT_TOO_EARLY');
    expect(record(i.id, { kind: 'NOTIFICATION_SENT', occurredAt: ago(50 * 60 * 1000) }), 'the notification is recorded', 201);
    expect(record(i.id, { kind: 'FINAL_REPORT_SENT' }), 'nor before a measure is available', 409, 'INCIDENT_FINAL_REPORT_TOO_EARLY');
    const measureAt = ago(40 * 60 * 1000);
    const m = data(record(i.id, { kind: 'MITIGATION_AVAILABLE', occurredAt: measureAt }));
    truthy('a measure starts the 14 days', sameInstant(stage(m, 'FINAL_REPORT').dueAt, new Date(Date.parse(measureAt) + 14 * 24 * HOUR).toISOString()), stage(m, 'FINAL_REPORT'));
    expect(record(i.id, { kind: 'FINAL_REPORT_SENT', reference: 'SRP-K6-1-F' }), 'the final report is recorded', 201);
    expect(record(i.id, { kind: 'CLOSED' }), 'not closed while businesses are untold', 409, 'INCIDENT_STAGES_OUTSTANDING');
    const told = tell(i.id, 'A patched release is live; no action needed beyond signing in again.');
    expect(told, 'the businesses affected are told', 200);
    truthy('one business, one notice', data(told).issued === 1 && data(told).total === 1, data(told));
    const closed = record(i.id, { kind: 'CLOSED' });
    expect(closed, 'closed once every stage is done', 201);
    truthy('and shows closed', data(closed).status === 'CLOSED', data(closed));
    expect(record(i.id, { kind: 'NOTE', note: 'late thought' }), 'nothing is added to a closed incident', 409, 'INCIDENT_CLOSED');
    expect(tell(i.id, 'again'), 'nor notices issued from it', 409, 'INCIDENT_CLOSED');
    const closedList = data(call('GET', `${INCIDENTS}?status=CLOSED`, { token: admin }));
    truthy('it is listed among the closed', Array.isArray(closedList) && closedList.some((x) => x.id === i.id), closedList);
  });

  group('3 a severe incident: overdue flagged, the final report a month after the notification', () => {
    const i = incident('SEVERE_INCIDENT', ago(30 * HOUR), []);
    truthy('a missed 24 hours is overdue', stage(i, 'EARLY_WARNING').state === 'OVERDUE', stage(i, 'EARLY_WARNING'));
    truthy('an incident naming no business affects all', i.affectsAllTenants === true, i);
    const row = (data(call('GET', `${INCIDENTS}?status=OPEN`, { token: admin })) || []).find((x) => x.id === i.id) || {};
    truthy('the register flags it and names the next stage', row.overdue === true && row.nextStage === 'EARLY_WARNING', row);
    const notifiedAt = ago(HOUR);
    const n = data(record(i.id, { kind: 'NOTIFICATION_SENT', occurredAt: notifiedAt }));
    const due = new Date(Date.parse(notifiedAt));
    due.setUTCMonth(due.getUTCMonth() + 1);
    truthy('the final report is due a calendar month after the notification', sameInstant(stage(n, 'FINAL_REPORT').dueAt, due.toISOString()), stage(n, 'FINAL_REPORT'));
    expect(record(i.id, { kind: 'MITIGATION_AVAILABLE' }), 'a severe incident has no measure stage', 400, 'INCIDENT_EVENT_NOT_APPLICABLE');
  });

  group('4 a personal data breach: the notice reaches only the business affected', () => {
    const i = incident('PERSONAL_DATA_BREACH', ago(20 * 60 * 1000), [tenant.tenantId]);
    truthy('its one stage is telling the business, citing GDPR art.33(2)', i.stages.length === 1 && /33\(2\)/.test(stage(i, 'TENANT_NOTICE').citation || ''), i.stages);
    expect(record(i.id, { kind: 'EARLY_WARNING_SENT' }), 'a breach has no CSIRT early warning', 400, 'INCIDENT_EVENT_NOT_APPLICABLE');
    must(tell(i.id, 'Customer email addresses were exposed. As controller you have 72 hours to tell your supervisory authority.'), 200, 'tell');
    truthy('a second issue sends nothing new', data(tell(i.id, 'again')).issued === 0);

    const mine = call('GET', NOTICES, { token: owner });
    expect(mine, 'the owner reads the notice', 200);
    const notice = (data(mine) || []).find((x) => x.incidentId === i.id) || {};
    truthy('with its message and unacknowledged', /supervisory authority/.test(notice.body || '') && notice.acknowledged === false, notice);
    truthy('the rival business has none of it', !(data(call('GET', NOTICES, { token: rival.owner.token })) || []).some((x) => x.incidentId === i.id));
    expect(call('GET', NOTICES, { token: cashier.token }), 'a cashier cannot read security notices', 403);
    expect(call('GET', NOTICES, {}), 'nor a guest', 401);
    expect(call('POST', `${NOTICES}/${notice.id}/acknowledge`, { token: rival.owner.token }), "the rival cannot acknowledge another business's notice", 404, 'SECURITY_NOTICE_NOT_FOUND');
    expect(call('POST', `${NOTICES}/${notice.id}/acknowledge`, { token: cashier.token }), 'nor can a cashier', 403);
    const ack = call('POST', `${NOTICES}/${notice.id}/acknowledge`, { token: owner });
    expect(ack, 'the owner acknowledges it', 200);
    const again = call('POST', `${NOTICES}/${notice.id}/acknowledge`, { token: owner });
    truthy('a second acknowledgement keeps the first time', data(again).acknowledgedAt === data(ack).acknowledgedAt, [data(ack), data(again)]);
    const sheet = data(call('GET', `${INCIDENTS}/${i.id}`, { token: admin }));
    truthy('the register sees one notice, one acknowledged, the stage done', sheet.noticesIssued === 1 && sheet.noticesAcknowledged === 1 && stage(sheet, 'TENANT_NOTICE').state === 'DONE', sheet);
  });

  group('5 only the platform administrator keeps the register', () => {
    const body = { kind: 'SEVERE_INCIDENT', title: 't', summary: 's', awareAt: ago(HOUR) };
    expect(open(body, owner), 'an owner cannot open an incident', 403);
    expect(call('GET', INCIDENTS, { token: owner }), 'nor list them', 403);
    expect(call('GET', INCIDENTS, { token: cashier.token }), 'nor a cashier', 403);
    expect(call('GET', INCIDENTS, {}), 'nor a guest', 401);
    expect(call('GET', INCIDENTS, { token: owner, headers: { 'X-Roles': 'PLATFORM_ADMIN' } }), 'a forged role header changes nothing', 403);
  });

  group('6 wrong input is refused by name', () => {
    const base = { kind: 'SEVERE_INCIDENT', title: 'k6 bad input', summary: 's', awareAt: ago(HOUR) };
    expect(open({ ...base, kind: 'PANIC' }), 'an unknown kind', 400, 'INCIDENT_KIND_UNKNOWN');
    expect(open({ ...base, awareAt: new Date(Date.now() + 2 * HOUR).toISOString() }), 'awareness in the future', 400, 'INCIDENT_AWARE_IN_FUTURE');
    expect(open({ ...base, awareAt: 'yesterday' }), 'awareness that is not a time', 400, 'INVALID_DATE');
    expect(open({ ...base, title: 'x'.repeat(201) }), 'a title over 200 characters', 400, 'INCIDENT_TITLE_INVALID');
    expect(open({ ...base, tenantIds: ['not-a-uuid'] }), 'a tenant id that is not one', 400, 'INVALID_UUID');
    expect(open({ ...base, tenantIds: ['01890000-0000-7000-8000-000000000000'] }), 'a business that does not exist', 400, 'INCIDENT_TENANT_UNKNOWN');
    expect(open({ ...base, tenantIds: Array.from({ length: 501 }, (_, k) => `01890000-0000-7000-8000-${String(k).padStart(12, '0')}`) }), 'more than 500 businesses', 400, 'INCIDENT_TOO_MANY_TENANTS');
    expect(call('GET', `${INCIDENTS}?status=${encodeURIComponent("OPEN' OR '1'='1")}`, { token: admin }), 'SQL in the status is an unknown status', 400, 'INCIDENT_STATUS_UNKNOWN');

    const awareAt = ago(HOUR);
    const i = incident('EXPLOITED_VULNERABILITY', awareAt, []);
    expect(record(i.id, { kind: 'PANICKED' }), 'an unknown event', 400, 'INCIDENT_EVENT_UNKNOWN');
    expect(record(i.id, { kind: 'EARLY_WARNING_SENT', occurredAt: new Date(Date.parse(awareAt) - 60000).toISOString() }), 'a report before awareness', 400, 'INCIDENT_EVENT_BEFORE_AWARE');
    expect(record(i.id, { kind: 'EARLY_WARNING_SENT', occurredAt: new Date(Date.now() + HOUR).toISOString() }), 'a report in the future', 400, 'INCIDENT_EVENT_IN_FUTURE');
    expect(record(i.id, { kind: 'TENANTS_NOTIFIED' }), 'businesses told only by issuing notices', 400, 'INCIDENT_EVENT_NOT_APPLICABLE');
    expect(record(i.id, { kind: 'EARLY_WARNING_SENT', reference: 'R'.repeat(121) }), 'a reference over 120 characters', 400, 'INCIDENT_REFERENCE_INVALID');
    expect(record(i.id, { kind: 'NOTE' }), 'a note without its text', 400, 'INCIDENT_NOTE_REQUIRED');
    expect(tell(i.id, '   '), 'a blank notice', 400);
    expect(record('01890000-0000-7000-8000-000000000001', { kind: 'NOTE', note: 'x' }), 'an incident that does not exist', 404, 'INCIDENT_NOT_FOUND');
    expect(call('PUT', `${INCIDENTS}/${i.id}`, { token: admin, body: {} }), 'no incident is edited', [404, 405]);
    expect(call('DELETE', `${INCIDENTS}/${i.id}`, { token: admin }), 'or deleted', [404, 405]);
  });

  group('7 the rush: twenty recording one report, twenty acknowledging one notice', () => {
    const i = incident('EXPLOITED_VULNERABILITY', ago(HOUR), [rival.tenantId]);
    const headers = { Authorization: `Bearer ${admin}`, 'Content-Type': 'application/json' };
    const body = JSON.stringify({ kind: 'EARLY_WARNING_SENT', reference: 'SRP-RUSH' });
    const rush = http.batch(Array.from({ length: 20 }, () => ['POST', `${BASE}${INCIDENTS}/${i.id}/events`, body, { headers, tags: { name: 'POST incident event rush' } }]));
    const statuses = rush.map((r) => r.status);
    truthy('exactly one records it, nineteen are told it already is', statuses.filter((s) => s === 201).length === 1 && statuses.filter((s) => s === 409).length === 19, statuses);
    const sheet = data(call('GET', `${INCIDENTS}/${i.id}`, { token: admin }));
    truthy('the timeline holds it once', (sheet.events || []).filter((e) => e.kind === 'EARLY_WARNING_SENT').length === 1, sheet.events);

    must(tell(i.id, 'Please sign out every till and sign in again.'), 200, 'tell rival');
    const notice = (data(call('GET', NOTICES, { token: rival.owner.token })) || []).find((x) => x.incidentId === i.id) || {};
    const ackHeaders = { Authorization: `Bearer ${rival.owner.token}`, 'Content-Type': 'application/json' };
    const acks = http.batch(Array.from({ length: 20 }, () => ['POST', `${BASE}${NOTICES}/${notice.id}/acknowledge`, null, { headers: ackHeaders, tags: { name: 'POST notice acknowledge rush' } }]));
    truthy('all twenty answer, with one acknowledgement time', acks.every((r) => r.status === 200) && new Set(acks.map((r) => data(r).acknowledgedAt)).size === 1, acks.map((r) => r.status));
  });
}
