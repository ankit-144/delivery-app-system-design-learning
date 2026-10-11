// Scenario 6.2 — Protecting core from a slow notification-service.
//
// Same 2 s vendor as 6.1. Three protections on core's calls to notification-service:
//
//   make load ID=6.2 MODE=broken    # none (6.1 again)
//   make load ID=6.2 MODE=timeout   # notify-timeout-ms=300: give up after 300 ms
//   make load ID=6.2 MODE=fixed     # timeout + circuit breaker + bulkhead (max 10 calls in flight)
//
// Every protection makes orders fast — by dropping the SMS. The summary counts how many.
// Needs Phase 6.   Knobs: RATE (orders/s, 30), DURATION (45s).
import {
  applyMode, env, envInt, promValue, resetFixtures, setNotify, setVendor, waitForScrape,
} from '../lib/quickdrop.js';
import { stream, streams, SUMMARY_STATS } from '../lib/streams.js';
import { browse as browseReq, orderInCity1 } from '../lib/traffic.js';

const DURATION = env('DURATION', '45s');
const s = streams(['order', 'browse']);
let dropped = 0;

export const options = {
  setupTimeout: '10m',
  scenarios: {
    orders: stream('order', envInt('RATE', 30), DURATION, 600),
    browsing: stream('browse', 20, DURATION),
  },
  summaryTrendStats: SUMMARY_STATS,
};

const COMMON = { 'notification-mode': 'http', 'inventory-reservation': 'atomic', 'rider-assignment': 'conditional' };
const MODES = {
  broken: { 'notify-timeout-ms': '0' },
  timeout: { 'notify-timeout-ms': '300' },
  fixed: { 'notify-timeout-ms': '300', 'notify-circuit-breaker': 'on', 'notify-bulkhead': '10' },
};

export function setup() {
  applyMode(MODES, COMMON);
  resetFixtures({ restockAll: 100000 });
  setNotify({ 'delivery-mode': 'sync', 'vendor-timeout-ms': '10000' });
  setVendor({ 'latency-ms': '2000', 'jitter-ms': '50' });
}

export function order() {
  const res = orderInCity1('30s');
  s.record('order', res, res.status === 201);
}

export function browse() {
  const res = browseReq('30s');
  s.record('browse', res, res.status === 200);
}

export function teardown() {
  waitForScrape();
  const window = `${parseInt(DURATION, 10) + 15}s`;
  const sent = promValue(`sum(increase(quickdrop_notifications_total{mode="http", result="sent"}[${window}]))`) || 0;
  const lost = promValue(`sum(increase(quickdrop_notifications_total{mode="http", result="dropped"}[${window}]))`) || 0;
  console.log(`order SMS: ${Math.round(sent)} sent, ${Math.round(lost)} dropped by timeout / breaker / bulkhead`);
}

export function handleSummary(data) {
  return s.summary(data, 'Scenario 6.2 — timeouts, circuit breaker, bulkhead');
}
