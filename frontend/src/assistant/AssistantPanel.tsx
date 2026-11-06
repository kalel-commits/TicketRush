import { ChatCircleDots, PaperPlaneRight, X, BookOpen } from '@phosphor-icons/react';
import { useEffect, useRef, useState, type FormEvent } from 'react';
import { Link, useLocation } from 'react-router-dom';
import { API_BASE, getToken, sessionEnded } from '../api/client';
import { messageFor } from '../api/errors';
import { createSseParser } from './sse';

interface Source { title: string; section: string; source?: string }
interface Action { label: string; href: string }
interface Turn {
  role: 'user' | 'assistant';
  text: string;
  sources?: Source[];
  actions?: Action[];
  error?: string;
  complete?: boolean;   // the stream reached `done`: only complete exchanges go back as history
}

const HISTORY_MAX_CHARS = 2000;   // ChatRequest.HistoryItem: @NotBlank @Size(max = 2000)

/** Last 3 complete exchanges, each entry valid for the API: a failed or blank exchange would 400 every later question. */
function historyFrom(turns: Turn[]) {
  const pairs: { role: 'user' | 'assistant'; content: string }[][] = [];
  for (let i = 0; i + 1 < turns.length; i++) {
    const [q, a] = [turns[i], turns[i + 1]];
    if (q.role === 'user' && a.role === 'assistant' && a.complete && !a.error && q.text.trim() && a.text.trim()) {
      pairs.push([
        { role: 'user', content: q.text.slice(0, HISTORY_MAX_CHARS) },
        { role: 'assistant', content: a.text.slice(0, HISTORY_MAX_CHARS) },
      ]);
    }
  }
  return pairs.slice(-3).flat();
}

/** Page context for the assistant: the event or booking the user is looking at (spec §23.2). */
function contextFrom(pathname: string): { eventId?: number; bookingId?: string } | undefined {
  const event = /^\/events\/(\d+)/.exec(pathname);
  if (event) return { eventId: Number(event[1]) };
  const booking = /^\/bookings\/([0-9a-f-]{36})/i.exec(pathname);
  if (booking) return { bookingId: booking[1] };
  return undefined;
}

/** One chip per article; its sections go in the tooltip (three excerpts of one article are one source). */
function uniqueByTitle(sources: Source[]): { title: string; sections: string }[] {
  const byTitle = new Map<string, string[]>();
  for (const s of sources) byTitle.set(s.title, [...(byTitle.get(s.title) ?? []), s.section]);
  return [...byTitle].map(([title, sections]) => ({ title, sections: sections.join(' · ') }));
}

/** Suggested actions navigate only, and only inside this app (never act, never leave). */
const internal = (a: Action) => a.href.startsWith('/') && !a.href.startsWith('//');

