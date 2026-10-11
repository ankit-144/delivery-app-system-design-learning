// Scenario 3.5 — Rate limiting a bot on the OTP endpoint.
//
// Every OTP is an SMS we pay for. The limit: 5 OTPs per phone number per minute. Two bots:
//   hammer   — 20 requests per second for one phone number, non-stop
//   boundary — knows how fixed windows work: sends 5 requests at hh:mm:59 and 5 more at hh:mm+1:00
//
//   make load ID=3.5 MODE=off            # no limit
//   make load ID=3.5 MODE=broken         # in-memory limiter: each of the 3 instances allows 5
//   make load ID=3.5 MODE=redis-fixed    # one shared counter per minute: correct per minute, 2x across a boundary
//   make load ID=3.5 MODE=fixed          # sliding log in Redis: never more than 5 in any 60 s
//   make load ID=3.5 MODE=token-bucket   # 5 tokens, refilled 1 per 12 s: smooth
//
// Needs Phase 3.   Knobs: DURATION (150s — needs to span at least two minute boundaries).
import http from 'k6/http';
import { sleep } from 'k6';
import { Counter } from 'k6/metrics';
import { BASE_URL, applyMode, env, resetFixtures } from '../lib/quickdrop.js';

const DURATION_S = parseInt(env('DURATION', '150s'), 10);
const accepted = new Counter('otp_accepted');
const limited = new Counter('otp_limited');

export const options = {
  setupTimeout: '6m',
  scenarios: {
    hammer: { executor: 'per-vu-iterations', exec: 'hammer', vus: 1, iterations: 1, maxDuration: `${DURATION_S + 30}s` },
    boundary: { executor: 'per-vu-iterations', exec: 'boundary', vus: 1, iterations: 1, maxDuration: `${DURATION_S + 30}s` },
  },
  thresholds: {
    'otp_accepted{bot:hammer}': ['count>=0'],
    'otp_accepted{bot:boundary}': ['count>=0'],
    'otp_limited{bot:hammer}': ['count>=0'],
    'otp_limited{bot:boundary}': ['count>=0'],
  },
};

const MODES = {
  off: { 'rate-limiter': 'off' },
  broken: { 'rate-limiter': 'local' },
  'redis-fixed': { 'rate-limiter': 'redis-fixed' },
  fixed: { 'rate-limiter': 'redis-sliding' },
  'token-bucket': { 'rate-limiter': 'redis-token-bucket' },
};

export function setup() {
  applyMode(MODES, { 'rate-limit-otp-per-minute': '5', 'notification-mode': 'async-memory' });
  resetFixtures({});
}

function requestOtp(phone, bot) {
  const res = http.post(`${BASE_URL}/api/auth/otp`, JSON.stringify({ phone }),
    { headers: { 'Content-Type': 'application/json' }, tags: { name: 'POST /api/auth/otp' } });
  if (res.status === 202) accepted.add(1, { bot });
  else if (res.status === 429) limited.add(1, { bot });
  return res.status === 202;
}

/** Highest number of accepted requests inside any 60 s span. */
function worstMinute(times) {
  let worst = 0;
  for (let i = 0, j = 0; j < times.length; j++) {
    while (times[j] - times[i] >= 60000) i++;
    worst = Math.max(worst, j - i + 1);
  }
  return worst;
}

export function hammer() {
  const end = Date.now() + DURATION_S * 1000;
  const times = [];
  while (Date.now() < end) {
    if (requestOtp('+919999000001', 'hammer')) times.push(Date.now());
    sleep(0.05);
  }
  console.log(`hammer:   ${times.length} OTPs sent in ${DURATION_S}s, worst 60 s span: ${worstMinute(times)} (limit 5)`);
}

export function boundary() {
  const end = Date.now() + DURATION_S * 1000;
  const times = [];
  while (Date.now() < end) {
    const second = new Date().getUTCSeconds();
    const ms = new Date().getUTCMilliseconds();
    if (second === 59 && ms >= 500) {
      for (let i = 0; i < 5; i++) if (requestOtp('+919999000002', 'boundary')) times.push(Date.now());   // last 0.5 s of the minute
      sleep(0.6);
      for (let i = 0; i < 5; i++) if (requestOtp('+919999000002', 'boundary')) times.push(Date.now());   // first moments of the next
      sleep(1);
    } else {
      sleep(0.1);
    }
  }
  console.log(`boundary: ${times.length} OTPs sent in ${DURATION_S}s, worst 60 s span: ${worstMinute(times)} (limit 5)`);
}
