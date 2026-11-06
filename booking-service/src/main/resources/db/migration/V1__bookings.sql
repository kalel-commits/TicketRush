CREATE TABLE bookings (
    id              BINARY(16)   NOT NULL,          -- UUIDv7
    user_id         BIGINT       NOT NULL,
    event_id        BIGINT       NOT NULL,
    seat_count      TINYINT      NOT NULL,
    amount_cents    BIGINT       NULL,              -- set from the hold response
    status          ENUM('HOLDING','PENDING_PAYMENT','CONFIRMED','CANCELLED','EXPIRED','FAILED') NOT NULL,
    idempotency_key VARCHAR(64)  NOT NULL,
    request_hash    CHAR(64)     NOT NULL,          -- SHA-256 of eventId + sorted seatIds
    expires_at      DATETIME(3)  NOT NULL,          -- HOLDING: now+60s; PENDING_PAYMENT: held_until
    failure_reason  VARCHAR(64)  NULL,
    payment_id      BINARY(16)   NULL,              -- set on CONFIRMED
    created_at      DATETIME(3)  NOT NULL,
    updated_at      DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_booking_idem (user_id, idempotency_key),
    KEY idx_booking_user_created (user_id, created_at),
    KEY idx_booking_status_expires (status, expires_at),
    CONSTRAINT chk_booking_seat_count CHECK (seat_count BETWEEN 1 AND 4)
) ENGINE=InnoDB;

CREATE TABLE booking_seats (
    booking_id BINARY(16)  NOT NULL,
    seat_id    BIGINT      NOT NULL,
    seat_no    VARCHAR(10) NULL,                   -- snapshot from the hold response
    PRIMARY KEY (booking_id, seat_id),
    CONSTRAINT fk_bs_booking FOREIGN KEY (booking_id) REFERENCES bookings (id)
) ENGINE=InnoDB;

CREATE TABLE user_event_quota (
    user_id        BIGINT  NOT NULL,
    event_id       BIGINT  NOT NULL,
    seats_reserved TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (user_id, event_id),
    CONSTRAINT chk_quota_non_negative CHECK (seats_reserved >= 0)
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
