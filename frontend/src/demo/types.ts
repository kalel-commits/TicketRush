/** Shapes shared by the demo page and the dev-only demo harness (plan 9). Everything here is sanitized data. */

/** One real POST /api/bookings sent for a buyer, with client-side timing. */
export interface Attempt {
  buyer: string;
  userId: number;
  idempotencyKey: string;
  seatId: number;
  sentAt: number;       // epoch ms, client clock
  receivedAt: number;   // epoch ms, client clock
  status: number;       // HTTP status, 0 = network error
  code?: string;        // ProblemDetail code on errors
  bookingId?: string;
}

export interface LockRow { seatId: number; ownerUserId: number; ownerKey: string; pttlMs: number }
export interface SeatRow { id: number; seatNo: string; status: string; bookingId: string | null; heldUntil: string | null }
export interface BookingRow {
  id: string; userId: number; idempotencyKey: string; status: string; failureReason: string | null; createdAt: string; updatedAt: string;
  seatIds?: number[];   // from booking_seats
}
export interface PaymentRow { id: string; bookingId: string; status: string; failureReason: string | null; lookupAttempts: number; updatedAt: string }

export interface OutboxStats { unpublished: number; published: number; lastPublishLagMs: number | null }

/** A read-only snapshot of the real stores for one demo event, taken by the harness. */
export interface Snapshot {
  startedAt: number; // epoch ms when the reads BEGAN: only reads begun after a response can be evidence about it
  takenAt: number;   // epoch ms when the reads finished
  readers: { db: boolean; redis: boolean };   // false = that reader failed: its arrays are empty, NOT "nothing there"
  locks: LockRow[];
  seats: SeatRow[];
  bookings: BookingRow[];
  payments: PaymentRow[];
  outbox?: { booking: OutboxStats; payment: OutboxStats };
  consumed?: { event: Record<string, number>; booking: Record<string, number>; payment: Record<string, number> };
  kafkaLag?: { group: string; topic: string; lag: number }[];
  metrics?: { seatlockBypassed: number | null; reconcilerLookups: number | null };
  errors?: string[];   // a reader that failed is reported, never silently shown as empty
}

export interface Stage { state: string; detail: string }
export interface Stages { redis: Stage; mysql: Stage; booking: Stage; payment: Stage }
