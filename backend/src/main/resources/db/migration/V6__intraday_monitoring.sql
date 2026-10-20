ALTER TABLE signal_event ADD COLUMN signal_at TIMESTAMPTZ;
ALTER TABLE signal_event DROP CONSTRAINT signal_event_symbol_trade_date_strategy_code_strategy_versi_key;
CREATE UNIQUE INDEX uq_signal_daily ON signal_event(symbol,trade_date,strategy_code,strategy_version,signal_type) WHERE signal_at IS NULL;
CREATE UNIQUE INDEX uq_signal_intraday ON signal_event(symbol,strategy_code,strategy_version,signal_type,signal_at) WHERE signal_at IS NOT NULL;

CREATE TABLE intraday_monitor (
    symbol VARCHAR(12) PRIMARY KEY REFERENCES stock_info(symbol),
    enabled BOOLEAN NOT NULL, rapid_pct NUMERIC(10,4) NOT NULL CHECK(rapid_pct>0 AND rapid_pct<=30),
    volume_multiplier NUMERIC(10,4) NOT NULL CHECK(volume_multiplier>0 AND volume_multiplier<=100),
    cooldown_minutes INT NOT NULL CHECK(cooldown_minutes BETWEEN 1 AND 1440),
    take_profit NUMERIC(18,4) CHECK(take_profit>0), stop_loss NUMERIC(18,4) CHECK(stop_loss>0),
    revision BIGINT NOT NULL DEFAULT 1, updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK(take_profit IS NULL OR stop_loss IS NULL OR stop_loss<take_profit)
);
CREATE TABLE market_minute_bar (
    symbol VARCHAR(12) NOT NULL REFERENCES stock_info(symbol), bar_at TIMESTAMPTZ NOT NULL,
    open_price NUMERIC(18,4) NOT NULL, high_price NUMERIC(18,4) NOT NULL,
    low_price NUMERIC(18,4) NOT NULL, close_price NUMERIC(18,4) NOT NULL,
    volume NUMERIC(24,4) NOT NULL, amount NUMERIC(24,4) NOT NULL,
    source VARCHAR(40) NOT NULL, fetched_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY(symbol,bar_at)
);
CREATE TABLE intraday_cursor (
    symbol VARCHAR(12) PRIMARY KEY REFERENCES stock_info(symbol),
    last_bar_at TIMESTAMPTZ NOT NULL, config_revision BIGINT NOT NULL
);
CREATE TABLE intraday_alert_state (
    symbol VARCHAR(12) NOT NULL REFERENCES stock_info(symbol), signal_type VARCHAR(30) NOT NULL,
    active BOOLEAN NOT NULL, config_revision BIGINT NOT NULL, last_trigger_at TIMESTAMPTZ,
    PRIMARY KEY(symbol,signal_type)
);
