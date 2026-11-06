package com.ticketrush.booking.domain;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Plain SQL so every state change is a visible conditional UPDATE (spec §7). Each transition returns
 * whether exactly one row changed; callers attach side effects only when it did.
 */
@Repository
public class BookingRepository {

    private static final String COLUMNS = """
            id, user_id, event_id, seat_count, amount_cents, status, idempotency_key, request_hash,
            expires_at, failure_reason, payment_id, created_at""";

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;

    BookingRepository(JdbcTemplate jdbc, NamedParameterJdbcTemplate named) {
        this.jdbc = jdbc;
        this.named = named;
    }

    // ---------- reads ----------

    public Optional<Booking> findById(UUID id) {
        List<Booking> rows = jdbc.query("SELECT " + COLUMNS + " FROM bookings WHERE id = ?", this::row, Uuids.toBytes(id));
        return rows.stream().findFirst().map(this::withSeats);
    }

    public Optional<Booking> findByIdempotency(long userId, String key) {
        List<Booking> rows = jdbc.query("SELECT " + COLUMNS + " FROM bookings WHERE user_id = ? AND idempotency_key = ?",
                this::row, userId, key);
        return rows.stream().findFirst().map(this::withSeats);
    }

    public List<Booking> findByUser(long userId, int page, int size) {
        List<Booking> rows = jdbc.query("SELECT " + COLUMNS + " FROM bookings WHERE user_id = ? ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?",
                this::row, userId, size, page * size);
        return withSeats(rows);
    }

    public List<Booking> findAll(List<UUID> ids) {
        if (ids.isEmpty()) return List.of();
        List<byte[]> keys = ids.stream().map(Uuids::toBytes).toList();
        return named.query("SELECT " + COLUMNS + " FROM bookings WHERE id IN (:ids)", Map.of("ids", keys), this::row);
    }

    /**
     * Claims overdue HOLDING / PENDING_PAYMENT bookings for this transaction. SKIP LOCKED lets two instances
     * split the work instead of blocking or double-processing (spec §12). Must run inside a transaction.
     */
    public List<Booking> lockOverdue(int limit) {
        List<Booking> rows = jdbc.query("SELECT " + COLUMNS + " FROM bookings "
                + "WHERE status IN ('HOLDING','PENDING_PAYMENT') AND expires_at <= NOW(3) "
                + "ORDER BY expires_at LIMIT ? FOR UPDATE SKIP LOCKED", this::row, limit);
        return withSeats(rows);
    }

    // ---------- writes ----------

    public void insertHolding(UUID id, long userId, long eventId, List<Long> seatIds, String key, String hash,
                              Instant expiresAt, Instant now) {
        jdbc.update("""
                INSERT INTO bookings (id, user_id, event_id, seat_count, status, idempotency_key, request_hash,
                                      expires_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'HOLDING', ?, ?, ?, ?, ?)""",
                Uuids.toBytes(id), userId, eventId, seatIds.size(), key, hash,
                Timestamp.from(expiresAt), Timestamp.from(now), Timestamp.from(now));
        List<Object[]> seats = seatIds.stream().map(s -> new Object[]{Uuids.toBytes(id), s}).toList();
        jdbc.batchUpdate("INSERT INTO booking_seats (booking_id, seat_id) VALUES (?, ?)", seats);
    }

    /** HOLDING -> PENDING_PAYMENT with the authoritative amount, expiry and seat numbers from event-service. */
    public boolean markPendingPayment(UUID id, long amountCents, Instant heldUntil, List<Long> sortedSeatIds, List<String> seatNos) {
        int n = jdbc.update("""
                UPDATE bookings SET status = 'PENDING_PAYMENT', amount_cents = ?, expires_at = ?, updated_at = NOW(3)
                 WHERE id = ? AND status = 'HOLDING'""", amountCents, Timestamp.from(heldUntil), Uuids.toBytes(id));
        if (n == 1) {
            for (int i = 0; i < sortedSeatIds.size() && i < seatNos.size(); i++) {
                jdbc.update("UPDATE booking_seats SET seat_no = ? WHERE booking_id = ? AND seat_id = ?",
                        seatNos.get(i), Uuids.toBytes(id), sortedSeatIds.get(i));
            }
        }
        return n == 1;
    }

