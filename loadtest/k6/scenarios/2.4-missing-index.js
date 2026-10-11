// Scenario 2.4 — A missing index.
//
// "My orders" filters 1.4M orders by customer_id. Without an index Postgres reads the whole table
// for every request: ~60 ms of CPU each, so two database CPUs top out around 30 requests/s.
//
//   make load ID=2.4 MODE=broken    # no index on orders(customer_id): sequential scans
//   make load ID=2.4 MODE=fixed     # CREATE INDEX CONCURRENTLY orders (customer_id, created_at DESC)
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
};

const MODES = { broken: {}, fixed: {} };

export function setup() {
  applyMode(MODES, COMMON);
  ensureHistory();
  customerIndex(__ENV.MODE === 'fixed' ? 'create' : 'drop');
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
  return s.summary(data, 'Scenario 2.4 — missing index', [
    'Postgres -> "Rows read by sequential scans" and "Top queries by total time"; Containers -> postgres CPU.',
  ]);
}
