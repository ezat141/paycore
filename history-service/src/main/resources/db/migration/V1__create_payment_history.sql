-- Append-only log of payment status changes (spec §6.2). The event id is the primary key, so
-- storing an event again is a no-op: that is what makes at-least-once delivery harmless.
CREATE TABLE payment_history (
    event_id     uuid         PRIMARY KEY,
    payment_id   uuid         NOT NULL,
    tenant       varchar(100) NOT NULL,
    version      bigint       NOT NULL,
    from_status  varchar(16),
    to_status    varchar(16)  NOT NULL CHECK (to_status IN ('PENDING', 'AUTHORIZED', 'DECLINED', 'UNKNOWN',
                                                            'CAPTURING', 'CAPTURED', 'VOIDING', 'VOIDED')),
    amount       bigint       NOT NULL,
    currency     char(3)      NOT NULL,
    decline_code varchar(64),
    occurred_at  timestamptz  NOT NULL,
    received_at  timestamptz  NOT NULL DEFAULT now(),
    UNIQUE (payment_id, version)
);

CREATE INDEX payment_history_read_idx ON payment_history (tenant, payment_id, version);
