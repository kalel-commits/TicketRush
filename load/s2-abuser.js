// S2 Abuser (spec §16.2): 10 users, each firing POST /api/bookings ~20 req/s for 30 s. The gateway's strict
// limiter (2 req/s, burst 5, keyed by user) must reject ~ requests - (2 x 30 + 5) per user, +-10 %.
import http from 'k6/http';
import { sleep } from 'k6';
import { Counter } from 'k6/metrics';
import { GW, loginAll, summary } from './lib.js';

const USERS = 10;
const SECONDS = 30;
const sent = new Counter('abuser_sent');
const limited = new Counter('abuser_429');

// k6 only reports tagged sub-metrics that a threshold mentions: declare a no-op one per user
const perUser = {};
for (let u = 1; u <= USERS; u++) {
    perUser[`abuser_sent{user:u${u}}`] = ['count>=0'];
    perUser[`abuser_429{user:u${u}}`] = ['count>=0'];
}

export const options = {
    setupTimeout: '120s',
    scenarios: { abuse: { executor: 'constant-vus', vus: USERS, duration: `${SECONDS}s` } },
    thresholds: perUser,
};

export function setup() {
    return { tokens: loginAll(USERS, 900) };   // load-901..910: never used by the crowd scenarios' first users
}

export default function (d) {
    const user = `u${__VU}`;
    const r = http.post(`${GW}/api/bookings`, JSON.stringify({ eventId: 0, seatIds: [1] }), {
        headers: { Authorization: `Bearer ${d.tokens[__VU - 1]}`, 'Content-Type': 'application/json', 'Idempotency-Key': `abuse-${__VU}-${__ITER}` },
        tags: { name: 'abuse' },
        responseCallback: http.expectedStatuses(404, 409, 429, 503),
    });
    sent.add(1, { user });
    if (r.status === 429) limited.add(1, { user });
    sleep(0.05);
}

const writeSummary = summary('s2');
export function handleSummary(data) {
    // per-user check: 429s ~= sent - (2 * 30 + 5), within +-10 % of sent
    const lines = [];
    let ok = true;
    for (let u = 1; u <= USERS; u++) {
        const s = (data.metrics[`abuser_sent{user:u${u}}`] || { values: { count: 0 } }).values.count;
        const l = (data.metrics[`abuser_429{user:u${u}}`] || { values: { count: 0 } }).values.count;
        const expected = s - (2 * SECONDS + 5);
        const pass = Math.abs(l - expected) <= 0.1 * s;
        ok = ok && pass;
        lines.push(`${pass ? 'PASS' : 'FAIL'}  user ${u}: sent ${s}, 429 ${l}, expected ~${expected} (+-${Math.round(0.1 * s)})`);
    }
    lines.push(ok ? 'S2 PASSED' : 'S2 FAILED');
    const out = writeSummary(data);
    out.stdout += '\n' + lines.join('\n') + '\n';
    out['/scripts/results/s2-verdict.txt'] = lines.join('\n') + '\n';
    return out;
}
