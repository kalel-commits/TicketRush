package com.ticketrush.event;

import com.ticketrush.common.id.UuidV7;
import com.ticketrush.event.hold.HoldRequest;
import com.ticketrush.event.hold.HoldService;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class BookingEventsIT extends IntegrationTestBase {

    @Autowired
    KafkaTemplate<String, String> kafka;
    @Autowired
    HoldService holds;
    @Autowired
    MeterRegistry meters;

    record Seat(long eventId, long seatId) {}

    Seat newSeat() throws Exception {
        long e = createEventId(1, 1);
        return new Seat(e, jdbc.queryForObject("SELECT id FROM seats WHERE event_id=?", Long.class, e));
    }

    void send(String topic, UUID messageId, UUID bookingId, Map<String, Object> payload) throws Exception {
        String value = json.writeValueAsString(Map.of(
                "eventId", messageId.toString(), "eventType", topic, "schemaVersion", 1,
                "occurredAt", Instant.now().toString(), "aggregateId", bookingId.toString(), "payload", payload));
        kafka.send(topic, bookingId.toString(), value).get();
    }

    void confirmed(UUID messageId, UUID booking, Seat s) throws Exception {
        send("booking.confirmed", messageId, booking, Map.of("bookingId", booking.toString(), "eventId", s.eventId(), "seatIds", List.of(s.seatId())));
    }

    void cancelled(UUID messageId, UUID booking, Seat s) throws Exception {
        send("booking.cancelled", messageId, booking, Map.of("bookingId", booking.toString(), "eventId", s.eventId(), "reason", "EXPIRED"));
    }

    String seatState(Seat s) {
        return jdbc.queryForObject("SELECT CONCAT(status, ':', COALESCE(BIN_TO_UUID(booking_id), '-')) FROM seats WHERE id=?", String.class, s.seatId());
    }

    void awaitState(Seat s, String expected) {
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(seatState(s)).isEqualTo(expected));
    }

    @Test
    void confirmedBooksHeldSeatsExactlyOnce() throws Exception {
        Seat s = newSeat();
        UUID b = UuidV7.next();
        holds.hold(s.eventId(), new HoldRequest(b, List.of(s.seatId()), 600));
        UUID msg = UuidV7.next();
        confirmed(msg, b, s);
        confirmed(msg, b, s);                                         // duplicate delivery
        awaitState(s, "BOOKED:" + b);
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(
                jdbc.queryForObject("SELECT COUNT(*) FROM processed_events WHERE event_id=UUID_TO_BIN(?)", Integer.class, msg.toString())).isEqualTo(1));
        assertThat(jdbc.queryForObject("SELECT held_until IS NULL FROM seats WHERE id=?", Boolean.class, s.seatId())).isTrue();
    }

    @Test
    void cancelledReleasesHeldSeats() throws Exception {
        Seat s = newSeat();
        UUID b = UuidV7.next();
        holds.hold(s.eventId(), new HoldRequest(b, List.of(s.seatId()), 600));
        cancelled(UuidV7.next(), b, s);
        awaitState(s, "AVAILABLE:-");
    }

    /** Spec §16.1 duplicate delivery: the redelivered cancel (same eventId) must not release a later hold. */
    @Test
    void cancelledDeliveredTwiceWithOneEventIdReleasesOnce() throws Exception {
        Seat s = newSeat();
        UUID b = UuidV7.next();
        holds.hold(s.eventId(), new HoldRequest(b, List.of(s.seatId()), 600));
        UUID msg = UuidV7.next();
        cancelled(msg, b, s);
        awaitState(s, "AVAILABLE:-");
        holds.hold(s.eventId(), new HoldRequest(b, List.of(s.seatId()), 600));   // held again (same booking id)
        cancelled(msg, b, s);                                                     // the duplicate arrives late
        UUID marker = UuidV7.next();                                              // a later message proves the duplicate was consumed
        cancelled(marker, UuidV7.next(), s);
        await().atMost(Duration.ofSeconds(15)).until(() -> jdbc.queryForObject(
                "SELECT COUNT(*) FROM processed_events WHERE event_id=UUID_TO_BIN(?)", Integer.class, marker.toString()) == 1);
        assertThat(seatState(s)).isEqualTo("HELD:" + b);
    }

    @Test
    void lateCancelDoesNotReleaseANewHoldersSeat() throws Exception {
        Seat s = newSeat();
        UUID x = UuidV7.next();
        UUID y = UuidV7.next();
        holds.hold(s.eventId(), new HoldRequest(x, List.of(s.seatId()), 600));
        cancelled(UuidV7.next(), x, s);
        awaitState(s, "AVAILABLE:-");
        holds.hold(s.eventId(), new HoldRequest(y, List.of(s.seatId()), 600));
        UUID lateMsg = UuidV7.next();
        cancelled(lateMsg, x, s);                                     // a second, late cancel for X
        await().atMost(Duration.ofSeconds(15)).until(() -> jdbc.queryForObject(
                "SELECT COUNT(*) FROM processed_events WHERE event_id=UUID_TO_BIN(?)", Integer.class, lateMsg.toString()) == 1);
        assertThat(seatState(s)).isEqualTo("HELD:" + y);
    }

    @Test
    void confirmWithoutHoldIsAnInvariantViolationAndChangesNothing() throws Exception {
        Seat s = newSeat();
        double before = meters.counter("event.invariant.violations").count();
        UUID msg = UuidV7.next();
        confirmed(msg, UuidV7.next(), s);
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(meters.counter("event.invariant.violations").count()).isEqualTo(before + 1));
        assertThat(seatState(s)).isEqualTo("AVAILABLE:-");
    }

    @Test
    void malformedMessagesGoToTheDeadLetterTopic() throws Exception {
        String marker = "not-json-" + UUID.randomUUID();
        kafka.send("booking.confirmed", "k", marker).get();
        try (var consumer = new KafkaConsumer<String, String>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "dlt-check-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"), new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of("booking.confirmed.DLT"));
            await().atMost(Duration.ofSeconds(30)).until(() -> {
                for (var r : consumer.poll(Duration.ofMillis(500))) {
                    if (marker.equals(r.value())) return true;
                }
                return false;
            });
        }
    }
}
