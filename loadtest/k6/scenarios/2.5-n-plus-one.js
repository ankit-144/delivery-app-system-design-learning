// Scenario 2.5 — N+1 queries.
//
// The index from 2.4 is in place, so each query is fast. But the naive history loads 20 orders, then
// lazily loads each order's items (20 more queries), then each item's product (dozens more).
//
//   make load ID=2.5 MODE=broken    # lazy loading: ~50-80 SQL statements per request
//   make load ID=2.5 MODE=fixed     # two queries: one page of ids, one fetch join
//
// Knobs: RATE (history requests/s, 60), DURATION (60s),
//        DB_LATENCY (ms added to every Postgres response by Toxiproxy, default 1 — a database in another
//        availability zone; set 0 to see how well a same-machine database hides N+1).
import {
  addToxic, applyMode, customerIndex, ensureHistory, env, envInt, resetFixtures,
} from '../lib/quickdrop.js';
import { stream, streams, SUMMARY_STATS } from '../lib/streams.js';
import { health as healthReq, history as historyReq } from '../lib/traffic.js';

const DURATION = env('DURATION', '60s');
const s = streams(['history', 'health']);

export const options = {
  setupTimeout: '10m',
  scenarios: {
    my_orders: stream('history', envInt('RATE', 60), DURATION, 600),
    probes: stream('health', 2, DURATION),
  },
  summaryTrendStats: SUMMARY_STATS,
};

const COMMON = { 'notification-mode': 'none' };

const MODES = {
  broken: { 'order-history-query': 'naive' },
  fixed: { 'order-history-query': 'two-step' },
};

export function setup() {
  applyMode(MODES, COMMON);
  ensureHistory();
  customerIndex('create');
  resetFixtures({});
  const latency = envInt('DB_LATENCY', 1);
  if (latency > 0) addToxic('postgres', 'latency', { latency, jitter: 0 });
}

export function teardown() {
  // The next scenario's setup clears toxics anyway; leaving them would confuse manual exploring.
  addToxic('postgres', 'latency', { latency: 0, jitter: 0 });
}

export function history() {
  const res = historyReq();
  s.record('history', res, res.status === 200);
}

export function health() {
  const res = healthReq();
  s.record('health', res, res.status === 200);
}

export function handleSummary(data) {
  return s.summary(data, 'Scenario 2.5 — N+1 queries', [
    'Saturation -> "JPA statements per request"; Postgres -> "Top queries by calls".',
  ]);
}
