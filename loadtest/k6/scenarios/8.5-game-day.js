// Scenario 8.5 — Game day: normal traffic, healthy configuration, for 15 minutes.
//
// This script injects nothing. In another terminal, `make gameday` breaks one random thing, and you find it with
// dashboards, alerts, traces and logs. `make gameday-reveal` tells you what it was; `make gameday-end` undoes it.
//
//   make load ID=8.5                 # terminal 1
//   make gameday                     # terminal 2, once the dashboards look steady (~1 minute)
//
// Needs Phase 8.   Knobs: DURATION (15m).
import http from 'k6/http';
import { BASE_URL, JSON_HEADERS, applyMode, ensureHistory, env, resetFixtures, setNotify } from '../lib/quickdrop.js';
import { stream, streams, SUMMARY_STATS } from '../lib/streams.js';
import { browse as browseReq, history as historyReq, orderInCity1 } from '../lib/traffic.js';

const D = env('DURATION', '15m');
const s = streams(['checkout', 'browse', 'history', 'otp']);

export const options = {
  setupTimeout: '10m',
  scenarios: {
    checkout: stream('checkout', 20, D),
    browsing: stream('browse', 40, D),
    history: stream('history', 8, D),
    otp: stream('otp', 4, D),
  },
  summaryTrendStats: SUMMARY_STATS,
};

// What a sensible production setup of QuickDrop looks like after Phases 1–8.
const HEALTHY = {
  'inventory-reservation': 'atomic', 'rider-assignment': 'conditional', 'order-idempotency': 'on',
  'notification-mode': 'outbox', 'outbox-trace-context': 'propagate', 'otp-topic': 'priority',
  'product-cache': 'two-tier', 'bestsellers-cache': 'single-flight', 'order-history-query': 'two-step',
  'redis-circuit-breaker': 'on', 'bestsellers-fallback': 'last-known',
};

export function setup() {
  ensureHistory();
  applyMode({ broken: {} }, HEALTHY);
  resetFixtures({ restockAll: 1000000 });
  setNotify({ 'delivery-mode': 'sync', 'poison-handling': 'dlq', 'vendor-retries': '2', 'vendor-retry-backoff': 'exponential-jitter', 'vendor-retry-budget': 'on' });
  console.log('Traffic is running. When the dashboards look steady, run  make gameday  in another terminal.');
}

export function checkout() {
  const res = orderInCity1('30s');
  s.record('checkout', res, res.status === 201);
}

export function browse() {
  const res = browseReq('30s');
  s.record('browse', res, res.status === 200);
}

export function history() {
  const res = historyReq('30s');
  s.record('history', res, res.status === 200);
}

export function otp() {
  const phone = `+9197${String(Math.floor(Math.random() * 1e8)).padStart(8, '0')}`;
  const res = http.post(`${BASE_URL}/api/auth/otp`, JSON.stringify({ phone }), { headers: JSON_HEADERS, timeout: '30s', tags: { name: 'POST /api/auth/otp' } });
  s.record('otp', res, res.status === 202);
}

export function handleSummary(data) {
  return s.summary(data, 'Scenario 8.5 — game day traffic');
}
