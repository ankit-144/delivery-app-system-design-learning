// Redis deep dive R1 — One KEYS command blocks every client.
//
// Redis executes commands one at a time on a single thread. Usually each takes microseconds, so that
// is a feature: no locks, every command atomic. But a command that walks the whole keyspace makes every
// other client wait until it's done.
//
// Setup puts 1,000,000 junk keys in Redis. Background traffic reads products through the Redis cache.
// Every 10 s, an admin "debug" endpoint looks for some keys:
//
//   make load ID=R1 MODE=broken    # KEYS pattern*   (one call, walks every key)
//   make load ID=R1 MODE=fixed     # SCAN … COUNT 1000 (many small calls, others run in between)
//
// Needs Phase 3.   Knobs: DURATION (45s).
import http from 'k6/http';
import { sleep } from 'k6';
import { BASE_URL, CORE_URLS, applyMode, env, randomInt, resetFixtures } from '../lib/quickdrop.js';
import { stream, streams, SUMMARY_STATS } from '../lib/streams.js';

const DURATION = env('DURATION', '45s');
const s = streams(['product']);

export const options = {
  setupTimeout: '10m',
  scenarios: {
    products: stream('product', 300, DURATION, 400),
    debug: { executor: 'shared-iterations', exec: 'debug', vus: 1, iterations: 1, startTime: '10s', maxDuration: '60s' },
  },
  summaryTrendStats: SUMMARY_STATS,
};

const MODES = { broken: {}, fixed: {} };

export function setup() {
  applyMode(MODES, { 'product-cache': 'redis', 'product-cache-ttl-s': '3600' });
  resetFixtures({});
  const populated = http.post(`${CORE_URLS[0]}/admin/redis-lab/populate?count=1000000`, null, { timeout: '5m' });
  console.log(`populate: ${populated.body}`);
  console.log(`redis: ${http.get(`${CORE_URLS[0]}/admin/redis-lab/info`).body}`);
}

export function product() {
  const res = http.get(`${BASE_URL}/api/products/${randomInt(1, 500)}`, { timeout: '30s', tags: { name: 'GET /api/products/{id}' } });
  s.record('product', res, res.status === 200);
}

export function debug() {
  const command = __ENV.MODE === 'fixed' ? 'scan' : 'keys';
  for (let i = 0; i < 3; i++) {
    const res = http.post(`${CORE_URLS[0]}/admin/redis-lab/${command}?pattern=customer-session:*`, null, { timeout: '60s' });
    console.log(`debug endpoint: ${res.body}`);
    sleep(10);
  }
}

export function teardown() {
  console.log(`cleanup: ${http.post(`${CORE_URLS[0]}/admin/redis-lab/flush-junk`, null, { timeout: '5m' }).body}`);
}

export function handleSummary(data) {
  return s.summary(data, 'Redis R1 — KEYS blocks everyone', [
    'Redis & scale-out -> "Average time per command" (keys) and Overview -> p99 for GET /api/products/{id}.',
    'Also: docker exec qd-redis redis-cli SLOWLOG GET 5',
  ]);
}
