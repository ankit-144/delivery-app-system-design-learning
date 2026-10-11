// Scenario 6.8 — An OTP stuck behind a promo blast.
//
// A 40,000-promo blast lands in Kafka; the vendor is slowish (50 ms), so the backlog takes minutes to drain.
// Meanwhile people try to log in: 2 OTPs per second. Nobody waits 3 minutes for a login code.
//
//   make load ID=6.8 MODE=broken    # otp-topic=shared: OTPs queue behind the promos in the same partitions
//   make load ID=6.8 MODE=fixed     # otp-topic=priority: own topic, own consumer group, own lag
//
// Needs Phase 6.   Knobs: DURATION (60s).
import http from 'k6/http';
import {
  BASE_URL, CORE_URLS, applyMode, env, promValue, randomInt, resetFixtures, setNotify, setVendor, waitForScrape, flushNotificationBacklog } from '../lib/quickdrop.js';

const DURATION = env('DURATION', '60s');

export const options = {
  setupTimeout: '10m',
  scenarios: {
    blast: { executor: 'shared-iterations', exec: 'blast', vus: 1, iterations: 1 },
    logins: { executor: 'constant-arrival-rate', exec: 'login', rate: 2, timeUnit: '1s', duration: DURATION, startTime: '5s', preAllocatedVUs: 5, maxVUs: 20 },
  },
};

const MODES = { broken: { 'otp-topic': 'shared' }, fixed: { 'otp-topic': 'priority' } };

export function setup() {
  applyMode(MODES, { 'notification-mode': 'kafka-direct' });
  flushNotificationBacklog();
  resetFixtures({});
  setNotify({ 'delivery-mode': 'sync' });
  setVendor({ 'latency-ms': '50', 'jitter-ms': '5' });
}

export function blast() {
  http.post(`${CORE_URLS[0]}/api/marketing/blast?count=40000&rate=5000`);
}

export function login() {
  http.post(`${BASE_URL}/api/auth/otp`, JSON.stringify({ phone: `+9190${randomInt(10000000, 99999999)}` }),
    { headers: { 'Content-Type': 'application/json' }, tags: { name: 'POST /api/auth/otp' } });
}

export function teardown() {
  waitForScrape();
  const window = `${parseInt(DURATION, 10) + 10}s`;
  const q = (p) => promValue(`histogram_quantile(${p}, sum by (le) (rate(notify_delivery_delay_seconds_bucket{type="OTP"}[${window}])))`);
  const delivered = promValue(`sum(increase(notify_delivered_total{type="OTP", result="sent"}[${window}]))`) || 0;
  console.log(`OTPs requested: ~${2 * parseInt(DURATION, 10)}, delivered during the run: ${Math.round(delivered)}`);
  console.log(`OTP delivery delay: p50 ${(q(0.5) || 0).toFixed(2)} s, p95 ${(q(0.95) || 0).toFixed(2)} s`);
  console.log(`promo backlog still in Kafka: ${Math.round(promValue('max(notify_consumer_lag{topic="notifications"})') || 0)}`);
}
