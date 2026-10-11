// Scenario 5.4 — Ids that collide across shards.
//
// Each shard has its own sequence: shard 0 hands out 1, 2, 3... and so does shard 1. Customers place
// orders and then open the order page by id.
//
//   make load ID=5.4 MODE=broken    # id-strategy=sequence: "order 57" exists on both shards
//   make load ID=5.4 MODE=fixed     # Snowflake ids: unique everywhere, and the id says which shard to ask
//
// Needs Phase 5.   Knobs: DURATION (30s).
import http from 'k6/http';
import { Counter } from 'k6/metrics';
import {
  BASE_URL, CORE_URLS, applyMode, env, placeShardedOrder, randomInt, resetFixtures, shardingReset,
} from '../lib/quickdrop.js';

const lookups = new Counter('order_page_lookups');

export const options = {
  setupTimeout: '10m',
  scenarios: { flow: { executor: 'constant-arrival-rate', rate: 30, timeUnit: '1s', duration: env('DURATION', '30s'), preAllocatedVUs: 20, maxVUs: 200 } },
  thresholds: {
    'order_page_lookups{result:correct}': ['count>=0'],
    'order_page_lookups{result:ambiguous}': ['count>=0'],
  },
};

const MODES = { broken: { 'id-strategy': 'sequence' }, fixed: { 'id-strategy': 'snowflake' } };

export function setup() {
  applyMode(MODES, { 'shard-key': 'customer' });
  resetFixtures({});
  shardingReset();
}

export default function () {
  const customer = randomInt(1, 30000);
  const placed = placeShardedOrder(customer, randomInt(1, 6));
  if (placed.status !== 201) return;
  const order = placed.json();
  const res = http.get(`${BASE_URL}/api/sharded/orders/${order.id}`, { tags: { name: 'GET /api/sharded/orders/{id}' } });
  const matches = res.json('result');
  lookups.add(1, { result: matches.length === 1 && matches[0].customerId === customer ? 'correct' : 'ambiguous' });
}

export function teardown() {
  console.log(`ids present on more than one shard: ${http.get(`${CORE_URLS[0]}/admin/sharding/duplicates`).body}`);
}

export function handleSummary(data) {
  const c = (r) => (data.metrics[`order_page_lookups{result:${r}}`] ? data.metrics[`order_page_lookups{result:${r}}`].values.count : 0);
  return {
    stdout: ['', `================ Scenario 5.4 — global ids (MODE=${__ENV.MODE || 'broken'}) ================`,
      `  order page lookups: ${c('correct')} found exactly the right order, ${c('ambiguous')} found several orders with that id`, ''].join('\n'),
  };
}
