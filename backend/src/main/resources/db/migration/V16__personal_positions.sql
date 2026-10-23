CREATE TABLE personal_position (
    group_hash VARCHAR(64) NOT NULL,
    user_id VARCHAR(256) NOT NULL,
    symbol VARCHAR(12) NOT NULL REFERENCES stock_info(symbol),
    quantity BIGINT NOT NULL CHECK(quantity BETWEEN 0 AND 1000000000),
    cost_price NUMERIC(18,4),
    sellable_quantity BIGINT,
    reported_on DATE NOT NULL,
    revision BIGINT NOT NULL DEFAULT 1,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY(group_hash,user_id,symbol),
    CHECK((quantity=0 AND cost_price IS NULL) OR (quantity>0 AND cost_price>0)),
    CHECK(sellable_quantity IS NULL OR sellable_quantity BETWEEN 0 AND quantity)
);
CREATE INDEX ix_personal_position_stock ON personal_position(symbol,group_hash);
COMMENT ON TABLE personal_position IS 'User-reported holdings only; no brokerage sync or automatic trade execution';
