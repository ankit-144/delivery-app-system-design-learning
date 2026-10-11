// Scenario 4.5 — Backfilling a new column on 1.5 million rows.
//
// orders.coupon_code was just added (nullable). Now every existing row needs a value.
//
//   make load ID=4.5 MODE=broken    # one UPDATE, one transaction: every row locked until it commits
//   make load ID=4.5 MODE=fixed     # 10,000 rows per transaction with a short pause between batches
//
// Meanwhile customers rate past deliveries: single-row UPDATEs on old orders — the rows the backfill locks.
// Works in any phase.   Knobs: DURATION (60s).
import http from 'k6/http';
import { sleep } from 'k6';
import {
  BASE_URL, CORE_URLS, applyMode, dbLab, dbLabJobs, ensureHistory, env, randomInt, resetFixtures, waitForJob,
} from '../lib/quickdrop.js';
import { stream, streams, SUMMARY_STATS } from '../lib/streams.js';
import { orderInCity1 } from '../lib/traffic.js';

const DURATION = env('DURATION', '70s');
const s = streams(['order', 'rating']);

export const options = {
  setupTimeout: '10m',
  scenarios: {
    orders: stream('order', 20, DURATION),
    ratings: stream('rate', 10, DURATION),
    backfill: { executor: 'shared-iterations', exec: 'backfill', vus: 1, iterations: 1, startTime: '10s' },
  },
  summaryTrendStats: SUMMARY_STATS,
};

const MODES = { broken: {}, fixed: {} };

export function setup() {
  applyMode(MODES, {
    'notification-mode': 'none', 'inventory-reservation': 'atomic', 'rider-assignment': 'conditional', 'auto-deliver-after-ms': '5000',
  });
  ensureHistory();
  dbLab('reset');
  waitForJob(dbLab('ddl', { name: 'add-column' }).id);
  resetFixtures({ restockAll: 100000 });
  console.log(`before: ${http.get(`${CORE_URLS[0]}/admin/db-lab/table-size?name=orders`).body}`);
}

export function backfill() {
  const job = dbLab('backfill', __ENV.MODE === 'fixed' ? { mode: 'batched', batch: 10000, pauseMs: 50 } : { mode: 'single' });
  console.log(`backfill started: ${job.what}`);
}

export function order() {
  const res = orderInCity1('30s');
  s.record('order', res, res.status === 201);
}

/** Seeded history orders have ids up to ~1.5M and are all DELIVERED. */
export function rate() {
  const res = http.post(`${BASE_URL}/api/orders/${randomInt(1, 1400000)}/rating`, JSON.stringify({ stars: randomInt(1, 5) }),
    { headers: { 'Content-Type': 'application/json' }, timeout: '30s', tags: { name: 'POST /api/orders/{id}/rating' } });
  s.record('rating', res, res.status === 200 || res.status === 404);
}

export function teardown() {
  sleep(2);
  for (const job of dbLabJobs()) console.log(`job ${job.id}: ${job.what} -> ${job.result} (${job.millis} ms)`);
  console.log(`after: ${http.get(`${CORE_URLS[0]}/admin/db-lab/table-size?name=orders`).body}`);
}

export function handleSummary(data) {
  return s.summary(data, 'Scenario 4.5 — backfill', [
    'Postgres -> "Sessions waiting for a lock", "Longest open transaction", "Dead rows per table".',
  ]);
}
