import { afterEach, describe, expect, it, vi } from 'vitest';
import { api, ApiError, getToken, setToken, UNAUTHORIZED_EVENT } from '../api/client';
import { messageFor } from '../api/errors';

function problem(status: number, code: string, headers: Record<string, string> = {}) {
  return new Response(JSON.stringify({ status, code, detail: 'x' }), {
    status,
    headers: { 'Content-Type': 'application/problem+json', ...headers },
  });
}

afterEach(() => {
  vi.restoreAllMocks();
  setToken(null);
});

describe('api client', () => {
  it('turns a ProblemDetail into an ApiError carrying the code', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(problem(409, 'SEATS_UNAVAILABLE'));
    const error = (await api('/api/bookings', { method: 'POST' }).catch((e) => e)) as ApiError;
    expect(error).toBeInstanceOf(ApiError);
    expect(error.status).toBe(409);
    expect(error.code).toBe('SEATS_UNAVAILABLE');
  });

  it('reads Retry-After on 429, defaulting to 5 seconds', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce(problem(429, 'TOO_MANY_REQUESTS', { 'Retry-After': '7' }));
    expect(((await api('/x').catch((e) => e)) as ApiError).retryAfter).toBe(7);
    vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce(new Response('', { status: 429 }));
    expect(((await api('/x').catch((e) => e)) as ApiError).retryAfter).toBe(5);
  });

  it('sends the bearer token when signed in, and JSON bodies', async () => {
    const fetchSpy = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response('{"ok":true}', { status: 200 }));
    setToken('abc');
    expect(await api<{ ok: boolean }>('/api/users/me', { method: 'POST', body: { a: 1 } })).toEqual({ ok: true });
    const init = fetchSpy.mock.calls[0][1] as RequestInit;
    expect((init.headers as Record<string, string>).Authorization).toBe('Bearer abc');
    expect(init.body).toBe('{"a":1}');
  });
});

describe('error copy (spec §23.2)', () => {
  it('maps known codes to a message and a recovery action', () => {
    expect(messageFor('SEATS_UNAVAILABLE').message).toBe('Someone got those seats first — the map has refreshed, pick again.');
    for (const code of ['SEAT_LIMIT_EXCEEDED', 'PAYMENT_IN_PROGRESS_OR_DONE', 'DECLINED', 'SALE_NOT_OPEN', 'DEPENDENCY_UNAVAILABLE', 'ASSISTANT_UNAVAILABLE']) {
      expect(messageFor(code).message.length).toBeGreaterThan(10);
      expect(messageFor(code).action.length).toBeGreaterThan(2);
    }
  });

  it('falls back for unknown codes', () => {
    expect(messageFor('WHATEVER').message).toBe('Something went wrong.');
  });

  it('a 401 clears the token and tells the app the session ended', async () => {
    setToken('expired');
    const ended = vi.fn();
    window.addEventListener(UNAUTHORIZED_EVENT, ended);
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response('', { status: 401 }));
    await expect(api('/api/bookings/me')).rejects.toMatchObject({ status: 401 });
    expect(getToken()).toBeNull();
    expect(ended).toHaveBeenCalledTimes(1);
    window.removeEventListener(UNAUTHORIZED_EVENT, ended);
  });

  it('a per-call token is an independent session: used instead of the app token, and its 401 does not sign the app out', async () => {
    setToken('app-token');
    const ended = vi.fn();
    window.addEventListener(UNAUTHORIZED_EVENT, ended);
    const fetchSpy = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response('', { status: 401 }));
    await expect(api('/api/bookings/me', { token: 'buyer-b-token' })).rejects.toMatchObject({ status: 401 });
    expect((fetchSpy.mock.calls[0][1] as RequestInit).headers).toMatchObject({ Authorization: 'Bearer buyer-b-token' });
    expect(getToken()).toBe('app-token');
    expect(ended).not.toHaveBeenCalled();
    window.removeEventListener(UNAUTHORIZED_EVENT, ended);
  });
});
