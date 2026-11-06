package com.ticketrush.event.catalog;

import com.ticketrush.common.error.ApiException;
import com.ticketrush.common.error.ErrorCode;
import com.ticketrush.event.catalog.EventDtos.EventPage;
import com.ticketrush.event.catalog.EventDtos.EventView;
import com.ticketrush.event.catalog.EventDtos.SeatView;
import com.ticketrush.event.config.CacheConfig;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Cached catalog reads. The cached views carry the seat count from when they were cached;
 * callers overlay the live (5 s cached) count from {@link AvailabilityService}.
 */
@Service
public class CatalogService {

    public static final int MAX_PAGE_SIZE = 50;

    private final EventRepository events;
    private final JdbcTemplate jdbc;

    CatalogService(EventRepository events, JdbcTemplate jdbc) {
        this.events = events;
        this.jdbc = jdbc;
    }

    @Cacheable(cacheNames = CacheConfig.EVENT, key = "#id", sync = true)
    public EventView event(long id) {
        Event e = events.findById(id).orElseThrow(() -> notFound(id));
        return EventView.of(e, e.getTotalSeats());
    }

    /** {@code listing} null = every event (the original behaviour); otherwise only that listing. */
    @Cacheable(cacheNames = CacheConfig.EVENTS, key = "#page + ':' + #size + ':' + (#listing ?: 'ALL')", sync = true)
    public EventPage list(int page, int size, Listing listing) {
        PageRequest req = PageRequest.of(page, size, Sort.by("startsAt").and(Sort.by("id")));
        Page<Event> p = listing == null ? events.findAll(req) : events.findByListing(listing, req);
        return new EventPage(p.getContent().stream().map(e -> EventView.of(e, e.getTotalSeats())).toList(),
                page, size, p.getTotalElements());
    }

    /** Warm the details cache and drop cached list pages when an event is created. */
    @CachePut(cacheNames = CacheConfig.EVENT, key = "#view.id()")
    @CacheEvict(cacheNames = CacheConfig.EVENTS, allEntries = true)
    public EventView created(EventView view) {
        return view;
    }

    /** Seat map: always from MySQL (correctness over speed). */
    public List<SeatView> seats(long eventId) {
        if (!events.existsById(eventId)) {
            throw notFound(eventId);
        }
        return jdbc.query("SELECT id, seat_no, status FROM seats WHERE event_id=? ORDER BY id",
                (rs, i) -> new SeatView(rs.getLong("id"), rs.getString("seat_no"), rs.getString("status")), eventId);
    }

    private static ApiException notFound(long id) {
        return new ApiException(ErrorCode.NOT_FOUND, "Event " + id + " not found");
    }
}
