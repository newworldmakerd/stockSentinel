CREATE TABLE pullback_candidate (
    symbol VARCHAR(12) NOT NULL REFERENCES stock_info(symbol),
    data_date DATE NOT NULL,
    version VARCHAR(30) NOT NULL,
    average_amount NUMERIC(24,4) NOT NULL CHECK(average_amount>0),
    reference_high NUMERIC(18,4) NOT NULL CHECK(reference_high>0),
    details JSONB NOT NULL,
    last_bar_at TIMESTAMPTZ,
    triggered_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY(symbol,data_date,version)
);
CREATE INDEX ix_pullback_pool ON pullback_candidate(data_date,version,average_amount DESC,symbol);
