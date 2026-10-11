// Per-stream latency and success tracking, for scenarios where one kind of traffic hurts another.
//
//   const s = streams(['order', 'browse', 'health']);
//   s.record('order', res, res.status === 201);
//   export function handleSummary(data) { return s.summary(data, 'Scenario 2.1'); }
import { Rate, Trend } from 'k6/metrics';

export function streams(names) {
  const trends = {};
  const oks = {};
  for (const name of names) {
    trends[name] = new Trend(`stream_${name}_ms`, true);
    oks[name] = new Rate(`stream_${name}_ok`);
  }
  return {
    record(name, res, ok) {
      trends[name].add(res.timings.duration);
      oks[name].add(ok);
    },
    summary(data, title, notes = []) {
      const mode = __ENV.MODE || 'broken';
      const lines = ['', `================ ${title} (MODE=${mode}) ================`,
        `  ${'stream'.padEnd(10)} ${'requests'.padStart(9)} ${'ok %'.padStart(7)} ${'p50 ms'.padStart(9)} ${'p95 ms'.padStart(9)} ${'p99 ms'.padStart(9)} ${'max ms'.padStart(9)}`];
      for (const name of names) {
        const t = data.metrics[`stream_${name}_ms`];
        const r = data.metrics[`stream_${name}_ok`];
        if (!t) continue;
        const v = t.values;
        const fmt = (x) => (x === undefined ? '-' : x.toFixed(0)).padStart(9);
        const count = r ? r.values.passes + r.values.fails : 0;
        lines.push(`  ${name.padEnd(10)} ${String(count).padStart(9)} ${((r ? r.values.rate : 0) * 100).toFixed(1).padStart(7)} ${fmt(v['p(50)'])} ${fmt(v['p(95)'])} ${fmt(v['p(99)'])} ${fmt(v.max)}`);
      }
      const dropped = data.metrics.dropped_iterations;
      if (dropped) {
        lines.push(`  dropped iterations (k6 had no free VU: the system could not keep up): ${dropped.values.count}`);
      }
      lines.push(...notes.map((n) => `  ${n}`), 'Dashboards: http://localhost:3000', '');
      return { stdout: lines.join('\n') };
    },
  };
}

/** constant-arrival-rate stream calling `fn`. */
export function stream(fn, rate, duration, maxVUs = 400) {
  return {
    executor: 'constant-arrival-rate',
    exec: fn,
    rate,
    timeUnit: '1s',
    duration,
    preAllocatedVUs: Math.min(maxVUs, Math.max(10, rate * 2)),
    maxVUs,
  };
}

export const SUMMARY_STATS = ['avg', 'p(50)', 'p(95)', 'p(99)', 'max'];
