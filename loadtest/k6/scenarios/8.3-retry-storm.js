// Scenario 8.3 — A retry storm.
//
// OTPs flow core → notification-service → SMS vendor, synchronously (notification-mode=http, delivery-mode=sync).
// The vendor works through requests with 20 workers at 50 ms each (capacity ~400/s); extra requests queue.
// 2% of sends fail randomly all the time. At t=15 s the vendor has a 10-second hiccup (250 ms per send).
//
//   make load ID=8.3 MODE=none      # no retries anywhere: 2% of OTPs lost all the time, a dip during the hiccup
//   make load ID=8.3 MODE=broken    # 3 immediate retries at BOTH layers: the hiccup never ends
//   make load ID=8.3 MODE=fixed     # 2 retries at one layer, exponential backoff + jitter, 10% retry budget
//
// Needs Phase 6+ (Phase 8 for traces).   Knobs: RATE (OTP/s, 150), DURATION (60s).
import http from 'k6/http';
import { sleep } from 'k6';
import { BASE_URL, JSON_HEADERS, NOTIFY_URLS, PROMETHEUS_URL, applyMode, env, envInt, liveNotifyUrls, resetFixtures, setNotify, setVendor, waitForScrape } from '../lib/quickdrop.js';
import { stream, streams, SUMMARY_STATS } from '../lib/streams.js';

const RATE = envInt('RATE', 150);
const DURATION = env('DURATION', '60s');
const s = streams(['otp']);

export const options = {
  setupTimeout: '10m',
  teardownTimeout: '5m',
  scenarios: {
    otp: stream('otp', RATE, DURATION, 1500),
    hiccup: { executor: 'shared-iterations', exec: 'hiccup', vus: 1, iterations: 1, startTime: '15s', maxDuration: '1m' },
  },
  summaryTrendStats: SUMMARY_STATS,
};

const COMMON = { 'notification-mode': 'http', 'notify-timeout-ms': '2000', 'tomcat-max-threads': '400', 'rate-limiter': 'off' };
const MODES = {
  none: {},
  broken: { 'notify-retries': '3' },
  fixed: { 'notify-retries': '0', 'notify-circuit-breaker': 'on' },
};
const NOTIFY = {
  none: { 'vendor-retries': '0' },
  broken: { 'vendor-retries': '3' },
  fixed: { 'vendor-retries': '2', 'vendor-retry-backoff': 'exponential-jitter', 'vendor-retry-budget': 'on' },
};
const VENDOR = { 'latency-ms': '50', 'jitter-ms': '0', 'max-concurrency': '20', 'overload-mode': 'queue', 'error-rate-percent': '2' };

export function setup() {
  // A previous broken run can leave an instance busy (or dead): start from a healthy system.
  for (let i = 0; i < 30 && liveNotifyUrls().length < NOTIFY_URLS.length; i++) sleep(2);
  if (liveNotifyUrls().length < NOTIFY_URLS.length) {
    throw new Error(`only ${liveNotifyUrls().length} of ${NOTIFY_URLS.length} notification-service instances answer: restart them (make up PHASE=8)`);
  }
  applyMode(MODES, COMMON);
  resetFixtures({});
  setNotify({ 'delivery-mode': 'sync', 'vendor-timeout-ms': '500', ...NOTIFY[__ENV.MODE || 'broken'] });
  setVendor(VENDOR);
  sleep(2);
  return { start: Date.now() };
}

export function otp() {
  const phone = `+9198${String(Math.floor(Math.random() * 1e8)).padStart(8, '0')}`;
  const res = http.post(`${BASE_URL}/api/auth/otp`, JSON.stringify({ phone }), { headers: JSON_HEADERS, timeout: '30s', tags: { name: 'POST /api/auth/otp' } });
  s.record('otp', res, res.status === 202);
}

/** The vendor's admin endpoint shares its (overloaded) HTTP port, so keep trying until the change sticks. */
function vendorLatency(ms) {
  const url = `${env('VENDOR_URL', 'http://vendor-mock:8080')}/admin/toggles/latency-ms?value=${ms}`;
  for (let attempt = 1; attempt <= 30; attempt++) {
    const res = http.put(url, null, { timeout: '5s', tags: { name: 'admin' } });
    if (res.status === 200) return attempt;
    sleep(0.5);
  }
  throw new Error(`could not set the vendor's latency to ${ms} ms`);
}

