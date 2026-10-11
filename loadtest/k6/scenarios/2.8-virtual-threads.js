// Scenario 2.8 — Virtual threads: the thread limit disappears, the next limit appears.
//
// Run it twice: once on platform threads (default), once after restarting core with virtual threads:
//
//   make load ID=2.8 MODE=broken                 # platform threads: same wall as 2.1
//   VIRTUAL_THREADS=true make rebuild S=core-1   # restart core-1 with virtual threads
//   make load ID=2.8 MODE=broken                 # virtual threads: the thread wall is gone
//   make load ID=2.8 MODE=in-tx                  # ...so 100 requests/s rush the 10-connection pool
//   make load ID=2.8 MODE=pinning                # synchronized + JDBC on JDK 21 pins the only carrier thread
//   make rebuild S=core-1                        # back to platform threads
//
// Knobs: RATE (orders/s, 100), DURATION (60s).
import http from 'k6/http';
import { CORE_URLS, applyMode, env, envInt, resetFixtures, setVendor } from '../lib/quickdrop.js';
import { stream, streams, SUMMARY_STATS } from '../lib/streams.js';
import { browse as browseReq, health as healthReq, orderInCity1 } from '../lib/traffic.js';

const DURATION = env('DURATION', '60s');
const s = streams(['order', 'browse', 'health']);

export const options = {
  setupTimeout: '6m',
  scenarios: {
    orders: stream('order', envInt('RATE', 100), DURATION, 1000),
    browsing: stream('browse', 20, DURATION),
    probes: stream('health', 2, DURATION),
  },
  summaryTrendStats: SUMMARY_STATS,
};

const COMMON = {
  'inventory-reservation': 'atomic',
  'inventory-lock-order': 'sorted',
  'rider-assignment': 'conditional',
  'auto-deliver-after-ms': '5000',
};

const MODES = {
  broken: { 'notification-mode': 'inline' },
  'in-tx': { 'notification-mode': 'inline-in-tx' },
  pinning: { 'notification-mode': 'none', 'inventory-reservation': 'synchronized', 'per-line-delay-ms': '5' },
};

let threads = 'unknown';

export function setup() {
  const rt = http.get(`${CORE_URLS[0]}/admin/runtime`).json();
  threads = rt.virtualThreads ? 'VIRTUAL threads' : `PLATFORM threads (max ${rt.tomcatMaxThreads})`;
  console.log(`core-1 runs on ${threads}, JDK ${rt.java}, ${rt.cpusVisibleToJvm} CPU`);
  applyMode(MODES, COMMON);
  resetFixtures({ restockAll: 100000 });
  setVendor({ 'latency-ms': '1000', 'jitter-ms': '50' });
  return { threads };
}

export function order() {
  const res = orderInCity1();
  s.record('order', res, res.status === 201);
}

export function browse() {
  const res = browseReq();
  s.record('browse', res, res.status === 200);
}

export function health() {
  const res = healthReq();
  s.record('health', res, res.status === 200);
}

export function handleSummary(data) {
  const vt = data.setup_data ? data.setup_data.threads : 'unknown';
  return s.summary(data, `Scenario 2.8 — ${vt}`, [
    'Saturation -> "Busy threads vs max", "Connections: active / idle / waiting", "JVM threads by state".',
  ]);
}
