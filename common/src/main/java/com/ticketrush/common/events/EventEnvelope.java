package com.ticketrush.common.events;

import java.time.Instant;
import java.util.UUID;

/** Kafka message envelope (spec §11.1). {@code eventId} is the dedupe key for consumers. */
public record EventEnvelope<T>(UUID eventId, String eventType, int schemaVersion, Instant occurredAt, UUID aggregateId, T payload) {
}
