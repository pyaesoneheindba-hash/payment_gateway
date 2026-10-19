-- Payment Gateway PostgreSQL schema - capability/plugin architecture: ABank/APlus + MPU + AYA PAY
-- ONE SQL FILE for fresh install and upgrade from earlier generated starters.
-- Application tables include version, created_at, updated_at; gateway security tables are written reactively via R2DBC.

-- ---------- Upgrade old table names before creating current tables ----------
DO $$ BEGIN
  IF to_regclass('public.provider_transaction') IS NULL AND to_regclass('public.payment_attempt') IS NOT NULL THEN
    ALTER TABLE public.payment_attempt RENAME TO provider_transaction;
  END IF;
  IF to_regclass('public.provider_callback') IS NULL AND to_regclass('public.provider_callback_receipt') IS NOT NULL THEN
    ALTER TABLE public.provider_callback_receipt RENAME TO provider_callback;
  END IF;
END $$;

CREATE TABLE IF NOT EXISTS payment_provider (
    provider_code VARCHAR(64) PRIMARY KEY,
    display_name VARCHAR(128) NOT NULL,
    dubbo_group VARCHAR(128) NOT NULL,
    service_version VARCHAR(32) NOT NULL DEFAULT '1.0.0',
    rpc_timeout_ms INTEGER NOT NULL DEFAULT 8000,
    rpc_retries INTEGER NOT NULL DEFAULT 0,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    capabilities_json TEXT NOT NULL DEFAULT '{}',
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
ALTER TABLE payment_provider ADD COLUMN IF NOT EXISTS service_version VARCHAR(32) NOT NULL DEFAULT '1.0.0';
ALTER TABLE payment_provider ADD COLUMN IF NOT EXISTS rpc_timeout_ms INTEGER NOT NULL DEFAULT 8000;
ALTER TABLE payment_provider ADD COLUMN IF NOT EXISTS rpc_retries INTEGER NOT NULL DEFAULT 0;
INSERT INTO payment_provider(provider_code,display_name,dubbo_group,service_version,rpc_timeout_ms,rpc_retries,enabled,capabilities_json)
VALUES
 ('ABANK_APLUS','ABank / APlus MMQR','provider-abank','1.0.0',8000,0,TRUE,'{"QR_PAYMENT":true,"MMQR_PAYMENT":true,"ENQUIRY":true,"CALLBACK":true}'),
 ('MPU','MPU National Payment Gateway','provider-mpu','1.0.0',8000,0,TRUE,'{"HOSTED_PAYMENT":true,"ENQUIRY":true,"CALLBACK":true,"VOID":true}'),
 ('AYAPAY','AYA PAY Online Merchant','provider-ayapay','1.0.0',8000,0,TRUE,'{"QR_PAYMENT":true,"MMQR_PAYMENT":true,"PUSH_PAYMENT":true,"CUSTOMER_PRESENT_QR":true,"ENQUIRY":true,"CALLBACK":true,"REFUND":true}')
ON CONFLICT(provider_code) DO UPDATE SET display_name=EXCLUDED.display_name,dubbo_group=EXCLUDED.dubbo_group,service_version=EXCLUDED.service_version,rpc_timeout_ms=EXCLUDED.rpc_timeout_ms,rpc_retries=EXCLUDED.rpc_retries,capabilities_json=EXCLUDED.capabilities_json;


-- Merchant public signing keys used by api-gateway to authenticate client requests.
-- Only public keys are stored here. Merchant private keys must never be stored in this database.
CREATE TABLE IF NOT EXISTS merchant_api_key (
    key_id VARCHAR(100) PRIMARY KEY,
    merchant_id UUID NOT NULL,
    algorithm VARCHAR(32) NOT NULL DEFAULT 'ED25519',
    public_key TEXT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    valid_from TIMESTAMPTZ NOT NULL DEFAULT now(),
    valid_until TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_merchant_api_key_algorithm CHECK (algorithm IN ('ED25519')),
    CONSTRAINT ck_merchant_api_key_status CHECK (status IN ('ACTIVE','REVOKED','EXPIRED')),
    CONSTRAINT ck_merchant_api_key_validity CHECK (valid_until IS NULL OR valid_until > valid_from)
);
CREATE INDEX IF NOT EXISTS idx_merchant_api_key_merchant_status
    ON merchant_api_key(merchant_id, status, valid_from, valid_until);

-- Security decision audit for client request signature / replay verification.
-- Raw nonces, signatures and idempotency keys are intentionally NOT stored; only hashes are retained.
CREATE TABLE IF NOT EXISTS client_request_security_log (
    security_event_id UUID PRIMARY KEY,
    correlation_id VARCHAR(128),
    merchant_id UUID,
    key_id VARCHAR(100),
    http_method VARCHAR(16) NOT NULL,
    request_path VARCHAR(1000) NOT NULL,
    request_timestamp TIMESTAMPTZ,
    nonce_hash VARCHAR(64),
    body_hash VARCHAR(64),
    idempotency_key_hash VARCHAR(64),
    signature_algorithm VARCHAR(32),
    signature_verified BOOLEAN,
    replay_detected BOOLEAN,
    decision VARCHAR(16) NOT NULL,
    failure_reason VARCHAR(64),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_client_security_decision CHECK (decision IN ('ACCEPTED','REJECTED','ERROR'))
);
CREATE INDEX IF NOT EXISTS idx_client_security_correlation
    ON client_request_security_log(correlation_id) WHERE correlation_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_client_security_merchant_created
    ON client_request_security_log(merchant_id, created_at DESC) WHERE merchant_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_client_security_nonce
    ON client_request_security_log(merchant_id, key_id, nonce_hash, created_at DESC) WHERE nonce_hash IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_client_security_rejected
    ON client_request_security_log(created_at DESC) WHERE decision IN ('REJECTED','ERROR');

-- Security decision audit for short-lived public mobile/web/desktop bearer tokens.
-- Raw JWTs are never stored. Only a SHA-256 hash of jti is retained.
CREATE TABLE IF NOT EXISTS public_token_security_log (
    security_event_id UUID PRIMARY KEY,
    correlation_id VARCHAR(128),
    merchant_id UUID,
    token_subject VARCHAR(200),
    token_jti_hash VARCHAR(64),
    token_scopes VARCHAR(1000),
    http_method VARCHAR(16) NOT NULL,
    request_path VARCHAR(1000) NOT NULL,
    token_expires_at TIMESTAMPTZ,
    one_time BOOLEAN,
    replay_detected BOOLEAN,
    decision VARCHAR(16) NOT NULL,
    failure_reason VARCHAR(64),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_public_token_security_decision CHECK (decision IN ('ACCEPTED','REJECTED','ERROR'))
);
CREATE INDEX IF NOT EXISTS idx_public_token_security_correlation
    ON public_token_security_log(correlation_id) WHERE correlation_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_public_token_security_merchant_created
    ON public_token_security_log(merchant_id, created_at DESC) WHERE merchant_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_public_token_security_jti
    ON public_token_security_log(merchant_id, token_jti_hash, created_at DESC) WHERE token_jti_hash IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_public_token_security_rejected
    ON public_token_security_log(created_at DESC) WHERE decision IN ('REJECTED','ERROR');

CREATE TABLE IF NOT EXISTS payment (
    payment_id UUID PRIMARY KEY,
    merchant_id UUID NOT NULL,
    merchant_order_id VARCHAR(128) NOT NULL,
    amount_minor BIGINT NOT NULL CHECK (amount_minor > 0),
    currency VARCHAR(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    status VARCHAR(32) NOT NULL,
    selected_provider_code VARCHAR(64),
    provider_order_id VARCHAR(64),
    provider_reference_id VARCHAR(200),
    provider_order_date DATE,
    provider_next_action VARCHAR(32),
    qr_payload TEXT,
    provider_redirect_url VARCHAR(2000),
    provider_form JSONB,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_payment_merchant_order UNIQUE (merchant_id, merchant_order_id)
);
ALTER TABLE payment ADD COLUMN IF NOT EXISTS provider_order_date DATE;
ALTER TABLE payment ADD COLUMN IF NOT EXISTS provider_reference_id VARCHAR(200);
ALTER TABLE payment ADD COLUMN IF NOT EXISTS provider_next_action VARCHAR(32);
ALTER TABLE payment ADD COLUMN IF NOT EXISTS provider_redirect_url VARCHAR(2000);
ALTER TABLE payment ADD COLUMN IF NOT EXISTS provider_form JSONB;
ALTER TABLE payment ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE payment ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE payment ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT now();
-- Normalize legacy fixed-width CHAR columns from the earliest starter schema.
-- CREATE TABLE IF NOT EXISTS does not change an existing column type, and Hibernate
-- validates VARCHAR mappings strictly. rtrim removes bpchar padding during migration.
ALTER TABLE payment ALTER COLUMN currency TYPE VARCHAR(3) USING rtrim(currency);
ALTER TABLE payment ALTER COLUMN provider_order_id TYPE VARCHAR(64);
ALTER TABLE payment DROP CONSTRAINT IF EXISTS payment_status_check;
ALTER TABLE payment DROP CONSTRAINT IF EXISTS ck_payment_status;
ALTER TABLE payment ADD CONSTRAINT ck_payment_status CHECK (status IN ('INITIATED','PROVIDER_REQUESTED','QR_REQUESTED','QR_GENERATED','ACTION_REQUIRED','PENDING','UNKNOWN','SUCCEEDED','PARTIALLY_REFUNDED','REFUNDED','VOIDED','FAILED','EXPIRED'));
CREATE INDEX IF NOT EXISTS idx_payment_merchant_created ON payment (merchant_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_payment_provider_order ON payment (selected_provider_code, provider_order_id) WHERE provider_order_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_payment_provider_reference ON payment (selected_provider_code, provider_reference_id) WHERE provider_reference_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_payment_recovery ON payment (status, updated_at) WHERE status IN ('PROVIDER_REQUESTED','QR_REQUESTED','ACTION_REQUIRED','PENDING','UNKNOWN');

CREATE TABLE IF NOT EXISTS provider_transaction (
    transaction_record_id UUID PRIMARY KEY,
    payment_id UUID NOT NULL REFERENCES payment(payment_id),
    attempt_no INTEGER NOT NULL,
    provider_code VARCHAR(64) NOT NULL,
    operation VARCHAR(32) NOT NULL,
    provider_request_no VARCHAR(200),
    provider_order_id VARCHAR(64),
    provider_reference_id VARCHAR(200),
    outcome VARCHAR(32) NOT NULL,
    provider_status VARCHAR(64),
    provider_transaction_id VARCHAR(200),
    provider_response_code VARCHAR(64),
    provider_response_message VARCHAR(1000),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_provider_transaction_attempt UNIQUE (payment_id, attempt_no)
);
-- Columns/PK name when upgrading from payment_attempt.
DO $$ BEGIN
  IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='provider_transaction' AND column_name='attempt_id') AND
     NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='provider_transaction' AND column_name='transaction_record_id') THEN
    ALTER TABLE provider_transaction RENAME COLUMN attempt_id TO transaction_record_id;
  END IF;
END $$;
ALTER TABLE provider_transaction ADD COLUMN IF NOT EXISTS provider_reference_id VARCHAR(200);
ALTER TABLE provider_transaction ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE provider_transaction ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE provider_transaction ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE provider_transaction ALTER COLUMN provider_order_id TYPE VARCHAR(64);
ALTER TABLE provider_transaction ALTER COLUMN provider_response_message TYPE VARCHAR(1000);
ALTER TABLE provider_transaction DROP CONSTRAINT IF EXISTS uq_payment_attempt_no;
ALTER TABLE provider_transaction DROP CONSTRAINT IF EXISTS uq_provider_transaction_attempt;
ALTER TABLE provider_transaction ADD CONSTRAINT uq_provider_transaction_attempt UNIQUE(payment_id,attempt_no);
CREATE INDEX IF NOT EXISTS idx_provider_transaction_order ON provider_transaction (provider_code, provider_order_id) WHERE provider_order_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_provider_transaction_ref ON provider_transaction (provider_code, provider_reference_id) WHERE provider_reference_id IS NOT NULL;


CREATE TABLE IF NOT EXISTS payment_refund (
    refund_id UUID PRIMARY KEY,
    payment_id UUID NOT NULL REFERENCES payment(payment_id),
    merchant_id UUID NOT NULL,
    merchant_refund_id VARCHAR(128) NOT NULL,
    amount_minor BIGINT NOT NULL CHECK (amount_minor > 0),
    currency VARCHAR(3) NOT NULL,
    provider_code VARCHAR(64) NOT NULL,
    provider_refund_reference_id VARCHAR(200),
    status VARCHAR(32) NOT NULL CHECK (status IN ('REQUESTED','PENDING','UNKNOWN','SUCCEEDED','FAILED')),
    provider_response_code VARCHAR(64),
    provider_response_message VARCHAR(1000),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_refund_merchant_ref UNIQUE (merchant_id, merchant_refund_id)
);
ALTER TABLE payment_refund ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE payment_refund ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE payment_refund ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT now();
CREATE INDEX IF NOT EXISTS idx_payment_refund_payment ON payment_refund(payment_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_payment_refund_provider_ref ON payment_refund(provider_code, provider_refund_reference_id) WHERE provider_refund_reference_id IS NOT NULL;

CREATE TABLE IF NOT EXISTS idempotency_record (
    merchant_id UUID NOT NULL, operation VARCHAR(64) NOT NULL, idempotency_key VARCHAR(128) NOT NULL,
    request_hash VARCHAR(64) NOT NULL, status VARCHAR(16) NOT NULL CHECK (status IN ('PROCESSING','COMPLETED')),
    resource_id UUID, completed_at TIMESTAMPTZ, version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (merchant_id, operation, idempotency_key)
);
ALTER TABLE idempotency_record ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE idempotency_record ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE idempotency_record ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE idempotency_record ALTER COLUMN request_hash TYPE VARCHAR(64) USING rtrim(request_hash);
CREATE INDEX IF NOT EXISTS idx_idempotency_resource ON idempotency_record(resource_id) WHERE resource_id IS NOT NULL;

CREATE TABLE IF NOT EXISTS provider_callback (
    callback_id UUID PRIMARY KEY,
    provider_code VARCHAR(64) NOT NULL, dedupe_key VARCHAR(256) NOT NULL,
    provider_order_id VARCHAR(64), provider_reference_id VARCHAR(200), callback_type VARCHAR(64) NOT NULL,
    amount VARCHAR(64), provider_status VARCHAR(64), provider_response_code VARCHAR(64), provider_transaction_id VARCHAR(200),
    approval_code VARCHAR(64), bill_no VARCHAR(200), end_to_end_id VARCHAR(200), transaction_datetime VARCHAR(64),
    institution_name VARCHAR(255), masked_pan VARCHAR(32), error_code VARCHAR(100), error_description TEXT,
    signature_verified BOOLEAN, raw_payload_json TEXT NOT NULL DEFAULT '{}', payload_hash VARCHAR(64) NOT NULL,
    processing_status VARCHAR(32) NOT NULL, processing_message TEXT, verified_provider_status VARCHAR(64),
    received_at TIMESTAMPTZ NOT NULL DEFAULT now(), processed_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0, created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_provider_callback UNIQUE (provider_code, dedupe_key)
);
DO $$ BEGIN
  IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='provider_callback' AND column_name='receipt_id') AND
     NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='provider_callback' AND column_name='callback_id') THEN
    ALTER TABLE provider_callback RENAME COLUMN receipt_id TO callback_id;
  END IF;
