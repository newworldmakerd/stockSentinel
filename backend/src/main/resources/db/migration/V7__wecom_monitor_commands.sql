CREATE TABLE wecom_monitor_command (
    command_key VARCHAR(64) PRIMARY KEY,
    status VARCHAR(30) NOT NULL,
    stock_symbol VARCHAR(12) REFERENCES stock_info(symbol),
    ack_status VARCHAR(20),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at TIMESTAMPTZ
);
COMMENT ON TABLE wecom_monitor_command IS 'Persistent callback deduplication; never stores message text, sender/chat IDs, model replies or credentials';
