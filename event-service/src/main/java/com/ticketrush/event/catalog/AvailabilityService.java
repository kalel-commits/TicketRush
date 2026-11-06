package com.ticketrush.event.catalog;

import com.ticketrush.event.config.CacheConfig;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

/** Available-seat count for display only (5 s cache, never used for decisions). */
@Service
public class AvailabilityService {

    private final NamedParameterJdbcTemplate jdbc;

    AvailabilityService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Cacheable(cacheNames = CacheConfig.AVAILABLE, key = "#eventId")
    public int availableSeats(long eventId) {
        Integer n = jdbc.getJdbcTemplate().queryForObject("SELECT COUNT(*) FROM seats WHERE event_id=? AND status='AVAILABLE'", Integer.class, eventId);
        return n == null ? 0 : n;
    }

    /**
     * Counts for a whole page in ONE query, uncached: a list page must not make one cache round-trip per
     * event (with Redis down that is 200 ms timeout x 20 events).
     */
    public Map<Long, Integer> availableSeats(Collection<Long> eventIds) {
        if (eventIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Integer> counts = new HashMap<>();
        eventIds.forEach(id -> counts.put(id, 0));
        jdbc.query("SELECT event_id, COUNT(*) AS n FROM seats WHERE event_id IN (:ids) AND status = 'AVAILABLE' GROUP BY event_id",
                Map.of("ids", eventIds), rs -> {
                    counts.put(rs.getLong("event_id"), rs.getInt("n"));
                });
        return counts;
    }

    @CacheEvict(cacheNames = CacheConfig.AVAILABLE, key = "#eventId")
    public void evict(long eventId) {
        // annotation does the work
    }
}
