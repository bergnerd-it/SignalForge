-- Preserve V4-V6 observations while allowing a funded pre-open point and a
-- closing mark on the same trading date. Legacy point timestamps are unknown.
DROP TRIGGER prevent_completed_daily_equity_insert;
DROP TRIGGER prevent_completed_daily_equity_update;
DROP TRIGGER prevent_completed_daily_equity_delete;

CREATE TABLE backtest_daily_equity_v7 (
    run_id TEXT NOT NULL REFERENCES backtest_runs(id),
    series_type TEXT NOT NULL CHECK(series_type IN ('CANDIDATE', 'BENCHMARK')),
    session_date TEXT NOT NULL,
    point_kind TEXT NOT NULL CHECK(point_kind IN ('INITIAL_FUNDED', 'SESSION_CLOSE')),
    observation_time TEXT,
    cash TEXT NOT NULL,
    holdings_value TEXT NOT NULL,
    receivables TEXT NOT NULL,
    total_equity TEXT NOT NULL,
    daily_return TEXT,
    drawdown TEXT NOT NULL,
    peak_equity TEXT NOT NULL,
    units TEXT NOT NULL,
    cost_basis TEXT NOT NULL,
    raw_close TEXT NOT NULL,
    PRIMARY KEY (run_id, series_type, session_date, point_kind)
);

INSERT INTO backtest_daily_equity_v7 (
    run_id, series_type, session_date, point_kind, observation_time,
    cash, holdings_value, receivables, total_equity, daily_return, drawdown,
    peak_equity, units, cost_basis, raw_close
)
SELECT run_id, series_type, session_date,
       CASE WHEN daily_return IS NULL THEN 'INITIAL_FUNDED' ELSE 'SESSION_CLOSE' END,
       NULL, cash, holdings_value, receivables, total_equity, daily_return,
       drawdown, peak_equity, units, cost_basis, raw_close
FROM backtest_daily_equity;

DROP TABLE backtest_daily_equity;
ALTER TABLE backtest_daily_equity_v7 RENAME TO backtest_daily_equity;
CREATE INDEX idx_backtest_daily_equity_lookup
    ON backtest_daily_equity(run_id, series_type, session_date, point_kind);

CREATE TRIGGER prevent_completed_daily_equity_insert
BEFORE INSERT ON backtest_daily_equity
FOR EACH ROW
WHEN (SELECT status FROM backtest_runs WHERE id = NEW.run_id) IN ('COMPLETED', 'FAILED', 'CANCELLED', 'INTERRUPTED')
BEGIN
    SELECT RAISE(FAIL, 'Cannot insert daily equity into a terminated backtest run');
END;

CREATE TRIGGER prevent_completed_daily_equity_update
BEFORE UPDATE ON backtest_daily_equity
FOR EACH ROW
WHEN (SELECT status FROM backtest_runs WHERE id = OLD.run_id) IN ('COMPLETED', 'FAILED', 'CANCELLED', 'INTERRUPTED')
BEGIN
    SELECT RAISE(FAIL, 'Cannot update daily equity of a terminated backtest run');
END;

CREATE TRIGGER prevent_completed_daily_equity_delete
BEFORE DELETE ON backtest_daily_equity
FOR EACH ROW
WHEN (SELECT status FROM backtest_runs WHERE id = OLD.run_id) IN ('COMPLETED', 'FAILED', 'CANCELLED', 'INTERRUPTED')
BEGIN
    SELECT RAISE(FAIL, 'Cannot delete daily equity of a terminated backtest run');
END;
