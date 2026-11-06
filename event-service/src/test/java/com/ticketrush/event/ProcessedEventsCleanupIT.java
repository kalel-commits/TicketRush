package com.ticketrush.event;

import com.ticketrush.common.id.UuidV7;
import com.ticketrush.event.jobs.ProcessedEventsCleanup;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Spec §12: dedupe rows older than 7 days are deleted (event-service has no outbox). */
class ProcessedEventsCleanupIT extends IntegrationTestBase {

    @Autowired
    ProcessedEventsCleanup cleanup;

    UUID processed(int daysAgo) {
        UUID id = UuidV7.next();
        jdbc.update("INSERT INTO processed_events (event_id, topic, processed_at) VALUES (UUID_TO_BIN(?), 't', NOW(3) - INTERVAL ? DAY)",
                id.toString(), daysAgo);
        return id;
    }

    boolean exists(UUID id) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM processed_events WHERE event_id = UUID_TO_BIN(?)", Integer.class, id.toString()) == 1;
    }

    @Test
    void deletesOnlyDedupeRowsOlderThanSevenDays() {
        UUID old = processed(8), recent = processed(6);

        assertThat(cleanup.run()).isGreaterThanOrEqualTo(1);

        assertThat(exists(old)).isFalse();
        assertThat(exists(recent)).isTrue();
    }
}
