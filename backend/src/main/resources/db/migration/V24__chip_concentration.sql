CREATE TABLE chip_scan_run (
    trade_date DATE PRIMARY KEY, parameters JSONB NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'RUNNING' CHECK(status IN ('RUNNING','COMPLETED','PARTIAL')),
    retry_after TIMESTAMPTZ, last_error VARCHAR(80),
    started_at TIMESTAMPTZ NOT NULL DEFAULT now(), finished_at TIMESTAMPTZ
);
CREATE TABLE chip_scan_item (
    trade_date DATE NOT NULL REFERENCES chip_scan_run(trade_date),
    symbol VARCHAR(12) NOT NULL REFERENCES stock_info(symbol),
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','EXCLUDED','MATCHED','NO_MATCH','FAILED')),
    code VARCHAR(80), attempts INT NOT NULL DEFAULT 0, next_retry_at TIMESTAMPTZ,
    snapshot JSONB, concentration90 NUMERIC(18,10), concentration70 NUMERIC(18,10), average_amount NUMERIC(24,4),
    last_attempt_at TIMESTAMPTZ, last_success_at TIMESTAMPTZ,
    PRIMARY KEY(trade_date,symbol)
);
CREATE INDEX ix_chip_pending ON chip_scan_item(trade_date,status,next_retry_at,symbol);
