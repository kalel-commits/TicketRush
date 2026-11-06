package com.ticketrush.payment.jobs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Spec §12 daily cleanup: published outbox rows and dedupe rows older than 7 days. Unpublished rows are never
 * deleted. Deletes in batches of 1000 so no statement holds many locks.
 */
@Component
public class OutboxCleanup {

    private static final Logger log = LoggerFactory.getLogger(OutboxCleanup.class);
    private static final int BATCH = 1000;

    private final JdbcTemplate jdbc;

    OutboxCleanup(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Scheduled(cron = "${ticketrush.cleanup.cron:0 30 3 * * *}")
    void scheduled() {
        try {
            int n = run();
            if (n > 0) log.info("Cleanup deleted {} outbox/dedupe rows older than 7 days", n);
        } catch (RuntimeException e) {
            log.warn("Cleanup failed, will retry tomorrow: {}", e.toString());
        }
    }

    /** @return rows deleted */
    public int run() {
        return deleteAll("DELETE FROM outbox_events WHERE published_at < NOW(3) - INTERVAL 7 DAY LIMIT " + BATCH)
                + deleteAll("DELETE FROM processed_events WHERE processed_at < NOW(3) - INTERVAL 7 DAY LIMIT " + BATCH);
    }

    private int deleteAll(String sql) {
        int total = 0, n;
        do {
            n = jdbc.update(sql);
            total += n;
        } while (n == BATCH);
        return total;
    }
}
