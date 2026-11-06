package com.ticketrush.payment.outbox;

import com.ticketrush.payment.domain.Uuids;
import com.ticketrush.common.events.EventEnvelope;
import com.ticketrush.common.id.UuidV7;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Transactional outbox (spec §11.2): the event row commits together with the state change that caused it.
 * MANDATORY propagation makes "write an event outside a transaction" a programming error.
 */
@Component
public class OutboxWriter {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final Clock clock;

    OutboxWriter(JdbcTemplate jdbc, ObjectMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public UUID append(UUID aggregateId, String topic, Object payload) {
        UUID id = UuidV7.next();
        Instant now = clock.instant();
        String envelope = json.writeValueAsString(new EventEnvelope<>(id, topic, 1, now, aggregateId, payload));
        jdbc.update("INSERT INTO outbox_events (id, aggregate_id, topic, payload, created_at) VALUES (?, ?, ?, ?, ?)",
                Uuids.toBytes(id), Uuids.toBytes(aggregateId), topic, envelope, Timestamp.from(now));
        return id;
    }
}
