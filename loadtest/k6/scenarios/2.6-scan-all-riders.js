// Scenario 2.6 — Rider matching that loads every rider.
//
// To find the nearest free rider, the first version loads every rider in the city as JPA entities and
// filters and sorts them in Java — on every order. Fine in a demo with 50 riders. This scenario gives
// Bengaluru a realistic fleet of 20,000.
//
//   make load ID=2.6 MODE=broken    # rider-locator=db-scan: CPU-bound app
//   make load ID=2.6 MODE=fixed     # rider-locator=db-nearby: bounding-box query, 10 rows back
//
// Knobs: RATE (orders/s, 150), DURATION (60s), FLEET (riders in the city, 20000).
import { applyMode, env, envInt, resetFixtures } from '../lib/quickdrop.js';
import { stream, streams, SUMMARY_STATS } from '../lib/streams.js';
import { browse as browseReq, health as healthReq, orderInCity1 } from '../lib/traffic.js';

const DURATION = env('DURATION', '60s');
const s = streams(['order', 'browse', 'health']);

export const options = {
  setupTimeout: '6m',
  scenarios: {
    orders: stream('order', envInt('RATE', 150), DURATION, 600),
    browsing: stream('browse', 20, DURATION),
    probes: stream('health', 2, DURATION),
  },
  summaryTrendStats: SUMMARY_STATS,
};

const COMMON = {
  'notification-mode': 'none',
  'inventory-reservation': 'atomic',
  'rider-assignment': 'conditional',
  'auto-deliver-after-ms': '5000',   // riders come back quickly so most orders get one
};

const MODES = {
  broken: { 'rider-locator': 'db-scan' },
  fixed: { 'rider-locator': 'db-nearby' },
};

export function setup() {
  applyMode(MODES, COMMON);
  resetFixtures({ restockAll: 100000, riderFleet: { cityId: 1, size: envInt('FLEET', 20000) } });
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
  return s.summary(data, 'Scenario 2.6 — matching scans every rider', [
    'Saturation -> "Container CPU (cores) vs limit" and "GC time per second"; Containers -> core-1 CPU.',
  ]);
}
