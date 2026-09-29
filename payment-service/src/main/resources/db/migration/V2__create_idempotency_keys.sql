-- One row per (tenant, key). Inserted in the same transaction as the PENDING payment, so this
-- primary key alone guarantees one payment per key — Redis in front of it is only a fast path.
CREATE TABLE idempotency_keys (
    tenant          varchar(100) NOT NULL,
    idempotency_key varchar(255) NOT NULL,
    fingerprint     char(64)     NOT NULL,
    -- DEFERRABLE: the key row is written before the payment row in the same transaction, so
    -- the reference can only be checked at commit.
    payment_id      uuid         NOT NULL REFERENCES payments (id) DEFERRABLE INITIALLY DEFERRED,
    state           varchar(16)  NOT NULL CHECK (state IN ('IN_PROGRESS', 'COMPLETED')),
    locked_until    timestamptz  NOT NULL,
    response_status smallint,
    response_body   jsonb,
    created_at      timestamptz  NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant, idempotency_key),
    -- A completed key always has a response to replay; an in-progress one never does.
    CHECK ((state = 'COMPLETED') = (response_status IS NOT NULL AND response_body IS NOT NULL))
);
