// Redis deep dive R2 — Big keys.
//
// Someone stores "last order per customer" in ONE hash with a million fields. Reading it all
// (HGETALL) or deleting it (DEL) is a single command — and Redis runs one command at a time.
//
//   make load ID=R2 MODE=broken    # HGETALL the whole hash, then DEL it
//   make load ID=R2 MODE=fixed     # never read it whole; UNLINK frees memory in a background thread
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
    big_key: { executor: 'shared-iterations', exec: 'bigKey', vus: 1, iterations: 1, startTime: '10s', maxDuration: '60s' },
  },
  summaryTrendStats: SUMMARY_STATS,
};

const MODES = { broken: {}, fixed: {} };

export function setup() {
  applyMode(MODES, { 'product-cache': 'redis', 'product-cache-ttl-s': '3600' });
  resetFixtures({});
  console.log(`big hash: ${http.post(`${CORE_URLS[0]}/admin/redis-lab/big-hash?fields=1000000`, null, { timeout: '5m' }).body}`);
  console.log(`redis: ${http.get(`${CORE_URLS[0]}/admin/redis-lab/info`).body}`);
}

export function product() {
  const res = http.get(`${BASE_URL}/api/products/${randomInt(1, 500)}`, { timeout: '30s', tags: { name: 'GET /api/products/{id}' } });
  s.record('product', res, res.status === 200);
}

export function bigKey() {
  if (__ENV.MODE === 'fixed') {
    sleep(10);
    console.log(`delete: ${http.post(`${CORE_URLS[0]}/admin/redis-lab/big-hash/delete?how=unlink`, null, { timeout: '60s' }).body}`);
    return;
  }
  console.log(`read:   ${http.post(`${CORE_URLS[0]}/admin/redis-lab/big-hash/read`, null, { timeout: '60s' }).body}`);
  sleep(10);
  console.log(`delete: ${http.post(`${CORE_URLS[0]}/admin/redis-lab/big-hash/delete?how=del`, null, { timeout: '60s' }).body}`);
}

export function teardown() {
  http.post(`${CORE_URLS[0]}/admin/redis-lab/flush-junk`, null, { timeout: '5m' });
}

export function handleSummary(data) {
  return s.summary(data, 'Redis R2 — big keys', [
    'Find big keys yourself: docker exec qd-redis redis-cli --bigkeys   and   redis-cli MEMORY USAGE lab:big-hash',
  ]);
}
