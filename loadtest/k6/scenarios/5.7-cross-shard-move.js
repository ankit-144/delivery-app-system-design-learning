// Scenario 5.7 — Moving an order between shards, with no transaction spanning both.
//
// Customers switch their order to a store in another city. With shard-key=city that moves the order to
// another shard: INSERT on the new shard, DELETE on the old one. 10% of moves crash in between.
//
//   make load ID=5.7 MODE=broken    # a crashed move is left half done: the order exists on both shards
//   make load ID=5.7 MODE=fixed     # both steps are idempotent; the client retries until the move completes
//
// Needs Phase 5.   Knobs: DURATION (30s).
import http from 'k6/http';
import { Counter } from 'k6/metrics';
import {
  BASE_URL, CORE_URLS, applyMode, env, placeShardedOrder, randomInt, resetFixtures, shardingReset,
} from '../lib/quickdrop.js';

const moves = new Counter('moves');

export const options = {
  setupTimeout: '10m',
  scenarios: { flow: { executor: 'constant-arrival-rate', rate: 20, timeUnit: '1s', duration: env('DURATION', '30s'), preAllocatedVUs: 20, maxVUs: 200 } },
  thresholds: { 'moves{result:done}': ['count>=0'], 'moves{result:crashed}': ['count>=0'], 'moves{result:retried}': ['count>=0'] },
};

const MODES = { broken: {}, fixed: {} };

export function setup() {
  applyMode(MODES, { 'shard-key': 'city', 'id-strategy': 'snowflake', 'cross-shard-crash-percent': '10' });
  resetFixtures({});
  shardingReset();
}

function move(id, store) {
  return http.post(`${BASE_URL}/api/sharded/orders/${id}/move?storeId=${store}`, null, { tags: { name: 'POST /api/sharded/orders/{id}/move' } });
}

export default function () {
  const placed = placeShardedOrder(3 * randomInt(1, 9999), randomInt(1, 2));   // Bengaluru store → shard 0
  if (placed.status !== 201) return;
  const id = placed.json('id');
  const target = randomInt(3, 4);                                              // a Mumbai store → shard 1
  let res = move(id, target);
  if (res.status === 200) {
    moves.add(1, { result: 'done' });
    return;
  }
  moves.add(1, { result: 'crashed' });
  if (__ENV.MODE === 'fixed') {
    for (let attempt = 0; attempt < 5 && res.status !== 200; attempt++) res = move(id, target);
    if (res.status === 200) moves.add(1, { result: 'retried' });
  }
}

export function teardown() {
  console.log(`after the run: ${http.get(`${CORE_URLS[0]}/admin/sharding/duplicates`).body}`);
}

export function handleSummary(data) {
  const c = (r) => (data.metrics[`moves{result:${r}}`] ? data.metrics[`moves{result:${r}}`].values.count : 0);
  return {
    stdout: ['', `================ Scenario 5.7 — cross-shard move (MODE=${__ENV.MODE || 'broken'}) ================`,
      `  moves: ${c('done')} completed first time, ${c('crashed')} crashed halfway, ${c('retried')} of those completed by a retry`, ''].join('\n'),
  };
}
