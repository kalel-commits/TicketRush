/** Thin fetch wrapper for the gateway's public API (spec §23). Backend errors are RFC 9457 ProblemDetails with a `code`. */
export const API_BASE = import.meta.env.VITE_API_BASE ?? 'http://localhost:8080';

export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    readonly detail: string,
    readonly retryAfter: number,
    readonly body: Record<string, unknown>,
  ) {
    super(detail || code);
  }
}

let token: string | null = null;

export function setToken(value: string | null) {
  token = value;
}

export function getToken() {
  return token;
}

/** Fired when the gateway rejects our token (expired after 1 h): AuthProvider signs out, RequireAuth sends to /login. */
export const UNAUTHORIZED_EVENT = 'ticketrush:unauthorized';

export function sessionEnded() {
  token = null;
  window.dispatchEvent(new Event(UNAUTHORIZED_EVENT));
}

export interface ApiOptions {
  method?: string;
  body?: unknown;
  headers?: Record<string, string>;
  signal?: AbortSignal;
  /** An independent session (e.g. a demo buyer panel): used instead of the app's token; its 401 leaves the app signed in. */
  token?: string;
}

export async function api<T>(path: string, options: ApiOptions = {}): Promise<T> {
  const headers: Record<string, string> = { Accept: 'application/json', ...options.headers };
  if (options.body !== undefined) headers['Content-Type'] = 'application/json';
  const bearer = options.token ?? token;
  if (bearer) headers.Authorization = `Bearer ${bearer}`;
  const response = await fetch(`${API_BASE}${path}`, {
    method: options.method ?? 'GET',
    headers,
    body: options.body === undefined ? undefined : JSON.stringify(options.body),
    signal: options.signal,
  });
  const text = await response.text();
  const json = text ? safeJson(text) : {};
  if (response.status === 401 && headers.Authorization && options.token === undefined) sessionEnded();   // a bad login (no token) is just an error
  if (!response.ok) {
    const retryAfter = Number(response.headers.get('Retry-After')) || 5;
    throw new ApiError(response.status, String(json.code ?? statusCode(response.status)), String(json.detail ?? ''), retryAfter, json);
  }
  return json as T;
}

function safeJson(text: string): Record<string, unknown> {
  try {
    return JSON.parse(text);
  } catch {
    return {};
  }
}

function statusCode(status: number) {
  if (status === 429) return 'TOO_MANY_REQUESTS';
  if (status === 401) return 'UNAUTHORIZED';
  if (status >= 500) return 'DEPENDENCY_UNAVAILABLE';
  return 'UNKNOWN';
}
