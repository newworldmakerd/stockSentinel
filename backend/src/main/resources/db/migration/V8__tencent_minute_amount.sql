-- Tencent minute OHLCV does not supply a trustworthy per-minute turnover amount.
-- Preserve missingness instead of inventing zeros or price*volume estimates.
ALTER TABLE market_minute_bar ALTER COLUMN amount DROP NOT NULL;
ALTER TABLE market_minute_bar ADD CONSTRAINT ck_minute_missing_amount_source
    CHECK(amount IS NOT NULL OR source='TENCENT_1M');
