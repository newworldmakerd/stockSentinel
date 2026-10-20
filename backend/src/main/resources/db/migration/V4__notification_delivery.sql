ALTER TABLE notify_record
    ADD COLUMN target_chat_id VARCHAR(256),
    ADD COLUMN payload TEXT,
    ADD COLUMN claim_token VARCHAR(36),
    ADD COLUMN lease_until TIMESTAMPTZ;

CREATE INDEX idx_notify_record_due ON notify_record(status, next_retry_at, id);
COMMENT ON COLUMN notify_record.target_chat_id IS 'Destination fixed at enqueue; no bot credentials';
COMMENT ON COLUMN notify_record.payload IS 'Frozen message after first claim; retries retain original content';
COMMENT ON COLUMN notify_record.claim_token IS 'Owner token; expired in-flight sends become UNKNOWN, never auto-replayed';
