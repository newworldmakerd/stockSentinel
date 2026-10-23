CREATE TABLE wecom_document_target (
    target_key VARCHAR(40) PRIMARY KEY CHECK(target_key='daily-stocks'),
    status VARCHAR(20) NOT NULL DEFAULT 'NEW' CHECK(status IN ('NEW','SETTING_UP','READY','UNKNOWN')),
    doc_id VARCHAR(512), doc_url TEXT, sheet_id VARCHAR(128),
    last_error VARCHAR(60), updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
INSERT INTO wecom_document_target(target_key) VALUES('daily-stocks');

CREATE TABLE wecom_document_export (
    trade_date DATE PRIMARY KEY,
    doc_id VARCHAR(512) NOT NULL, sheet_id VARCHAR(128) NOT NULL,
    records JSONB NOT NULL CHECK(jsonb_typeof(records)='array'),
    total_rows INT NOT NULL CHECK(total_rows>=0),
    confirmed_rows INT NOT NULL DEFAULT 0 CHECK(confirmed_rows>=0 AND confirmed_rows<=total_rows),
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','SENDING','SUCCESS','EMPTY','FAILED','UNKNOWN')),
    last_error VARCHAR(60), created_at TIMESTAMPTZ NOT NULL DEFAULT now(), finished_at TIMESTAMPTZ
);
COMMENT ON TABLE wecom_document_export IS 'Public daily stock snapshot only; SENDING/UNKNOWN is never blindly replayed';
