CREATE TABLE strategy_revision (
    strategy_code VARCHAR(60) NOT NULL REFERENCES strategy_definition(code),
    version VARCHAR(30) NOT NULL,
    parameters JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY(strategy_code, version)
);

INSERT INTO strategy_definition(code,name,version,parameters,enabled)
VALUES ('SWING_TREND_V1','波段趋势观察','1.0.0',
 '{"volumeMultiplier":1.2,"adjustment":"QFQ","calculationVersion":"ma-macd-vol-v1","riskMode":"CROSS_BELOW_MA60","sellMode":"DEAD_CROSS_BELOW_MA20"}'::jsonb,true)
ON CONFLICT(code) DO NOTHING;

INSERT INTO strategy_revision(strategy_code,version,parameters)
SELECT code,version,parameters FROM strategy_definition WHERE code='SWING_TREND_V1'
ON CONFLICT(strategy_code,version) DO NOTHING;