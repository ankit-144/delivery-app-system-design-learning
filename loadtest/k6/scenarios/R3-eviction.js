// Redis deep dive R3 — Memory full: what does Redis throw away?
//
// Redis is capped at 64 MB and someone filled it with cache junk (no TTL). Then our app keeps going:
// a bot hammers the OTP endpoint (limit 5/min, sliding window in Redis) while customers browse products
// (Redis product cache) and order. What happens depends on maxmemory-policy:
//
//   make load ID=R3 MODE=broken       # allkeys-lru: Redis evicts the least recently used keys —
//                                     #   including the rate limiter's. The bot's limit resets.
//   make load ID=R3 MODE=volatile     # volatile-lru: evicts only keys WITH a TTL — every key our app writes
//                                     #   has one; the junk doesn't. Worst of both worlds.
//   make load ID=R3 MODE=noeviction   # refuse writes when full: rate limiter, cache writes fail loudly
//   make load ID=R3 MODE=fixed        # noeviction + enough memory (no junk): the right setup for this data
//
// Needs Phase 3.   Knobs: DURATION (60s).
import http from 'k6/http';
import { sleep } from 'k6';
import { Counter } from 'k6/metrics';
import { BASE_URL, CORE_URLS, applyMode, env, randomInt, resetFixtures } from '../lib/quickdrop.js';

const DURATION_S = parseInt(env('DURATION', '60s'), 10);
const otp = new Counter('otp_results');

export const options = {
  setupTimeout: '10m',
  scenarios: {
    bot: { executor: 'per-vu-iterations', exec: 'bot', vus: 1, iterations: 1, maxDuration: `${DURATION_S + 30}s` },
    products: { executor: 'constant-arrival-rate', exec: 'product', rate: 200, timeUnit: '1s', duration: `${DURATION_S}s`, preAllocatedVUs: 30, maxVUs: 200 },
  },
  thresholds: { 'otp_results{result:accepted}': ['count>=0'], 'otp_results{result:limited}': ['count>=0'], 'otp_results{result:error}': ['count>=0'] },
};

const POLICIES = { broken: 'allkeys-lru', volatile: 'volatile-lru', noeviction: 'noeviction', fixed: 'noeviction' };
const MODES = { broken: {}, volatile: {}, noeviction: {}, fixed: {} };

function config(name, value) {
  return http.post(`${CORE_URLS[0]}/admin/redis-lab/config?name=${name}&value=${value}`).body;
}

export function setup() {
  applyMode(MODES, {
    'rate-limiter': 'redis-sliding', 'rate-limit-otp-per-minute': '5', 'notification-mode': 'async-memory',
    'product-cache': 'redis', 'product-cache-ttl-s': '600',
  });
  resetFixtures({});
  http.post(`${CORE_URLS[0]}/admin/redis-lab/flush-junk`, null, { timeout: '5m' });
  config('maxmemory', '64mb');
  config('maxmemory-policy', POLICIES[__ENV.MODE || 'broken']);
  if (__ENV.MODE !== 'fixed') {
    // ~70 MB of junk without TTL: more than the cap, so Redis is at its limit before we start.
    console.log(`junk: ${http.post(`${CORE_URLS[0]}/admin/redis-lab/populate?count=450000`, null, { timeout: '5m' }).body}`);
  }
  console.log(`redis: ${http.get(`${CORE_URLS[0]}/admin/redis-lab/info`).body}`);
}

export function bot() {
  const end = Date.now() + DURATION_S * 1000;
  let accepted = 0;
  while (Date.now() < end) {
    const res = http.post(`${BASE_URL}/api/auth/otp`, JSON.stringify({ phone: '+919999000003' }),
      { headers: { 'Content-Type': 'application/json' }, tags: { name: 'POST /api/auth/otp' } });
    const result = res.status === 202 ? 'accepted' : res.status === 429 ? 'limited' : 'error';
    if (result === 'accepted') accepted++;
    otp.add(1, { result });
    sleep(0.05);
  }
  console.log(`bot: ${accepted} OTPs accepted in ${DURATION_S}s (limit: 5 per minute)`);
}

export function product() {
  // Many different products: lots of cache writes competing for memory.
  http.get(`${BASE_URL}/api/products/${randomInt(1, 500)}`, { tags: { name: 'GET /api/products/{id}' } });
}

export function teardown() {
  console.log(`redis after: ${http.get(`${CORE_URLS[0]}/admin/redis-lab/info`).body}`);
  http.post(`${CORE_URLS[0]}/admin/redis-lab/flush-junk`, null, { timeout: '5m' });
  config('maxmemory', '256mb');
  config('maxmemory-policy', 'noeviction');
}

export function handleSummary(data) {
  const c = (r) => (data.metrics[`otp_results{result:${r}}`] ? data.metrics[`otp_results{result:${r}}`].values.count : 0);
  return {
    stdout: ['', `================ Redis R3 — eviction (MODE=${__ENV.MODE || 'broken'}, policy ${POLICIES[__ENV.MODE || 'broken']}) ================`,
      `  bot OTP requests: ${c('accepted')} accepted, ${c('limited')} rate-limited, ${c('error')} errors   (limit 5 per minute)`,
      '  Redis & scale-out -> "Evicted and expired keys", "Rejected / failed commands", "Memory used vs maxmemory".', ''].join('\n'),
  };
}