export function AssistantPanel() {
  const location = useLocation();
  const [open, setOpen] = useState(false);
  const [turns, setTurns] = useState<Turn[]>([]);
  const [question, setQuestion] = useState('');
  const [busy, setBusy] = useState(false);
  const toggleRef = useRef<HTMLButtonElement>(null);
  const inputRef = useRef<HTMLTextAreaElement>(null);

  useEffect(() => { if (open) inputRef.current?.focus(); }, [open]);

  function close() {
    setOpen(false);
    toggleRef.current?.focus();   // focus returns to the control that opened the panel
  }

  function update(fn: (t: Turn) => Turn) {
    setTurns((all) => [...all.slice(0, -1), fn(all[all.length - 1])]);
  }

  async function send(e: FormEvent) {
    e.preventDefault();
    const message = question.trim();
    if (!message || busy) return;
    const history = historyFrom(turns);
    setTurns((all) => [...all, { role: 'user', text: message }, { role: 'assistant', text: '' }]);
    setQuestion('');
    setBusy(true);
    try {
      const headers: Record<string, string> = { 'Content-Type': 'application/json', Accept: 'text/event-stream' };
      const token = getToken();
      if (token) headers.Authorization = `Bearer ${token}`;
      const response = await fetch(`${API_BASE}/api/assistant/chat/stream`, {
        method: 'POST', headers, body: JSON.stringify({ message, history, context: contextFrom(location.pathname) }),
      });
      if (response.status === 401) {
        sessionEnded();
        update((t) => ({ ...t, error: messageFor('UNAUTHORIZED').message }));
        return;
      }
      if (response.status === 429) {
        update((t) => ({ ...t, error: `Too many questions — wait ${response.headers.get('Retry-After') ?? 'a few'} seconds and ask again.` }));
        return;
      }
      if (!response.ok || !response.body) {
        const problem = await response.json().catch(() => ({}));
        const excerpts: { title: string; section: string; text: string }[] = problem.fallbackExcerpts ?? [];
        update((t) => ({
          ...t,
          text: excerpts.map((x) => x.text).join('\n\n'),
          sources: excerpts.map((x) => ({ title: x.title, section: x.section })),
          error: messageFor(problem.code ?? 'ASSISTANT_UNAVAILABLE').message,
        }));
        return;
      }
      const parser = createSseParser();
      const reader = response.body.getReader();
      const decoder = new TextDecoder();
      let finished = false;   // saw `done` or `error`; a stream that just stops is an interrupted answer
      const handle = (chunk: string) => {
        for (const ev of parser.push(chunk)) {
          let data: Record<string, any>;   // eslint-disable-line @typescript-eslint/no-explicit-any
          try {
            data = ev.data ? JSON.parse(ev.data) : {};
          } catch {
            continue;   // one malformed event must not drop the rest of the answer
          }
          if (ev.event === 'done') {
            finished = true;
            update((t) => ({ ...t, complete: true }));
          }
          if (ev.event === 'delta') update((t) => ({ ...t, text: t.text + (typeof data.text === 'string' ? data.text : '') }));
          if (ev.event === 'meta') update((t) => ({ ...t, sources: data.sources, actions: (data.suggestedActions ?? []).filter(internal) }));
          if (ev.event === 'error') {
            finished = true;
            const excerpts: { title: string; section: string; text: string }[] = data.fallbackExcerpts ?? [];
            update((t) => ({
              ...t,
              text: t.text || excerpts.map((x) => x.text).join('\n\n'),
              sources: t.sources?.length ? t.sources : excerpts.map((x) => ({ title: x.title, section: x.section })),
              error: messageFor('ASSISTANT_UNAVAILABLE').message,
            }));
          }
        }
      };
      try {
        for (;;) {
          const { value, done } = await reader.read();
          if (done) break;
          handle(decoder.decode(value, { stream: true }));
        }
        handle(decoder.decode() + '\n\n');   // flush the decoder and any final event missing its blank line
      } finally {
        void reader.cancel().catch(() => {});
      }
      if (!finished) update((t) => ({ ...t, error: 'The answer was interrupted — ask again.' }));
    } catch {
      update((t) => ({ ...t, error: messageFor('ASSISTANT_UNAVAILABLE').message }));
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <button
        ref={toggleRef}
        type="button"
        onClick={() => setOpen((o) => !o)}
        aria-expanded={open}
        aria-controls="assistant-panel"
        className="fixed bottom-4 right-4 z-40 inline-flex min-h-12 cursor-pointer items-center gap-2 rounded-full bg-accent px-4 font-medium text-on-accent shadow-lg"
      >
        <ChatCircleDots aria-hidden size={22} /> {open ? 'Close assistant' : 'Open assistant'}
      </button>
      {open && (
        <div
          id="assistant-panel"
          role="dialog"
          aria-label="Assistant"
          onKeyDown={(e) => { if (e.key === 'Escape') close(); }}
          className="fixed inset-x-0 bottom-0 z-50 flex max-h-[80dvh] flex-col border-t border-border bg-card sm:inset-x-auto sm:right-4 sm:bottom-20 sm:w-[26rem] sm:rounded-lg sm:border"
        >
          <div className="flex items-center justify-between border-b border-border p-3">
            <h2 className="font-semibold">Assistant</h2>
            <button type="button" onClick={close} aria-label="Close assistant" className="inline-flex h-11 w-11 cursor-pointer items-center justify-center rounded-md hover:bg-muted">
              <X aria-hidden size={20} />
            </button>
          </div>
          <div className="flex-1 space-y-3 overflow-y-auto p-3" aria-live="polite">
            {turns.length === 0 && <p className="text-muted-fg">Ask about events, your bookings, payments or refunds.</p>}
            {turns.map((t, i) => (
              <div key={i} className={t.role === 'user' ? 'ml-8 rounded-md bg-muted p-2' : 'mr-4'}>
                {t.text && <p className="whitespace-pre-wrap break-words">{t.text}</p>}
                {t.error && <p role="alert" className="mt-1 text-sm text-danger">{t.error}</p>}
                {t.sources && t.sources.length > 0 && (
                  <ul aria-label="Sources" className="mt-2 flex flex-wrap gap-1">
                    {uniqueByTitle(t.sources).map((s) => (
                      <li key={s.title} title={s.sections} className="inline-flex items-center gap-1 rounded-full border border-border px-2 py-0.5 text-xs">
                        <BookOpen aria-hidden size={12} />{s.title}
                      </li>
                    ))}
                  </ul>
                )}
                {t.actions && t.actions.length > 0 && (
                  <div className="mt-2 flex flex-wrap gap-2">
                    {t.actions.map((a, j) => (
                      <Link key={j} to={a.href} className="rounded-md border border-accent px-2 py-1 text-sm text-accent hover:bg-muted">{a.label}</Link>
                    ))}
                  </div>
                )}
              </div>
            ))}
          </div>
          <form onSubmit={send} className="flex items-end gap-2 border-t border-border p-3">
            <div className="flex-1">
              <label htmlFor="assistant-question" className="sr-only">Ask a question</label>
              <textarea
                id="assistant-question" ref={inputRef} rows={2} maxLength={1000} value={question}
                onChange={(e) => setQuestion(e.target.value)}
                onKeyDown={(e) => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); void send(e); } }}
                className="w-full resize-none rounded-md border border-border bg-bg p-2 text-fg"
              />
              <p className="text-xs text-muted-fg">AI can make mistakes. Check important details on the booking page.</p>
            </div>
            <button type="submit" disabled={busy || !question.trim()} aria-label="Send"
                    className="inline-flex h-11 w-11 cursor-pointer items-center justify-center rounded-md bg-accent text-on-accent disabled:opacity-50">
              <PaperPlaneRight aria-hidden size={20} />
            </button>
          </form>
        </div>
      )}
    </>
  );
}
