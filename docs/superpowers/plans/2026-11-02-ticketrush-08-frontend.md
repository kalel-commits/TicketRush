# TicketRush Plan 8 — Demo Frontend Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** a small React UI that makes the backend's guarantees visible in a 3-minute demo:
- log in, watch a live seat map, hold up to 4 seats, pay against a countdown, and see the booking confirm;
- ask the assistant a question and see its sources;
- view the measured load-test results.

**Architecture:** `frontend/` (npm, not a Maven module). Vite + React 18 + TypeScript calls the gateway at `http://localhost:8080`; CORS for `http://localhost:5173` already exists, so there are no backend changes. Data comes from TanStack Query: polling, retries and cache. Auth is React context, with the JWT in memory plus `sessionStorage` (demo trade-off, noted in the README). Styling is Tailwind with CSS-variable tokens from spec §23.3 (UI UX Pro Max design system), Phosphor icons, and Fira Sans/Code from `@fontsource`, so there are no external font requests. Recharts powers the results page.

**Tech Stack (exact pins):**
- react / react-dom 18.3.1, react-router-dom 6.27.0, @tanstack/react-query 5.59.16, @phosphor-icons/react 2.1.7, recharts 2.13.0, @fontsource/fira-sans 5.1.0, @fontsource/fira-code 5.1.0;
- vite 5.4.10, @vitejs/plugin-react 4.3.3, typescript 5.6.3, tailwindcss 3.4.14, postcss 8.4.47, autoprefixer 10.4.20;
- vitest 2.1.4, jsdom 25.0.1, @testing-library/react 16.0.1, @testing-library/jest-dom 6.6.2, @testing-library/user-event 14.5.2, @playwright/test 1.48.2.

**Spec:** `docs/superpowers/specs/2026-11-01-ticketrush-design.md` §23 (+ §24.8 assistant contract). **Roadmap:** plan 8. "Done when": the Playwright smoke login → hold → pay → confirmed passes, plus one assistant question with a source chip.

## Global Constraints

- No backend changes (spec §23). The frontend only uses public `/api/**` routes through the gateway.
- Color only via CSS variables (`--bg #0F172A`, `--card #1B2336`, `--muted #272F42`, `--border #475569`, `--fg #F8FAFC`, `--muted-fg #94A3B8`, `--accent #22C55E`, `--on-accent #0F172A`, `--danger #EF4444`, `--focus #FFFFFF`). No raw hex in components.
- Seat and status states always show **icon + text** (never color alone):
  - AVAILABLE: outline + accent;
  - SELECTED: filled accent + check icon;
  - HELD: muted + clock icon;
  - BOOKED: muted + lock icon.
- Accessibility bar (spec §23.4 + UI UX Pro Max §1–§3):
  - visible focus rings, contrast ≥ 4.5:1 for text;
  - the seat map is a keyboard grid (arrow keys move, Space toggles) where each seat is named "Row A seat 1, available" and the selection count is announced politely;
  - the countdown announces at 5 min, 1 min and expiry only;
  - buttons disable with a spinner while a request runs;
  - forms have visible labels, `autocomplete`, paste allowed, errors on blur;
  - motion respects `prefers-reduced-motion`;
  - no page-level horizontal scroll at 375 px (the seat grid scrolls in its own container).
- Idempotency-Key: generated once per user attempt (`crypto.randomUUID()`) and reused when that attempt is retried.
- Error codes map to human messages with a recovery action, as listed in spec §23.2. A 429 disables retry for the `Retry-After` period, or 5 s when the header is absent.
- Assistant suggested actions render only as `<Link>`s (navigate, never act). Source chips come from `meta.sources`, or from `fallbackExcerpts` titles on 503.
- No commits unless the user authorizes them.

## Review Focus

