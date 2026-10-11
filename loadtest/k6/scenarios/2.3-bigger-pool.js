// Scenario 2.3 — "Just make the connection pool bigger."
//
// "My orders" is slow (it's the missing index from 2.4, but nobody has looked yet). Requests wait
// for database connections, so someone raises the pool size. Does it help?
//
//   make load ID=2.3 MODE=pool10     # the default pool
//   make load ID=2.3 MODE=pool40     # 4x the connections
//   make load ID=2.3 MODE=pool120    # more than Postgres allows (max_connections = 100)
//
// There is no MODE=fixed: the real fix is the index in scenario 2.4.
//
// Knobs: RATE (history requests/s, 40), DURATION (60s).
import { applyMode, customerIndex, ensureHistory, env, envInt, resetFixtures } from '../lib/quickdrop.js';
import { stream, streams, SUMMARY_STATS } from '../lib/streams.js';
import { health as healthReq, history as historyReq, orderInCity1 } from '../lib/traffic.js';

const DURATION = env('DURATION', '60s');
const s = streams(['history', 'order', 'health']);

export const options = {
  setupTimeout: '10m',
  scenarios: {
    my_orders: stream('history', envInt('RATE', 40), DURATION, 600),
    orders: stream('order', 10, DURATION),
    probes: stream('health', 2, DURATION),
  },
  summaryTrendStats: SUMMARY_STATS,
};

const COMMON = {
  'notification-mode': 'none',
  'inventory-reservation': 'atomic',
  'rider-assignment': 'conditional',
  'order-history-query': 'two-step',
  'tomcat-max-threads': '200',   // so the thread pool is not the first limit we hit
};

const MODES = {
  pool10: { 'db-pool-size': '10' },
  pool40: { 'db-pool-size': '40' },
  pool120: { 'db-pool-size': '120' },
};
MODES.broken = MODES.pool40;

export function setup() {
  applyMode(MODES, COMMON);
  ensureHistory();
  customerIndex('drop');
  resetFixtures({ restockAll: 100000 });
}

export function history() {
  const res = historyReq();
  s.record('history', res, res.status === 200);
}

export function order() {
  const res = orderInCity1();
  s.record('order', res, res.status === 201);
}

export function health() {
  const res = healthReq();
  s.record('health', res, res.status === 200);
}

export function handleSummary(data) {
  return s.summary(data, 'Scenario 2.3 — bigger connection pool', [
    'Compare: Postgres CPU, "Mean time per call", history throughput, and Saturation -> pool waiting.',
  ]);
}
