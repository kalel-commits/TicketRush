package com.ticketrush.event;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class EventQueryIT extends IntegrationTestBase {

    @Autowired
    CacheManager cacheManager;

    private String getJson(String url) throws Exception {
        return mvc.perform(get(url)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    @Test
    void detailsSeatMapAndNotFound() throws Exception {
        long id = createEventId(2, 3);
        mvc.perform(get("/api/events/" + id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.totalSeats").value(6))
                .andExpect(jsonPath("$.availableSeats").value(6));
        mvc.perform(get("/api/events/" + id + "/seats")).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(6))
                .andExpect(jsonPath("$[0].seatNo").value("A1"))
                .andExpect(jsonPath("$[0].status").value("AVAILABLE"));
        mvc.perform(get("/api/events/99999999")).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mvc.perform(get("/api/events/99999999/seats")).andExpect(status().isNotFound());
    }

    @Test
    void listIsPagedOrderedAndSeesNewEvents() throws Exception {
        getJson("/api/events?page=0&size=50");                       // warm the list cache
        long a = createEventId(1, 1);
        long b = createEventId(1, 1);
        var page = json.readTree(getJson("/api/events?page=0&size=50"));
        List<Long> ids = new ArrayList<>();
        page.get("content").forEach(n -> ids.add(n.get("id").asLong()));
        assertThat(ids).contains(a, b);                             // creation evicted the cached list
        assertThat(page.get("size").asInt()).isEqualTo(50);
        assertThat(page.get("totalElements").asLong()).isGreaterThanOrEqualTo(2);
        List<String> starts = new ArrayList<>();
        page.get("content").forEach(n -> starts.add(n.get("startsAt").asString()));
        assertThat(starts).isSorted();
        assertThat(json.readTree(getJson("/api/events?page=0&size=500")).get("size").asInt()).isEqualTo(50);   // capped
    }

    @Test
    void eventDetailsAreCached() throws Exception {
        long id = createEventId(1, 2);
        getJson("/api/events/" + id);
        jdbc.update("UPDATE events SET name='Renamed behind the cache' WHERE id=?", id);
        assertThat(json.readTree(getJson("/api/events/" + id)).get("name").asString()).isNotEqualTo("Renamed behind the cache");
        cacheManager.getCache("ev-event").invalidate();   // clear() may be deferred; invalidate() is immediate
        assertThat(json.readTree(getJson("/api/events/" + id)).get("name").asString()).isEqualTo("Renamed behind the cache");
    }

    @Test
    void listStaysFastWhenRedisIsDown() throws Exception {
        for (int i = 0; i < 20; i++) createEventId(1, 1);
        cacheManager.getCache("ev-events").invalidate();
        var docker = REDIS.getDockerClient();
        docker.pauseContainerCmd(REDIS.getContainerId()).exec();
        try {
            long started = System.nanoTime();
            mvc.perform(get("/api/events?page=0&size=20")).andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(20));
            assertThat(java.time.Duration.ofNanos(System.nanoTime() - started)).isLessThan(java.time.Duration.ofMillis(1500));
        } finally {
            docker.unpauseContainerCmd(REDIS.getContainerId()).exec();
        }
    }

    @Test
    void readsStillWorkWhenRedisIsDown() throws Exception {
        long id = createEventId(1, 3);
        cacheManager.getCache("ev-event").invalidate();   // clear() may be deferred; invalidate() is immediate
        var docker = REDIS.getDockerClient();
        docker.pauseContainerCmd(REDIS.getContainerId()).exec();
        try {
            mvc.perform(get("/api/events/" + id)).andExpect(status().isOk()).andExpect(jsonPath("$.availableSeats").value(3));
        } finally {
            docker.unpauseContainerCmd(REDIS.getContainerId()).exec();
        }
    }

    @Test
    void catalogListingCarriesMetadataAndHidesInternalEvents() throws Exception {
        var body = eventBody(2, 5);
        body.put("listing", "CATALOG");
        body.put("artist", "Aurora Lane");
        body.put("category", "POP");
        body.put("city", "Mumbai");
        body.put("description", "A fictional demo listing.");
        String created = createEvent(body, admin()).andExpect(status().isCreated())
                .andExpect(jsonPath("$.listing").value("CATALOG"))
                .andExpect(jsonPath("$.artist").value("Aurora Lane"))
                .andExpect(jsonPath("$.category").value("POP"))
                .andExpect(jsonPath("$.city").value("Mumbai"))
                .andExpect(jsonPath("$.description").value("A fictional demo listing."))
                .andReturn().getResponse().getContentAsString();
        long catalog = json.readTree(created).get("id").asLong();
        long internal = createEventId(1, 1);   // no listing given: an internal (test) event

        mvc.perform(get("/api/events/" + internal)).andExpect(status().isOk()).andExpect(jsonPath("$.listing").value("INTERNAL"));
        List<Long> listed = new ArrayList<>();
        json.readTree(getJson("/api/events?page=0&size=50&listing=CATALOG")).get("content").forEach(n -> {
            listed.add(n.get("id").asLong());
            assertThat(n.get("listing").asString()).isEqualTo("CATALOG");
        });
        assertThat(listed).contains(catalog).doesNotContain(internal);
        List<Long> all = new ArrayList<>();
        json.readTree(getJson("/api/events?page=0&size=50")).get("content").forEach(n -> all.add(n.get("id").asLong()));
        assertThat(all).contains(internal);   // without the filter the list is unchanged
    }

    @Test
    void unknownListingOrCategoryIsRejected() throws Exception {
        var body = eventBody(1, 1);
        body.put("listing", "PUBLIC");
        createEvent(body, admin()).andExpect(status().isBadRequest());
        var body2 = eventBody(1, 1);
        body2.put("category", "OPERA_HOUSE_PARTY");
        createEvent(body2, admin()).andExpect(status().isBadRequest());
        mvc.perform(get("/api/events?listing=NOPE")).andExpect(status().isBadRequest());
    }
}
