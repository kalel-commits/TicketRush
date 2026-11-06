// Shared k6 helpers for the TicketRush load scenarios (spec §16.2). Runs inside the grafana/k6 container on the
// Compose network, so the gateway is http://api-gateway:8080.
import http from 'k6/http';
import { sleep } from 'k6';
import { textSummary } from 'https://jslib.k6.io/k6-summary/0.1.0/index.js';

export const GW = __ENV.GW || 'http://api-gateway:8080';
const JSON_HEADERS = { 'Content-Type': 'application/json' };

function auth(token, extra) {
    return { headers: Object.assign({ Authorization: `Bearer ${token}` }, JSON_HEADERS, extra || {}) };
}

export function login(email, password) {
    const r = http.post(`${GW}/api/users/login`, JSON.stringify({ email, password }), { headers: JSON_HEADERS, tags: { name: 'login' } });
    return r.status === 200 ? r.json('accessToken') : null;
}

/**
 * Logs in load-1..load-n at <= 15 req/s: anonymous calls share the gateway's per-IP limit (20/s).
 * Throws if fewer than 99 % succeed, so a throttled setup never runs a scenario with missing users.
 */
export function loginAll(n, offset) {
    const start = offset || 0;
    const tokens = [];
    for (let i = start + 1; i <= start + n; i += 15) {
        const batch = [];
        for (let j = i; j < Math.min(i + 15, start + n + 1); j++) {
            batch.push(['POST', `${GW}/api/users/login`, JSON.stringify({ email: `load-${j}@example.com`, password: 'Load#12345' }),
                { headers: JSON_HEADERS, tags: { name: 'login' } }]);
        }
        for (const r of http.batch(batch)) tokens.push(r.status === 200 ? r.json('accessToken') : null);
        sleep(1);
    }
    // second, slower pass for failures: right after a restart the gateway may not have discovered user-service yet
    for (let k = 0; k < tokens.length; k++) {
        if (!tokens[k]) {
            tokens[k] = login(`load-${start + k + 1}@example.com`, 'Load#12345');
            sleep(0.1);
        }
    }
    const ok = tokens.filter((t) => t).length;
    if (ok < n * 0.99) throw new Error(`setup: only ${ok}/${n} logins succeeded (rate limited or users not seeded)`);
    return tokens;
}

/** Creates an event through the admin API whose sale opens in opensInSec seconds; returns {id, opensAt (epoch ms)}. */
export function adminCreateEvent(rows, perRow, opensInSec) {
    const token = login(__ENV.ADMIN_EMAIL, __ENV.ADMIN_PASSWORD);
    if (!token) throw new Error('setup: admin login failed (ADMIN_EMAIL / ADMIN_PASSWORD)');
    const opensAt = Date.now() + opensInSec * 1000;
    const body = {
        name: `Load ${new Date().toISOString()}`, venue: 'Load Arena',
        startsAt: new Date(Date.now() + 30 * 86400000).toISOString(),
        saleOpensAt: new Date(opensAt).toISOString(), priceCents: 2500, rows, seatsPerRow: perRow,
    };
    const r = http.post(`${GW}/api/events`, JSON.stringify(body), auth(token));
    if (r.status !== 201) throw new Error(`setup: event creation failed: ${r.status} ${r.body}`);
    return { id: r.json('id'), opensAt };
}

/** Start barrier: every VU sleeps until the same instant, so the spike is real. */
export function waitUntil(epochMs) {
    const ms = epochMs - Date.now();
    if (ms > 0) sleep(ms / 1000);
}

export function seats(eventId, token) {
    const r = http.get(`${GW}/api/events/${eventId}/seats`, Object.assign(auth(token), { tags: { name: 'seats' } }));
    return r.status === 200 ? r.json() : [];
}

export function book(token, eventId, seatId, key) {
    return http.post(`${GW}/api/bookings`, JSON.stringify({ eventId, seatIds: [seatId] }),
        Object.assign(auth(token, { 'Idempotency-Key': key }), {
            tags: { name: 'book' },
            responseCallback: http.expectedStatuses(200, 201, 409, 429),
        }));
}

export function pay(token, bookingId, key) {
    return http.post(`${GW}/api/payments`, JSON.stringify({ bookingId }),
        Object.assign(auth(token, { 'Idempotency-Key': key }), {
            tags: { name: 'pay' },
            responseCallback: http.expectedStatuses(200, 202, 402, 409),
        }));
}

/** Polls GET url until the JSON status is one of `until` (or timeout); returns the last status or null. */
export function pollStatus(token, url, until, timeoutSec, name) {
    const deadline = Date.now() + timeoutSec * 1000;
    let status = null;
    while (Date.now() < deadline) {
        const r = http.get(url, Object.assign(auth(token), { tags: { name } }));
        if (r.status === 200) {
            status = r.json('status');
            if (until.includes(status)) return status;
        }
        sleep(0.25);
    }
    return status;
}

export function randomKey(prefix) {
    return `${prefix}-${__VU}-${__ITER}-${Date.now()}-${Math.floor(Math.random() * 1e9)}`;
}

/** handleSummary: JSON for the record (load/results) + the usual text summary on stdout. */
export function summary(name) {
    return (data) => {
        const ts = new Date().toISOString().replace(/[:.]/g, '-');
        const out = { stdout: textSummary(data, { indent: ' ', enableColors: false }) };
        const record = Object.assign({}, data);
        delete record.setup_data;   // holds the users' JWTs: never write them to disk
        out[`/scripts/results/${name}-${ts}.json`] = JSON.stringify(record, null, 2);
        out[`/scripts/results/${name}-latest.json`] = JSON.stringify(record, null, 2);
        return out;
    };
}
