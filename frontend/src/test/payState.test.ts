import { describe, expect, it } from 'vitest';
import { announcement, formatClock, payState } from '../booking/payState';

const NOW = Date.parse('2026-11-03T10:00:00Z');
const future = '2026-11-03T10:08:00Z';
const past = '2026-11-03T09:59:00Z';
const booking = (status: string, expiresAt = future) => ({ id: 'b1', eventId: 7, status, seatNos: ['A1'], amountCents: 2500, expiresAt });

describe('payState', () => {
  it('awaiting payment with time left can pay', () => {
    expect(payState(booking('PENDING_PAYMENT'), null, null, NOW)).toMatchObject({ kind: 'ready', canPay: true });
  });

  it('a processing payment cannot be paid again', () => {
    expect(payState(booking('PENDING_PAYMENT'), { id: 'p1', status: 'PROCESSING' }, null, NOW))
      .toMatchObject({ kind: 'confirming', canPay: false });
  });

  it('a declined attempt offers a retry', () => {
    expect(payState(booking('PENDING_PAYMENT'), null, 'DECLINED', NOW)).toMatchObject({ kind: 'declined', canPay: true });
  });

  it('confirmed is final', () => {
    expect(payState(booking('CONFIRMED'), { id: 'p1', status: 'SUCCEEDED' }, null, NOW)).toMatchObject({ kind: 'confirmed', canPay: false });
  });

  it('an expired hold disables pay and points to new seats, even before the server says so', () => {
    expect(payState(booking('EXPIRED'), null, null, NOW)).toMatchObject({ kind: 'expired', canPay: false });
    expect(payState(booking('PENDING_PAYMENT', past), null, null, NOW)).toMatchObject({ kind: 'expired', canPay: false });
  });

  it('cancelled or failed bookings are ended', () => {
    expect(payState(booking('CANCELLED'), null, null, NOW).kind).toBe('ended');
    expect(payState(booking('FAILED'), null, null, NOW).kind).toBe('ended');
  });
});

describe('countdown', () => {
  it('formats mm:ss with leading zeros', () => {
    expect(formatClock(605)).toBe('10:05');
    expect(formatClock(0)).toBe('00:00');
    expect(formatClock(-3)).toBe('00:00');
  });

  it('announces only at 5 minutes, 1 minute and expiry', () => {
    expect(announcement(300)).toBe('5 minutes left to pay');
    expect(announcement(60)).toBe('1 minute left to pay');
    expect(announcement(0)).toBe('Your hold has expired');
    expect(announcement(299)).toBeNull();
    expect(announcement(61)).toBeNull();
  });

  it('a payment still processing after expiresAt keeps confirming, never "expired"', () => {
    const late = { ...booking('PENDING_PAYMENT'), expiresAt: new Date(NOW - 60_000).toISOString() };
    expect(payState(late, { id: 'p1', status: 'PROCESSING' }, null, NOW).kind).toBe('confirming');
    expect(payState(late, { id: 'p1', status: 'SUCCEEDED' }, null, NOW).kind).toBe('confirming');
  });

  it('a payment the reconciler marked FAILED/DECLINED shows declined', () => {
    const s = payState(booking('PENDING_PAYMENT'), { id: 'p1', status: 'FAILED', failureReason: 'DECLINED' }, null, NOW);
    expect(s.kind).toBe('declined');
    expect(s.canPay).toBe(true);
  });
});
