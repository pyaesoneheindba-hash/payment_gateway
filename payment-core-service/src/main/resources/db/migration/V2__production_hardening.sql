-- Production hardening added after the Angular portal starter.
-- Safe to apply to the previous generated schema. Foreign keys are NOT VALID so
-- legacy orphan rows do not block deployment; they still protect all new writes.

-- OIDC portal identities: authentication is external, local table is authorization only.
ALTER TABLE portal_user ADD COLUMN IF NOT EXISTS oidc_subject VARCHAR(255);
-- Legacy local portal credentials are no longer authentication inputs. Remove the
-- stored verifier material after switching portal authentication to external OIDC.
ALTER TABLE portal_user DROP COLUMN IF EXISTS password_salt;
ALTER TABLE portal_user DROP COLUMN IF EXISTS password_hash;
ALTER TABLE portal_user DROP COLUMN IF EXISTS password_iterations;
CREATE UNIQUE INDEX IF NOT EXISTS uq_portal_user_oidc_subject
    ON portal_user(oidc_subject) WHERE oidc_subject IS NOT NULL;

-- Atomic per-payment provider attempt allocation; replaces COUNT(*) + 1 races.
CREATE TABLE IF NOT EXISTS payment_provider_attempt_counter (
    payment_id UUID PRIMARY KEY REFERENCES payment(payment_id) ON DELETE CASCADE,
    last_attempt_no INTEGER NOT NULL CHECK (last_attempt_no > 0),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
INSERT INTO payment_provider_attempt_counter(payment_id,last_attempt_no)
SELECT payment_id, MAX(attempt_no)
FROM provider_transaction
GROUP BY payment_id
ON CONFLICT(payment_id) DO UPDATE
SET last_attempt_no=GREATEST(payment_provider_attempt_counter.last_attempt_no,EXCLUDED.last_attempt_no),
    updated_at=now();

-- RocketMQ outbox relay operational state.
ALTER TABLE outbox_event ADD COLUMN IF NOT EXISTS publish_attempt_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE outbox_event ADD COLUMN IF NOT EXISTS last_publish_error TEXT;
ALTER TABLE outbox_event ADD COLUMN IF NOT EXISTS published_at TIMESTAMPTZ;
ALTER TABLE outbox_event ADD COLUMN IF NOT EXISTS next_publish_at TIMESTAMPTZ NOT NULL DEFAULT now();
CREATE INDEX IF NOT EXISTS idx_outbox_publish_due ON outbox_event(next_publish_at,created_at) WHERE status='NEW';
ALTER TABLE outbox_event DROP CONSTRAINT IF EXISTS ck_outbox_publish_attempt_count;
ALTER TABLE outbox_event ADD CONSTRAINT ck_outbox_publish_attempt_count CHECK (publish_attempt_count >= 0);

-- Consumer idempotency. One event is processed at most once per logical consumer.
CREATE TABLE IF NOT EXISTS inbox_event (
    consumer_name VARCHAR(128) NOT NULL,
    event_id UUID NOT NULL,
    message_id VARCHAR(255),
    event_type VARCHAR(128) NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    processed_at TIMESTAMPTZ,
    PRIMARY KEY(consumer_name,event_id)
);
CREATE INDEX IF NOT EXISTS idx_inbox_event_received ON inbox_event(received_at DESC);

-- Stronger refund row invariants. Aggregate over-refund is enforced by a payment-row
-- pessimistic lock in application code because it spans multiple refund rows.
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='ck_payment_refund_amount_positive') THEN
    ALTER TABLE payment_refund ADD CONSTRAINT ck_payment_refund_amount_positive CHECK (amount_minor > 0);
  END IF;
END $$;

-- Merchant ownership constraints. NOT VALID protects new writes immediately without
-- failing migration if an older environment contains legacy orphan rows.
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='fk_merchant_api_key_merchant') THEN
    ALTER TABLE merchant_api_key ADD CONSTRAINT fk_merchant_api_key_merchant FOREIGN KEY(merchant_id) REFERENCES merchant(merchant_id) NOT VALID;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='fk_client_security_merchant') THEN
    ALTER TABLE client_request_security_log ADD CONSTRAINT fk_client_security_merchant FOREIGN KEY(merchant_id) REFERENCES merchant(merchant_id) NOT VALID;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='fk_public_token_security_merchant') THEN
    ALTER TABLE public_token_security_log ADD CONSTRAINT fk_public_token_security_merchant FOREIGN KEY(merchant_id) REFERENCES merchant(merchant_id) NOT VALID;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='fk_payment_merchant') THEN
    ALTER TABLE payment ADD CONSTRAINT fk_payment_merchant FOREIGN KEY(merchant_id) REFERENCES merchant(merchant_id) NOT VALID;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='fk_refund_merchant') THEN
    ALTER TABLE payment_refund ADD CONSTRAINT fk_refund_merchant FOREIGN KEY(merchant_id) REFERENCES merchant(merchant_id) NOT VALID;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='fk_idempotency_merchant') THEN
    ALTER TABLE idempotency_record ADD CONSTRAINT fk_idempotency_merchant FOREIGN KEY(merchant_id) REFERENCES merchant(merchant_id) NOT VALID;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='fk_webhook_endpoint_merchant') THEN
    ALTER TABLE merchant_webhook_endpoint ADD CONSTRAINT fk_webhook_endpoint_merchant FOREIGN KEY(merchant_id) REFERENCES merchant(merchant_id) NOT VALID;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='fk_webhook_delivery_merchant') THEN
    ALTER TABLE webhook_delivery ADD CONSTRAINT fk_webhook_delivery_merchant FOREIGN KEY(merchant_id) REFERENCES merchant(merchant_id) NOT VALID;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='fk_checkout_session_merchant') THEN
    ALTER TABLE payment_checkout_session ADD CONSTRAINT fk_checkout_session_merchant FOREIGN KEY(merchant_id) REFERENCES merchant(merchant_id) NOT VALID;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='fk_api_call_log_merchant') THEN
    ALTER TABLE api_call_log ADD CONSTRAINT fk_api_call_log_merchant FOREIGN KEY(merchant_id) REFERENCES merchant(merchant_id) NOT VALID;
  END IF;
END $$;

-- updated_at trigger for newly introduced counter table.
DROP TRIGGER IF EXISTS trg_payment_provider_attempt_counter_updated_at ON payment_provider_attempt_counter;
CREATE TRIGGER trg_payment_provider_attempt_counter_updated_at
BEFORE UPDATE ON payment_provider_attempt_counter
FOR EACH ROW EXECUTE FUNCTION payment_gateway_set_updated_at();
