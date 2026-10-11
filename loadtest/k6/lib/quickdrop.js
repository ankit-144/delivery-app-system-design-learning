// Shared helpers for every scenario script.
//
// Scripts run inside the k6 container (make load ID=...), where the services are reachable by
// their compose names. Each script:
//   setup()    -> applies the toggles for the chosen MODE and resets fixtures
//   default    -> generates load
//   teardown() -> prints the invariant checker's verdict
import http from 'k6/http';
import { check, fail, sleep } from 'k6';

export const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
// Toggles are per instance, so they are applied to every core instance that is running.
export const CORE_URLS = (__ENV.CORE_URLS || 'http://localhost:8081').split(',');
export const VENDOR_URL = __ENV.VENDOR_URL || 'http://localhost:8090';
export const MODE = __ENV.MODE || 'broken';
export const TOXIPROXY_URL = __ENV.TOXIPROXY_URL || 'http://localhost:8474';
export const PROMETHEUS_URL = __ENV.PROMETHEUS_URL || 'http://localhost:9090';
export const NOTIFY_URLS = (__ENV.NOTIFY_URLS || 'http://localhost:8091,http://localhost:8092').split(',');

/** Prometheus scrapes every 5 s: wait for one more scrape so the last seconds of a run are counted. */
export function waitForScrape() {
  sleep(7);
}

/** One number from Prometheus (first series), or null. Lets a script report server-side metrics. */
export function promValue(expr) {
  const res = http.get(`${PROMETHEUS_URL}/api/v1/query?query=${encodeURIComponent(expr)}`, { tags: { name: 'admin' } });
  if (res.status !== 200) return null;
  const result = res.json().data.result;
  return result.length ? parseFloat(result[0].value[1]) : null;
}

export const JSON_HEADERS = { 'Content-Type': 'application/json' };

// 409 (out of stock, conflicts) is a business answer, not a failed request.
http.setResponseCallback(http.expectedStatuses({ min: 200, max: 299 }, 409));

export function env(name, fallback) {
  const value = __ENV[name];
  return value === undefined || value === '' ? fallback : value;
}

export function envInt(name, fallback) {
  return parseInt(env(name, `${fallback}`), 10);
}

let liveCache = null;

/**
 * Core instances that are running (core-2/core-3 only exist from Phase 3). An instance that is
 * running but too busy to answer (still draining the previous run) is waited for; one that does not
 * exist (DNS failure, connection refused) is skipped. Cached for the rest of setup().
 */
export function liveCoreUrls() {
  if (liveCache) return liveCache;
  const live = [];
  for (const url of CORE_URLS) {
    for (let waited = 0; waited <= 180; waited += 5) {
      const res = http.get(`${url}/actuator/health/liveness`, { timeout: '5s', tags: { name: 'admin' } });
      if (res.status === 200) {
        live.push(url);
        break;
      }
      if (res.error_code !== 1050) break; // not a timeout: the instance is not running
      console.log(`${url} is running but not answering yet (busy with the previous run?), waiting...`);
    }
  }
  if (live.length === 0) {
    fail('No core instance answered its health check. Is the stack up (make ps)? Try `make restart S=core-1`.');
  }
  liveCache = live;
  return live;
}

/**
 * Waits until the previous run's backlog has drained. When k6 gives up on a request the server does
 * not know: it keeps working through every queued request. Starting a new run on top of that
 * backlog would measure the old run.
 */
export function waitForQuiet(maxSeconds = 180) {
  for (const url of liveCoreUrls()) {
    for (let waited = 0; waited <= maxSeconds; waited += 2) {
      const res = http.get(`${url}/admin/runtime`, { timeout: '5s', tags: { name: 'admin' } });
      if (res.status === 200) {
        const rt = res.json();
        const busy = rt.tomcatBusyThreads;
        if (rt.dbPool.waiting === 0 && rt.dbPool.active <= 2 && (busy < 0 || busy <= 3)) {
          if (waited > 0) console.log(`${rt.instance} is quiet again after ${waited}s`);
          break;
        }
        if (waited % 10 === 0) {
          console.log(`waiting for ${rt.instance} to drain the previous run: ${busy} busy threads, `
            + `${rt.dbPool.active} connections in use, ${rt.dbPool.waiting} requests waiting for one`);
        }
      }
      sleep(2);
    }
  }
}

export function setToggles(toggles, urls = liveCoreUrls()) {
  for (const url of urls) {
    for (const [name, value] of Object.entries(toggles)) {
      const res = http.put(`${url}/admin/toggles/${name}?value=${encodeURIComponent(value)}`, null, { tags: { name: 'admin' } });
      if (res.status !== 200) {
        fail(`Could not set ${name}=${value} on ${url}: ${res.status} ${res.body}`);
      }
    }
  }
  console.log(`toggles on ${urls.length} instance(s): ${JSON.stringify(toggles)}`);
}

