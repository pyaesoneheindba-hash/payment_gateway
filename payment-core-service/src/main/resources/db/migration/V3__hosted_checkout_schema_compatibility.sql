-- Compatibility repair for databases that were baselined at Flyway version 1
-- from an older starter schema. In that situation Flyway records a baseline at
-- version 1 and intentionally does not execute V1__baseline_schema.sql, so later
-- hosted-checkout columns introduced into V1 would otherwise be absent.
--
-- This migration is deliberately idempotent so it is safe both for:
--   * legacy/baselined local databases, and
--   * fresh databases where V1 already created these objects.

ALTER TABLE payment
    ADD COLUMN IF NOT EXISTS expires_at TIMESTAMPTZ;

ALTER TABLE payment
    ADD COLUMN IF NOT EXISTS presentation_mode VARCHAR(20) NOT NULL DEFAULT 'QR_STRING';

ALTER TABLE payment DROP CONSTRAINT IF EXISTS ck_payment_presentation_mode;
ALTER TABLE payment
    ADD CONSTRAINT ck_payment_presentation_mode
    CHECK (presentation_mode IN ('QR_STRING', 'HOSTED_PAGE'));

CREATE INDEX IF NOT EXISTS idx_payment_expiry
    ON payment(expires_at)
    WHERE status IN ('QR_GENERATED', 'ACTION_REQUIRED', 'PENDING');

CREATE TABLE IF NOT EXISTS payment_checkout_session (
    checkout_session_id UUID PRIMARY KEY,
    payment_id UUID NOT NULL UNIQUE REFERENCES payment(payment_id) ON DELETE CASCADE,
    merchant_id UUID NOT NULL REFERENCES merchant(merchant_id),
    session_token_hash VARCHAR(64) NOT NULL UNIQUE,
    return_url VARCHAR(2000) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    expires_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    redirected_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_checkout_status CHECK (status IN ('ACTIVE', 'COMPLETED', 'EXPIRED', 'CANCELLED'))
);

CREATE INDEX IF NOT EXISTS idx_checkout_expiry
    ON payment_checkout_session(status, expires_at)
    WHERE status = 'ACTIVE';

CREATE INDEX IF NOT EXISTS idx_checkout_merchant_created
    ON payment_checkout_session(merchant_id, created_at DESC);

-- Preserve the same updated_at behavior as the rest of the schema when the
-- helper function is available (it is part of the legacy/full schema).
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM pg_proc p
        JOIN pg_namespace n ON n.oid = p.pronamespace
        WHERE p.proname = 'payment_gateway_set_updated_at'
          AND n.nspname = current_schema()
    ) THEN
        DROP TRIGGER IF EXISTS trg_payment_checkout_session_updated_at ON payment_checkout_session;
        CREATE TRIGGER trg_payment_checkout_session_updated_at
        BEFORE UPDATE ON payment_checkout_session
        FOR EACH ROW EXECUTE FUNCTION payment_gateway_set_updated_at();
    END IF;
END $$;
