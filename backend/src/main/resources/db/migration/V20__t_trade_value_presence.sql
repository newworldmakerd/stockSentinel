ALTER TABLE t_trade_event ADD CONSTRAINT ck_t_trade_value_presence
CHECK(kind='VOID' OR (quantity IS NOT NULL AND price IS NOT NULL));
