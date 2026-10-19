-- Runtime consolidation cleanup.
-- RocketMQ 5.x rejects dots in topic names, so normalize the historical topic.
UPDATE outbox_event
SET topic='payment_lifecycle_v1', updated_at=now()
WHERE topic='payment.lifecycle.v1';