1. **Double-click "Hold seats" / "Pay".** One request with one Idempotency-Key, and a retry reuses that key. Vitest in Tasks 2–3.
2. **The hold expires while the user is on the pay page.** The UI shows EXPIRED with "pick new seats", and pay is disabled. Vitest in Task 3.
3. **SSE chunks split mid-line** (`event:` in one chunk, `data:` in the next). The parser still yields complete events. Vitest in Task 4.
4. **Assistant answer contains a malicious-looking link or markdown.** The answer is rendered as text, and only `suggestedActions` with an internal `href` beginning `/` become links. Vitest in Task 4.
5. **The seat map polls while the tab is hidden.** Polling pauses (`refetchIntervalInBackground: false`). Asserted in Task 2.

---

## File Structure

```
frontend/
  package.json, package-lock.json, vite.config.ts, tsconfig.json, tailwind.config.js, postcss.config.js, index.html
  playwright.config.ts, e2e/smoke.spec.ts
  public/results/{s1,s2,s3}.json            exported k6 summaries + invariants verdict (copied by scripts/export-results.mjs)
  scripts/export-results.mjs
  src/
    main.tsx, App.tsx, index.css (tokens, fonts, focus, reduced motion)
    api/client.ts        fetch wrapper: base URL, bearer, ProblemDetail -> ApiError(status, code, detail, retryAfter, body)
    api/errors.ts        code -> {message, action}
    auth/AuthContext.tsx login/register/logout, token in memory + sessionStorage
    components/Layout.tsx, Button.tsx, StatusBadge.tsx, Countdown.tsx, ErrorNotice.tsx
    pages/LoginPage.tsx, RegisterPage.tsx, EventsPage.tsx, SeatMapPage.tsx, BookingPage.tsx, MyBookingsPage.tsx, ResultsPage.tsx
    seatmap/selection.ts selection reducer (<= 4, toggle, keyboard move)
    booking/payState.ts  derive UI state from booking + payment
    assistant/sse.ts     incremental SSE parser
    assistant/AssistantPanel.tsx
    test/*.test.ts(x)
```

---

### Task 1: Scaffold, API client, errors, auth pages

- [ ] **Step 1: Failing tests** (`src/test/errors.test.ts`, `src/test/client.test.ts`):
  - `messageFor(code)` returns the spec §23.2 copy for SEATS_UNAVAILABLE, SEAT_LIMIT_EXCEEDED, PAYMENT_IN_PROGRESS_OR_DONE, DECLINED, SALE_NOT_OPEN, DEPENDENCY_UNAVAILABLE and ASSISTANT_UNAVAILABLE, and a generic fallback for unknown codes;
  - `api()` with a mocked `fetch` returning a ProblemDetail 409 throws an `ApiError` with that `code`. A 429 with `Retry-After: 7` gives `retryAfter` 7; without the header it gives 5. The `Authorization` header is sent when a token exists.
- [ ] **Step 2:** `npm test` → FAIL.
- [ ] **Step 3:** Implement the scaffold (configs, tokens, fonts), `client.ts`, `errors.ts`, `AuthContext`, `Layout` (nav with icon + text labels, skip link, active state), and Login/Register (visible labels, autocomplete, errors on blur, a spinner button).
- [ ] **Step 4:** `npm test` → PASS; `npm run build` (tsc + vite) → success.

### Task 2: Events list and live seat map

- [ ] **Step 1: Failing tests** `src/test/selection.test.ts` + `src/test/seatmap.test.tsx`:
  - toggle selects and deselects; a 5th select is refused with a reason; a seat that turns HELD or BOOKED in a poll is dropped from the selection;
  - arrow keys move focus within the grid bounds; Space toggles;
  - a seat's accessible name is "Row A seat 1, available";
  - the live region announces "2 of 4 seats selected";
  - double-clicking "Hold seats" sends one `POST /api/bookings`, and both clicks use one Idempotency-Key (Review Focus 1);
  - the seat query uses `refetchInterval: 2000` and `refetchIntervalInBackground: false` (Review Focus 5).
