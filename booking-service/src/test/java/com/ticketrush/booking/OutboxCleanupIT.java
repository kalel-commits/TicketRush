package com.ticketrush.booking;

import com.ticketrush.booking.jobs.OutboxCleanup;
import com.ticketrush.common.id.UuidV7;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Spec §12: published outbox rows and dedupe rows older than 7 days are deleted; nothing newer, nothing unpublished. */
class OutboxCleanupIT extends IntegrationTestBase {

    @Autowired
    OutboxCleanup cleanup;

    UUID outbox(int daysAgo, boolean published) {
        UUID id = UuidV7.next();
        jdbc.update("INSERT INTO outbox_events (id, aggregate_id, topic, payload, created_at) "
                + "VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), 't', '{}', NOW(3) - INTERVAL ? DAY)", id.toString(), UUID.randomUUID().toString(), daysAgo);
        if (published) {
            jdbc.update("UPDATE outbox_events SET published_at = NOW(3) - INTERVAL ? DAY WHERE id = UUID_TO_BIN(?)", daysAgo, id.toString());
        }
        return id;
    }

    UUID processed(int daysAgo) {
        UUID id = UuidV7.next();
        jdbc.update("INSERT INTO processed_events (event_id, topic, processed_at) VALUES (UUID_TO_BIN(?), 't', NOW(3) - INTERVAL ? DAY)",
                id.toString(), daysAgo);
        return id;
    }

    boolean exists(String table, String column, UUID id) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + column + " = UUID_TO_BIN(?)", Integer.class, id.toString()) == 1;
    }

    @Test
    void deletesOnlyOldPublishedOutboxRowsAndOldDedupeRows() {
        UUID oldPublished = outbox(8, true), recentPublished = outbox(6, true), oldUnpublished = outbox(8, false);
        UUID oldProcessed = processed(8), recentProcessed = processed(6);

        assertThat(cleanup.run()).isGreaterThanOrEqualTo(2);

        assertThat(exists("outbox_events", "id", oldPublished)).isFalse();
        assertThat(exists("outbox_events", "id", recentPublished)).isTrue();
        assertThat(exists("outbox_events", "id", oldUnpublished)).as("never delete an unsent event").isTrue();
        assertThat(exists("processed_events", "event_id", oldProcessed)).isFalse();
        assertThat(exists("processed_events", "event_id", recentProcessed)).isTrue();
        // don't leave an unsent dummy event behind for other tests' relays to publish
        jdbc.update("DELETE FROM outbox_events WHERE id = UUID_TO_BIN(?)", oldUnpublished.toString());
    }
}
