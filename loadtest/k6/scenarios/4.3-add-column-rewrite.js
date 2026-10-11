// Scenario 4.3 — Which ALTER TABLE rewrites the whole table?
//
//   make load ID=4.3 MODE=fixed      # ADD COLUMN source TEXT NOT NULL DEFAULT 'app'          constant default: instant
//   make load ID=4.3 MODE=broken     # ADD COLUMN public_ref UUID NOT NULL DEFAULT gen_random_uuid()   volatile: rewrite
//   make load ID=4.3 MODE=widen      # ALTER COLUMN quantity TYPE BIGINT on order_items        type change: rewrite
//
// A rewrite copies every row into a new file while holding ACCESS EXCLUSIVE: nobody can read or write
// the table until it's done.   Works in any phase.   Knobs: DURATION (45s).
import { sleep } from 'k6';
import {
  applyMode, customerIndex, dbLab, dbLabJobs, ensureHistory, env, resetFixtures,
} from '../lib/quickdrop.js';
import { stream, streams, SUMMARY_STATS } from '../lib/streams.js';
import { history as historyReq, orderInCity1 } from '../lib/traffic.js';

const DURATION = env('DURATION', '45s');
const s = streams(['order', 'history']);

export const options = {
  setupTimeout: '10m',
  scenarios: {
    orders: stream('order', 20, DURATION),
    my_orders: stream('history', 20, DURATION),
    migration: { executor: 'shared-iterations', exec: 'migration', vus: 1, iterations: 1, startTime: '10s' },
  },
  summaryTrendStats: SUMMARY_STATS,
};

const MODES = { broken: {}, fixed: {}, widen: {} };
const DDL = { broken: 'add-column-volatile-default', fixed: 'add-column-constant-default', widen: 'widen-column' };

export function setup() {
  applyMode(MODES, { 'notification-mode': 'none', 'inventory-reservation': 'atomic', 'rider-assignment': 'conditional', 'order-history-query': 'two-step' });
  ensureHistory();
  customerIndex('create');
  dbLab('reset');
  resetFixtures({ restockAll: 100000 });
}

export function migration() {
  console.log(`migration: ${JSON.stringify(dbLab('ddl', { name: DDL[__ENV.MODE || 'broken'] }))}`);
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
}

export function handleSummary(data) {
  return s.summary(data, `Scenario 4.3 — ${DDL[__ENV.MODE || 'broken']}`, [
    'Postgres -> "Sessions waiting for a lock" (AccessExclusiveLock); look at the max column.',
  ]);
}
