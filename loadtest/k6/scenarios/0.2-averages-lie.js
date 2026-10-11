// Scenario 0.2 — Averages lie.
//
// Orders send their confirmation SMS inline. The vendor answers in ~30 ms... except 2% of calls,
// which take 1.5 s (every real vendor has a long tail). The average stays small; p99 does not.
//
//   make load ID=0.2                 # vendor with a long tail
//   make load ID=0.2 MODE=fixed      # same vendor without the tail, for comparison
//
// Knobs: RATE (30/s), DURATION (2m), SLOW_PERCENT (2).
import { Trend } from 'k6/metrics';
import {
  applyMode, checkOrder, env, envInt, placeOrder, randomCustomer, randomInt, resetFixtures, setVendor,
} from '../lib/quickdrop.js';

const orderLatency = new Trend('order_latency', true);

export const options = {
  setupTimeout: '6m', // setup may wait for the previous run's backlog to drain
  scenarios: {
    orders: {
      executor: 'constant-arrival-rate',
      rate: envInt('RATE', 30),
      timeUnit: '1s',
      duration: env('DURATION', '2m'),
      preAllocatedVUs: 50,
      maxVUs: 300,
    },
  },
  summaryTrendStats: ['avg', 'p(50)', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

const MODES = { broken: {}, fixed: {} };

export function setup() {
  applyMode(MODES, {
    'notification-mode': 'inline',
    'inventory-reservation': 'atomic',
    'rider-assignment': 'conditional',
    'race-window-ms': '0',
    'per-line-delay-ms': '0',
  });
  resetFixtures({ restockAll: 100000 });
  const slow = __ENV.MODE === 'fixed' ? '0' : env('SLOW_PERCENT', '2');
  setVendor({ 'latency-ms': '30', 'jitter-ms': '10', 'slow-percent': slow, 'slow-ms': '1500' });
}

export default function () {
  const store = randomInt(1, 2);
  const res = placeOrder(randomCustomer(1), store, [{ productId: randomInt(1, 500), quantity: 1 }]);
  orderLatency.add(res.timings.duration);
  checkOrder(res, ['placed']);
}


export function handleSummary(data) {
  const v = data.metrics.order_latency.values;
  const line = (label, ms) => `  ${label.padEnd(8)} ${ms.toFixed(1).padStart(8)} ms`;
  const text = [
    '',
    `================ Scenario 0.2 — averages lie (MODE=${__ENV.MODE || 'broken'}) ================`,
    line('average', v.avg),
    line('p50', v['p(50)']),
    line('p95', v['p(95)']),
    line('p99', v['p(99)']),
    line('max', v.max),
    '',
    `The average says ~${Math.round(v.avg)} ms. Yet ${v['p(99)'] > 1000 ? 'one customer in fifty waited over a second' : 'without the slow tail, average and p99 tell the same story'}.`,
    'Overview -> "Order placement latency" shows the same thing over time.',
    '',
  ].join('\n');
  return { stdout: text };
}
