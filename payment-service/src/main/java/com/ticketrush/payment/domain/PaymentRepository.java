package com.ticketrush.payment.domain;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Plain SQL. Every status change is a conditional UPDATE: exactly one concurrent actor wins (spec §7). */
@Repository
public class PaymentRepository {

    private static final String COLUMNS = "id, booking_id, user_id, amount_cents, status, idempotency_key, request_hash, "
            + "provider_ref, failure_reason, created_at, updated_at";

    private final JdbcTemplate jdbc;

    PaymentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Payment> findById(UUID id) {
        return one("SELECT " + COLUMNS + " FROM payments WHERE id = ?", Uuids.toBytes(id));
    }

    public Optional<Payment> findByIdempotency(long userId, String key) {
        return one("SELECT " + COLUMNS + " FROM payments WHERE user_id = ? AND idempotency_key = ?", userId, key);
    }

    /** The one attempt that is in flight or has taken money, if any (uk_payment_active_booking). */
    public Optional<Payment> findActiveByBooking(UUID bookingId) {
        return one("SELECT " + COLUMNS + " FROM payments WHERE active_booking_id = ?", Uuids.toBytes(bookingId));
    }

    /**
     * Inserts a PROCESSING attempt. Throws DuplicateKeyException when the same (user, key) exists, or when the
     * booking already has an active attempt; that check happens before any money moves (spec §6.5).
     */
    public void insertProcessing(Payment p) {
        // DB clock, like every later transition: the reconciler ages rows by updated_at (Docker VM clocks drift)
        jdbc.update("INSERT INTO payments (id, booking_id, user_id, amount_cents, status, idempotency_key, request_hash, "
                        + "created_at, updated_at) VALUES (?, ?, ?, ?, 'PROCESSING', ?, ?, NOW(3), NOW(3))",
                Uuids.toBytes(p.id()), Uuids.toBytes(p.bookingId()), p.userId(), p.amountCents(), p.idempotencyKey(),
                p.requestHash());
    }

    // ---------- reconciler (spec §10) ----------

    public List<Payment> findProcessingOlderThan(int seconds, int limit) {
        return jdbc.query("SELECT " + COLUMNS + " FROM payments WHERE status = 'PROCESSING' AND updated_at < NOW(3) - INTERVAL ? SECOND "
                + "ORDER BY updated_at LIMIT ?", PaymentRepository::row, seconds, limit);
    }

    public List<Payment> findUnverifiedSucceededOlderThan(int seconds, int limit) {
        return jdbc.query("SELECT " + COLUMNS + " FROM payments WHERE status = 'SUCCEEDED' AND verified_at IS NULL "
                + "AND updated_at < NOW(3) - INTERVAL ? SECOND ORDER BY updated_at LIMIT ?", PaymentRepository::row, seconds, limit);
    }

    public List<Payment> findRefundPendingOlderThan(int seconds, int limit) {
        return jdbc.query("SELECT " + COLUMNS + " FROM payments WHERE status = 'REFUND_PENDING' "
                + "AND updated_at < NOW(3) - INTERVAL ? SECOND ORDER BY updated_at LIMIT ?", PaymentRepository::row, seconds, limit);
    }

    /** The provider answered "no such charge": count it, and restart the 30 s clock to the next lookup. */
    public boolean recordNotFound(UUID id) {
        return jdbc.update("UPDATE payments SET lookup_attempts = lookup_attempts + 1, updated_at = NOW(3) "
                + "WHERE id = ? AND status = 'PROCESSING'", Uuids.toBytes(id)) == 1;
    }

    /** Assumption A7: two "not found" answers >= 30 s apart mean the charge never happened. */
    public boolean failIfNotFoundTwice(UUID id) {
        return jdbc.update("UPDATE payments SET status = 'FAILED', failure_reason = 'NOT_CHARGED', updated_at = NOW(3) "
                + "WHERE id = ? AND status = 'PROCESSING' AND lookup_attempts >= 1 AND updated_at <= NOW(3) - INTERVAL 30 SECOND",
                Uuids.toBytes(id)) == 1;
    }

    public boolean markVerified(UUID id) {
        return jdbc.update("UPDATE payments SET verified_at = NOW(3) WHERE id = ? AND status = 'SUCCEEDED' AND verified_at IS NULL",
                Uuids.toBytes(id)) == 1;
    }

    /** Report support: local status by payment id (= provider idempotency key). */
    public java.util.Map<UUID, PaymentStatus> statusesOf(java.util.Collection<UUID> ids) {
        java.util.Map<UUID, PaymentStatus> out = new java.util.HashMap<>();
        for (UUID id : ids) findById(id).ifPresent(p -> out.put(id, p.status()));
        return out;
    }

    public java.util.Map<PaymentStatus, Integer> countByStatus() {
        java.util.Map<PaymentStatus, Integer> out = new java.util.EnumMap<>(PaymentStatus.class);
        jdbc.query("SELECT status, COUNT(*) n FROM payments GROUP BY status",
                rs -> { out.put(PaymentStatus.valueOf(rs.getString("status")), rs.getInt("n")); });
        return out;
    }

    /** {@code providerRef}/{@code reason} are kept when null. True only when this call made the change. */
    public boolean transition(UUID id, PaymentStatus from, PaymentStatus to, String providerRef, String reason) {
        return jdbc.update("UPDATE payments SET status = ?, provider_ref = COALESCE(?, provider_ref), "
                        + "failure_reason = COALESCE(?, failure_reason), updated_at = NOW(3) WHERE id = ? AND status = ?",
                to.name(), providerRef, reason, Uuids.toBytes(id), from.name()) == 1;
    }

    /** Consumer dedupe (spec §11.3): true the first time this message id is seen, in the caller's transaction. */
    public boolean markProcessed(UUID messageId, String topic) {
        return jdbc.update("INSERT IGNORE INTO processed_events (event_id, topic, processed_at) VALUES (?, ?, NOW(3))",
                Uuids.toBytes(messageId), topic) == 1;
    }

    private Optional<Payment> one(String sql, Object... args) {
        List<Payment> rows = jdbc.query(sql, PaymentRepository::row, args);
        return rows.stream().findFirst();
    }

    private static Payment row(ResultSet rs, int n) throws SQLException {
        return new Payment(Uuids.fromBytes(rs.getBytes("id")), Uuids.fromBytes(rs.getBytes("booking_id")), rs.getLong("user_id"),
                rs.getLong("amount_cents"), PaymentStatus.valueOf(rs.getString("status")), rs.getString("idempotency_key"),
                rs.getString("request_hash"), rs.getString("provider_ref"), rs.getString("failure_reason"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }
}
