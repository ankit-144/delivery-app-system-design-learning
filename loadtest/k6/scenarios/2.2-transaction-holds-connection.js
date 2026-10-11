// Scenario 2.2 — Calling the vendor inside the transaction drains the connection pool.
//
// Same 1 s vendor. This time the SMS is sent *before* commit (notification-mode=inline-in-tx), so
// every order keeps a database connection — and its row locks — for the whole second. The pool has
// 10 connections: at most 10 orders per second, and browsing (which also needs a connection) starves.
//
//   make load ID=2.2 MODE=broken    # vendor call inside the transaction
//   make load ID=2.2 MODE=fixed     # vendor call after commit
//
// Knobs: RATE (orders/s, 15), DURATION (60s).
import { applyMode, env, envInt, resetFixtures, setVendor } from '../lib/quickdrop.js';
import { stream, streams, SUMMARY_STATS } from '../lib/streams.js';
import { browse as browseReq, health as healthReq, orderInCity1 } from '../lib/traffic.js';

const DURATION = env('DURATION', '60s');
const s = streams(['order', 'browse', 'health']);

export const options = {
  setupTimeout: '6m', // setup may wait for the previous run's backlog to drain
  scenarios: {
    orders: stream('order', envInt('RATE', 15), DURATION, 600),
    browsing: stream('browse', 40, DURATION, 600),
    probes: stream('health', 2, DURATION),
  },
  summaryTrendStats: SUMMARY_STATS,
};

const COMMON = {
  'inventory-reservation': 'atomic',
  'inventory-lock-order': 'sorted',
  'rider-assignment': 'conditional',
  'order-idempotency': 'off',
};

const MODES = {
  broken: { 'notification-mode': 'inline-in-tx' },
  fixed: { 'notification-mode': 'inline' },
};

export function setup() {
  applyMode(MODES, COMMON);
  resetFixtures({ restockAll: 100000 });
  setVendor({ 'latency-ms': '1000', 'jitter-ms': '50' });
}

export function order() {
  const res = orderInCity1();
  s.record('order', res, res.status === 201);
}

export function browse() {
  const res = browseReq();
  s.record('browse', res, res.status === 200);
}

export function health() {
  const res = healthReq();
  s.record('health', res, res.status === 200);
}

export function handleSummary(data) {
  return s.summary(data, 'Scenario 2.2 — vendor call inside the transaction', [
    'Saturation -> "Connections: active / idle / waiting"; Postgres -> "Connections by state" (idle in transaction).',
  ]);
}
