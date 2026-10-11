// Scenario 4.2 — Building an index on a busy table.
//
// A new index to look orders up by a public reference: orders (md5(id::text)). ~1.5M rows, about 2 s to
// build here (production tables take minutes; the shape of the problem is the same).
//
//   make load ID=4.2 MODE=broken        # CREATE INDEX: SHARE lock — reads fine, every INSERT waits for the build
//   make load ID=4.2 MODE=fixed         # CREATE INDEX CONCURRENTLY: writes continue, build takes longer
//   make load ID=4.2 MODE=interrupted   # CONCURRENTLY, but a long transaction makes it wait and lock_timeout
//                                       #   cancels it: an INVALID index is left behind
//
// Works in any phase.   Knobs: DURATION (45s).
import http from 'k6/http';
import { sleep } from 'k6';
import {
  CORE_URLS, applyMode, customerIndex, dbLab, dbLabJobs, ensureHistory, env, resetFixtures,
} from '../lib/quickdrop.js';
import { stream, streams, SUMMARY_STATS } from '../lib/streams.js';
import { history as historyReq, orderInCity1 } from '../lib/traffic.js';

const DURATION = env('DURATION', '45s');
const s = streams(['order', 'history']);

export const options = {
  setupTimeout: '10m',
  scenarios: {
    orders: stream('order', 30, DURATION),
    my_orders: stream('history', 20, DURATION),
    long_tx: { executor: 'shared-iterations', exec: 'longTransaction', vus: 1, iterations: 1, startTime: '5s' },
    migration: { executor: 'shared-iterations', exec: 'migration', vus: 1, iterations: 1, startTime: '10s' },
  },
  summaryTrendStats: SUMMARY_STATS,
};

const MODES = { broken: {}, fixed: {}, interrupted: {} };

export function setup() {
  applyMode(MODES, { 'notification-mode': 'none', 'inventory-reservation': 'atomic', 'rider-assignment': 'conditional', 'order-history-query': 'two-step' });
  ensureHistory();
  customerIndex('create');
  dbLab('reset');
  resetFixtures({ restockAll: 100000 });
}

export function longTransaction() {
  if (__ENV.MODE === 'interrupted') console.log(`long transaction: ${JSON.stringify(dbLab('hold', { seconds: 25 }))}`);
}

export function migration() {
  const params = {
    broken: { name: 'create-index', maintenanceWorkMemMb: 1 },
    fixed: { name: 'create-index-concurrently', maintenanceWorkMemMb: 1 },
    interrupted: { name: 'create-index-concurrently', maintenanceWorkMemMb: 1, lockTimeoutMs: 5000 },
  }[__ENV.MODE || 'broken'];
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

export function teardown() {
  sleep(3);
  for (const job of dbLabJobs()) console.log(`job ${job.id}: ${job.what} -> ${job.result} (${job.millis} ms)`);
  const lab = http.get(`${CORE_URLS[0]}/admin/db-lab/indexes`).json().filter((i) => i.index === 'orders_ref_lab_idx');
  console.log(`orders_ref_lab_idx: ${lab.length ? JSON.stringify(lab[0]) : 'does not exist'}`);
}

export function handleSummary(data) {
  return s.summary(data, 'Scenario 4.2 — building an index on a busy table', [
    'Postgres -> "Sessions waiting for a lock" (ShareLock vs RowExclusiveLock).',
  ]);
}
