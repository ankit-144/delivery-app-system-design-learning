// Scenario 0.1 — A normal day. Realistic traffic mix at a comfortable rate.
//
// Use it to learn the dashboards, and as background traffic while you run other scenarios.
//
//   make load ID=0.1                    # the toggles a first version would ship (naive everything)
//   make load ID=0.1 MODE=fixed         # the correct strategies
//   make load ID=0.1 MODE=keep          # don't touch toggles (background load for other scenarios)
//
// Knobs: DURATION (5m), SCALE (1 = ~90 req/s; 2 doubles every stream).
import http from 'k6/http';
import { check } from 'k6';
import {
  BASE_URL, applyMode, checkOrder, env, placeOrder, randomCustomer, randomInt, resetFixtures,
} from '../lib/quickdrop.js';
import { CATEGORIES, PRODUCTS, RIDERS, STORES, storeOfRider } from '../lib/world.js';

const DURATION = env('DURATION', '5m');
const SCALE = parseFloat(env('SCALE', '1'));

function stream(fn, ratePerSecond) {
  return {
    executor: 'constant-arrival-rate',
    exec: fn,
    rate: Math.max(1, Math.round(ratePerSecond * SCALE)),
    timeUnit: '1s',
    duration: DURATION,
    preAllocatedVUs: 20,
    maxVUs: 500,
  };
}

export const options = {
  setupTimeout: '6m', // setup may wait for the previous run's backlog to drain
  scenarios: {
    browse: stream('browse', 40),
    order: stream('order', 10),
    history: stream('history', 5),
    otp: stream('otp', 2),
    rider_ping: stream('riderPing', 30),
  },
};

const MODES = {
  broken: {
    'inventory-reservation': 'naive',
    'rider-assignment': 'naive',
    'order-idempotency': 'off',
    'notification-mode': 'inline',
    'order-history-query': 'naive',
  },
  fixed: {
    'inventory-reservation': 'atomic',
    'inventory-lock-order': 'sorted',
    'rider-assignment': 'conditional',
    'order-idempotency': 'on',
    'notification-mode': 'inline',
    'order-history-query': 'two-step',
  },
};

export function setup() {
  applyMode(MODES, { 'race-window-ms': '0', 'per-line-delay-ms': '0', 'auto-deliver-after-ms': '20000' });
  if (env('RESET', 'true') === 'true') {
    resetFixtures({ restockAll: 100000 });
  }
}

function randomStore() {
  return randomInt(1, 6);
}

export function browse() {
  const store = randomStore();
  const category = CATEGORIES[randomInt(0, CATEGORIES.length - 1)];
  const res = http.get(`${BASE_URL}/api/stores/${store}/products?category=${category}&limit=20`,
    { tags: { name: 'GET /api/stores/{id}/products' } });
  check(res, { 'browse 200': (r) => r.status === 200 });
}

export function order() {
  const store = randomStore();
  const items = [];
  for (let i = 0; i < randomInt(1, 4); i++) {
    items.push({ productId: randomInt(1, PRODUCTS), quantity: randomInt(1, 3) });
  }
  const res = placeOrder(randomCustomer(STORES[store].city), store, items, { idempotencyKey: `${__VU}-${__ITER}-${Date.now()}` });
  checkOrder(res, ['placed', 'out_of_stock']);
}

export function history() {
  const res = http.get(`${BASE_URL}/api/customers/${randomCustomer(randomInt(1, 3))}/orders?limit=20`,
    { tags: { name: 'GET /api/customers/{id}/orders' } });
  check(res, { 'history 200': (r) => r.status === 200 });
}

export function otp() {
  const res = http.post(`${BASE_URL}/api/auth/otp`, JSON.stringify({ phone: `+9190${randomInt(10000000, 99999999)}` }),
    { headers: { 'Content-Type': 'application/json' }, tags: { name: 'POST /api/auth/otp' } });
  check(res, { 'otp 202': (r) => r.status === 202 });
}

export function riderPing() {
  const rider = randomInt(1, RIDERS);
  const store = STORES[storeOfRider(rider)];
  const body = JSON.stringify({ lat: store.lat + (Math.random() - 0.5) * 0.054, lon: store.lon + (Math.random() - 0.5) * 0.054 });
  const res = http.post(`${BASE_URL}/api/riders/${rider}/location`, body,
    { headers: { 'Content-Type': 'application/json' }, tags: { name: 'POST /api/riders/{id}/location' } });
  check(res, { 'ping 204': (r) => r.status === 204 });
}
