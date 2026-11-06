-- Spec §16.4 post-run invariants. Read-only, cross-schema, run as root: TEST TOOLING ONLY.
-- Every SELECT returns violating rows only (first column = check number), so "no output" means "all hold".
-- @event limits checks to one event (NULL = everything); @quiet = 1 adds check 5 (run after a quiet period).

-- 1. no seat is in more than one active booking
SELECT 1 AS chk, b.event_id, bs.seat_id, COUNT(*) AS bookings
  FROM booking_db.booking_seats bs JOIN booking_db.bookings b ON b.id = bs.booking_id
 WHERE b.status IN ('HOLDING', 'PENDING_PAYMENT', 'CONFIRMED') AND (@event IS NULL OR b.event_id = @event)
 GROUP BY b.event_id, bs.seat_id HAVING COUNT(*) > 1;

-- 2a. every BOOKED seat belongs to a CONFIRMED booking
SELECT 2 AS chk, s.event_id, s.id AS seat_id, COALESCE(b.status, 'NO_BOOKING') AS booking_status
  FROM event_db.seats s LEFT JOIN booking_db.bookings b ON b.id = s.booking_id
 WHERE s.status = 'BOOKED' AND (b.id IS NULL OR b.status <> 'CONFIRMED') AND (@event IS NULL OR s.event_id = @event);

-- 2b. every seat of a CONFIRMED booking is BOOKED by that booking
SELECT 2 AS chk, b.event_id, BIN_TO_UUID(b.id) AS booking_id, bs.seat_id, COALESCE(s.status, 'NO_SEAT') AS seat_status
  FROM booking_db.bookings b JOIN booking_db.booking_seats bs ON bs.booking_id = b.id
  LEFT JOIN event_db.seats s ON s.id = bs.seat_id
 WHERE b.status = 'CONFIRMED' AND (s.id IS NULL OR s.status <> 'BOOKED' OR s.booking_id <> b.id)
   AND (@event IS NULL OR b.event_id = @event);

-- 3a. every CONFIRMED booking has exactly one SUCCEEDED payment
SELECT 3 AS chk, b.event_id, BIN_TO_UUID(b.id) AS booking_id,
       (SELECT COUNT(*) FROM payment_db.payments p WHERE p.booking_id = b.id AND p.status = 'SUCCEEDED') AS succeeded
  FROM booking_db.bookings b
 WHERE b.status = 'CONFIRMED' AND (@event IS NULL OR b.event_id = @event)
   AND (SELECT COUNT(*) FROM payment_db.payments p WHERE p.booking_id = b.id AND p.status = 'SUCCEEDED') <> 1;

-- 3b. no booking has more than one active (non-FAILED) payment
SELECT 3 AS chk, BIN_TO_UUID(p.booking_id) AS booking_id, COUNT(*) AS active_payments
  FROM payment_db.payments p LEFT JOIN booking_db.bookings b ON b.id = p.booking_id
 WHERE p.status <> 'FAILED' AND (@event IS NULL OR b.event_id = @event)
 GROUP BY p.booking_id HAVING COUNT(*) > 1;

-- 4a. quota row = seats of active bookings, and never above 4
SELECT 4 AS chk, q.user_id, q.event_id, q.seats_reserved, COALESCE(a.seats, 0) AS active_seats
  FROM booking_db.user_event_quota q
  LEFT JOIN (SELECT user_id, event_id, SUM(seat_count) AS seats FROM booking_db.bookings
              WHERE status IN ('HOLDING', 'PENDING_PAYMENT', 'CONFIRMED') GROUP BY user_id, event_id) a
         ON a.user_id = q.user_id AND a.event_id = q.event_id
 WHERE (q.seats_reserved <> COALESCE(a.seats, 0) OR q.seats_reserved > 4) AND (@event IS NULL OR q.event_id = @event);

-- 4b. active bookings without a quota row
SELECT 4 AS chk, a.user_id, a.event_id, 'NO_QUOTA_ROW' AS problem, a.seats
  FROM (SELECT user_id, event_id, SUM(seat_count) AS seats FROM booking_db.bookings
         WHERE status IN ('HOLDING', 'PENDING_PAYMENT', 'CONFIRMED') GROUP BY user_id, event_id) a
  LEFT JOIN booking_db.user_event_quota q ON q.user_id = a.user_id AND q.event_id = a.event_id
 WHERE q.user_id IS NULL AND (@event IS NULL OR a.event_id = @event);

-- 5. after a quiet period: nothing in flight or stuck
SELECT 5 AS chk, 'PROCESSING_PAYMENT' AS problem, BIN_TO_UUID(p.id) AS id
  FROM payment_db.payments p LEFT JOIN booking_db.bookings b ON b.id = p.booking_id
 WHERE @quiet = 1 AND p.status = 'PROCESSING' AND (@event IS NULL OR b.event_id = @event);
SELECT 5 AS chk, 'UNPUBLISHED_OUTBOX_BOOKING' AS problem, COUNT(*) AS n FROM booking_db.outbox_events
 WHERE @quiet = 1 AND published_at IS NULL HAVING COUNT(*) > 0;
SELECT 5 AS chk, 'UNPUBLISHED_OUTBOX_PAYMENT' AS problem, COUNT(*) AS n FROM payment_db.outbox_events
 WHERE @quiet = 1 AND published_at IS NULL HAVING COUNT(*) > 0;
SELECT 5 AS chk, 'HELD_PAST_EXPIRY' AS problem, s.event_id, s.id AS seat_id
  FROM event_db.seats s
 WHERE @quiet = 1 AND s.status = 'HELD' AND s.held_until < NOW(3) - INTERVAL 3 MINUTE AND (@event IS NULL OR s.event_id = @event);
