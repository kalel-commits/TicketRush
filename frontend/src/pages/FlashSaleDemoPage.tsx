import { Broadcast, Flag, Gauge, Warning } from '@phosphor-icons/react';
import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { Button, Card } from '../components/ui';
import { BuyerPanel, MechanismTimeline, ObserverPanel, SeatLegend } from '../demo/components';
import { getProvider, observe, setProvider, setupRace, startLoad, type ProviderMode, type RaceSetup } from '../demo/harnessClient';
import { createSseParser } from '../assistant/sse';
import { useBuyer } from '../demo/useBuyer';
import type { Summary } from '../demo/stats';

const PRICE_CENTS = 2500;

/**
 * Live flash-sale demo (plan 9). Everything shown is read from the real stack: buyer panels call the gateway with
 * their own JWTs; the observer reads Redis/MySQL/Kafka through the dev-only harness. Updates are polled, not pushed.
 */
export function FlashSaleDemoPage() {
  const [race, setRace] = useState<RaceSetup | null>(null);
  const [setupError, setSetupError] = useState<string | null>(null);
  const [starting, setStarting] = useState(false);
  const [selA, setSelA] = useState<number | null>(null);
  const [selB, setSelB] = useState<number | null>(null);
  const a = useBuyer(race?.buyers[0], race?.eventId);
  const b = useBuyer(race?.buyers[1], race?.eventId);

  const snap = useQuery({
    queryKey: ['demo-observe', race?.eventId],
    queryFn: () => observe(race!.eventId),
    enabled: !!race,
    refetchInterval: 1000,
    refetchIntervalInBackground: false,
  });

  async function newRace() {
    setStarting(true);
    setSetupError(null);
    try {
      a.reset(); b.reset(); setSelA(null); setSelB(null);
      setRace(await setupRace());
    } catch (e) {
      setSetupError((e as Error).message);
    } finally {
      setStarting(false);
    }
  }

  const target = a.seats[0];   // seat A1 of this race's event
  async function raceBoth() {
    if (!target) return;
    setSelA(target.id);
    setSelB(target.id);
    await Promise.all([a.book(target.id), b.book(target.id)]);   // both fetches start in the same tick
  }

  const attempts = [a.attempt, b.attempt].filter((x): x is NonNullable<typeof x> => !!x);

  return (
    <div className="flex flex-col gap-6">
      <div className="flex flex-col gap-2">
        <h1 className="text-2xl font-semibold">Flash-sale engineering demo</h1>
        <p className="max-w-3xl text-muted-fg">
          Two real buyer accounts race for the same seat through the real gateway and services. The panels below show
          what each buyer sees, and the observer shows why the backend decided the way it did.
        </p>
        <p className="flex flex-wrap items-center gap-2 text-sm">
          <span className="inline-flex items-center gap-1.5 rounded-full border border-accent/50 px-2.5 py-0.5 text-accent">
            <Broadcast aria-hidden />Live data from the real stack · polled every 1 s (no push channel)
          </span>
          {race && <span className="num text-muted-fg">event #{race.eventId}</span>}
        </p>
      </div>

      <Card className="flex flex-col gap-3">
        <div className="flex flex-wrap items-center gap-3">
          <Button onClick={newRace} busy={starting}><Flag aria-hidden />Start a new race</Button>
          <Button variant="secondary" disabled={!race || !target || a.phase === 'booking' || b.phase === 'booking'} onClick={raceBoth}
                  aria-label={`Race both buyers for ${target?.seatNo ?? 'the first seat'}`}>
            Race both buyers for {target?.seatNo ?? 'A1'}
          </Button>
          <SeatLegend />
        </div>
        <p className="text-xs text-muted-fg">
          "Start a new race" creates a fresh 16-seat event and signs in two demo buyers. "Race" sends both holds in the
          same JavaScript tick, each with its own JWT and Idempotency-Key. You can also pick seats and book from each panel.
        </p>
        {setupError && <p role="alert" className="text-danger">Could not start: {setupError}. Is the stack up and is this the dev server (npm run dev)?</p>}
      </Card>

      {race && (
        <div className="grid gap-4 lg:grid-cols-3">
          <BuyerPanel label="A" tone="bg-accent" buyer={a} snapshot={snap.data} selected={selA} onSelect={setSelA} priceCents={PRICE_CENTS} />
          <BuyerPanel label="B" tone="bg-info" buyer={b} snapshot={snap.data} selected={selB} onSelect={setSelB} priceCents={PRICE_CENTS} />
          <ObserverPanel snapshot={snap.data} updatedAt={snap.dataUpdatedAt} error={snap.error} />
        </div>
      )}

      <MechanismTimeline attempts={attempts} snapshot={snap.data} />
      <PaymentScenarios />
      <LoadTestPanel />
    </div>
  );
}

/* ------------------------------------------------------------------ payment scenarios */

const MODES: { mode: ProviderMode; title: string; what: string }[] = [
  { mode: 'succeed', title: 'Succeeds', what: 'Charge SUCCEEDED → payment.succeeded via outbox/Kafka → booking CONFIRMED → seat BOOKED.' },
  { mode: 'decline', title: 'Declined', what: 'Provider declines → 402 DECLINED, payment FAILED, booking stays PENDING_PAYMENT; a retry is a new attempt.' },
  { mode: 'ambiguous', title: 'Times out after charging', what: 'Money taken but the answer arrives after payment-service gives up → PROCESSING. The reconciler (every 30 s) looks the charge up and settles it: confirm, or refund if the hold is gone.' },
  { mode: 'down', title: 'Provider down', what: 'Provider answers 503 → payment FAILED, nothing charged, hold kept; retry later with a new key.' },
];

function PaymentScenarios() {
  const current = useQuery({ queryKey: ['demo-provider'], queryFn: getProvider, refetchInterval: 5000 });
  const [busy, setBusy] = useState<ProviderMode | null>(null);
  const [error, setError] = useState<string | null>(null);
  async function choose(mode: ProviderMode) {
    setBusy(mode);
    setError(null);
    try {
      await setProvider(mode);
      await current.refetch();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(null);
    }
  }
  return (
    <section aria-label="Payment scenarios" className="rounded-lg border border-border bg-card p-4">
      <h2 className="mb-1 text-lg font-semibold">Payment scenarios</h2>
      <p className="mb-3 text-sm text-muted-fg">
        Sets the fake payment provider's behaviour (a test double on the internal network). Then win a seat with Buyer A
        and press Pay: the states shown are the real payment and booking rows. Current mode:{' '}
        <strong className="text-fg">{current.data?.mode ?? '…'}</strong>
      </p>
      <div className="grid gap-2 md:grid-cols-2">
        {MODES.map((m) => (
          <button key={m.mode} type="button" aria-pressed={current.data?.mode === m.mode} onClick={() => choose(m.mode)} disabled={busy !== null}
                  className={`cursor-pointer rounded-md border p-3 text-left transition-colors duration-200 hover:bg-muted ${current.data?.mode === m.mode ? 'border-accent' : 'border-border'}`}>
            <span className="font-medium">{busy === m.mode ? 'Switching…' : m.title}</span>
            <span className="mt-1 block text-sm text-muted-fg">{m.what}</span>
          </button>
        ))}
      </div>
      {error && <p role="alert" className="mt-2 text-danger">{error}</p>}
      <p className="mt-2 text-xs text-muted-fg">Switch back to "Succeeds" when you are done; the mode stays until changed or the provider restarts.</p>
    </section>
  );
}

/* ------------------------------------------------------------------ load test */

interface LoadResult {
  eventId: number;
  config: { buyers: number; seats: number; pay: boolean };
  race: Summary & { wallMs: number; codes: Record<string, number> };
  evidence: { activeBookings: number; seatsTaken: number; mysqlRejected: number; redisRejected: number; otherFailures: number; mismatches: string[]; duplicateSeats: number };
  payments: { sent: number; httpCodes: Record<string, number>; confirmed: number; failed: number; processing: number; byStatus: Record<string, number>; settled: boolean } | null;
  invariants: { pass: boolean; violations: string[] };
}

function Kpi({ id, label, value, tone = '' }: { id: string; label: string; value: string | number; tone?: string }) {
  return (
    <div className="rounded-md border border-border bg-bg/60 p-3">
      <div className="text-xs text-muted-fg">{label}</div>
      <div data-testid={`kpi-${id}`} className={`num text-2xl font-semibold ${tone}`}>{value}</div>
    </div>
  );
}

function LoadTestPanel() {
  const [buyers, setBuyers] = useState(100);
  const [seats, setSeats] = useState(20);
  const [pay, setPay] = useState(false);
  const [running, setRunning] = useState(false);
  const [progress, setProgress] = useState<string>('');
  const [result, setResult] = useState<LoadResult | null>(null);
  const [error, setError] = useState<string | null>(null);

  async function run() {
    setRunning(true); setResult(null); setError(null); setProgress('Starting…');
    try {
      const r = await startLoad({ buyers, seats, pay });
      const parser = createSseParser();
      const reader = r.body!.getReader();
      const decoder = new TextDecoder();
      for (;;) {
        const { value, done } = await reader.read();
        if (done) break;
        for (const ev of parser.push(decoder.decode(value, { stream: true }))) {
          let data: Record<string, unknown> = {};
          try { data = JSON.parse(ev.data); } catch { continue; }
          if (ev.event === 'progress') {
            const d = data as { message?: string; done?: number; total?: number; byStatus?: Record<string, number>; confirmed?: number };
            setProgress(d.message ?? (d.total ? `Signed in ${d.done} / ${d.total} buyers` : d.byStatus ? `Payments: ${JSON.stringify(d.byStatus)} · confirmed ${d.confirmed}` : ''));
          }
          if (ev.event === 'result') setResult(data as unknown as LoadResult);
          if (ev.event === 'error') setError(String(data.message ?? 'Load test failed'));
        }
      }
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setRunning(false);
    }
  }

  const won = result?.race.byStatus[201] ?? 0;
  const rejected = result?.race.codes.SEATS_UNAVAILABLE ?? 0;
  const other = result ? result.race.total - won - rejected : 0;
  return (
    <section aria-label="Flash-sale load test" className="rounded-lg border border-border bg-card p-4">
      <h2 className="mb-1 flex items-center gap-2 text-lg font-semibold"><Gauge aria-hidden />Flash-sale load test</h2>
      <p className="mb-3 flex items-start gap-2 rounded-md border border-warn/60 bg-warn/10 p-2 text-sm">
        <Warning aria-hidden className="mt-0.5 shrink-0 text-warn" />
        This creates real demo bookings (and real fake-provider charges if "pay" is on) in a dedicated, newly created
        test event. Use a demo database, not one with data you care about.
      </p>
      <div className="flex flex-wrap items-end gap-3">
        <label className="flex flex-col text-sm">Buyers<input type="number" min={2} max={200} value={buyers} onChange={(e) => setBuyers(Number(e.target.value))}
          className="num mt-1 w-28 rounded-md border border-border bg-bg p-2" /></label>
        <label className="flex flex-col text-sm">Seats<input type="number" min={1} max={50} value={seats} onChange={(e) => setSeats(Number(e.target.value))}
          className="num mt-1 w-28 rounded-md border border-border bg-bg p-2" /></label>
        <label className="flex min-h-11 items-center gap-2 text-sm"><input type="checkbox" checked={pay} onChange={(e) => setPay(e.target.checked)} className="h-5 w-5" />
          Also pay for every winner (uses the current provider mode)</label>
        <Button onClick={run} busy={running}>Run load test</Button>
      </div>
      <p className="mt-2 text-xs text-muted-fg">
        Runs in the dev harness, not in this tab: {buyers} separate buyer accounts, each with its own JWT, send their hold
        at the same moment (buyer i → seat i mod {seats}, so every seat is contested). Correctness is then checked in SQL.
      </p>
      {running && <p aria-live="polite" className="mt-3 flex items-center gap-2 text-sm"><span className="h-2 w-2 animate-pulse rounded-full bg-accent" />{progress}</p>}
      {error && <p role="alert" className="mt-3 text-danger">{error}</p>}
      {result && (
        <div className="mt-4 flex flex-col gap-3">
          <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">
            <Kpi id="total" label="Total booking requests" value={result.race.total} />
            <Kpi id="won" label={`Seat holds won (max ${result.config.seats})`} value={won} tone="text-accent" />
            <Kpi id="rejected" label="Rejected: seat taken (409)" value={rejected} />
            <Kpi id="duplicates" label="Seats double-booked (SQL)" value={result.evidence.duplicateSeats} tone={result.evidence.duplicateSeats === 0 ? 'text-accent' : 'text-danger'} />
            <Kpi id="prevented" label="Duplicate bookings prevented" value={rejected} />
            <Kpi id="p95" label="Latency p50 / p95 / p99" value={`${result.race.p50} / ${result.race.p95} / ${result.race.p99} ms`} />
            <Kpi id="wall" label="Time for all requests" value={`${result.race.wallMs} ms`} />
            <Kpi id="other" label="Other responses" value={other} tone={other ? 'text-warn' : ''} />
          </div>
          <p className="text-sm text-muted-fg">
            Who stopped each loser (its own booking row, by Idempotency-Key): <strong className="text-fg">{result.evidence.redisRejected}</strong> at
            the Redis filter (no row written) · <strong className="text-fg">{result.evidence.mysqlRejected}</strong> by the MySQL conditional UPDATE
            (row FAILED / SEATS_UNAVAILABLE).{' '}
            {result.evidence.activeBookings === result.evidence.seatsTaken
              ? <>Active bookings <span className="num">{result.evidence.activeBookings}</span> = seats taken in event_db <span className="num">{result.evidence.seatsTaken}</span>.</>
              : <strong className="text-danger">Mismatch: {result.evidence.activeBookings} active bookings vs {result.evidence.seatsTaken} seats taken in event_db.</strong>}
            {other > 0 && <> Other codes: {Object.entries(result.race.codes).filter(([c]) => c !== 'SEATS_UNAVAILABLE').map(([c, n]) => `${c} ${n}`).join(', ')}.</>}
          </p>
          {result.evidence.mismatches.length > 0 && (
            <ul role="alert" className="num text-sm text-danger">{result.evidence.mismatches.slice(0, 5).map((m) => <li key={m}>{m}</li>)}</ul>
          )}
          {result.payments && (
            <p className="text-sm text-muted-fg">
              Payment answers: {Object.entries(result.payments.httpCodes).map(([k, n]) => `${k} × ${n}`).join(' · ')}
              {!result.payments.settled && <strong className="text-warn"> · not settled within 75 s: counts below are a snapshot, not final</strong>}
            </p>
          )}
          {result.payments && (
            <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">
              <Kpi id="paid" label="Payments sent" value={result.payments.sent} />
              <Kpi id="confirmed" label="Bookings confirmed" value={result.payments.confirmed} tone="text-accent" />
              <Kpi id="failed" label="Failed payments" value={result.payments.failed} />
              <Kpi id="processing" label="Still processing" value={result.payments.processing} />
            </div>
          )}
          <p className={result.invariants.pass ? 'text-accent' : 'text-danger'}>
            Invariants (spec §16.4, run in SQL for event #{result.eventId}): {result.invariants.pass ? 'all pass' : 'FAILED'}
          </p>
          {!result.invariants.pass && <ul className="num text-sm text-danger">{result.invariants.violations.map((v) => <li key={v}>{v}</li>)}</ul>}
        </div>
      )}
    </section>
  );
}
