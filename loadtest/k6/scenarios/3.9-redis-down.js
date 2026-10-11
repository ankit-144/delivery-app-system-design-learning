// Scenario 3.9 — Redis goes away.
//
// Everything Redis-backed is on: Redis stock lock, sliding-window rate limits, Redis product cache,
// Redis GEO rider matching, cached best sellers. 15 s in, Redis stops answering (a Toxiproxy black hole)
// for 30 s, then comes back.
//
//   make load ID=3.9 MODE=broken    # redis-failure-mode=fail: every Redis-backed feature fails with it
//   make load ID=3.9 MODE=degrade   # fallbacks: DB lock instead of Redis lock, DB queries instead of cache/GEO,
//                                   #   orders fail OPEN on the rate limit, OTPs fail CLOSED — but every call
//                                   #   still waits for Redis to time out (1 s) before falling back
//   make load ID=3.9 MODE=breaker   # degrade + circuit breaker: after a few failures, stop calling Redis for 5 s —
//                                   #   now every fallback runs instantly... including the expensive one
//   make load ID=3.9 MODE=fixed     # breaker + cheap fallbacks: best sellers served from the last copy, not recomputed
//
// Needs Phase 3.   Knobs: DURATION (60s).
import http from 'k6/http';
import { sleep } from 'k6';
import {
  BASE_URL, CORE_URLS, addToxic, applyMode, env, randomInt, removeToxic, resetFixtures,
} from '../lib/quickdrop.js';
import { stream, streams, SUMMARY_STATS } from '../lib/streams.js';
import { orderInCity1 } from '../lib/traffic.js';

const DURATION = env('DURATION', '60s');
const s = streams(['order', 'otp', 'product', 'bestsellers']);

export const options = {
  setupTimeout: '10m',
  scenarios: {
    orders: stream('order', 20, DURATION),
    otps: stream('otp', 5, DURATION),
    products: stream('product', 30, DURATION),
    bestsellers: stream('bestseller', 10, DURATION),
    outage: { executor: 'shared-iterations', exec: 'outage', vus: 1, iterations: 1, startTime: '15s', maxDuration: '60s' },
  },
  summaryTrendStats: SUMMARY_STATS,
};

const COMMON = {
  'notification-mode': 'none',
  'inventory-reservation': 'redis-lock',
  'rider-assignment': 'conditional',
  'rider-locator': 'redis-geo',
  'rate-limiter': 'redis-sliding',
  'rate-limit-orders-per-minute': '1000',
  'rate-limit-otp-per-minute': '1000',
  'product-cache': 'redis',
  'bestsellers-cache': 'single-flight',
};

const MODES = {
  broken: { 'redis-failure-mode': 'fail', 'redis-circuit-breaker': 'off' },
  degrade: { 'redis-failure-mode': 'degrade', 'redis-circuit-breaker': 'off', 'bestsellers-fallback': 'compute' },
  breaker: { 'redis-failure-mode': 'degrade', 'redis-circuit-breaker': 'on', 'bestsellers-fallback': 'compute' },
  fixed: { 'redis-failure-mode': 'degrade', 'redis-circuit-breaker': 'on', 'bestsellers-fallback': 'last-known' },
};

export function setup() {
  applyMode(MODES, COMMON);
  resetFixtures({ restockAll: 100000 });
  http.post(`${CORE_URLS[0]}/admin/riders/geo-sync`);
}

export function outage() {
  addToxic('redis', 'timeout', { timeout: 0 }, 'redis-blackhole');
  console.log('Redis is now a black hole (accepts connections, never answers)');
  sleep(30);
  removeToxic('redis', 'redis-blackhole');
  console.log('Redis is back');
}

export function order() {
  const res = orderInCity1('30s');
  s.record('order', res, res.status === 201);
}

export function otp() {
  const res = http.post(`${BASE_URL}/api/auth/otp`, JSON.stringify({ phone: `+9190${randomInt(10000000, 99999999)}` }),
    { headers: { 'Content-Type': 'application/json' }, timeout: '30s', tags: { name: 'POST /api/auth/otp' } });
  s.record('otp', res, res.status === 202);
}

export function product() {
  const res = http.get(`${BASE_URL}/api/products/${randomInt(1, 500)}`, { timeout: '30s', tags: { name: 'GET /api/products/{id}' } });
  s.record('product', res, res.status === 200);
}

export function bestseller() {
  const res = http.get(`${BASE_URL}/api/stores/${randomInt(1, 6)}/bestsellers`, { timeout: '30s', tags: { name: 'GET /api/stores/{id}/bestsellers' } });
  s.record('bestsellers', res, res.status === 200);
}

export function teardown() {
  removeToxic('redis', 'redis-blackhole');
}

export function handleSummary(data) {
  return s.summary(data, 'Scenario 3.9 — Redis outage (30 s of the run)', [
    'Overview -> API errors (redis_unavailable); Redis & scale-out -> lock fallbacks, rate limiter "redis_down_*".',
  ]);
}
