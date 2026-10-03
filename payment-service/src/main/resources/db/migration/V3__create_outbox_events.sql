-- The transactional outbox (spec §4.2). A row is written in the same transaction as the payment
-- status change it describes, so "change committed" and "event recorded" cannot disagree.
CREATE TABLE outbox_events (
    id           uuid         PRIMARY KEY,           -- the eventId
    -- Relay order. For one payment, seq order is commit order: the next change can only insert
    -- after reading the previous change's committed version (optimistic locking, spec §5.1).
    seq          bigserial    NOT NULL UNIQUE,
    aggregate_id uuid         NOT NULL,              -- payment id, the Kafka key
    event_type   varchar(64)  NOT NULL,
    payload      jsonb        NOT NULL,
    created_at   timestamptz  NOT NULL DEFAULT now(),
    published_at timestamptz                          -- set only after the broker acknowledges
);

-- The relay only ever scans unpublished rows, so it stays cheap however large the table grows.
CREATE INDEX outbox_events_unpublished_idx ON outbox_events (seq) WHERE published_at IS NULL;
