// Scenario 4.4 — Renaming a column while three instances are serving traffic.
//
// customers.phone becomes customers.phone_number. Orders read the phone (to send the SMS); customers
// update their phone numbers. Each instance's toggle customer-phone-schema stands in for the app version
// it runs (v1 reads/writes phone ... v3 reads/writes phone_number).
//
//   make load ID=4.4 MODE=broken    # t=15 s: RENAME COLUMN. The v1 instances break.
//                                   # t=35 s: the deploy of v3 finally reaches every instance.
//   make load ID=4.4 MODE=fixed     # expand / migrate / contract — nothing breaks:
//                                   #   expand (add phone_number) → roll v1.5 (write both) → backfill
//                                   #   → roll v2 (read new) → roll v3 (write new only) → contract (drop phone)
//
// Needs Phase 3 (three instances).   Takes ~75 s.
import http from 'k6/http';
import { sleep } from 'k6';
import {
  BASE_URL, applyMode, dbLab, randomInt, resetFixtures, setToggles, waitForJob,
} from '../lib/quickdrop.js';
import { stream, streams, SUMMARY_STATS } from '../lib/streams.js';
import { orderInCity1 } from '../lib/traffic.js';

const DURATION = '75s';
const INSTANCES = ['http://core-1:8080', 'http://core-2:8080', 'http://core-3:8080'];
const s = streams(['order', 'profile', 'update']);

export const options = {
  setupTimeout: '10m',
  scenarios: {
    orders: stream('order', 20, DURATION),
    profiles: stream('profile', 10, DURATION),
    updates: stream('update', 5, DURATION),
    deploy: { executor: 'shared-iterations', exec: 'deploy', vus: 1, iterations: 1, startTime: '5s', maxDuration: '80s' },
  },
  summaryTrendStats: SUMMARY_STATS,
};

const MODES = { broken: {}, fixed: {} };

export function setup() {
  applyMode(MODES, {
    'notification-mode': 'none', 'inventory-reservation': 'atomic', 'rider-assignment': 'conditional', 'customer-phone-schema': 'v1',
  });
  dbLab('reset');
  resetFixtures({ restockAll: 100000 });
}

function step(message) {
  console.log(`t+${Math.round((Date.now() - globalThis.start) / 1000) + 5}s  ${message}`);
}

function ddl(name) {
  const job = waitForJob(dbLab('ddl', { name }).id);
  step(`${name}: ${job.result} (${job.millis} ms)`);
}

function roll(version) {
  for (const url of INSTANCES) {
    setToggles({ 'customer-phone-schema': version }, [url]);
    step(`${url.replace('http://', '').replace(':8080', '')} now runs ${version}`);
    sleep(3);
  }
}

function drift() {
  return http.get(INSTANCES[0].replace('core-1:8080', 'core-1:8080') + '/admin/db-lab/phone-drift').json('drift');
}

export function deploy() {
  globalThis.start = Date.now();
  if (__ENV.MODE === 'fixed') {
    ddl('expand-phone');                  // t≈5   new column, nullable: instant
    roll('v1.5');                         // t≈5-14  every instance writes both columns
    ddl('backfill-phone');                // copy old values; only safe once *every* instance dual-writes
    step(`phone vs phone_number drift after backfill: ${drift()}`);
    sleep(3);
    roll('v2');                           // read from the new column
    step(`drift before switching writes: ${drift()}`);
    roll('v3');                           // write only the new column; nobody reads the old one any more
    sleep(3);
    ddl('contract-phone');                // drop the old column
  } else {
    sleep(10);
    ddl('rename-phone');                  // t≈15  "it's just a rename"
    sleep(20);
    step('the v3 deploy reaches every instance');
    for (const url of INSTANCES) setToggles({ 'customer-phone-schema': 'v3' }, [url]);
  }
}

export function order() {
  const res = orderInCity1('30s');
  s.record('order', res, res.status === 201);
}

export function profile() {
  const res = http.get(`${BASE_URL}/api/customers/${randomInt(1, 30000)}`, { timeout: '30s', tags: { name: 'GET /api/customers/{id}' } });
  s.record('profile', res, res.status === 200);
}

export function update() {
  const res = http.put(`${BASE_URL}/api/customers/${randomInt(1, 30000)}/phone`, JSON.stringify({ phone: `+9177${randomInt(10000000, 99999999)}` }),
    { headers: { 'Content-Type': 'application/json' }, timeout: '30s', tags: { name: 'PUT /api/customers/{id}/phone' } });
  s.record('update', res, res.status === 200);
}

export function teardown() {
  // Put the schema and every instance back for the next run.
  dbLab('reset');
  for (const url of INSTANCES) setToggles({ 'customer-phone-schema': 'v1' }, [url]);
}

export function handleSummary(data) {
  return s.summary(data, 'Scenario 4.4 — renaming a column during a rolling deploy', [
    'Overview -> "5xx error ratio" per endpoint and "API errors by type" (internal_error = column does not exist).',
  ]);
}
