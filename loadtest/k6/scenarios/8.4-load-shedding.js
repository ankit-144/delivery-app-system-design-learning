// Scenario 8.4 — Load shedding: keep checkout alive during a flash sale.
//
// 40 checkouts/s all along. At t=15 s a flash sale starts: for 45 s everyone browses, refreshes best-sellers and
// checks their order history (expensive queries: no caches, naive history). Postgres and the connection pools
// fill up with browsing.
//
//   make load ID=8.4 MODE=broken    # load-shedding=off: browsing and checkout queue for the same connections
//   make load ID=8.4 MODE=fixed     # priority: at most 4 browsing requests in flight per instance, the rest 503 at once
//
// Needs Phase 3+.   Knobs: RATE (checkouts/s, 40), SURGE (browse requests/s, 300).
import { sleep } from 'k6';
import http from 'k6/http';
import { BASE_URL, applyMode, ensureHistory, envInt, randomInt, resetFixtures } from '../lib/quickdrop.js';
import { stream, streams, SUMMARY_STATS } from '../lib/streams.js';
import { browse as browseReq, history as historyReq, orderInCity1 } from '../lib/traffic.js';

const SURGE = envInt('SURGE', 300);
const s = streams(['checkout', 'browse', 'shed']);

function surge(fn, rate) {
  return { ...stream(fn, rate, '45s', 1500), startTime: '15s' };
}

export const options = {
  setupTimeout: '10m',
  scenarios: {
    checkout: stream('checkout', envInt('RATE', 40), '75s', 1500),
    browsing: surge('browse', Math.round(SURGE * 0.6)),
    history: surge('history', Math.round(SURGE * 0.33)),
    bestsellers: surge('bestsellers', Math.max(1, Math.round(SURGE * 0.07))),
  },
  summaryTrendStats: SUMMARY_STATS,
};

const COMMON = {
  'inventory-reservation': 'atomic', 'rider-assignment': 'conditional', 'notification-mode': 'outbox',
  'bestsellers-cache': 'none', 'product-cache': 'none', 'order-history-query': 'naive',
};
const MODES = { broken: { 'load-shedding': 'off' }, fixed: { 'load-shedding': 'priority', 'shed-browse-limit': '4' } };

export function setup() {
  ensureHistory();
  applyMode(MODES, COMMON);
  resetFixtures({ restockAll: 100000 });
  sleep(2);
}

export function checkout() {
  const res = orderInCity1('30s');
  s.record('checkout', res, res.status === 201);
}

function sheddable(res) {
  if (res.status === 503) {
    s.record('shed', res, true);   // refused at once: the customer can retry; nobody waited
    return;
  }
  s.record('browse', res, res.status === 200);
}

export function browse() {
  sheddable(browseReq('30s'));
}

export function history() {
  sheddable(historyReq('30s'));
}

export function bestsellers() {
  sheddable(http.get(`${BASE_URL}/api/stores/${randomInt(1, 6)}/bestsellers`, { timeout: '30s', tags: { name: 'GET /api/stores/{id}/bestsellers' } }));
}

export function handleSummary(data) {
  return s.summary(data, 'Scenario 8.4 — load shedding', [
    'checkout: POST /api/orders (never shed). browse: product lists, best-sellers, order history that were served.',
    'shed: browsing requests refused at once with 503 (fixed mode only): latency is how fast the refusal came back.',
  ]);
}
