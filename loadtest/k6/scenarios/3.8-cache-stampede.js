// Scenario 3.8 — Cache stampede on a hot key.
//
// Every app open shows "best sellers in your store" — store 1 gets 100 requests per second. The
// query costs ~0.6 CPU-seconds of Postgres; Postgres has 2 CPUs. It's cached for 10 s.
//
//   make load ID=3.8 MODE=none         # no cache: Postgres melts
//   make load ID=3.8 MODE=broken       # cache-aside: every 10 s the key expires and dozens of requests recompute at once
//   make load ID=3.8 MODE=fixed        # single-flight: one request recomputes, everyone else gets the stale copy
//
// Needs Phase 3.   Knobs: RATE (100/s), DURATION (60s).
import http from 'k6/http';
import {
  BASE_URL, applyMode, env, envInt, promValue, resetFixtures, ensureHistory, waitForScrape,
} from '../lib/quickdrop.js';
import { stream, streams, SUMMARY_STATS } from '../lib/streams.js';

const DURATION = env('DURATION', '60s');
const s = streams(['bestsellers']);
let verdict = {};

export const options = {
  setupTimeout: '10m',
  scenarios: { home_screen: stream('bestsellers', envInt('RATE', 100), DURATION, 1000) },
  summaryTrendStats: SUMMARY_STATS,
};

const MODES = {
  none: { 'bestsellers-cache': 'none' },
  broken: { 'bestsellers-cache': 'cache-aside' },
  fixed: { 'bestsellers-cache': 'single-flight' },
};

export function setup() {
  applyMode(MODES, { 'bestsellers-ttl-s': '10', 'tomcat-max-threads': '200' });
  ensureHistory();
  resetFixtures({});
}

export function bestsellers() {
  const res = http.get(`${BASE_URL}/api/stores/1/bestsellers`, { timeout: '30s', tags: { name: 'GET /api/stores/{id}/bestsellers' } });
  s.record('bestsellers', res, res.status === 200);
}

export function teardown() {
  waitForScrape();
  const window = `${parseInt(DURATION, 10) + 10}s`;
  console.log(`expensive queries run: ${Math.round(promValue(`sum(increase(quickdrop_bestsellers_query_seconds_count[${window}]))`) || 0)}`
    + ` | served stale: ${Math.round(promValue(`sum(increase(quickdrop_bestsellers_requests_total{result="stale"}[${window}]))`) || 0)}`
    + ` | Postgres CPU peak: ${(promValue(`max_over_time(rate(container_cpu_usage_seconds_total{container="postgres"}[10s])[${window}:5s])`) || 0).toFixed(2)} cores`);
}

export function handleSummary(data) {
  return s.summary(data, 'Scenario 3.8 — cache stampede', [
    'Redis & scale-out -> "Best sellers"; Postgres -> container CPU (spikes every 10 s with cache-aside).',
  ]);
}