/** Every toggle back to its startup value, so one scenario's settings never leak into the next. */
export function resetToggles(urls = liveCoreUrls()) {
  for (const url of urls) {
    const res = http.post(`${url}/admin/toggles/reset`, null, { tags: { name: 'admin' } });
    if (res.status !== 200) {
      fail(`Could not reset toggles on ${url}: ${res.status} ${res.body}`);
    }
  }
}

/** notification-service instances that are running (Phase 6+); empty before that. */
export function liveNotifyUrls() {
  return NOTIFY_URLS.filter((url) => http.get(`${url}/actuator/health/liveness`, { timeout: '2s', tags: { name: 'admin' } }).status === 200);
}

/** Set toggles on every notification-service instance. */
export function setNotify(toggles) {
  const urls = liveNotifyUrls();
  if (urls.length === 0) fail('notification-service is not running: start Phase 6 with "make up PHASE=6"');
  setToggles(toggles, urls);
}

/**
 * Waits until every notification backlog (Kafka lag, memory queue, Postgres queue, outbox) is empty, or
 * until maxSeconds. Prometheus scrapes every 5 s, so "empty" is checked twice in a row.
 */
export function waitForNotificationsToDrain(maxSeconds = 240) {
  // Lag from committed offsets (notification-service's LagReporter): the consumers' own records_lag ignores
  // prefetched messages, and kafka-exporter's series vanish during rebalances ("missing" is not "zero").
  const backlog = () => (promValue('sum(max by (group, topic) (notify_consumer_lag{topic=~"notifications.*"})) or vector(0)') || 0)
    + (promValue('sum(notify_memory_queue) or vector(0)') || 0)
    + (promValue('max(notify_db_pending) or vector(0)') || 0)
    + (promValue('max(quickdrop_outbox_pending) or vector(0)') || 0);
  let emptyChecks = 0;
  for (let waited = 0; waited < maxSeconds; waited += 5) {
    const left = backlog();
    if (left < 1) {
      if (++emptyChecks >= 2) return true;
    } else {
      emptyChecks = 0;
      if (waited % 15 === 0) console.log(`waiting for notifications to drain: ${Math.round(left)} left`);
    }
    sleep(5);
  }
  return false;
}

/**
 * Flushes whatever an earlier run left in the notification pipeline (so it can't delay this run's messages):
 * makes the vendor instant, waits for every backlog to empty. resetFixtures() restores the vendor afterwards.
 */
export function flushNotificationBacklog() {
  setVendor({ 'latency-ms': '0', 'jitter-ms': '0', 'rate-limit-rps': '0', 'error-rate-percent': '0' });
  if (!waitForNotificationsToDrain(300)) console.warn('notification backlog did not drain within 5 minutes');
}

export function reconcileSms() {
  return http.get(`${CORE_URLS[0]}/admin/reconcile/sms`, { timeout: '30s', tags: { name: 'admin' } }).json();
}

export function setVendor(toggles) {
  setToggles(toggles, [VENDOR_URL]);
}

/**
 * modes = { broken: {...toggles}, fixed: {...toggles}, ... }. MODE=keep leaves toggles alone, so
 * you can flip them by hand while the test runs.
 */
export function applyMode(modes, common = {}) {
  if (MODE === 'keep') {
    console.log('MODE=keep: not touching toggles');
    return;
  }
  const toggles = modes[MODE];
  if (!toggles) {
    fail(`Unknown MODE '${MODE}'. Use one of: ${Object.keys(modes).join(', ')}, keep`);
  }
  const urls = liveCoreUrls();
  resetToggles(urls);
  resetToggles(liveNotifyUrls());
  clearToxics();
  setToggles({ ...common, ...toggles }, urls);
}

export function resetFixtures(body = {}) {
  waitForQuiet();
  let res;
  for (let attempt = 1; attempt <= 5; attempt++) {
    res = http.post(`${CORE_URLS[0]}/admin/fixtures/reset`, JSON.stringify(body), { headers: JSON_HEADERS, timeout: '60s', tags: { name: 'admin' } });
    if (res.status === 200) break;
    console.warn(`fixtures reset attempt ${attempt} failed (${res.status}), retrying: ${res.body}`);
    sleep(2);
  }
  if (res.status !== 200) {
    fail(`fixtures reset failed: ${res.status} ${res.body}`);
  }
  console.log(`fixtures: ${res.body}`);
  http.post(`${VENDOR_URL}/v1/stats/reset`, null, { tags: { name: 'admin' } });
  if (MODE !== 'keep') {
    resetToggles([VENDOR_URL]);
  }
}

