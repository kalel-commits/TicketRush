import type { Snapshot } from './types';

/** Calls the dev-only demo harness on the Vite dev server (same origin). The custom header is its CSRF guard. */
async function harness<T>(path: string, init: RequestInit = {}): Promise<T> {
  const r = await fetch(`/__demo${path}`, { ...init, headers: { 'x-demo-harness': '1', 'Content-Type': 'application/json', ...init.headers } });
  const body = await r.json().catch(() => ({}));
  if (!r.ok) throw new Error(body.message ?? body.code ?? `Demo harness answered ${r.status}`);
  return body as T;
}

export interface DemoBuyer { label: string; userId: number; token: string }
export interface RaceSetup { eventId: number; buyers: DemoBuyer[] }
export type ProviderMode = 'succeed' | 'decline' | 'ambiguous' | 'down' | 'custom';

export const setupRace = () => harness<RaceSetup>('/race/setup', { method: 'POST', body: '{}' });
export const observe = (eventId: number) => harness<Snapshot>(`/observe?eventId=${eventId}`);
export const getProvider = () => harness<{ mode: ProviderMode }>('/provider');
export const setProvider = (mode: ProviderMode) => harness<{ mode: ProviderMode }>('/provider', { method: 'POST', body: JSON.stringify({ mode }) });

/** POST /__demo/load streams `progress` events and one `result` (or `error`) as SSE. */
export async function startLoad(params: { buyers: number; seats: number; pay: boolean }): Promise<Response> {
  const r = await fetch('/__demo/load', {
    method: 'POST', headers: { 'x-demo-harness': '1', 'Content-Type': 'application/json' }, body: JSON.stringify(params),
  });
  if (!r.ok || !r.body) {
    const body = await r.json().catch(() => ({}));
    throw new Error(body.code === 'LOAD_TEST_RUNNING' ? 'A load test is already running.' : body.message ?? `Demo harness answered ${r.status}`);
  }
  return r;
}
