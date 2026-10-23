CREATE TABLE group_watch_owner (
    group_hash VARCHAR(64) NOT NULL,
    user_id VARCHAR(256) NOT NULL,
    symbol VARCHAR(12) NOT NULL REFERENCES stock_info(symbol) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY(group_hash,user_id,symbol)
);
CREATE INDEX ix_group_watch_owner_stock ON group_watch_owner(group_hash,symbol);
CREATE TABLE group_member_alias (
    group_hash VARCHAR(64) NOT NULL,
    user_id VARCHAR(256) NOT NULL,
    display_name VARCHAR(40) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY(group_hash,user_id)
);
COMMENT ON TABLE group_watch_owner IS 'Explicit group follow attribution from authenticated bot callback; legacy ownership remains unknown';
