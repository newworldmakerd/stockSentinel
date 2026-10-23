-- Operational metadata only; do not rewrite signals, positions or old migrations.
ALTER TABLE trend_follow_item ADD COLUMN last_attempt_at TIMESTAMPTZ,
    ADD COLUMN last_success_at TIMESTAMPTZ, ADD COLUMN last_error_code VARCHAR(80);
ALTER TABLE market_screen_item ADD COLUMN attempts INT NOT NULL DEFAULT 0 CHECK(attempts>=0),
    ADD COLUMN last_attempt_at TIMESTAMPTZ, ADD COLUMN last_success_at TIMESTAMPTZ,
    ADD COLUMN last_error_code VARCHAR(80);

CREATE TABLE scan_quote_state (
    trade_date DATE NOT NULL, phase VARCHAR(12) NOT NULL CHECK(phase IN ('PREVIEW','CONFIRMED')),
    quote_key VARCHAR(8) NOT NULL CHECK(quote_key ~ '^(sh|sz)[0-9]{6}$'),
    cycle_id VARCHAR(36) NOT NULL,
    outcome VARCHAR(12) NOT NULL CHECK(outcome IN ('CHECKED','SKIPPED','FAILED')),
    code VARCHAR(80) NOT NULL, data_valid BOOLEAN NOT NULL,
    attempts INT NOT NULL DEFAULT 1 CHECK(attempts>0), consecutive_failures INT NOT NULL DEFAULT 0 CHECK(consecutive_failures>=0),
    last_attempt_at TIMESTAMPTZ NOT NULL, last_success_at TIMESTAMPTZ, last_quote_at TIMESTAMPTZ,
    PRIMARY KEY(trade_date,phase,quote_key)
);
CREATE INDEX ix_scan_quote_cycle ON scan_quote_state(trade_date,phase,cycle_id);

CREATE TABLE scan_retry_request (
    request_id VARCHAR(64) PRIMARY KEY CHECK(request_id ~ '^[A-Za-z0-9_-]{1,64}$'),
    pipeline VARCHAR(20) NOT NULL CHECK(pipeline IN ('TREND_HISTORY','SCREENING')),
    run_date DATE NOT NULL, scope_hash VARCHAR(64) NOT NULL, scope JSONB NOT NULL,
    before_state JSONB NOT NULL, requested_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE scan_retry_item (
    request_id VARCHAR(64) NOT NULL REFERENCES scan_retry_request(request_id),
    pipeline VARCHAR(20) NOT NULL CHECK(pipeline IN ('TREND_HISTORY','SCREENING')),
    run_date DATE NOT NULL, symbol VARCHAR(12) NOT NULL REFERENCES stock_info(symbol),
    status VARCHAR(12) NOT NULL DEFAULT 'QUEUED' CHECK(status IN ('QUEUED','SUCCESS','FAILED','SKIPPED')),
    attempts_before INT NOT NULL, attempts_after INT, result_code VARCHAR(80),
    requested_at TIMESTAMPTZ NOT NULL DEFAULT now(), completed_at TIMESTAMPTZ,
    PRIMARY KEY(request_id,symbol)
);
CREATE UNIQUE INDEX ux_scan_retry_pending ON scan_retry_item(pipeline,run_date,symbol) WHERE status='QUEUED';
