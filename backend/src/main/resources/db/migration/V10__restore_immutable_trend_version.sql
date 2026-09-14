-- Restore the original 1.0.0 metadata after V9 and publish corrected metadata as 1.0.1.
DROP TRIGGER IF EXISTS prevent_strategy_versions_update;

UPDATE strategy_versions
SET parameters_schema_json = '{"type":"object","properties":{"lookbackMonths":{"type":"integer","default":10}}}'
WHERE strategy_id = 'ETF_TREND_10M_V1' AND strategy_version = '1.0.0';

CREATE TRIGGER prevent_strategy_versions_update
BEFORE UPDATE ON strategy_versions
FOR EACH ROW
BEGIN
    SELECT RAISE(FAIL, 'Cannot update immutable strategy version');
END;

INSERT INTO strategy_versions (
    strategy_id, strategy_version, name, description, parameters_schema_json,
    calculation_policy_version, decision_schedule, created_at
)
SELECT strategy_id, '1.0.1', name, description, '{"type":"object","properties":{}}',
       calculation_policy_version, decision_schedule, '2026-09-14T00:00:00Z'
FROM strategy_versions
WHERE strategy_id = 'ETF_TREND_10M_V1' AND strategy_version = '1.0.0';

-- A final in-window signal has no eligible execution date. Preserve existing rows
-- while allowing that auditable UNEXECUTED state.
CREATE TABLE backtest_signals_new (
    id TEXT PRIMARY KEY,
    run_id TEXT NOT NULL REFERENCES backtest_runs(id),
    strategy_id TEXT NOT NULL,
    strategy_version TEXT NOT NULL,
    universe_id TEXT REFERENCES universes(id),
    evaluation_date TEXT NOT NULL,
    evaluation_time TEXT NOT NULL,
    decision_instant TEXT NOT NULL,
    scheduled_execution_date TEXT,
    target_allocation_summary TEXT NOT NULL,
    status TEXT NOT NULL CHECK(status IN ('SCHEDULED', 'EXECUTED', 'UNEXECUTED', 'BLOCKED', 'SKIPPED')),
    reason_code TEXT NOT NULL,
    details_json TEXT NOT NULL,
    created_at TEXT NOT NULL
);

INSERT INTO backtest_signals_new SELECT * FROM backtest_signals;
DROP TABLE backtest_signals;
ALTER TABLE backtest_signals_new RENAME TO backtest_signals;
CREATE INDEX idx_backtest_signals_run ON backtest_signals(run_id, evaluation_date);

CREATE TRIGGER prevent_completed_signals_insert
BEFORE INSERT ON backtest_signals
FOR EACH ROW
WHEN (SELECT status FROM backtest_runs WHERE id = NEW.run_id) IN ('COMPLETED', 'FAILED', 'CANCELLED', 'INTERRUPTED')
BEGIN
    SELECT RAISE(FAIL, 'Cannot insert signals for a terminated backtest run');
END;

CREATE TRIGGER prevent_completed_signals_update
BEFORE UPDATE ON backtest_signals
FOR EACH ROW
WHEN (SELECT status FROM backtest_runs WHERE id = OLD.run_id) IN ('COMPLETED', 'FAILED', 'CANCELLED', 'INTERRUPTED')
BEGIN
    SELECT RAISE(FAIL, 'Cannot update signals of a terminated backtest run');
END;

CREATE TRIGGER prevent_completed_signals_delete
BEFORE DELETE ON backtest_signals
FOR EACH ROW
WHEN (SELECT status FROM backtest_runs WHERE id = OLD.run_id) IN ('COMPLETED', 'FAILED', 'CANCELLED', 'INTERRUPTED')
BEGIN
    SELECT RAISE(FAIL, 'Cannot delete signals of a terminated backtest run');
END;