- [ ] **Step 2:** FAIL.
- [ ] **Step 3:** Implement EventsPage (cards: sale status Upcoming / On sale / Sold out with icon + text, a countdown to sale opening) and SeatMapPage (grid by row letter, "last updated Xs ago" plus a stale indicator after 6 s, a "Hold seats" CTA → navigate to `/bookings/:id`, error mapping with a map refresh).
- [ ] **Step 4:** PASS + build.

### Task 3: Hold & pay, my bookings

- [ ] **Step 1: Failing tests** `src/test/payState.test.ts` + `src/test/booking.test.tsx`:
  - `payState(booking, payment)` gives:
    - PENDING_PAYMENT without a payment → `canPay`;
    - payment PROCESSING → "Confirming with the bank…", no pay;
    - DECLINED → "retry with a new attempt";
    - booking CONFIRMED → success;
    - booking EXPIRED, or `expiresAt` in the past → expired, pay disabled, "pick new seats" (Review Focus 2);
  - a double-clicked Pay sends one `POST /api/payments` with one key; the retry after 402 uses a **new** key;
  - the countdown announces at 5 min, 1 min and 0 only, and renders `mm:ss` in Fira Code.
- [ ] **Step 2:** FAIL.
- [ ] **Step 3:** Implement BookingPage (poll the booking every 2 s until it is final, and the payment while it is PROCESSING) and MyBookingsPage (status badges with icon + text).
- [ ] **Step 4:** PASS + build.

### Task 4: Assistant panel

- [ ] **Step 1: Failing tests** `src/test/sse.test.ts` + `src/test/assistant.test.tsx`:
  - the parser handles one event per chunk, several events in one chunk, and an event split across chunks mid-line (Review Focus 3);
  - multi-line `data:` is handled;
  - the panel streams deltas into one answer, then shows source chips from `meta`;
  - an `error` event shows the 503 message;
  - an answer containing `<a href="javascript:…">` or `[x](https://evil)` is shown as text, with no link elements except suggested actions whose `href` starts with `/` (Review Focus 4);
  - an HTTP 503 with `fallbackExcerpts` shows the excerpts and their title chips;
  - a 429 shows the wait message;
  - the panel sends `context.eventId` / `bookingId` from the current route.
- [ ] **Step 2:** FAIL.
- [ ] **Step 3:** Implement `sse.ts` and `AssistantPanel` (toggle button with an accessible name, a dialog-like side panel with Escape to close, focus returning to the toggle, "AI can make mistakes" note, history ≤ 6 turns sent).
- [ ] **Step 4:** PASS + build.

### Task 5: Results page, Playwright smoke, README

- [ ] `scripts/export-results.mjs` copies `load/results/{s1,s2,s3}-latest.json` → `public/results/` and adds the invariant verdicts recorded in the README table.
- [ ] ResultsPage:
  - KPI tiles: confirmed = 100, double-sells = 0, double charges = 0 (from the exports);
  - a latency p50/p95/p99 grouped bar and a response-mix bar (Recharts);
  - each chart has a legend, tooltips and a data-table fallback;
  - empty and error states.
- [ ] Playwright (`e2e/smoke.spec.ts`, Chromium, against the Vite dev server and a running backend):
  1. register + log in → the admin creates an event through the API (sale open) → open the seat map → select 1 seat → Hold → Pay → "Confirmed" visible within 30 s;
  2. open the assistant, ask "How many seats can I book?" → a source chip containing "Booking policy" is visible (with or without an API key: the chip comes from `meta.sources` or `fallbackExcerpts`).

  To spare memory, step 1 runs with the core stack and step 2 with the assistant subset (`--grep`).
- [ ] README "Demo frontend" section: run commands, the screens, the token-storage trade-off, and test commands.
- [ ] Verify: `npm test`, `npm run build`, and both Playwright runs pass → roadmap row 8 Done.
