// Scenario 5.5 — One city, one hot shard.
//
// 80% of orders come from Bengaluru, 10% each from Mumbai and Delhi. With shard-key=city and two
// shards, Bengaluru and Delhi share shard 0.
//
//   make load ID=5.5 MODE=broken      # shard by city: shard 0 takes 90% of the writes
//   make load ID=5.5 MODE=fixed       # shard by customer hash: an even split
//
// Needs Phase 5.   Knobs: RATE (writes/s, 400), DURATION (45s).
import http from 'k6/http';
import {
  CORE_URLS, applyMode, env, envInt, placeShardedOrder, promValue, randomInt, resetFixtures, shardingReset, waitForScrape,
} from '../lib/quickdrop.js';

export const options = {
  setupTimeout: '10m',
  scenarios: { writes: { executor: 'constant-arrival-rate', rate: envInt('RATE', 400), timeUnit: '1s', duration: env('DURATION', '45s'), preAllocatedVUs: 50, maxVUs: 400 } },
  summaryTrendStats: ['avg', 'p(50)', 'p(95)', 'p(99)', 'max'],
};

const MODES = { broken: { 'shard-key': 'city' }, fixed: { 'shard-key': 'customer' } };

export function setup() {
  applyMode(MODES, { 'id-strategy': 'snowflake' });
  resetFixtures({});
  shardingReset();
}

export default function () {
  const r = Math.random();
  const city = r < 0.8 ? 1 : r < 0.9 ? 2 : 3;
  placeShardedOrder(3 * randomInt(1, 9999) + (city - 1), 2 * (city - 1) + randomInt(1, 2));
}

export function teardown() {
  waitForScrape();
  const status = http.get(`${CORE_URLS[0]}/admin/sharding/status`).json();
  console.log(`rows per shard: ${status.map((s) => `shard ${s.shard}: ${s.rows}`).join(', ')}`);
  for (const shard of [0, 1]) {
    const qps = promValue(`sum(rate(quickdrop_shard_queries_total{shard="${shard}", kind="insert"}[40s]))`) || 0;
    console.log(`shard ${shard}: ${qps.toFixed(0)} writes/s`);
  }
}
