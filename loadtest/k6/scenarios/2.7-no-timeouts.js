// Scenario 2.7 — No timeouts.
//
// The SMS vendor's load balancer goes silent: connections are accepted, nothing ever comes back
// (Toxiproxy "timeout" toxic with timeout=0 — a network blackhole). Our HTTP client's read timeout
// is 0, which means "wait forever".
//
//   make load ID=2.7 MODE=broken    # vendor-timeout-ms=0: threads wait forever, the service dies
//   make load ID=2.7 MODE=fixed     # vendor-timeout-ms=500: SMS fail fast, orders keep working
//
// Knobs: RATE (orders/s, 20), DURATION (60s).
import { addToxic, applyMode, env, envInt, removeToxic, resetFixtures } from '../lib/quickdrop.js';
import { stream, streams, SUMMARY_STATS } from '../lib/streams.js';
import { browse as browseReq, health as healthReq, orderInCity1 } from '../lib/traffic.js';

const DURATION = env('DURATION', '60s');
const s = streams(['order', 'browse', 'health']);

export const options = {
  setupTimeout: '6m',
  scenarios: {
    orders: stream('order', envInt('RATE', 20), DURATION, 600),
    browsing: stream('browse', 20, DURATION),
    probes: stream('health', 2, DURATION),
  },
  summaryTrendStats: SUMMARY_STATS,
};

const COMMON = {
  'notification-mode': 'inline',
  'inventory-reservation': 'atomic',
  'rider-assignment': 'conditional',
};

const MODES = {
  broken: { 'vendor-timeout-ms': '0' },
  fixed: { 'vendor-timeout-ms': '500' },
};

export function setup() {
  applyMode(MODES, COMMON);
  resetFixtures({ restockAll: 100000 });
  addToxic('vendor', 'timeout', { timeout: 0 }, 'vendor-blackhole');
}

export function order() {
  const res = orderInCity1('30s');
  s.record('order', res, res.status === 201);
}

export function browse() {
  const res = browseReq('30s');
  s.record('browse', res, res.status === 200);
}

export function health() {
  const res = healthReq();
  s.record('health', res, res.status === 200);
}

export function teardown() {
  // Removing the toxic lets the stuck calls finish, so the service recovers on its own.
  removeToxic('vendor', 'vendor-blackhole');
}

export function handleSummary(data) {
  return s.summary(data, 'Scenario 2.7 — no timeouts', [
    'Saturation -> "Busy threads vs max" and "Vendor call latency"; Overview -> "SMS sent" (failed).',
  ]);
}