END $$;
ALTER TABLE provider_callback ADD COLUMN IF NOT EXISTS provider_reference_id VARCHAR(200);
ALTER TABLE provider_callback ADD COLUMN IF NOT EXISTS provider_response_code VARCHAR(64);
ALTER TABLE provider_callback ADD COLUMN IF NOT EXISTS approval_code VARCHAR(64);
ALTER TABLE provider_callback ADD COLUMN IF NOT EXISTS masked_pan VARCHAR(32);
ALTER TABLE provider_callback ADD COLUMN IF NOT EXISTS signature_verified BOOLEAN;
ALTER TABLE provider_callback ADD COLUMN IF NOT EXISTS raw_payload_json TEXT;
ALTER TABLE provider_callback ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE provider_callback ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE provider_callback ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE provider_callback ALTER COLUMN payload_hash TYPE VARCHAR(64) USING rtrim(payload_hash);
DO $$ BEGIN
 IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='provider_callback' AND column_name='raw_query_json') THEN
   EXECUTE 'UPDATE provider_callback SET raw_payload_json = COALESCE(raw_payload_json, raw_query_json) WHERE raw_payload_json IS NULL';
 END IF;
END $$;
UPDATE provider_callback SET raw_payload_json='{}' WHERE raw_payload_json IS NULL;
ALTER TABLE provider_callback ALTER COLUMN raw_payload_json SET NOT NULL;
ALTER TABLE provider_callback DROP CONSTRAINT IF EXISTS provider_callback_receipt_callback_type_check;
ALTER TABLE provider_callback DROP CONSTRAINT IF EXISTS ck_callback_type;
ALTER TABLE provider_callback DROP CONSTRAINT IF EXISTS provider_callback_receipt_processing_status_check;
ALTER TABLE provider_callback DROP CONSTRAINT IF EXISTS ck_callback_processing_status;
ALTER TABLE provider_callback ADD CONSTRAINT ck_callback_processing_status CHECK (processing_status IN ('RECEIVED','PROCESSED','FAILED'));
CREATE INDEX IF NOT EXISTS idx_provider_callback_order ON provider_callback(provider_code, provider_order_id, received_at DESC);
CREATE INDEX IF NOT EXISTS idx_provider_callback_reference ON provider_callback(provider_code, provider_reference_id, received_at DESC) WHERE provider_reference_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_provider_callback_received ON provider_callback(received_at DESC);

