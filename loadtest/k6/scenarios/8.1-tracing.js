// Scenario 8.1 — Where did the time go? Distributed tracing across HTTP, Postgres, the outbox and Kafka.
//
// Customers say order confirmations "sometimes take ages". Orders go through the outbox (6.6) → Kafka →
// notification-service → SMS vendor, and 5% of vendor sends take 2 s. Every service sends spans to Tempo.
//
//   make load ID=8.1 MODE=broken    # outbox-trace-context=drop: the order's trace ends at the INSERT
//   make load ID=8.1 MODE=fixed     # propagate: one trace from the HTTP request to the vendor call
//
// Needs Phase 8 (make up PHASE=8).   Knobs: RATE (orders/s, 20), DURATION (45s).
import http from 'k6/http';
import { sleep } from 'k6';
import { applyMode, env, envInt, resetFixtures, setNotify, setVendor, waitForNotificationsToDrain } from '../lib/quickdrop.js';
import { stream, streams, SUMMARY_STATS } from '../lib/streams.js';
import { orderInCity1 } from '../lib/traffic.js';

const TEMPO = env('TEMPO_URL', 'http://tempo:3200');
const DURATION = env('DURATION', '45s');
const s = streams(['order']);

export const options = {
  setupTimeout: '10m',
  teardownTimeout: '5m',
  scenarios: { orders: stream('order', envInt('RATE', 20), DURATION) },
  summaryTrendStats: SUMMARY_STATS,
};

const COMMON = { 'notification-mode': 'outbox', 'inventory-reservation': 'atomic', 'rider-assignment': 'conditional' };
const MODES = { broken: { 'outbox-trace-context': 'drop' }, fixed: { 'outbox-trace-context': 'propagate' } };

export function setup() {
  let ready = null;
  for (let i = 0; i < 30 && !(ready && ready.status === 200); i++) {   // Tempo can be busy for a while after a big run
    if (i > 0) sleep(2);
    ready = http.get(`${TEMPO}/ready`, { timeout: '5s' });
  }
  if (ready.status !== 200) throw new Error(`Tempo is not ready (${ready.status} ${ready.error || ''}): is Phase 8 running?  make up PHASE=8`);
  applyMode(MODES, COMMON);
  resetFixtures({ restockAll: 100000 });
  setNotify({ 'delivery-mode': 'sync' });
  setVendor({ 'latency-ms': '80', 'jitter-ms': '20', 'slow-percent': '5', 'slow-ms': '2000' });
  return { start: Math.floor(Date.now() / 1000) };
}

export function order() {
  const res = orderInCity1('30s');
  s.record('order', res, res.status === 201);
}

function search(q, start, end, limit = 1000) {
  const url = `${TEMPO}/api/search?q=${encodeURIComponent(q)}&start=${start}&end=${end}&limit=${limit}`;
  const res = http.get(url, { timeout: '60s', tags: { name: 'tempo' } });
  return res.status === 200 ? (res.json().traces || []) : [];
}

export function teardown(data) {
  waitForNotificationsToDrain(120);
  // Tempo holds a trace in memory until no new span has arrived for trace_idle_period (60 s, infra/tempo/tempo.yml),
  // and only then makes it searchable. Order traces continue for a while after the HTTP response (outbox, Kafka).
  sleep(75);
  const end = Math.floor(Date.now() / 1000);
  const orders = search('{ rootName = "http post /api/orders" && status != error }', data.start, end).length;
  const connected = search('{ rootName = "http post /api/orders" && name = "notifications receive" }', data.start, end).length;
  const orphans = search('{ rootName = "notifications send" }', data.start, end).length;
  const slow = search('{ rootName = "http post /api/orders" && name = "notifications receive" && duration > 1s }', data.start, end, 20);
  const slowOrphans = search('{ rootName = "notifications send" && duration > 1s }', data.start, end, 20);
  const lines = ['', `================ 8.1 — traces in Tempo (MODE=${__ENV.MODE || 'broken'}) ================`,
    `  order traces:                                  ${orders}`,
    `  ...that reach the SMS vendor (one connected trace): ${connected}`,
    `  orphan traces starting at "notifications send":    ${orphans}   (an SMS with no link to its order)`];
  const example = (slow[0] || slowOrphans[0]);
  if (example) {
    lines.push(`  a slow one: trace ${example.traceID} (${example.rootTraceName}, ${example.durationMs} ms)`);
    lines.push('  open it: Grafana → Explore → Tempo → paste the trace id, or run the TraceQL below.');
  }
  lines.push('  TraceQL to try in Grafana → Explore → Tempo:',
    '    { resource.service.name = "vendor-mock" && duration > 1s }              slow vendor calls',
    '    { rootName = "http post /api/orders" && name = "query" } | select(span."jdbc.query[0]")   orders and their SQL',
    '    { name = "outbox relay" }                                                  time between INSERT and publish');
  console.log(lines.join('\n'));
  setVendor({ 'slow-percent': '0', 'latency-ms': '100' });
}

export function handleSummary(data) {
  return s.summary(data, 'Scenario 8.1 — tracing');
}
