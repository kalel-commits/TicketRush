/** Backend error codes -> human message + recovery action (spec §23.2). */
export interface Friendly {
  message: string;
  action: string;
}

const MESSAGES: Record<string, Friendly> = {
  SEATS_UNAVAILABLE: { message: 'Someone got those seats first — the map has refreshed, pick again.', action: 'Pick other seats' },
  SEAT_LIMIT_EXCEEDED: { message: 'You can hold at most 4 seats for an event.', action: 'Choose fewer seats' },
  SALE_NOT_OPEN: { message: 'Ticket sales for this event are not open yet.', action: 'Wait for the countdown' },
  PAYMENT_IN_PROGRESS_OR_DONE: { message: 'A payment for this booking is already in progress or done.', action: 'Check the booking status' },
  DECLINED: { message: 'Your payment was declined. You were not charged.', action: 'Try again' },
  INVALID_STATE: { message: 'This booking can no longer be paid or changed.', action: 'Pick new seats' },
  TOO_MANY_REQUESTS: { message: 'Too many attempts — wait a moment.', action: 'Retry shortly' },
  DEPENDENCY_UNAVAILABLE: { message: 'The service is busy right now. Nothing was charged.', action: 'Retry' },
  ASSISTANT_UNAVAILABLE: { message: 'The assistant is unavailable right now. Here is what the help articles say.', action: 'Try again later' },
  INVALID_CREDENTIALS: { message: 'That email and password do not match.', action: 'Check and try again' },
  EMAIL_TAKEN: { message: 'An account with this email already exists.', action: 'Sign in instead' },
  UNAUTHORIZED: { message: 'Your session has ended.', action: 'Sign in again' },
  NOT_FOUND: { message: 'We could not find that.', action: 'Go back' },
  VALIDATION_FAILED: { message: 'Some fields need attention.', action: 'Fix the highlighted fields' },
};

export function messageFor(code: string): Friendly {
  return MESSAGES[code] ?? { message: 'Something went wrong.', action: 'Retry' };
}