    /** PENDING_PAYMENT -> CONFIRMED, only while the hold is still valid (spec §7.1 guard). */
    public boolean confirm(UUID id, UUID paymentId) {
        return jdbc.update("""
                UPDATE bookings SET status = 'CONFIRMED', payment_id = ?, updated_at = NOW(3)
                 WHERE id = ? AND status = 'PENDING_PAYMENT' AND expires_at > NOW(3)""",
                Uuids.toBytes(paymentId), Uuids.toBytes(id)) == 1;
    }

    /** Dedupe for consumed messages; returns false if this message id was seen before. */
    public boolean markProcessed(UUID messageId, String topic) {
        return jdbc.update("INSERT IGNORE INTO processed_events (event_id, topic, processed_at) VALUES (?, ?, NOW(3))",
                Uuids.toBytes(messageId), topic) == 1;
    }

    /** Generic conditional transition into a terminal state. */
    public boolean transition(UUID id, BookingStatus from, BookingStatus to, String reason) {
        return jdbc.update("UPDATE bookings SET status = ?, failure_reason = ?, updated_at = NOW(3) WHERE id = ? AND status = ?",
                to.name(), reason, Uuids.toBytes(id), from.name()) == 1;
    }

    // ---------- quota (spec §9.3) ----------

    /** Atomic: row-locks the quota row; parallel requests of one user serialise here. */
    public boolean reserveQuota(long userId, long eventId, int seats, int max) {
        jdbc.update("INSERT INTO user_event_quota (user_id, event_id, seats_reserved) VALUES (?, ?, 0) "
                + "ON DUPLICATE KEY UPDATE user_id = user_id", userId, eventId);
        return jdbc.update("UPDATE user_event_quota SET seats_reserved = seats_reserved + ? "
                + "WHERE user_id = ? AND event_id = ? AND seats_reserved + ? <= ?", seats, userId, eventId, seats, max) == 1;
    }

    /** Called only in the same transaction as a terminal transition that changed exactly one row. */
    public void releaseQuota(long userId, long eventId, int seats) {
        jdbc.update("UPDATE user_event_quota SET seats_reserved = seats_reserved - ? "
                + "WHERE user_id = ? AND event_id = ? AND seats_reserved >= ?", seats, userId, eventId, seats);
    }

    // ---------- mapping ----------

    private Booking row(ResultSet rs, int i) throws SQLException {
        long amount = rs.getLong("amount_cents");
        Timestamp created = rs.getTimestamp("created_at");
        return new Booking(Uuids.fromBytes(rs.getBytes("id")), rs.getLong("user_id"), rs.getLong("event_id"),
                rs.getInt("seat_count"), rs.wasNull() ? null : amount, BookingStatus.valueOf(rs.getString("status")),
                rs.getString("idempotency_key"), rs.getString("request_hash"), rs.getTimestamp("expires_at").toInstant(),
                rs.getString("failure_reason"), Uuids.fromBytes(rs.getBytes("payment_id")), created.toInstant(),
                List.of(), List.of());
    }

    private Booking withSeats(Booking b) {
        return withSeats(List.of(b)).getFirst();
    }

    private List<Booking> withSeats(List<Booking> bookings) {
        if (bookings.isEmpty()) return bookings;
        Map<UUID, List<Long>> ids = new HashMap<>();
        Map<UUID, List<String>> nos = new HashMap<>();
        named.query("SELECT booking_id, seat_id, seat_no FROM booking_seats WHERE booking_id IN (:ids) ORDER BY seat_id",
                Map.of("ids", bookings.stream().map(b -> Uuids.toBytes(b.id())).toList()), rs -> {
                    UUID b = Uuids.fromBytes(rs.getBytes("booking_id"));
                    ids.computeIfAbsent(b, k -> new ArrayList<>()).add(rs.getLong("seat_id"));
                    String no = rs.getString("seat_no");
                    if (no != null) nos.computeIfAbsent(b, k -> new ArrayList<>()).add(no);
                });
        return bookings.stream().map(b -> new Booking(b.id(), b.userId(), b.eventId(), b.seatCount(), b.amountCents(),
                b.status(), b.idempotencyKey(), b.requestHash(), b.expiresAt(), b.failureReason(), b.paymentId(),
                b.createdAt(), ids.getOrDefault(b.id(), List.of()), nos.getOrDefault(b.id(), List.of()))).toList();
    }
}