/**
 * Toxiproxy sits between the apps and Postgres / Redis / the vendor. A "toxic" changes what flows
 * through a proxy: latency, a blackhole (timeout), bandwidth limits, cut connections...
 *   addToxic('postgres', 'latency', { latency: 1 })      every response from Postgres 1 ms later
 *   addToxic('vendor', 'timeout', { timeout: 0 })        vendor accepts connections, never answers
 */
export function addToxic(proxy, type, attributes, name = `${proxy}-${type}`) {
  removeToxic(proxy, name);
  const body = JSON.stringify({ name, type, stream: 'downstream', toxicity: 1.0, attributes });
  const res = http.post(`${TOXIPROXY_URL}/proxies/${proxy}/toxics`, body, { headers: JSON_HEADERS, tags: { name: 'admin' } });
  if (res.status !== 200) fail(`could not add toxic ${name}: ${res.status} ${res.body}`);
  console.log(`toxic added: ${name} ${JSON.stringify(attributes)}`);
}

export function removeToxic(proxy, name) {
  http.del(`${TOXIPROXY_URL}/proxies/${proxy}/toxics/${name}`, null, { tags: { name: 'admin' } });
}

/** Removes every toxic from every proxy. Every scenario's setup starts from a clean network. */
export function clearToxics() {
  const res = http.get(`${TOXIPROXY_URL}/proxies`, { tags: { name: 'admin' } });
  if (res.status !== 200) return;
  for (const [proxy, info] of Object.entries(res.json())) {
    for (const toxic of info.toxics || []) {
      removeToxic(proxy, toxic.name);
    }
  }
}

/** Phase 4 database lab: a second client that runs schema changes or holds transactions (DbLabController). */
export function dbLab(path, params = {}) {
  const query = Object.entries(params).map(([k, v]) => `${k}=${encodeURIComponent(v)}`).join('&');
  const res = http.post(`${CORE_URLS[0]}/admin/db-lab/${path}${query ? `?${query}` : ''}`, null, { timeout: '5m', tags: { name: 'admin' } });
  if (res.status !== 200) fail(`db-lab ${path} failed: ${res.status} ${res.body}`);
  return res.json();
}

/** Waits for a db-lab job to finish and returns it. */
export function waitForJob(id, maxSeconds = 300) {
  for (let i = 0; i < maxSeconds * 2; i++) {
    const job = http.get(`${CORE_URLS[0]}/admin/db-lab/jobs`, { tags: { name: 'admin' } }).json().find((j) => j.id === id);
    if (job && job.finishedAt) return job;
    sleep(0.5);
  }
  return null;
}

export function dbLabJobs() {
  return http.get(`${CORE_URLS[0]}/admin/db-lab/jobs`, { tags: { name: 'admin' } }).json();
}

/** Kafka lab (Phase 7), on notification-1. */
export function lab(path, params = {}, method = 'POST') {
  const query = Object.entries(params).map(([k, v]) => `${k}=${encodeURIComponent(v)}`).join('&');
  const url = `${NOTIFY_URLS[0]}/admin/kafka-lab/${path}${query ? `?${query}` : ''}`;
  const res = method === 'GET' ? http.get(url, { timeout: '10m', tags: { name: 'admin' } }) : http.post(url, null, { timeout: '10m', tags: { name: 'admin' } });
  if (res.status !== 200) fail(`kafka-lab ${path} failed: ${res.status} ${res.body}`);
  return res.json();
}

/**
 * Waits until a lab consumer group has no lag left (or maxSeconds). Pass the topic so partitions the group
 * hasn't committed on yet count too. Timing out is loud: a lab result read before the group finished is wrong.
 */
export function labWaitForLag(group, maxSeconds = 300, topic = undefined) {
  const params = topic ? { group, topic } : { group };
  let lag = -1;
  for (let i = 0; i < maxSeconds; i += 2) {
    lag = lab('lag', params, 'GET').lag;
    if (lag === 0) return true;
    sleep(2);
  }
  console.warn(`!!! group ${group} still has lag ${lag} after ${maxSeconds} s: the numbers below are from an unfinished run`);
  return false;
}

/** Sharding lab (Phase 5): empty every shard, schema back to version 2. */
export function shardingReset() {
  const res = http.post(`${CORE_URLS[0]}/admin/sharding/reset`, null, { timeout: '2m', tags: { name: 'admin' } });
  if (res.status !== 200) fail(`sharding reset failed (is Phase 5 running? make up PHASE=5): ${res.status} ${res.body}`);
  return res.json();
}

