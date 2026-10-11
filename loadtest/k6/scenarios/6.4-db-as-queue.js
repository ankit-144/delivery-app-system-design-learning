// Scenario 6.4 — Postgres as the notification queue, during a flash-sale blast.
//
// delivery-mode=db-queue: every notification is a row in notify.notifications, on the same Postgres that
// takes orders. Marketing blasts 60,000 "flash sale!" promos. Meanwhile customers keep ordering.
//
//   make load ID=6.4 MODE=broken    # db-queue: inserts, polling and status updates all hit the shared database
//
// Compare with 6.5 (the same blast through Kafka). Needs Phase 6.   Knobs: DURATION (60s), BLAST (60000).
import http from 'k6/http';
import exec from 'k6/execution';
import {
  applyMode, env, envInt, promValue, resetFixtures, setNotify, setVendor, waitForScrape, flushNotificationBacklog } from '../lib/quickdrop.js';
import { stream, streams, SUMMARY_STATS } from '../lib/streams.js';
import { history as historyReq, orderInCity1 } from '../lib/traffic.js';

const DURATION = env('DURATION', '60s');
const s = streams(['order', 'history']);

export const options = {
  setupTimeout: '10m',
  scenarios: {
    orders: stream('order', 20, DURATION),
    my_orders: stream('history', 20, DURATION),
    blast: { executor: 'shared-iterations', exec: 'blast', vus: 2, iterations: 2, startTime: '10s', maxDuration: '5m' },
  },
  summaryTrendStats: SUMMARY_STATS,
};

const MODES = { broken: {} };

export function setup() {
  applyMode(MODES, { 'notification-mode': 'http', 'notify-timeout-ms': '2000', 'inventory-reservation': 'atomic', 'rider-assignment': 'conditional', 'order-history-query': 'two-step' });
  flushNotificationBacklog();
  resetFixtures({ restockAll: 100000 });
  setNotify({ 'delivery-mode': 'db-queue', workers: env('WORKERS', '16'), 'db-poll-ms': '50' });
  setVendor({ 'latency-ms': '20', 'jitter-ms': '5' });
}

/** Each of the two notification instances receives half of the blast straight into its queue table. */
export function blast() {
  const url = exec.scenario.iterationInTest === 0 ? 'http://notification-1:8080' : 'http://notification-2:8080';
  const res = http.post(`${url}/admin/blast?count=${envInt('BLAST', 60000) / 2}`, null, { timeout: '5m' });
  console.log(`blast via ${url}: ${res.status} ${res.body}`);
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
  console.log(`queue table rows still PENDING: ${Math.round(promValue('max(notify_db_pending)') || 0)}`);
}

export function handleSummary(data) {
  return s.summary(data, 'Scenario 6.4 — Postgres as the notification queue', [
    'Postgres -> "Top queries by calls" (the polling), "Transactions per second", "Rows per second".',
  ]);
}
