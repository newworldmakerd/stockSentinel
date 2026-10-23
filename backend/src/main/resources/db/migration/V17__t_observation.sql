CREATE TABLE t_observation_day (
    group_hash VARCHAR(64) NOT NULL,
    user_id VARCHAR(256) NOT NULL,
    symbol VARCHAR(12) NOT NULL REFERENCES stock_info(symbol),
    trade_date DATE NOT NULL,
    position_revision BIGINT NOT NULL,
    baseline_quantity BIGINT NOT NULL CHECK(baseline_quantity>0),
    baseline_sellable BIGINT NOT NULL CHECK(baseline_sellable>0 AND baseline_sellable<=baseline_quantity),
    buy_filled BIGINT NOT NULL DEFAULT 0 CHECK(buy_filled>=0),
    sell_filled BIGINT NOT NULL DEFAULT 0 CHECK(sell_filled>=0),
    buy_price NUMERIC(18,4), sell_price NUMERIC(18,4),
    buy_observed BOOLEAN NOT NULL DEFAULT false,
    sell_observed BOOLEAN NOT NULL DEFAULT false,
    restore_observed BOOLEAN NOT NULL DEFAULT false,
    last_five_at TIMESTAMPTZ,
    rsi5 NUMERIC(24,16), rsi15 NUMERIC(24,16),
    fifteen_at TIMESTAMPTZ,
    last_code VARCHAR(64) NOT NULL DEFAULT 'WAITING_RSI',
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY(group_hash,user_id,symbol,trade_date)
);
CREATE TABLE t_observation_receipt (
    message_hash VARCHAR(64) PRIMARY KEY,
    group_hash VARCHAR(64) NOT NULL,
    user_id VARCHAR(256) NOT NULL,
    symbol VARCHAR(12) NOT NULL,
    trade_date DATE NOT NULL,
    side VARCHAR(4) NOT NULL CHECK(side IN ('BUY','SELL')),
    quantity BIGINT NOT NULL CHECK(quantity>0),
    price NUMERIC(18,4) NOT NULL CHECK(price>0),
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY(group_hash,user_id,symbol,trade_date) REFERENCES t_observation_day
);
COMMENT ON TABLE t_observation_receipt IS 'User reported T fills, no order execution; one aggregate receipt per side per day';
CREATE UNIQUE INDEX ux_t_receipt_side ON t_observation_receipt(group_hash,user_id,symbol,trade_date,side);
CREATE TABLE t_observation_notice (
    idempotency_key VARCHAR(160) PRIMARY KEY,
    group_hash VARCHAR(64) NOT NULL,user_id VARCHAR(256) NOT NULL,symbol VARCHAR(12) NOT NULL,trade_date DATE NOT NULL,
    position_revision BIGINT NOT NULL,buy_filled BIGINT NOT NULL,sell_filled BIGINT NOT NULL,
    phase VARCHAR(16) NOT NULL CHECK(phase IN ('BUY','SELL','RESTORE')),
    FOREIGN KEY(group_hash,user_id,symbol,trade_date) REFERENCES t_observation_day
);
