// Scenario 8.2 — Alerts: which ones should wake someone up?
//
// Seven minutes of normal traffic (30 checkouts/s, 50 browses/s) with two incidents:
//   t=60–150 s   a nightly export job pegs notification-1's CPU (and a marketing blast keeps the consumers busy).
//                Checkout never waits for notification-service. Busy machines, happy customers.
//   t=150–330 s  core-2 starts failing 75% of its checkouts (a bad deploy on one instance): ~25% of all checkouts fail.
//   t=330 s      core-2 is fixed.
// Prometheus evaluates infra/prometheus/rules/alerts.yml; Alertmanager sends what fires to notification-service.
// At the end the script prints what reached the "pager" and when.
//
//   make load ID=8.2
//
// Needs Phase 8 (make up PHASE=8).
import http from 'k6/http';
import { sleep } from 'k6';
import { BASE_URL, NOTIFY_URLS, applyMode, flushNotificationBacklog, resetFixtures, setNotify, setToggles, setVendor } from '../lib/quickdrop.js';
import { stream, streams, SUMMARY_STATS } from '../lib/streams.js';
import { browse as browseReq, orderInCity1 } from '../lib/traffic.js';

const s = streams(['checkout', 'browse']);
const CORE2 = ['http://core-2:8080'];

export const options = {
  setupTimeout: '10m',
  teardownTimeout: '5m',
  scenarios: {
    checkout: stream('checkout', 30, '420s'),
    browsing: stream('browse', 50, '420s'),
    blast: { executor: 'shared-iterations', exec: 'blast', vus: 1, iterations: 1, startTime: '60s', maxDuration: '1m' },
    incident: { executor: 'shared-iterations', exec: 'incident', vus: 1, iterations: 1, startTime: '150s', maxDuration: '4m' },
  },
  summaryTrendStats: SUMMARY_STATS,
};

export function setup() {
  const am = http.get('http://alertmanager:9093/-/ready', { timeout: '5s' });
  if (am.status !== 200) throw new Error('Alertmanager is not running: start Phase 8 with  make up PHASE=8');
  applyMode({ broken: {} }, { 'inventory-reservation': 'atomic', 'rider-assignment': 'conditional', 'notification-mode': 'kafka-direct',
    'product-cache': 'redis' });
  flushNotificationBacklog();
  resetFixtures({ restockAll: 100000 });
  setNotify({ 'delivery-mode': 'sync' });
  setVendor({ 'latency-ms': '3', 'jitter-ms': '1' });   // a fast vendor: the consumers' own CPU becomes the limit
  http.del(`${NOTIFY_URLS[0]}/api/alerts`);
  return { start: Date.now() };
}

export function checkout() {
  const res = orderInCity1('30s');
  s.record('checkout', res, res.status === 201);
}

export function browse() {
  const res = browseReq('30s');
  s.record('browse', res, res.status === 200);
}

export function blast() {
  http.post(`${NOTIFY_URLS[0]}/admin/chaos/cpu?seconds=90&threads=2`, null, { tags: { name: 'admin' } });
  http.post(`${BASE_URL}/api/marketing/blast?count=30000&rate=500`, null, { tags: { name: 'blast' } });
  console.log('t=60s export job pegs notification-1 CPU for 90 s; marketing blast: 30,000 promos at 500/s through Kafka');
}

export function incident() {
  setToggles({ 'error-percent': '75', 'error-scope': 'checkout' }, CORE2);
  console.log('t=150s core-2 fails 75% of checkouts (a bad deploy on one instance)');
  sleep(180);
  setToggles({ 'error-percent': '0' }, CORE2);
  console.log('t=330s core-2 fixed');
}

export function teardown(data) {
  sleep(60);   // give resolutions time to arrive
  // Only alerts that started during this run (a resolution from an earlier run can still arrive at the start).
  const alerts = http.get(`${NOTIFY_URLS[0]}/api/alerts`).json()
    .filter((a) => a.alertname !== 'Watchdog' && Date.parse(a.startsAt) >= data.start - 5000);
  const lines = ['', '================ 8.2 — what reached the pager ================',
    '  incidents: notification-1 CPU pegged + promo blast t=60–150 s (customers fine) · core-2 fails 75% of checkouts t=150–330 s'];
  if (alerts.length === 0) lines.push('  nothing arrived (is Alertmanager running? docker logs qd-alertmanager)');
  for (const a of alerts) {
    const t = Math.round((Date.parse(a.receivedAt) - data.start) / 1000);
    lines.push(`  t=${String(t).padStart(4)}s  ${a.status.toUpperCase().padEnd(8)} ${a.alertname.padEnd(28)} [${a.severity}] ${a.summary}`);
  }
  const heartbeats = http.get(`${NOTIFY_URLS[0]}/api/alerts`).json().filter((a) => a.alertname === 'Watchdog').length;
  lines.push(`  (plus ${heartbeats} Watchdog heartbeats: proof the pipeline itself was working)`);
  console.log(lines.join('\n'));
  setToggles({ 'error-percent': '0' }, CORE2);
  setVendor({ 'latency-ms': '100', 'jitter-ms': '20' });
  flushNotificationBacklog();
}

export function handleSummary(data) {
  return s.summary(data, 'Scenario 8.2 — alerts');
}
