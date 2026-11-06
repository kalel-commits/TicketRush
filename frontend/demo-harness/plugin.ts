import type { IncomingMessage, ServerResponse } from 'node:http';
import type { Plugin } from 'vite';
import { providerBehaviour } from './docker';
import { buyer, call, createEvent, GatewayError, seats } from './gateway';
import { runLoad } from './loadtest';
import { snapshot, startSlowReaders } from './observe';

/**
 * Dev-only demo harness (plan 9), mounted on the Vite dev server at /__demo/*. It never runs in a production build.
 * Secrets (.env) stay in this process; responses carry sanitized data, plus each demo buyer's OWN JWT so the browser
 * panels act as independent authenticated sessions against the real gateway.
 */
export const PROVIDER_MODES: Record<string, Record<string, unknown>> = {
  succeed: { declineRate: 0, timeoutAfterChargeRate: 0, latencyMs: 0, down: false },
  decline: { declineRate: 1, timeoutAfterChargeRate: 0, latencyMs: 0, down: false },
  ambiguous: { declineRate: 0, timeoutAfterChargeRate: 1, timeoutSleepMs: 5000, latencyMs: 0, down: false },
  down: { declineRate: 0, timeoutAfterChargeRate: 0, latencyMs: 0, down: true },
};

function modeOf(b: Record<string, unknown>): string {
  if (b.down) return 'down';
  if (Number(b.timeoutAfterChargeRate) >= 1) return 'ambiguous';
  if (Number(b.declineRate) >= 1) return 'decline';
  if (!b.declineRate && !b.timeoutAfterChargeRate) return 'succeed';
  return 'custom';
}

let loadRunning = false;
let warmed: Promise<void> | null = null;

/**
 * Once per harness process: a few real holds on a throwaway event, then cancelled. After a restart the first Redis
 * call can exceed booking-service's 200 ms command timeout, and the lock filter then (correctly) fails open; the
 * demo should not start on that cold path by accident. Failures here are ignored: it is only a warm-up.
 */
function warmUp(): Promise<void> {
  warmed ??= (async () => {
    try {
      const { id } = await createEvent('Demo warm-up (safe to ignore)', 1, 4);
      const sessions = await Promise.all([0, 1, 2, 3].map((i) => buyer(`warmup-${i}@demo.ticketrush.local`)));
      const seatIds = (await seats(id, sessions[0].token)).map((x) => x.id);
      await Promise.all(sessions.map(async (s, i) => {
        const r = await call<{ id?: string }>('/api/bookings', { method: 'POST', token: s.token,
          headers: { 'Idempotency-Key': `warmup-${Date.now()}-${i}` }, body: { eventId: id, seatIds: [seatIds[i]] } });
        if (r.body.id) await call(`/api/bookings/${r.body.id}/cancel`, { method: 'POST', token: s.token });
      }));
    } catch {
      // best effort
    }
  })();
  return warmed;
}

async function readJson(req: IncomingMessage): Promise<Record<string, unknown>> {
  let raw = '';
  for await (const chunk of req) {
    raw += chunk;
    if (raw.length > 10_000) throw new Error('body too large');
  }
  return raw ? JSON.parse(raw) : {};
}

function send(res: ServerResponse, status: number, body: unknown) {
  res.statusCode = status;
  res.setHeader('Content-Type', 'application/json');
  res.setHeader('Cache-Control', 'no-store');
  res.end(JSON.stringify(body));
}

/** Same-origin only: a custom header forces a CORS preflight that a foreign page cannot pass. */
function allowed(req: IncomingMessage): boolean {
  const host = req.headers.host ?? '';
  const origin = req.headers.origin;
  return req.headers['x-demo-harness'] === '1' && /^(localhost|127\.0\.0\.1)(:\d+)?$/.test(host)
    && (!origin || /^http:\/\/(localhost|127\.0\.0\.1)(:\d+)?$/.test(origin));
}

export function demoHarness(): Plugin {
  return {
    name: 'ticketrush-demo-harness',
    apply: 'serve',
    configureServer(server) {
      server.middlewares.use('/__demo', async (req, res) => {
        if (!allowed(req)) return send(res, 403, { code: 'FORBIDDEN' });
        const url = new URL(req.url ?? '/', 'http://localhost');
        try {
          if (req.method === 'POST' && url.pathname === '/race/setup') {
            await warmUp();
            const { id: eventId } = await createEvent(`Flash sale · race ${new Date().toISOString().slice(11, 19)}`, 2, 8);
            const [a, b] = await Promise.all([buyer('buyer-a@demo.ticketrush.local'), buyer('buyer-b@demo.ticketrush.local')]);
            startSlowReaders();
            return send(res, 200, {
              eventId,
              buyers: [{ label: 'A', userId: a.userId, token: a.token }, { label: 'B', userId: b.userId, token: b.token }],
            });
          }
          if (req.method === 'GET' && url.pathname === '/observe') {
            return send(res, 200, await snapshot(Number(url.searchParams.get('eventId'))));
          }
          if (url.pathname === '/provider') {
            if (req.method === 'POST') {
              const { mode } = await readJson(req);
              if (!Object.hasOwn(PROVIDER_MODES, String(mode))) return send(res, 400, { code: 'UNKNOWN_MODE' });
              const body = PROVIDER_MODES[String(mode)];
              const now = await providerBehaviour(body);
              return send(res, 200, { mode: modeOf(now), behaviour: now });
            }
            const now = await providerBehaviour();
            return send(res, 200, { mode: modeOf(now), behaviour: now });
          }
          if (req.method === 'POST' && url.pathname === '/load') {
            if (loadRunning) return send(res, 409, { code: 'LOAD_TEST_RUNNING' });
            loadRunning = true;   // set before any await: two POSTs can never both start a run
            let params: Record<string, unknown>;
            try {
              params = await readJson(req);
            } catch {
              loadRunning = false;
              return send(res, 400, { code: 'BAD_BODY' });
            }
            res.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-store', Connection: 'keep-alive' });
            const emit = (event: string, data: unknown) => res.write(`event:${event}\ndata:${JSON.stringify(data)}\n\n`);
            try {
              const result = await runLoad({ buyers: Number(params.buyers), seats: Number(params.seats), pay: !!params.pay },
                (phase, detail) => emit('progress', { phase, ...detail }));
              emit('result', result);
            } catch (e) {
              emit('error', { message: e instanceof GatewayError ? `Gateway answered ${e.status} ${e.code}` : (e as Error).message });
            } finally {
              loadRunning = false;
              res.end();
            }
            return;
          }
          return send(res, 404, { code: 'NOT_FOUND' });
        } catch (e) {
          const message = e instanceof GatewayError ? `Gateway answered ${e.status} ${e.code}` : (e as Error).message;
          if (!res.headersSent) send(res, 502, { code: 'HARNESS_ERROR', message });
        }
      });
    },
  };
}
