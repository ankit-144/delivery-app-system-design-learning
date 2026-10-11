// Scenario 5.1 — "I placed an order, but it's not in my orders."
//
// "My orders" reads move to a streaming read replica to take load off the primary. The replica is
// 1.5 s behind (recovery_min_apply_delay — real lag comes from load, long queries on the replica or the
// network; the effect is the same). Customers place an order and immediately open "My orders".
//
//   make load ID=5.1 MODE=primary   # all reads on the primary: always consistent
//   make load ID=5.1 MODE=broken    # reads on the replica: the new order is missing
//   make load ID=5.1 MODE=fixed     # read-your-writes: for 5 s after ordering, that customer reads the primary
//
// Needs Phase 5: make up PHASE=5.   Knobs: DURATION (45s), LAG_MS (1500).
import http from 'k6/http';
import { Counter } from 'k6/metrics';
import {
  BASE_URL, CORE_URLS, applyMode, customerIndex, ensureHistory, env, envInt, placeOrder, promValue, randomCustomer,
  randomInt, resetFixtures, waitForScrape,
} from '../lib/quickdrop.js';

const DURATION = env('DURATION', '45s');
const flows = new Counter('own_order_visible');

export const options = {
  setupTimeout: '10m',
  scenarios: {
    order_then_view: { executor: 'constant-arrival-rate', exec: 'orderThenView', rate: 15, timeUnit: '1s', duration: DURATION, preAllocatedVUs: 30, maxVUs: 200 },
    browsing_history: { executor: 'constant-arrival-rate', exec: 'browseHistory', rate: 30, timeUnit: '1s', duration: DURATION, preAllocatedVUs: 20, maxVUs: 200 },
  },
  thresholds: { 'own_order_visible{visible:yes}': ['count>=0'], 'own_order_visible{visible:no}': ['count>=0'] },
};

const MODES = {
  primary: { 'read-routing': 'primary' },
  broken: { 'read-routing': 'replica' },
  fixed: { 'read-routing': 'replica-ryw' },
};

export function setup() {
  applyMode(MODES, {
    'notification-mode': 'none', 'inventory-reservation': 'atomic', 'rider-assignment': 'conditional',
    'order-history-query': 'two-step', 'ryw-window-s': '5',
  });
  ensureHistory();
  customerIndex('create');
  resetFixtures({ restockAll: 100000 });
  const status = http.post(`${CORE_URLS[0]}/admin/db-lab/replica-delay?ms=${envInt('LAG_MS', 1500)}`).json();
  console.log(`replica: ${JSON.stringify(status)}`);
  if (!status.isReplica) console.warn('The replica URL points at the primary: start Phase 5 with "make up PHASE=5".');
}

export function orderThenView() {
  const customer = randomCustomer(1);
  const placed = placeOrder(customer, randomInt(1, 2), [{ productId: randomInt(1, 500), quantity: 1 }]);
  if (placed.status !== 201) return;
  const id = placed.json('id');
  const res = http.get(`${BASE_URL}/api/customers/${customer}/orders?limit=5`, { tags: { name: 'GET /api/customers/{id}/orders' } });
  const visible = res.status === 200 && res.json().some((o) => o.id === id);
  flows.add(1, { visible: visible ? 'yes' : 'no' });
}

export function browseHistory() {
  http.get(`${BASE_URL}/api/customers/${randomInt(1, 30000)}/orders?limit=20`, { tags: { name: 'GET /api/customers/{id}/orders' } });
}

export function teardown() {
  http.post(`${CORE_URLS[0]}/admin/db-lab/replica-delay?ms=0`);
  waitForScrape();
  const primary = promValue(`sum(increase(quickdrop_reads_routed_total{target="primary"}[${parseInt(DURATION, 10) + 10}s]))`) || 0;
  const replica = promValue(`sum(increase(quickdrop_reads_routed_total{target="replica"}[${parseInt(DURATION, 10) + 10}s]))`) || 0;
  console.log(`history reads routed: ${Math.round(primary)} to the primary, ${Math.round(replica)} to the replica`);
}

export function handleSummary(data) {
  const c = (v) => (data.metrics[`own_order_visible{visible:${v}}`] ? data.metrics[`own_order_visible{visible:${v}}`].values.count : 0);
  const yes = c('yes');
  const no = c('no');
  return {
    stdout: ['', `================ Scenario 5.1 — read replica (MODE=${__ENV.MODE || 'broken'}) ================`,
      `  customers who opened "My orders" right after ordering: ${yes + no}`,
      `  ...and did NOT see the order they just placed: ${no} (${yes + no ? ((100 * no) / (yes + no)).toFixed(1) : 0}%)`,
      '  Postgres dashboards: compare primary vs replica load; replica lag on the replica exporter.', ''].join('\n'),
  };
}
