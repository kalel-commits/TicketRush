import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AssistantPanel } from '../assistant/AssistantPanel';

function sse(body: string, status = 200) {
  const bytes = new TextEncoder().encode(body);
  const stream = new ReadableStream({
    start(controller) {
      controller.enqueue(bytes.slice(0, 25));   // split mid-event, like the network does
      controller.enqueue(bytes.slice(25));
      controller.close();
    },
  });
  return new Response(stream, { status, headers: { 'Content-Type': 'text/event-stream' } });
}

const meta = (extra = '') =>
  `event:meta\ndata:{"sources":[{"title":"Booking policy","section":"How many seats can I book?","source":"booking-policy.md"}],` +
  `"suggestedActions":[{"label":"Open booking","href":"/bookings/b1"},{"label":"Evil","href":"https://evil.example"}],"grounded":true${extra}}\n\n`;

async function ask(question: string, path = '/events/7') {
  render(<MemoryRouter initialEntries={[path]}><AssistantPanel /></MemoryRouter>);
  await userEvent.click(screen.getByRole('button', { name: /open assistant/i }));
  await userEvent.type(screen.getByLabelText(/ask a question/i), question);
  await userEvent.click(screen.getByRole('button', { name: /^send$/i }));
}

afterEach(() => vi.restoreAllMocks());

describe('assistant panel', () => {
  it('streams the answer, then shows source chips and only internal suggested links', async () => {
    const fetchSpy = vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      sse('event:delta\ndata:{"text":"You can book "}\n\nevent:delta\ndata:{"text":"4 seats."}\n\n' + meta() + 'event:done\ndata:{}\n\n'));

    await ask('How many seats can I book?');

    expect(await screen.findByText('You can book 4 seats.')).toBeInTheDocument();
    expect(screen.getByText('Booking policy')).toBeInTheDocument();
    const panel = screen.getByRole('dialog', { name: /assistant/i });
    const links = within(panel).getAllByRole('link');
    expect(links.map((l) => l.getAttribute('href'))).toEqual(['/bookings/b1']);   // external href dropped
    const body = JSON.parse(String((fetchSpy.mock.calls[0][1] as RequestInit).body));
    expect(body.context).toEqual({ eventId: 7 });
  });

  it('renders model text as text: no links or markup from the answer', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      sse('event:delta\ndata:{"text":"<a href=\\"javascript:alert(1)\\">click</a> [x](https://evil)"}\n\nevent:meta\ndata:{"sources":[],"suggestedActions":[],"grounded":false}\n\nevent:done\ndata:{}\n\n'));
    await ask('hi');
    expect(await screen.findByText('<a href="javascript:alert(1)">click</a> [x](https://evil)')).toBeInTheDocument();
    expect(within(screen.getByRole('dialog', { name: /assistant/i })).queryAllByRole('link')).toHaveLength(0);
  });

  it('an error event shows the unavailable message', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(sse('event:delta\ndata:{"text":"Part"}\n\nevent:error\ndata:{"code":"ASSISTANT_UNAVAILABLE"}\n\n'));
    await ask('hi');
    expect(await screen.findByRole('alert')).toHaveTextContent(/assistant is unavailable/i);
  });

  it('a stream error with fallback excerpts shows them with their titles', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(sse('event:error\ndata:{"code":"ASSISTANT_UNAVAILABLE",' +
      '"fallbackExcerpts":[{"title":"Booking policy","section":"Holds","text":"Seats are held for 10 minutes."}]}\n\n'));
    await ask('How long is a hold?');
    expect(await screen.findByText('Seats are held for 10 minutes.')).toBeInTheDocument();
    expect(within(screen.getByRole('list', { name: 'Sources' })).getByText('Booking policy')).toBeInTheDocument();
    expect(screen.getByRole('alert')).toHaveTextContent(/assistant is unavailable/i);
  });

  it('several excerpts from one article show one source chip', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(sse('event:error\ndata:{"code":"ASSISTANT_UNAVAILABLE","fallbackExcerpts":[' +
      '{"title":"Booking policy","section":"Seats","text":"At most 4."},{"title":"Booking policy","section":"Holds","text":"10 minutes."}]}\n\n'));
    await ask('Seats and holds?');
    expect(await screen.findAllByText('Booking policy')).toHaveLength(1);
  });

  it('a 503 with fallback excerpts shows them with their titles', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(JSON.stringify({
      code: 'ASSISTANT_UNAVAILABLE', status: 503,
      fallbackExcerpts: [{ title: 'Booking policy', section: 'How many seats can I book?', text: 'You can book at most 4 seats per event.' }],
    }), { status: 503, headers: { 'Content-Type': 'application/problem+json' } }));
    await ask('How many seats can I book?');
    expect(await screen.findByText(/at most 4 seats per event/)).toBeInTheDocument();
    expect(screen.getByText('Booking policy')).toBeInTheDocument();
  });

  it('a 429 asks the user to wait', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response('', { status: 429, headers: { 'Retry-After': '6' } }));
    await ask('hi');
    expect(await screen.findByRole('alert')).toHaveTextContent(/too many questions/i);
  });

  it('notes that AI can make mistakes and closes with Escape', async () => {
    render(<MemoryRouter><AssistantPanel /></MemoryRouter>);
    const toggle = screen.getByRole('button', { name: /open assistant/i });
    await userEvent.click(toggle);
    expect(screen.getByText(/AI can make mistakes/i)).toBeInTheDocument();
    await userEvent.keyboard('{Escape}');
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(toggle).toHaveFocus();
  });

  it('sends only valid history: long answers trimmed to 2000 chars, failed exchanges dropped', async () => {
    const long = 'x'.repeat(2500);
    const fetchSpy = vi.spyOn(globalThis, 'fetch')
      .mockResolvedValueOnce(sse(`event:delta\ndata:{"text":"${long}"}\n\nevent:done\ndata:{}\n\n`))
      .mockResolvedValueOnce(sse(''))   // closed with no events: a failed exchange
      .mockResolvedValueOnce(sse('event:delta\ndata:{"text":"ok"}\n\nevent:done\ndata:{}\n\n'));
    await ask('first');
    await screen.findByText(long);
    for (const q of ['second', 'third']) {
      await userEvent.type(screen.getByLabelText(/ask a question/i), q);
      await userEvent.click(screen.getByRole('button', { name: /^send$/i }));
      await waitFor(() => expect(screen.getByRole('button', { name: /^send$/i })).toBeDisabled());
    }
    await screen.findByText('ok');
    const body = JSON.parse(String((fetchSpy.mock.calls[2][1] as RequestInit).body));
    expect(body.history).toEqual([
      { role: 'user', content: 'first' },
      { role: 'assistant', content: 'x'.repeat(2000) },
    ]);
  });

  it('a stream that ends without done is reported as interrupted', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(sse('event:delta\ndata:{"text":"Half an ans"}\n\n'));
    await ask('hi');
    expect(await screen.findByRole('alert')).toHaveTextContent(/interrupted/i);
    expect(screen.getByText('Half an ans')).toBeInTheDocument();
  });

  it('a malformed event is skipped and the rest of the answer still arrives', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      sse('event:delta\ndata:{broken\n\nevent:delta\ndata:{"text":"still here"}\n\nevent:done\ndata:{}\n\n'));
    await ask('hi');
    expect(await screen.findByText('still here')).toBeInTheDocument();
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('a 401 says the session ended, not that the assistant is down', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response('', { status: 401 }));
    await ask('hi');
    expect(await screen.findByRole('alert')).toHaveTextContent(/session has ended/i);
  });
});
