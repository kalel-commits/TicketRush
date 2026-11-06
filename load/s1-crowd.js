// S1 Crowd (spec §16.2): 1,000 users, 100 seats, 1 seat each. Also the traffic for S5 (chaos) and, with
// VUS/ROWS/MAX_PAY_TRIES overrides, for S3 (declines) and S4 (ambiguous provider).
import { sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import { GW, loginAll, adminCreateEvent, waitUntil, seats, book, pay, pollStatus, randomKey, summary } from './lib.js';

const VUS = parseInt(__ENV.VUS || '1000');
const ROWS = parseInt(__ENV.ROWS || '10');
const PER_ROW = parseInt(__ENV.PER_ROW || '10');
const MAX_PAY_TRIES = parseInt(__ENV.MAX_PAY_TRIES || '1');   // S3: 3 (new key on each decline)
const NAME = __ENV.NAME || 's1';

// one plain counter per status code (k6 only reports tagged sub-metrics that a threshold names)
const counters = {};
function countStatus(kind, status) {
    const code = [200, 201, 202, 402, 409, 429].includes(status) ? String(status) : (status >= 500 ? '5xx' : 'other');
    const name = `${kind}_${code}`;
    if (!counters[name]) counters[name] = new Counter(name);
    counters[name].add(1);
}
for (const k of ['book_201', 'book_409', 'book_429', 'book_5xx', 'book_other', 'pay_200', 'pay_202', 'pay_402', 'pay_409', 'pay_5xx', 'pay_other']) {
    counters[k] = new Counter(k);   // metrics must be created in the init context
}
const confirmed = new Counter('bookings_confirmed');
const payToConfirmed = new Trend('pay_to_confirmed', true);
const confirmedAfterOpen = new Trend('confirmed_after_open', true);   // max = time to sell out

export const options = {
    setupTimeout: '600s',
    scenarios: { crowd: { executor: 'per-vu-iterations', vus: VUS, iterations: 1, maxDuration: '6m' } },
    thresholds: {
        'http_req_failed{name:book}': ['rate<0.005'],   // 5xx share of bookings < 0.5 % (409/429 are expected)
        'http_req_duration{name:book}': ['p(99)<60000'],   // (named so k6 reports POST /api/bookings latency)
    },
    summaryTrendStats: ['avg', 'min', 'med', 'p(95)', 'p(99)', 'max', 'count'],
};

export function setup() {
    const tokens = loginAll(VUS);
    const ev = adminCreateEvent(ROWS, PER_ROW, 30);   // sale opens 30 s after setup: the start barrier
    return { tokens, ev };
}

export default function (d) {
    const token = d.tokens[__VU - 1];
    waitUntil(d.ev.opensAt);

    let bookingId = null;
    for (let attempt = 0; attempt < 30 && !bookingId; attempt++) {
        const free = seats(d.ev.id, token).filter((s) => s.status === 'AVAILABLE');
        if (free.length === 0) return;   // sold out (or all held): nothing left to try
        const seat = free[Math.floor(Math.random() * free.length)];
        const r = book(token, d.ev.id, seat.id, randomKey('b'));
        countStatus('book', r.status);
        if (r.status === 201) bookingId = r.json('id');
        else sleep(r.status === 429 ? 0.5 : 0.1 + Math.random() * 0.2);
    }
    if (!bookingId) return;

    for (let t = 0; t < MAX_PAY_TRIES; t++) {
        const p = pay(token, bookingId, randomKey('p'));
        countStatus('pay', p.status);
        if (p.status === 402) continue;   // declined: retry with a new key while the hold lasts (S3)
        if (p.status !== 200 && p.status !== 202) return;
        const paidAt = Date.now();
        const st = pollStatus(token, `${GW}/api/bookings/${bookingId}`, ['CONFIRMED', 'EXPIRED', 'CANCELLED', 'FAILED'],
            p.status === 202 ? 90 : 30, 'poll');
        if (st === 'CONFIRMED') {
            confirmed.add(1);
            payToConfirmed.add(Date.now() - paidAt);
            confirmedAfterOpen.add(Date.now() - d.ev.opensAt);
        }
        return;
    }
}

const writeSummary = summary(NAME);
export function handleSummary(data) {
    const out = writeSummary(data);
    out[`/scripts/results/${NAME}-event.json`] = JSON.stringify(data.setup_data ? data.setup_data.ev : null);
    return out;
}
