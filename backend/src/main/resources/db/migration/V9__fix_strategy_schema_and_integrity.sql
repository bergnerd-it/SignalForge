-- Flyway migration V9: Fix strategy parameter schema for S3 and restore update guard

DROP TRIGGER IF EXISTS prevent_strategy_versions_update;

UPDATE strategy_versions
SET parameters_schema_json = '{"type":"object","properties":{}}'
WHERE strategy_id = 'ETF_TREND_10M_V1';

CREATE TRIGGER prevent_strategy_versions_update
BEFORE UPDATE ON strategy_versions
FOR EACH ROW
BEGIN
    SELECT RAISE(FAIL, 'Cannot modify immutable strategy version');
END;
