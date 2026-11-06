-- Additive only: customer-facing metadata and a listing flag that separates the discovery catalog
-- from test / load-test / demo-race events. Existing rows become INTERNAL (still bookable by id, not listed).
ALTER TABLE events
    ADD COLUMN listing     VARCHAR(16)   NOT NULL DEFAULT 'INTERNAL',
    ADD COLUMN artist      VARCHAR(200)  NULL,
    ADD COLUMN category    VARCHAR(20)   NULL,
    ADD COLUMN city        VARCHAR(100)  NULL,
    ADD COLUMN description VARCHAR(2000) NULL,
    ADD KEY idx_events_listing_starts (listing, starts_at);