CREATE TABLE IF NOT EXISTS outbox_event (
    event_id UUID PRIMARY KEY, aggregate_type VARCHAR(64) NOT NULL, aggregate_id UUID NOT NULL, event_type VARCHAR(128) NOT NULL,
    event_version INTEGER NOT NULL, topic VARCHAR(160) NOT NULL, message_key VARCHAR(160) NOT NULL, payload JSONB NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'NEW' CHECK (status IN ('NEW','CLAIMED','PUBLISHED','DEAD')),
    version BIGINT NOT NULL DEFAULT 0, created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
ALTER TABLE outbox_event ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE outbox_event ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE outbox_event ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT now();
-- Upgrade safety: older project revisions used an inline status CHECK that did not
-- contain CLAIMED/DEAD. Re-create it explicitly so the dispatcher can claim and
-- dead-letter rows when this script is applied to an existing database.
ALTER TABLE outbox_event DROP CONSTRAINT IF EXISTS outbox_event_status_check;
ALTER TABLE outbox_event DROP CONSTRAINT IF EXISTS ck_outbox_status;
ALTER TABLE outbox_event ADD CONSTRAINT ck_outbox_status
    CHECK (status IN ('NEW','CLAIMED','PUBLISHED','DEAD'));
CREATE INDEX IF NOT EXISTS idx_outbox_new ON outbox_event(created_at) WHERE status='NEW';


-- Merchant-owned HTTPS endpoints that receive normalized payment status webhooks.
-- Registration is exposed only through the trusted Ed25519-authenticated merchant API.
CREATE TABLE IF NOT EXISTS merchant_webhook_endpoint (
    endpoint_id UUID PRIMARY KEY,
    merchant_id UUID NOT NULL,
    event_type VARCHAR(128) NOT NULL DEFAULT 'PAYMENT_STATUS_CHANGED',
    url VARCHAR(2000) NOT NULL,
    description VARCHAR(255),
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_merchant_webhook_status CHECK (status IN ('ACTIVE','PAUSED')),
    CONSTRAINT ck_merchant_webhook_event CHECK (event_type IN ('PAYMENT_STATUS_CHANGED')),
    CONSTRAINT uq_merchant_webhook UNIQUE (merchant_id, event_type, url)
);
CREATE INDEX IF NOT EXISTS idx_merchant_webhook_active
    ON merchant_webhook_endpoint(merchant_id, event_type) WHERE status='ACTIVE';

-- Durable at-least-once delivery state for outbound merchant webhooks.
-- event_id is stable across retries and is the merchant's deduplication key.
CREATE TABLE IF NOT EXISTS webhook_delivery (
    delivery_id UUID PRIMARY KEY,
    event_id UUID NOT NULL REFERENCES outbox_event(event_id),
    endpoint_id UUID NOT NULL REFERENCES merchant_webhook_endpoint(endpoint_id),
    merchant_id UUID NOT NULL,
    payment_id UUID NOT NULL REFERENCES payment(payment_id),
    event_type VARCHAR(128) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_attempt_at TIMESTAMPTZ,
    last_http_status INTEGER,
    last_error TEXT,
    delivered_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_webhook_delivery_status CHECK (status IN ('PENDING','SENDING','RETRY','DELIVERED','DEAD')),
    CONSTRAINT uq_webhook_delivery_event_endpoint UNIQUE (event_id, endpoint_id)
);
CREATE INDEX IF NOT EXISTS idx_webhook_delivery_due
    ON webhook_delivery(next_attempt_at, created_at) WHERE status IN ('PENDING','RETRY');
CREATE INDEX IF NOT EXISTS idx_webhook_delivery_payment
    ON webhook_delivery(payment_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_webhook_delivery_merchant
    ON webhook_delivery(merchant_id, created_at DESC);

CREATE TABLE IF NOT EXISTS api_call_log (
    call_id UUID PRIMARY KEY, correlation_id VARCHAR(128), service_name VARCHAR(100) NOT NULL,
    direction VARCHAR(16) NOT NULL CHECK (direction IN ('INBOUND','OUTBOUND')), source_system VARCHAR(100), target_system VARCHAR(100),
    operation VARCHAR(100) NOT NULL, http_method VARCHAR(16) NOT NULL, request_path VARCHAR(1000) NOT NULL, query_string TEXT,
    request_headers TEXT, request_body TEXT, response_http_status INTEGER, response_headers TEXT, response_body TEXT,
    result_status VARCHAR(32) NOT NULL CHECK (result_status IN ('STARTED','COMPLETED','FAILED')), error_class VARCHAR(255), error_message TEXT,
    merchant_id UUID, payment_id UUID, provider_order_id VARCHAR(64), started_at TIMESTAMPTZ NOT NULL DEFAULT now(), completed_at TIMESTAMPTZ,
    duration_ms BIGINT, version BIGINT NOT NULL DEFAULT 0, created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
ALTER TABLE api_call_log ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE api_call_log ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE api_call_log ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT now();
CREATE INDEX IF NOT EXISTS idx_api_call_started ON api_call_log(started_at DESC);
CREATE INDEX IF NOT EXISTS idx_api_call_payment ON api_call_log(payment_id, started_at DESC) WHERE payment_id IS NOT NULL;

CREATE TABLE IF NOT EXISTS provider_api_exchange (
    exchange_id UUID PRIMARY KEY, payment_id UUID, provider_code VARCHAR(64) NOT NULL, operation VARCHAR(64) NOT NULL,
    provider_request_no VARCHAR(200), provider_order_id VARCHAR(64), provider_reference_id VARCHAR(200),
    http_method VARCHAR(16) NOT NULL, request_url VARCHAR(2000) NOT NULL, request_headers TEXT, request_body TEXT,
    response_http_status INTEGER, response_headers TEXT, response_body TEXT, provider_response_code VARCHAR(64),
    provider_response_message VARCHAR(1000), outcome VARCHAR(32), error_class VARCHAR(255), error_message TEXT,
    started_at TIMESTAMPTZ NOT NULL DEFAULT now(), completed_at TIMESTAMPTZ, duration_ms BIGINT,
    version BIGINT NOT NULL DEFAULT 0, created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
ALTER TABLE provider_api_exchange ADD COLUMN IF NOT EXISTS provider_reference_id VARCHAR(200);
ALTER TABLE provider_api_exchange ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE provider_api_exchange ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE provider_api_exchange ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT now();
CREATE INDEX IF NOT EXISTS idx_provider_exchange_payment ON provider_api_exchange(payment_id, started_at DESC) WHERE payment_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_provider_exchange_order ON provider_api_exchange(provider_code, provider_order_id, started_at DESC);
CREATE INDEX IF NOT EXISTS idx_provider_exchange_ref ON provider_api_exchange(provider_code, provider_reference_id, started_at DESC) WHERE provider_reference_id IS NOT NULL;

-- Copy audit history from the previous integration_audit schema, then retire it so \dt shows all application tables.
DO $$ BEGIN
 IF to_regclass('integration_audit.api_call_log') IS NOT NULL THEN
   INSERT INTO public.api_call_log SELECT * FROM integration_audit.api_call_log ON CONFLICT(call_id) DO NOTHING;
 END IF;
END $$;
-- provider_api_exchange gained provider_reference_id, so copy by named columns.
DO $$ BEGIN
 IF to_regclass('integration_audit.provider_api_exchange') IS NOT NULL THEN
   INSERT INTO public.provider_api_exchange(exchange_id,payment_id,provider_code,operation,provider_request_no,provider_order_id,http_method,request_url,request_headers,request_body,response_http_status,response_headers,response_body,provider_response_code,provider_response_message,outcome,error_class,error_message,started_at,completed_at,duration_ms,version,created_at,updated_at)
   SELECT exchange_id,payment_id,provider_code,operation,provider_request_no,provider_order_id,http_method,request_url,request_headers,request_body,response_http_status,response_headers,response_body,provider_response_code,provider_response_message,outcome,error_class,error_message,started_at,completed_at,duration_ms,version,created_at,updated_at
   FROM integration_audit.provider_api_exchange ON CONFLICT(exchange_id) DO NOTHING;
 END IF;
END $$;
DROP TABLE IF EXISTS integration_audit.api_call_log;
DROP TABLE IF EXISTS integration_audit.provider_api_exchange;
DROP SCHEMA IF EXISTS integration_audit;

-- DB-side updated_at safety net. JPA entities also use Spring Data auditing; gateway security tables are written via R2DBC.
CREATE OR REPLACE FUNCTION payment_gateway_set_updated_at() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN NEW.updated_at = now(); RETURN NEW; END $$;
DO $$
DECLARE t text;
BEGIN
  FOREACH t IN ARRAY ARRAY['payment_provider','merchant_api_key','client_request_security_log','public_token_security_log','payment','provider_transaction','payment_refund','idempotency_record','provider_callback','outbox_event','merchant_webhook_endpoint','webhook_delivery','api_call_log','provider_api_exchange'] LOOP
    EXECUTE format('DROP TRIGGER IF EXISTS trg_%s_updated_at ON %I', t, t);
    EXECUTE format('CREATE TRIGGER trg_%s_updated_at BEFORE UPDATE ON %I FOR EACH ROW EXECUTE FUNCTION payment_gateway_set_updated_at()', t, t);
  END LOOP;
END $$;


-- ==================== Merchant portal / hosted checkout additions ====================
CREATE TABLE IF NOT EXISTS merchant (
    merchant_id UUID PRIMARY KEY,
    merchant_code VARCHAR(64) NOT NULL UNIQUE,
    merchant_name VARCHAR(200) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    environment VARCHAR(16) NOT NULL DEFAULT 'UAT',
    default_currency VARCHAR(3) NOT NULL DEFAULT 'MMK',
    contact_email VARCHAR(255),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_merchant_status CHECK (status IN ('PENDING','ACTIVE','SUSPENDED','CLOSED')),
    CONSTRAINT ck_merchant_environment CHECK (environment IN ('UAT','PRODUCTION'))
);

CREATE TABLE IF NOT EXISTS merchant_redirect_uri (
    redirect_uri_id UUID PRIMARY KEY,
    merchant_id UUID NOT NULL REFERENCES merchant(merchant_id) ON DELETE CASCADE,
    redirect_uri VARCHAR(2000) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_merchant_redirect_status CHECK (status IN ('ACTIVE','DISABLED')),
    CONSTRAINT uq_merchant_redirect UNIQUE (merchant_id, redirect_uri)
);
CREATE INDEX IF NOT EXISTS idx_merchant_redirect_active ON merchant_redirect_uri(merchant_id) WHERE status='ACTIVE';

CREATE TABLE IF NOT EXISTS portal_user (
    portal_user_id UUID PRIMARY KEY,
    username VARCHAR(120) NOT NULL UNIQUE,
    display_name VARCHAR(200) NOT NULL,
    role VARCHAR(32) NOT NULL,
    merchant_id UUID REFERENCES merchant(merchant_id),
    password_salt VARCHAR(128) NOT NULL,
    password_hash VARCHAR(256) NOT NULL,
    password_iterations INTEGER NOT NULL DEFAULT 210000,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_portal_user_role CHECK (role IN ('SUPER_ADMIN','OPERATIONS','FINANCE','SUPPORT','SECURITY_AUDITOR','MERCHANT_ADMIN','MERCHANT_DEVELOPER','MERCHANT_FINANCE','MERCHANT_VIEWER')),
    CONSTRAINT ck_portal_user_status CHECK (status IN ('ACTIVE','LOCKED','DISABLED')),
    CONSTRAINT ck_portal_user_merchant_binding CHECK ((role LIKE 'MERCHANT_%' AND merchant_id IS NOT NULL) OR (role NOT LIKE 'MERCHANT_%'))
);
CREATE INDEX IF NOT EXISTS idx_portal_user_merchant ON portal_user(merchant_id) WHERE merchant_id IS NOT NULL;

ALTER TABLE payment ADD COLUMN IF NOT EXISTS expires_at TIMESTAMPTZ;
ALTER TABLE payment ADD COLUMN IF NOT EXISTS presentation_mode VARCHAR(20) NOT NULL DEFAULT 'QR_STRING';
ALTER TABLE payment DROP CONSTRAINT IF EXISTS ck_payment_presentation_mode;
ALTER TABLE payment ADD CONSTRAINT ck_payment_presentation_mode CHECK (presentation_mode IN ('QR_STRING','HOSTED_PAGE'));
CREATE INDEX IF NOT EXISTS idx_payment_expiry ON payment(expires_at) WHERE status IN ('QR_GENERATED','ACTION_REQUIRED','PENDING');

CREATE TABLE IF NOT EXISTS payment_checkout_session (
    checkout_session_id UUID PRIMARY KEY,
    payment_id UUID NOT NULL UNIQUE REFERENCES payment(payment_id) ON DELETE CASCADE,
    merchant_id UUID NOT NULL,
    session_token_hash VARCHAR(64) NOT NULL UNIQUE,
    return_url VARCHAR(2000) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    expires_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    redirected_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_checkout_status CHECK (status IN ('ACTIVE','COMPLETED','EXPIRED','CANCELLED'))
);
CREATE INDEX IF NOT EXISTS idx_checkout_expiry ON payment_checkout_session(status, expires_at) WHERE status='ACTIVE';
CREATE INDEX IF NOT EXISTS idx_checkout_merchant_created ON payment_checkout_session(merchant_id, created_at DESC);
