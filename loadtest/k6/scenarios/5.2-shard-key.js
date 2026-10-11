// Scenario 5.2 — Choosing a shard key: every choice has a losing query.
//
// Orders live on two shards. Customers order mostly in their home city; 10% order in another city
// (travel, family). Three kinds of traffic: placing orders, "My orders" (by customer) and a store's
// order screen (by store). Shard 1 is in a slower rack today: +50 ms per query (Toxiproxy).
//
//   make load ID=5.2 MODE=city        # shard by the store's city
//   make load ID=5.2 MODE=customer    # shard by a hash of the customer id
//
// Needs Phase 5.   Knobs: DURATION (45s).
import http from 'k6/http';
import { Trend, Counter } from 'k6/metrics';
import {
  BASE_URL, addToxic, applyMode, env, placeShardedOrder, randomInt, resetFixtures, shardingReset,
} from '../lib/quickdrop.js';

const DURATION = env('DURATION', '45s');
const latency = { mine: new Trend('my_orders_ms', true), store: new Trend('store_screen_ms', true) };
const shardsAsked = { mine: new Trend('my_orders_shards'), store: new Trend('store_screen_shards') };
const placed = new Counter('sharded_orders_placed');

export const options = {
  setupTimeout: '10m',
  scenarios: {
    writes: { executor: 'constant-arrival-rate', exec: 'write', rate: 40, timeUnit: '1s', duration: DURATION, preAllocatedVUs: 20, maxVUs: 200 },
    my_orders: { executor: 'constant-arrival-rate', exec: 'myOrders', rate: 30, timeUnit: '1s', duration: DURATION, startTime: '5s', preAllocatedVUs: 20, maxVUs: 200 },
    store_screen: { executor: 'constant-arrival-rate', exec: 'storeScreen', rate: 10, timeUnit: '1s', duration: DURATION, startTime: '5s', preAllocatedVUs: 10, maxVUs: 100 },
  },
  summaryTrendStats: ['avg', 'p(50)', 'p(95)', 'max'],
};

const MODES = { city: { 'shard-key': 'city' }, customer: { 'shard-key': 'customer' } };
MODES.broken = MODES.city;

export function setup() {
  applyMode(MODES, { 'id-strategy': 'snowflake' });
  resetFixtures({});
  shardingReset();
  addToxic('shard1', 'latency', { latency: 50, jitter: 0 });
}

function customerAndStore() {
  const customer = randomInt(1, 30000);
  const home = 1 + (customer % 3);
  const city = Math.random() < 0.9 ? home : 1 + ((home + randomInt(0, 1)) % 3);
  return { customer, store: 2 * (city - 1) + randomInt(1, 2) };
}

export function write() {
  const { customer, store } = customerAndStore();
  if (placeShardedOrder(customer, store).status === 201) placed.add(1);
}

export function myOrders() {
  const res = http.get(`${BASE_URL}/api/sharded/customers/${randomInt(1, 30000)}/orders`, { tags: { name: 'GET /api/sharded/customers/{id}/orders' } });
  if (res.status === 200) {
    latency.mine.add(res.timings.duration);
    shardsAsked.mine.add(res.json('shardsQueried'));
  }
}

export function storeScreen() {
  const res = http.get(`${BASE_URL}/api/sharded/stores/${randomInt(1, 6)}/orders`, { tags: { name: 'GET /api/sharded/stores/{id}/orders' } });
  if (res.status === 200) {
    latency.store.add(res.timings.duration);
    shardsAsked.store.add(res.json('shardsQueried'));
  }
}

export function handleSummary(data) {
  if (!data.metrics.my_orders_ms || !data.metrics.store_screen_ms) {
    return { stdout: '\nScenario 5.2: no data — did setup fail? (Phase 5 must be running: make up PHASE=5)\n' };
  }
  const v = (m) => data.metrics[m].values;
  const row = (label, lat, shards) => `  ${label.padEnd(18)} shards asked per request: ${v(shards).avg.toFixed(2)}   p50 ${v(lat)['p(50)'].toFixed(0)} ms   p95 ${v(lat)['p(95)'].toFixed(0)} ms`;
  return {
    stdout: ['', `================ Scenario 5.2 — shard key = ${__ENV.MODE || 'city'} ================`,
      `  orders placed: ${data.metrics.sharded_orders_placed.values.count} (shard 1 is 50 ms slower than shard 0)`,
      row('"My orders"', 'my_orders_ms', 'my_orders_shards'),
      row('store screen', 'store_screen_ms', 'store_screen_shards'), ''].join('\n'),
  };
}
