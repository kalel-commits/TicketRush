-- payment_db (spec §6.4). One row per payment attempt; many FAILED attempts per booking are allowed,
-- but at most one "active" attempt (in flight or money taken) thanks to the generated column below (§6.5).
CREATE TABLE payments (
    id                BINARY(16)   NOT NULL,             -- UUIDv7; also the provider idempotency key
    booking_id        BINARY(16)   NOT NULL,
    user_id           BIGINT       NOT NULL,
    amount_cents      BIGINT       NOT NULL,
    status            ENUM('PROCESSING','SUCCEEDED','FAILED','REFUND_PENDING','REFUNDED') NOT NULL,
    idempotency_key   VARCHAR(64)  NOT NULL,
    request_hash      CHAR(64)     NOT NULL,
    provider_ref      VARCHAR(64)  NULL,
    failure_reason    VARCHAR(64)  NULL,
    lookup_attempts   INT          NOT NULL DEFAULT 0,  -- reconciliation retries (plan 5)
    verified_at       DATETIME(3)  NULL,                -- set when booking confirmed CONFIRMED (plan 5)
    created_at        DATETIME(3)  NOT NULL,
    updated_at        DATETIME(3)  NOT NULL,
    active_booking_id BINARY(16) AS (
        CASE WHEN status IN ('PROCESSING','SUCCEEDED','REFUND_PENDING','REFUNDED') THEN booking_id END) STORED,
    PRIMARY KEY (id),
    UNIQUE KEY uk_payment_idem (user_id, idempotency_key),
    UNIQUE KEY uk_payment_active_booking (active_booking_id),
    KEY idx_payment_booking (booking_id),
    KEY idx_payment_status_updated (status, updated_at)
) ENGINE=InnoDB;

CREATE TABLE outbox_events (
    id           BINARY(16)   NOT NULL,            -- UUIDv7 = eventId in the envelope
    aggregate_id BINARY(16)   NOT NULL,            -- bookingId (Kafka key)
    topic        VARCHAR(100) NOT NULL,
    payload      JSON         NOT NULL,
    created_at   DATETIME(3)  NOT NULL,
    published_at DATETIME(3)  NULL,
    PRIMARY KEY (id),
    KEY idx_outbox_unpublished (published_at, created_at)
) ENGINE=InnoDB;

CREATE TABLE processed_events (
    event_id     BINARY(16)   NOT NULL,
    topic        VARCHAR(100) NOT NULL,
    processed_at DATETIME(3)  NOT NULL,
    PRIMARY KEY (event_id)
) ENGINE=InnoDB;
