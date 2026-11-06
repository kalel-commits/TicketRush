package com.ticketrush.payment.outbox;

import com.ticketrush.payment.domain.Uuids;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Polling outbox relay (spec §11.2). Each instance claims a batch with SKIP LOCKED, sends synchronously and
 * marks the rows published in the same transaction. A crash after send but before commit re-sends the rows:
 * at-least-once, and consumers deduplicate by eventId. (This is the one place a DB transaction is open
 * during a Kafka send, by design of the pattern.)
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final int BATCH = 100;

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate tx;

    OutboxRelay(JdbcTemplate jdbc, NamedParameterJdbcTemplate named, KafkaTemplate<String, String> kafka,
                PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.named = named;
        this.kafka = kafka;
        // READ COMMITTED: the locking read then takes record locks only. Under the default REPEATABLE READ it also
        // gap-locked the "unpublished" index range for the whole Kafka send, so every business transaction appending
        // an outbox row waited for Kafka (load test S1: 197 s of lock waits; with Kafka down: lock wait timeouts).
        // SKIP LOCKED still splits batches between instances; new rows are simply picked up by the next batch.
        this.tx = new TransactionTemplate(txManager);
        this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @Scheduled(fixedDelayString = "${ticketrush.outbox.relay-interval-ms}")
    void scheduled() {
        try {
            publishBatch();
        } catch (RuntimeException e) {
            log.warn("Outbox relay batch failed, will retry: {}", e.toString());
        }
    }

    /** @return number of rows published */
    public int publishBatch() {
        Integer n = tx.execute(status -> {
            record Row(byte[] id, byte[] aggregate, String topic, String payload) {}
            List<Row> rows = jdbc.query("""
                    SELECT id, aggregate_id, topic, payload FROM outbox_events
                     WHERE published_at IS NULL ORDER BY created_at LIMIT ? FOR UPDATE SKIP LOCKED""",
                    (rs, i) -> new Row(rs.getBytes("id"), rs.getBytes("aggregate_id"), rs.getString("topic"), rs.getString("payload")),
                    BATCH);
            for (Row r : rows) {
                try {
                    kafka.send(r.topic(), Uuids.fromBytes(r.aggregate()).toString(), r.payload()).get(10, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new IllegalStateException("Kafka send failed for outbox row " + Uuids.fromBytes(r.id()), e);
                }
            }
            if (!rows.isEmpty()) {
                named.update("UPDATE outbox_events SET published_at = NOW(3) WHERE id IN (:ids)",
                        Map.of("ids", rows.stream().map(Row::id).toList()));
            }
            return rows.size();
        });
        return n == null ? 0 : n;
    }
}
