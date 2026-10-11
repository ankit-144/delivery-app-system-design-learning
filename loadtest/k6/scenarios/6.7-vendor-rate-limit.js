// Scenario 6.7 — The vendor allows 100 SMS per second. We have 6 consumers on 2 instances.
//
// A 20,000-promo blast is queued in Kafka; notification-service drains it as fast as it can.
//
//   make load ID=6.7 MODE=broken    # no throttling: the vendor answers 429 to most sends — those SMS are lost
//   make load ID=6.7 MODE=local     # 90/s per instance: 2 instances = 180/s, still over the limit
//   make load ID=6.7 MODE=fixed     # one 90/s token bucket in Redis shared by both instances: no 429s, lag instead
//
// Needs Phase 6.   Knobs: DURATION (60s).
import http from 'k6/http';
import { sleep } from 'k6';
import { CORE_URLS, VENDOR_URL, applyMode, env, promValue, resetFixtures, setNotify, setVendor, waitForScrape , flushNotificationBacklog } from '../lib/quickdrop.js';

export const options = {
  setupTimeout: '10m',
  scenarios: { observe: { executor: 'shared-iterations', exec: 'observe', vus: 1, iterations: 1, maxDuration: '5m' } },
};

const LIMIT = { broken: 'off', local: 'local', fixed: 'redis' };
const MODES = { broken: {}, local: {}, fixed: {} };

export function setup() {
  applyMode(MODES, { 'notification-mode': 'kafka-direct' });
  flushNotificationBacklog();
  resetFixtures({});
  setNotify({ 'delivery-mode': 'sync', 'vendor-rate-limit': LIMIT[__ENV.MODE || 'broken'], 'vendor-rate-limit-rps': '90' });
  setVendor({ 'latency-ms': '10', 'jitter-ms': '0', 'rate-limit-rps': '100' });
}

export function observe() {
  http.post(`${CORE_URLS[0]}/api/marketing/blast?count=20000&rate=5000`);
  sleep(parseInt(env('DURATION', '60s'), 10));
}

export function teardown() {
  waitForScrape();
  const stats = http.get(`${VENDOR_URL}/v1/stats`).json();
  console.log(`vendor: ${stats.accepted} accepted, ${stats.rejected} rejected with 429 (lost SMS)`);
  console.log(`sends per second at the vendor (peak): ${Math.round(promValue('max_over_time(sum(rate(vendor_messages_total{result="accepted"}[10s]))[70s:5s])') || 0)}`);
  console.log(`Kafka lag now: ${Math.round(promValue('max(notify_consumer_lag{topic="notifications"})') || 0)}`);
  setVendor({ 'rate-limit-rps': '0' });
}
