// Scenario 2.1 — A slow vendor eats every request thread.
//
// The SMS vendor has a bad day: every send takes 1 s. Orders send their SMS inline, on the request
// thread. 60 orders/s × 1 s = 60 threads busy waiting, and Tomcat only has 50.
//
//   make load ID=2.1 MODE=broken     # inline SMS: threads exhausted, even health checks queue
//   make load ID=2.1 MODE=timeout    # inline SMS with a 300 ms timeout: fast, but SMS are lost
//   make load ID=2.1 MODE=async      # SMS handed to an in-memory queue: fast... watch the queue
//
// Knobs: RATE (orders/s, 60), DURATION (60s).
import { applyMode, env, envInt, resetFixtures, setVendor } from '../lib/quickdrop.js';
import { stream, streams, SUMMARY_STATS } from '../lib/streams.js';
import { browse as browseReq, health as healthReq, orderInCity1 } from '../lib/traffic.js';

const DURATION = env('DURATION', '60s');
const s = streams(['order', 'browse', 'health']);

export const options = {
  setupTimeout: '6m', // setup may wait for the previous run's backlog to drain
  scenarios: {
    orders: stream('order', envInt('RATE', 60), DURATION, 600),
    browsing: stream('browse', 20, DURATION),
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
  broken: { 'notification-mode': 'inline' },
  timeout: { 'notification-mode': 'inline', 'vendor-timeout-ms': '300' },
  async: { 'notification-mode': 'async-memory' },
};
MODES.fixed = MODES.async;

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
  return s.summary(data, 'Scenario 2.1 — slow vendor, inline SMS', [
    'Saturation -> "Busy threads vs max" shows the thread pool; Overview -> "SMS sent" shows failed/queued.',
  ]);
}
