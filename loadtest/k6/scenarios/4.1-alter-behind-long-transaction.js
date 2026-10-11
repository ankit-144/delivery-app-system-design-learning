// Scenario 4.1 — An instant ALTER TABLE that freezes the orders table.
//
// Adding a nullable column is instant: Postgres only updates its catalog. But it needs an ACCESS EXCLUSIVE
// lock for that instant. At t=5 s an analyst's psql session reads orders and then sits idle in its
// transaction for 30 s (holding a harmless ACCESS SHARE lock). At t=10 s the migration runs.
//
//   make load ID=4.1 MODE=broken    # ALTER waits for the analyst... and every query on orders waits behind the ALTER
//   make load ID=4.1 MODE=fixed     # SET lock_timeout = '2s': give up quickly, let traffic through, retry
//
// Works in any phase.   Knobs: DURATION (50s).
import { sleep } from 'k6';
import {
  applyMode, customerIndex, dbLab, dbLabJobs, ensureHistory, env, resetFixtures,
} from '../lib/quickdrop.js';
import { stream, streams, SUMMARY_STATS } from '../lib/streams.js';
import { browse as browseReq, history as historyReq, orderInCity1 } from '../lib/traffic.js';

const DURATION = env('DURATION', '50s');
const s = streams(['order', 'history', 'browse']);

export const options = {
  setupTimeout: '10m',
  scenarios: {
    orders: stream('order', 20, DURATION),
    my_orders: stream('history', 20, DURATION),
    browsing: stream('browse', 20, DURATION),
    analyst: { executor: 'shared-iterations', exec: 'analyst', vus: 1, iterations: 1, startTime: '5s' },
    migration: { executor: 'shared-iterations', exec: 'migration', vus: 1, iterations: 1, startTime: '10s' },
  },
  summaryTrendStats: SUMMARY_STATS,
};

const MODES = { broken: {}, fixed: {} };

export function setup() {
  applyMode(MODES, { 'notification-mode': 'none', 'inventory-reservation': 'atomic', 'rider-assignment': 'conditional', 'order-history-query': 'two-step' });
  ensureHistory();
  customerIndex('create');
  dbLab('reset');
  resetFixtures({ restockAll: 100000 });
}

export function analyst() {
  console.log(`analyst: ${JSON.stringify(dbLab('hold', { seconds: 30 }))}`);
}

export function migration() {
  const params = __ENV.MODE === 'fixed' ? { name: 'add-column', lockTimeoutMs: 2000, retries: 20 } : { name: 'add-column' };
  console.log(`migration: ${JSON.stringify(dbLab('ddl', params))}`);
}

export function order() {
  const res = orderInCity1('60s');
  s.record('order', res, res.status === 201);
}

export function history() {
  const res = historyReq('60s');
  s.record('history', res, res.status === 200);
}

export function browse() {
  const res = browseReq('60s');
  s.record('browse', res, res.status === 200);
}

export function teardown() {
  sleep(5);
  for (const job of dbLabJobs()) console.log(`job ${job.id}: ${job.what} -> ${job.result} (${job.millis} ms)`);
}

export function handleSummary(data) {
  return s.summary(data, 'Scenario 4.1 — ALTER TABLE behind a long transaction', [
    'Postgres -> "Sessions waiting for a lock" and "Connections by state" (idle in transaction).',
    'browse does not touch the orders table: it is the control group.',
  ]);
}
