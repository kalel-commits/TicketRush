package com.ticketrush.event.catalog;

import com.ticketrush.event.catalog.EventDtos.CreateEventRequest;
import com.ticketrush.event.catalog.EventDtos.EventPage;
import com.ticketrush.event.catalog.EventDtos.EventView;
import com.ticketrush.event.catalog.EventDtos.SeatView;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/events")
class EventController {

    private final EventAdminService admin;
    private final CatalogService catalog;
    private final AvailabilityService availability;

    EventController(EventAdminService admin, CatalogService catalog, AvailabilityService availability) {
        this.admin = admin;
        this.catalog = catalog;
        this.availability = availability;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    EventView create(@Valid @RequestBody CreateEventRequest req) {
        return catalog.created(admin.create(req));
    }

    @GetMapping
    EventPage list(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
                   @RequestParam(required = false) Listing listing) {
        int p = Math.max(page, 0);
        int s = Math.min(Math.max(size, 1), CatalogService.MAX_PAGE_SIZE);
        EventPage cached = catalog.list(p, s, listing);
        var counts = availability.availableSeats(cached.content().stream().map(EventView::id).toList());   // one query per page
        return new EventPage(cached.content().stream().map(e -> e.withAvailable(counts.getOrDefault(e.id(), 0))).toList(),
                cached.page(), cached.size(), cached.totalElements());
    }

    @GetMapping("/{id}")
    EventView details(@PathVariable long id) {
        return catalog.event(id).withAvailable(availability.availableSeats(id));
    }

    @GetMapping("/{id}/seats")
    List<SeatView> seats(@PathVariable long id) {
        return catalog.seats(id);
    }
}
