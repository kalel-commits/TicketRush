import { buyerPassword, GATEWAY, secret } from './env';

/** Calls the PUBLIC API through the gateway: JWT checks and rate limits apply exactly as for real clients. */
export interface Session { email: string; userId: number; token: string; expiresAt: number }

export class GatewayError extends Error {
  constructor(readonly status: number, readonly code: string) {
    super(`gateway ${status} ${code}`);
  }
}

async function call<T>(path: string, init: { method?: string; token?: string; body?: unknown; headers?: Record<string, string> } = {}): Promise<{ status: number; body: T }> {
  const headers: Record<string, string> = { Accept: 'application/json', ...init.headers };
  if (init.body !== undefined) headers['Content-Type'] = 'application/json';
  if (init.token) headers.Authorization = `Bearer ${init.token}`;
  const r = await fetch(`${GATEWAY}${path}`, { method: init.method ?? 'GET', headers, body: init.body === undefined ? undefined : JSON.stringify(init.body) });
  const text = await r.text();
  let body: unknown = {};
  try { body = text ? JSON.parse(text) : {}; } catch { body = {}; }
  return { status: r.status, body: body as T };
}

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

/** The JWT's `sub` is the user id (UserTokenIssuer); `exp` bounds how long we reuse it. Never logged. */
function claims(token: string): { sub: string; exp: number } {
  return JSON.parse(Buffer.from(token.split('.')[1], 'base64url').toString('utf8'));
}

async function login(email: string, password: string): Promise<Session> {
  for (let attempt = 0; ; attempt++) {
    const r = await call<{ accessToken?: string; code?: string }>('/api/users/login', { method: 'POST', body: { email, password } });
    if (r.status === 200 && r.body.accessToken) {
      const c = claims(r.body.accessToken);
      return { email, userId: Number(c.sub), token: r.body.accessToken, expiresAt: c.exp * 1000 };
    }
    if (r.status !== 429 && r.status < 500 || attempt >= 8) throw new GatewayError(r.status, String(r.body.code ?? 'LOGIN_FAILED'));
    await sleep(1000 * (attempt + 1));   // rate limited or a cold service: back off, the gateway limit stays in force
  }
}

const sessions = new Map<string, Session>();

/** A demo buyer account (created once through POST /api/users/register) with a fresh-enough token. */
export async function buyer(email: string): Promise<Session> {
  const cached = sessions.get(email);
  if (cached && cached.expiresAt - Date.now() > 10 * 60_000) return cached;
  const password = buyerPassword();
  let session: Session;
  try {
    session = await login(email, password);
  } catch (e) {
    if (!(e instanceof GatewayError) || e.status !== 401) throw e;
    const reg = await call<{ code?: string }>('/api/users/register', { method: 'POST', body: { email, password } });
    if (reg.status !== 201) throw new GatewayError(reg.status, String(reg.body.code ?? 'REGISTER_FAILED'));
    session = await login(email, password);
  }
  sessions.set(email, session);
  return session;
}

/** Many buyers, at most `perSecond` new logins/registrations per second: /api/users is rate-limited per client. */
export async function buyers(emails: string[], perSecond: number, onProgress?: (done: number) => void): Promise<Session[]> {
  const out: Session[] = [];
  for (let i = 0; i < emails.length; i += perSecond) {
    const batch = emails.slice(i, i + perSecond);
    const fresh = batch.some((e) => !sessions.get(e) || sessions.get(e)!.expiresAt - Date.now() < 10 * 60_000);
    out.push(...await Promise.all(batch.map(buyer)));
    onProgress?.(out.length);
    if (fresh && i + perSecond < emails.length) await sleep(1000);
  }
  return out;
}

let admin: Session | null = null;

async function adminToken(): Promise<string> {
  if (!admin || admin.expiresAt - Date.now() < 5 * 60_000) admin = await login(secret('ADMIN_EMAIL'), secret('ADMIN_PASSWORD'));
  return admin.token;
}

/** A dedicated demo event, sale already open (backdated: the Docker VM clock can lag the host). */
export async function createEvent(name: string, rows: number, seatsPerRow: number): Promise<{ id: number }> {
  const r = await call<{ id: number; code?: string }>('/api/events', {
    method: 'POST', token: await adminToken(),
    body: {
      name, venue: 'Demo Arena', priceCents: 2500, rows, seatsPerRow,
      startsAt: new Date(Date.now() + 30 * 86_400_000).toISOString(),
      saleOpensAt: new Date(Date.now() - 10 * 60_000).toISOString(),
    },
  });
  if (r.status !== 201) throw new GatewayError(r.status, String(r.body.code ?? 'CREATE_EVENT_FAILED'));
  return { id: r.body.id };
}

export async function seats(eventId: number, token: string): Promise<{ id: number; seatNo: string; status: string }[]> {
  const r = await call<{ id: number; seatNo: string; status: string }[]>(`/api/events/${eventId}/seats`, { token });
  if (r.status !== 200) throw new GatewayError(r.status, 'SEATS_FAILED');
  return r.body;
}

export interface Timed<T> { status: number; ms: number; body: T; sentAt: number }

export async function timed<T>(path: string, init: Parameters<typeof call>[1]): Promise<Timed<T>> {
  const sentAt = Date.now();
  const t0 = performance.now();
  try {
    const r = await call<T>(path, init);
    return { status: r.status, ms: Math.round(performance.now() - t0), body: r.body, sentAt };
  } catch {
    return { status: 0, ms: Math.round(performance.now() - t0), body: {} as T, sentAt };   // network error
  }
}

export { call };
