/** What the hold & pay screen should show, from the booking + latest payment (spec §23.2). Pure, so it is unit-tested. */
export interface Booking {
  id: string;
  eventId: number;
  status: string;
  seatNos: string[];
  amountCents: number | null;
  expiresAt: string;
  failureReason?: string | null;
}

export interface Payment {
  id: string;
  status: string;
  failureReason?: string | null;
}

export type PayKind = 'holding' | 'ready' | 'confirming' | 'declined' | 'confirmed' | 'expired' | 'ended';

export interface PayState {
  kind: PayKind;
  canPay: boolean;
  message: string;
}

export function payState(booking: Booking, payment: Payment | null, lastErrorCode: string | null, now: number): PayState {
  if (booking.status === 'CONFIRMED') return { kind: 'confirmed', canPay: false, message: 'Confirmed — the seats are yours.' };
  if (booking.status === 'CANCELLED' || booking.status === 'FAILED') {
    return { kind: 'ended', canPay: false, message: 'This booking was not completed. You were not charged.' };
  }
  // a charge in flight outranks the local clock: the server decides whether the hold still stands (the reconciler
  // may settle an ambiguous charge minutes later), so never tell someone who may have paid that seats were released
  if (booking.status !== 'EXPIRED' && payment && (payment.status === 'PROCESSING' || payment.status === 'SUCCEEDED')) {
    return { kind: 'confirming', canPay: false, message: 'Confirming with the bank…' };
  }
  const expired = booking.status === 'EXPIRED' || Date.parse(booking.expiresAt) <= now;
  if (expired) return { kind: 'expired', canPay: false, message: 'Your hold has expired. The seats were released.' };
  if (booking.status === 'HOLDING') return { kind: 'holding', canPay: false, message: 'Reserving your seats…' };
  const reconciledDecline = payment?.status === 'FAILED' && payment.failureReason === 'DECLINED';
  if (lastErrorCode === 'DECLINED' || reconciledDecline) {
    return { kind: 'declined', canPay: true, message: 'Your payment was declined and you were not charged. Retry with a new attempt.' };
  }
  return { kind: 'ready', canPay: true, message: 'Your seats are held. Pay before the timer runs out.' };
}

export function formatClock(secondsLeft: number): string {
  const s = Math.max(0, Math.floor(secondsLeft));
  return `${String(Math.floor(s / 60)).padStart(2, '0')}:${String(s % 60).padStart(2, '0')}`;
}

/** Screen readers hear the countdown only at 5 min, 1 min and expiry, not every second (spec §23.4). */
export function announcement(secondsLeft: number): string | null {
  if (secondsLeft === 300) return '5 minutes left to pay';
  if (secondsLeft === 60) return '1 minute left to pay';
  if (secondsLeft === 0) return 'Your hold has expired';
  return null;
}