export function hiccup() {
  vendorLatency(250);
  console.log('t=15s vendor hiccup: 250 ms per send (capacity ~80/s)');
  sleep(10);
  const attempts = vendorLatency(50);
  console.log(`t=25s vendor back to 50 ms (capacity ~400/s): the trigger is gone${attempts > 1 ? ` (took ${attempts} tries: the vendor's admin API shares its overloaded port)` : ''}`);
}

function promRange(expr, start, end) {
  const url = `${PROMETHEUS_URL}/api/v1/query_range?query=${encodeURIComponent(expr)}&start=${start}&end=${end}&step=10`;
  const res = http.get(url, { tags: { name: 'prometheus' } });
  const out = {};
  try {
    for (const [t, v] of res.json().data.result[0].values) out[Math.round(t)] = parseFloat(v);
  } catch (e) { /* no data */ }
  return out;
}

export function teardown(data) {
  console.log('load stopped at t=60s; watching the vendor for another 40 s');
  sleep(40);
  waitForScrape();
  const start = Math.floor(data.start / 1000);
  const end = start + 100;
  const series = {
    sent: promRange('sum(rate(quickdrop_notifications_total{mode="http", result="sent"}[10s]))', start, end),
    dropped: promRange('sum(rate(quickdrop_notifications_total{mode="http", result="dropped"}[10s]))', start, end),
    vendor: promRange('sum(rate(vendor_messages_total[10s]))', start, end),
    dup: promRange('sum(rate(vendor_duplicate_messages_total[10s]))', start, end),
    notifyMs: promRange('1000 * sum(rate(http_server_requests_seconds_sum{job="notification", uri="/notifications"}[10s])) / sum(rate(http_server_requests_seconds_count{job="notification", uri="/notifications"}[10s]))', start, end),
    coreRetries: promRange('sum(rate(quickdrop_retry_attempts_total{client="notification-service", kind="retry"}[10s]))', start, end),
    notifyRetries: promRange('sum(rate(quickdrop_retry_attempts_total{client="sms-vendor", kind="retry"}[10s]))', start, end),
  };
  const f = (x) => (x === undefined || Number.isNaN(x) ? '-' : Math.round(x).toString()).padStart(9);
  const lines = ['', `================ 8.3 timeline (MODE=${__ENV.MODE || 'broken'}, ${RATE} OTP/s offered until t=60s) ================`,
    `  ${'t'.padStart(4)} ${'SMS ok/s'.padStart(9)} ${'lost/s'.padStart(9)} ${'vendor/s'.padStart(9)} ${'dup/s'.padStart(9)} ${'ntfy ms'.padStart(9)} ${'core rt/s'.padStart(9)} ${'ntfy rt/s'.padStart(9)}`];
  for (let t = start + 10; t <= end; t += 10) {
    const note = t - start === 20 ? '  ← hiccup (15–25 s)' : t - start === 30 ? '  ← trigger gone' : t - start === 70 ? '  ← load stopped' : '';
    lines.push(`  ${String(t - start).padStart(3)}s ${f(series.sent[t])} ${f(series.dropped[t])} ${f(series.vendor[t])} ${f(series.dup[t])} ${f(series.notifyMs[t])} ${f(series.coreRetries[t])} ${f(series.notifyRetries[t])}${note}`);
  }
  const vendor = http.get(`${env('VENDOR_URL', 'http://vendor-mock:8080')}/v1/stats`).json();
  lines.push(`  vendor totals: ${vendor.accepted} SMS accepted, ${vendor.duplicates} duplicates (same OTP texted again), ${vendor.rejected} errors`);
  lines.push('  SMS ok/s, lost/s: what core saw. vendor/s: sends the vendor processed; dup/s: of those, OTPs it had already sent.');
  lines.push('  ntfy ms: average time notification-service took per request. rt/s: retries per second at core / at notification-service.');
  console.log(lines.join('\n'));
  setVendor({ 'max-concurrency': '0', 'overload-mode': 'reject', 'error-rate-percent': '0', 'latency-ms': '100', 'jitter-ms': '20' });
}

export function handleSummary(data) {
  return s.summary(data, 'Scenario 8.3 — retry storm (OTP request latency as the customer sees it)');
}
