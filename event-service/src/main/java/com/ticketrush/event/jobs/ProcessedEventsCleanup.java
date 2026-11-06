package com.ticketrush.event.jobs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Spec §12 daily cleanup: dedupe rows older than 7 days (event-service publishes nothing, so it has no outbox). */
@Component
public class ProcessedEventsCleanup {

    private static final Logger log = LoggerFactory.getLogger(ProcessedEventsCleanup.class);
    private static final int BATCH = 1000;

    private final JdbcTemplate jdbc;

    ProcessedEventsCleanup(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Scheduled(cron = "${ticketrush.cleanup.cron:0 30 3 * * *}")
    void scheduled() {
        try {
            int n = run();
            if (n > 0) log.info("Cleanup deleted {} dedupe rows older than 7 days", n);
        } catch (RuntimeException e) {
            log.warn("Cleanup failed, will retry tomorrow: {}", e.toString());
        }
    }

    /** @return rows deleted */
    public int run() {
        int total = 0, n;
        do {
            n = jdbc.update("DELETE FROM processed_events WHERE processed_at < NOW(3) - INTERVAL 7 DAY LIMIT " + BATCH);
            total += n;
        } while (n == BATCH);
        return total;
    }
}
