CREATE TABLE intraday_ma60_state (
    symbol VARCHAR(12) PRIMARY KEY REFERENCES stock_info(symbol),
    trade_date DATE NOT NULL,
    reference_date DATE,
    baseline_key VARCHAR(64),
    threshold NUMERIC(38,16),
    baseline JSONB,
    last_bar_at TIMESTAMPTZ,
    last_trigger_at TIMESTAMPTZ,
    status VARCHAR(20) NOT NULL,
    code VARCHAR(80) NOT NULL,
    checked_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