export function placeShardedOrder(customerId, storeId, totalPaise = 25000) {
  return http.post(`${BASE_URL}/api/sharded/orders`, JSON.stringify({ customerId, storeId, totalPaise }),
    { headers: JSON_HEADERS, tags: { name: 'POST /api/sharded/orders' } });
}

/** ~1.4M past orders so "my orders" has a real table to search. Idempotent; ~15 s the first time. */
export function ensureHistory() {
  const res = http.post(`${CORE_URLS[0]}/admin/fixtures/history?orders=1500000`, null, { timeout: '10m', tags: { name: 'admin' } });
  if (res.status !== 200) fail(`history seeding failed: ${res.status} ${res.body}`);
  console.log(`history: ${res.body}`);
}

/** Create or drop the orders(customer_id, created_at) index used by "my orders" (scenario 2.4). */
export function customerIndex(action) {
  const res = http.post(`${CORE_URLS[0]}/admin/schema/orders-customer-index?action=${action}`, null, { timeout: '10m', tags: { name: 'admin' } });
  if (res.status !== 200) fail(`index ${action} failed: ${res.status} ${res.body}`);
  console.log(`index: ${res.body}`);
}

export function placeOrder(customerId, storeId, items, { idempotencyKey, timeout = '60s' } = {}) {
  const headers = { ...JSON_HEADERS };
  if (idempotencyKey) {
    headers['Idempotency-Key'] = idempotencyKey;
  }
  return http.post(`${BASE_URL}/api/orders`, JSON.stringify({ customerId, storeId, items }), {
    headers,
    timeout,
    tags: { name: 'POST /api/orders' },
  });
}

/** Classifies an order response for counters: placed, replayed, out_of_stock, deadlock, ... */
export function outcome(res) {
  if (res.status === 201) return 'placed';
  if (res.status === 200) return 'replayed';
  if (res.status === 0) return 'network_error';
  try {
    return JSON.parse(res.body).error || `http_${res.status}`;
  } catch (e) {
    return `http_${res.status}`;
  }
}

export function checkOrder(res, allowed = ['placed', 'out_of_stock']) {
  const result = outcome(res);
  check(res, { [`order outcome is ${allowed.join(' | ')}`]: () => allowed.includes(result) });
  return result;
}

/**
 * k6's constant-arrival-rate starts iterations evenly spaced (every 20 ms at 50/s). Real customers
 * arrive independently and in clumps. Use this executor + poissonPause() to get random arrivals at
 * an average of `rate` per second: VUS virtual users each pause a random exponential time.
 */
export function poissonArrivals(rate, duration, vus = 200) {
  return { executor: 'constant-vus', vus, duration, env: { POISSON_MEAN_PAUSE: `${vus / rate}` } };
}

export function poissonPause() {
  const mean = parseFloat(__ENV.POISSON_MEAN_PAUSE || '1');
  sleep(-Math.log(1 - Math.random()) * mean);
}

export function randomInt(min, max) {
  return min + Math.floor(Math.random() * (max - min + 1));
}

/** Store 1-2 are in city 1 (Bengaluru), 3-4 city 2 (Mumbai), 5-6 city 3 (Delhi). */
export function cityOfStore(storeId) {
  return Math.ceil(storeId / 2);
}

/** Seeded customers: customer g lives in city 1 + g % 3. */
export function randomCustomer(cityId = 1) {
  const k = randomInt(1, 9999);
  return 3 * k + ((cityId - 1) % 3);
}

export function invariants() {
  const res = http.get(`${CORE_URLS[0]}/admin/invariants?samples=3`, { tags: { name: 'admin' } });
  return res.status === 200 ? JSON.parse(res.body) : [];
}

export function vendorStats() {
  const res = http.get(`${VENDOR_URL}/v1/stats`, { tags: { name: 'admin' } });
  return res.status === 200 ? JSON.parse(res.body) : {};
}

/** Prints the invariant checker's verdict; call from teardown(). */
export function printVerdict(title, extra = {}) {
  const lines = [`\n================ ${title} (MODE=${MODE}) ================`];
  for (const inv of invariants()) {
    const mark = inv.violations === 0 ? 'OK  ' : 'FAIL';
    lines.push(`${mark} ${inv.name.padEnd(22)} violations=${inv.violations}`);
    for (const sample of inv.samples) {
      lines.push(`       ${JSON.stringify(sample)}`);
    }
  }
  for (const [key, value] of Object.entries(extra)) {
    lines.push(`${key}: ${typeof value === 'string' ? value : JSON.stringify(value)}`);
  }
  lines.push('Dashboards: http://localhost:3000  (Overview -> Correctness row)');
  console.log(lines.join('\n'));
}
