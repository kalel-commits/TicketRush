import { CircleNotch, CheckCircle, Clock, Lock, Prohibit, Warning, XCircle } from '@phosphor-icons/react';
import type { ButtonHTMLAttributes, ReactNode } from 'react';
import { ApiError } from '../api/client';
import { messageFor } from '../api/errors';

/** Primary/secondary button that disables itself with a spinner while busy (UI UX Pro Max §2 loading-buttons). */
export function Button({ busy, variant = 'primary', children, className = '', ...rest }:
  ButtonHTMLAttributes<HTMLButtonElement> & { busy?: boolean; variant?: 'primary' | 'secondary' | 'danger' }) {
  const look = variant === 'primary'
    ? 'bg-brand text-on-accent font-semibold shadow-[0_8px_30px_-8px_rgb(167_139_250/0.6)] hover:brightness-110 active:scale-[0.98]'
    : variant === 'danger' ? 'bg-danger text-on-accent hover:brightness-110' : 'border border-input-border bg-card text-fg hover:bg-muted';
  return (
    <button
      {...rest}
      disabled={busy || rest.disabled}
      aria-busy={busy || undefined}
      className={`inline-flex min-h-11 cursor-pointer items-center justify-center gap-2 rounded-full px-5 py-2 font-medium transition duration-150 disabled:cursor-not-allowed disabled:opacity-50 ${look} ${className}`}
    >
      {busy && <CircleNotch aria-hidden className="animate-spin" size={18} />}
      {children}
    </button>
  );
}

/** A backend error as a sentence + recovery hint; role=alert so screen readers hear it. */
export function ErrorNotice({ error, children }: { error: unknown; children?: ReactNode }) {
  if (!error) return null;
  const code = error instanceof ApiError ? error.code : 'UNKNOWN';
  const friendly = messageFor(code);
  return (
    <div role="alert" className="flex items-start gap-2 rounded-xl border border-danger bg-card p-3 text-sm">
      <Warning aria-hidden size={20} className="mt-0.5 shrink-0 text-danger" />
      <div>
        <p>{friendly.message}</p>
        <p className="text-muted-fg">{friendly.action}</p>
        {children}
      </div>
    </div>
  );
}

const STATUS: Record<string, { label: string; icon: ReactNode; tone: string }> = {
  CONFIRMED: { label: 'Confirmed', icon: <CheckCircle aria-hidden weight="fill" />, tone: 'text-success' },
  PENDING_PAYMENT: { label: 'Awaiting payment', icon: <Clock aria-hidden />, tone: 'text-fg' },
  HOLDING: { label: 'Holding', icon: <Clock aria-hidden />, tone: 'text-fg' },
  EXPIRED: { label: 'Expired', icon: <Prohibit aria-hidden />, tone: 'text-muted-fg' },
  CANCELLED: { label: 'Cancelled', icon: <XCircle aria-hidden />, tone: 'text-muted-fg' },
  FAILED: { label: 'Failed', icon: <XCircle aria-hidden />, tone: 'text-danger' },
  BOOKED: { label: 'Booked', icon: <Lock aria-hidden />, tone: 'text-muted-fg' },
};

/** Status always as icon + text, never color alone (spec §23.3). */
export function StatusBadge({ status }: { status: string }) {
  const s = STATUS[status] ?? { label: status, icon: null, tone: 'text-fg' };
  return (
    <span className={`inline-flex items-center gap-1 rounded-full border border-border px-2 py-0.5 text-sm ${s.tone}`}>
      {s.icon}
      {s.label}
    </span>
  );
}

export function Card({ children, className = '' }: { children: ReactNode; className?: string }) {
  return <section className={`rounded-2xl border border-border bg-card p-5 ${className}`}>{children}</section>;
}

/** Placeholder block shown while content loads (reserves space: no layout jump). */
export function Skeleton({ className = '' }: { className?: string }) {
  return <div aria-hidden className={`skeleton rounded-lg ${className}`} />;
}
