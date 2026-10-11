// Scenario 6.1 — A synchronous call chain: the vendor's latency becomes our latency.
//
// Notifications now live in notification-service. core calls it over HTTP and waits; it calls the vendor
// and waits. The vendor takes 2 s today.  core → notification-service → vendor
//
//   make load ID=6.1 MODE=broken    # notification-mode=http, notify-timeout-ms=0, delivery-mode=sync
//
// (6.2 adds the protections.) Needs Phase 6: make up PHASE=6.   Knobs: RATE (orders/s, 80), DURATION (45s).
//
// 80 orders/s over 3 core instances × 2 s each ≈ 53 request threads busy per instance: Tomcat has 50.
import { applyMode, env, envInt, resetFixtures, setNotify, setVendor } from '../lib/quickdrop.js';
import { stream, streams, SUMMARY_STATS } from '../lib/streams.js';
import { browse as browseReq, health as healthReq, orderInCity1 } from '../lib/traffic.js';

const DURATION = env('DURATION', '45s');
const s = streams(['order', 'browse', 'health']);

export const options = {
  setupTimeout: '10m',
  scenarios: {
    orders: stream('order', envInt('RATE', 80), DURATION, 800),
    browsing: stream('browse', 20, DURATION),
    probes: stream('health', 2, DURATION),
  },
  summaryTrendStats: SUMMARY_STATS,
};

const MODES = { broken: {} };

export function setup() {
  applyMode(MODES, { 'notification-mode': 'http', 'notify-timeout-ms': '0', 'inventory-reservation': 'atomic', 'rider-assignment': 'conditional' });
  resetFixtures({ restockAll: 100000 });
  setNotify({ 'delivery-mode': 'sync', 'vendor-timeout-ms': '10000' });
  setVendor({ 'latency-ms': '2000', 'jitter-ms': '50' });
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

export function handleSummary(data) {
  return s.summary(data, 'Scenario 6.1 — synchronous chain core → notification-service → vendor', [
    'Saturation -> core and notification-service busy threads; Kafka & notifications -> "core: notifications by mode".',
  ]);
}
