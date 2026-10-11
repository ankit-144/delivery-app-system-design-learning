// Scenario 6.5 — The same flash-sale blast, through Kafka.
//
// core publishes notifications to the "notifications" topic; notification-service consumes at whatever
// pace the vendor allows. Marketing blasts 60,000 promos at 3,000/s while customers keep ordering.
//
//   make load ID=6.5 MODE=fixed    # notification-mode=kafka-direct: Kafka absorbs the spike; Postgres never sees it
//
// Compare with 6.4. Needs Phase 6.   Knobs: DURATION (60s), BLAST (60000).
import http from 'k6/http';
import {
  CORE_URLS, applyMode, env, envInt, flushNotificationBacklog, promValue, resetFixtures, setNotify, setVendor,
  waitForNotificationsToDrain, waitForScrape,
} from '../lib/quickdrop.js';
import { stream, streams, SUMMARY_STATS } from '../lib/streams.js';
import { history as historyReq, orderInCity1 } from '../lib/traffic.js';

const DURATION = env('DURATION', '60s');
const s = streams(['order', 'history']);

export const options = {
  setupTimeout: '10m',
  teardownTimeout: '10m',
  scenarios: {
    orders: stream('order', 20, DURATION),
    my_orders: stream('history', 20, DURATION),
    blast: { executor: 'shared-iterations', exec: 'blast', vus: 1, iterations: 1, startTime: '10s' },
  },
  summaryTrendStats: SUMMARY_STATS,
};

const MODES = { fixed: {}, broken: {} };

export function setup() {
  applyMode(MODES, { 'notification-mode': 'kafka-direct', 'inventory-reservation': 'atomic', 'rider-assignment': 'conditional', 'order-history-query': 'two-step' });
  flushNotificationBacklog();
  resetFixtures({ restockAll: 100000 });
  setNotify({ 'delivery-mode': 'sync' });
  setVendor({ 'latency-ms': '20', 'jitter-ms': '5' });
}

export function blast() {
  const res = http.post(`${CORE_URLS[0]}/api/marketing/blast?count=${envInt('BLAST', 60000)}&rate=3000`);
  console.log(`blast: ${res.body}`);
}

export function order() {
  const res = orderInCity1('30s');
  s.record('order', res, res.status === 201);
}

export function history() {
  const res = historyReq('30s');
  s.record('history', res, res.status === 200);
}

export function teardown() {
  waitForScrape();
  console.log(`Postgres CPU peak: ${(promValue('max_over_time(rate(container_cpu_usage_seconds_total{container="postgres"}[10s])[80s:5s])') || 0).toFixed(2)} of 2 cores`);
  console.log(`peak Kafka lag: ${Math.round(promValue('max_over_time(max(notify_consumer_lag{topic="notifications"})[80s:5s])') || 0)} messages`);
  // Keep the vendor at its normal speed and time how long the consumers need to work through the backlog.
  const start = Date.now();
  const drained = waitForNotificationsToDrain(540);
  console.log(drained ? `backlog drained ${Math.round((Date.now() - start) / 1000)} s after the run ended` : 'backlog still draining after 9 minutes');
}

export function handleSummary(data) {
  return s.summary(data, 'Scenario 6.5 — the blast through Kafka', [
    'Kafka & notifications -> "Consumer lag" climbs, then drains. Postgres stays flat.',
  ]);
}
