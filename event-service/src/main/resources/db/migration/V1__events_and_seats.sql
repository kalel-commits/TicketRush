CREATE TABLE events (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    name           VARCHAR(200) NOT NULL,
    venue          VARCHAR(200) NOT NULL,
    starts_at      DATETIME(3)  NOT NULL,
    sale_opens_at  DATETIME(3)  NOT NULL,
    sale_closes_at DATETIME(3)  NOT NULL,
    price_cents    BIGINT       NOT NULL,
    total_seats    INT          NOT NULL,
    created_at     DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_events_sale_opens (sale_opens_at),
    KEY idx_events_starts (starts_at),
    CONSTRAINT chk_events_price CHECK (price_cents > 0),
    CONSTRAINT chk_events_window CHECK (sale_opens_at < sale_closes_at)
) ENGINE=InnoDB;

CREATE TABLE seats (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    event_id   BIGINT      NOT NULL,
    seat_no    VARCHAR(10) NOT NULL,
    status     ENUM('AVAILABLE','HELD','BOOKED') NOT NULL,
    booking_id BINARY(16)  NULL,
    held_until DATETIME(3) NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_seat (event_id, seat_no),
    KEY idx_seat_event_status (event_id, status),      -- seat map, available count
    KEY idx_seat_booking (booking_id),                 -- confirm/release by booking
    KEY idx_seat_status_held (status, held_until),     -- reconciler scan (plan 5)
    CONSTRAINT fk_seat_event FOREIGN KEY (event_id) REFERENCES events (id),
    -- illegal seat states are unrepresentable
    CONSTRAINT chk_seat_state CHECK (
        (status = 'AVAILABLE' AND booking_id IS NULL AND held_until IS NULL)
     OR (status = 'HELD'      AND booking_id IS NOT NULL AND held_until IS NOT NULL)
     OR (status = 'BOOKED'    AND booking_id IS NOT NULL))
) ENGINE=InnoDB;

CREATE TABLE processed_events (
    event_id     BINARY(16)   NOT NULL,
    topic        VARCHAR(100) NOT NULL,
    processed_at DATETIME(3)  NOT NULL,
    PRIMARY KEY (event_id)
) ENGINE=InnoDB;
