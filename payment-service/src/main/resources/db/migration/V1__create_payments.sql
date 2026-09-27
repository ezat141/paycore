-- One row per payment. The CHECK constraints repeat the application's validation on purpose:
-- the database is the last line, and a bug in the service must not be able to store an amount
-- or a status that the rest of the system cannot interpret.
CREATE TABLE payments (
    id                        uuid         PRIMARY KEY,
    tenant                    varchar(100) NOT NULL,
    amount_minor              bigint       NOT NULL CHECK (amount_minor BETWEEN 1 AND 1000000000),
    currency                  char(3)      NOT NULL CHECK (currency IN ('USD', 'EUR', 'GBP', 'EGP')),
    card_token                varchar(64)  NOT NULL,
    description               varchar(255),
    status                    varchar(16)  NOT NULL CHECK (status IN ('PENDING', 'AUTHORIZED', 'DECLINED',
                                                                      'UNKNOWN', 'CAPTURING', 'CAPTURED',
                                                                      'VOIDING', 'VOIDED')),
    decline_code              varchar(64),
    acquirer_authorization_id varchar(64),
    transition_started_at     timestamptz,
    version                   bigint       NOT NULL,
    created_at                timestamptz  NOT NULL,
    updated_at                timestamptz  NOT NULL
);

-- Serves GET /payments: one tenant, newest first, id as the tie-breaker.
CREATE INDEX payments_tenant_created_idx ON payments (tenant, created_at DESC, id DESC);
