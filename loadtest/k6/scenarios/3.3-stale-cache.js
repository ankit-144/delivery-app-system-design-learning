// Scenario 3.3 — A price change that two out of three instances never hear about.
//
// Product 7's price is cached. 10 s into the test, the price goes up — the admin request lands on
// core-1. Customers keep reading the product page and ordering it through nginx (round-robin).
//
//   make load ID=3.3 MODE=broken      # product-cache=local: each JVM keeps its copy for 5 minutes
//   make load ID=3.3 MODE=redis       # one shared copy in Redis, deleted on change
//   make load ID=3.3 MODE=fixed       # two-tier: local copy + Redis pub/sub invalidation to every instance
//   make load ID=3.3 MODE=none        # no cache at all (always correct, every read hits Postgres)
//
// Needs Phase 3.   Knobs: DURATION (40s).
import http from 'k6/http';
import { Counter } from 'k6/metrics';
import {
  BASE_URL, CORE_URLS, applyMode, env, placeOrder, randomCustomer, resetFixtures,
} from '../lib/quickdrop.js';

const PRODUCT = 7;
const CHANGE_AT_S = 10;
const reads = new Counter('price_reads');
const charges = new Counter('order_charges');

export const options = {
  setupTimeout: '6m',
  scenarios: {
    readers: { executor: 'constant-arrival-rate', exec: 'read', rate: 60, timeUnit: '1s', duration: env('DURATION', '40s'), preAllocatedVUs: 20, maxVUs: 100 },
    buyers: { executor: 'constant-arrival-rate', exec: 'buy', rate: 10, timeUnit: '1s', duration: env('DURATION', '40s'), preAllocatedVUs: 10, maxVUs: 50 },
    price_change: { executor: 'shared-iterations', exec: 'changePrice', vus: 1, iterations: 1, startTime: `${CHANGE_AT_S}s` },
  },
  // Declaring these makes k6 keep per-tag counts for the summary below.
  thresholds: {
    'price_reads{freshness:fresh}': ['count>=0'],
    'price_reads{freshness:stale}': ['count>=0'],
    'order_charges{freshness:fresh}': ['count>=0'],
    'order_charges{freshness:stale}': ['count>=0'],
  },
};

const MODES = {
  broken: { 'product-cache': 'local' },
  redis: { 'product-cache': 'redis' },
  fixed: { 'product-cache': 'two-tier' },
  none: { 'product-cache': 'none' },
};

export function setup() {
  applyMode(MODES, { 'notification-mode': 'none', 'inventory-reservation': 'atomic', 'rider-assignment': 'conditional' });
  resetFixtures({ restockAll: 100000 });
  const oldPrice = http.get(`${CORE_URLS[0]}/api/products/${PRODUCT}`).json('pricePaise');
  return { oldPrice, newPrice: oldPrice + 5000, changeAt: Date.now() + CHANGE_AT_S * 1000 };
}

function classify(data, price) {
  if (Date.now() < data.changeAt + 1000) return 'before_change';   // 1 s grace for the change itself
  return price === data.newPrice ? 'fresh' : 'stale';
}

export function read(data) {
  const res = http.get(`${BASE_URL}/api/products/${PRODUCT}`, { tags: { name: 'GET /api/products/{id}' } });
  if (res.status === 200) reads.add(1, { freshness: classify(data, res.json('pricePaise')), served_by: res.headers['X-Served-By'] });
}

export function buy(data) {
  const res = placeOrder(randomCustomer(1), 1, [{ productId: PRODUCT, quantity: 1 }]);
  if (res.status === 201) charges.add(1, { freshness: classify(data, res.json('items')[0].pricePaise) });
}

export function changePrice(data) {
  // An admin raises the price; the request happens to hit core-1.
  const res = http.put(`${CORE_URLS[0]}/admin/products/${PRODUCT}/price`, JSON.stringify({ pricePaise: data.newPrice }),
    { headers: { 'Content-Type': 'application/json' } });
  console.log(`price of product ${PRODUCT}: ${data.oldPrice} -> ${data.newPrice} paise (${res.status}, via core-1)`);
}

export function teardown(data) {
  http.put(`${CORE_URLS[0]}/admin/products/${PRODUCT}/price`, JSON.stringify({ pricePaise: data.oldPrice }),
    { headers: { 'Content-Type': 'application/json' } });
}

export function handleSummary(summary) {
  const count = (metric, tag) => {
    const m = summary.metrics[`${metric}{freshness:${tag}}`];
    return m ? m.values.count : 0;
  };
  const line = (metric, label) => {
    const fresh = count(metric, 'fresh');
    const stale = count(metric, 'stale');
    const pct = fresh + stale ? ((100 * stale) / (fresh + stale)).toFixed(1) : '0.0';
    return `  ${label.padEnd(34)} after the change: ${String(fresh).padStart(5)} fresh, ${String(stale).padStart(5)} stale (${pct}% stale)`;
  };
  return {
    stdout: ['', `================ Scenario 3.3 — stale cache (MODE=${__ENV.MODE || 'broken'}) ================`,
      line('price_reads', 'product page reads'),
      line('order_charges', 'orders charged'),
      '  Redis dashboard -> "Product cache"; stale reads come from the instances that did not make the change.', ''].join('\n'),
  };
}
