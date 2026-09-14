-- V8__strategy_versions_and_comparisons.sql
-- SignalForge Milestone M4: Strategy Versions, Universes, Multi-Asset Orders, Auditable Signals, Comparisons, and Experiments

-- 1. Universes & Universe Listings
CREATE TABLE universes (
    id TEXT PRIMARY KEY,
    owner_id TEXT NOT NULL DEFAULT 'default',
    name TEXT NOT NULL,
    version TEXT NOT NULL DEFAULT '1.0.0',
    description TEXT,
    dataset_id TEXT NOT NULL REFERENCES datasets(id),
    calendar_id TEXT NOT NULL,
    currency TEXT NOT NULL DEFAULT 'EUR',
    provenance TEXT,
    created_at TEXT NOT NULL,
    UNIQUE (owner_id, name, version)
);

CREATE INDEX idx_universes_owner ON universes(owner_id);
CREATE INDEX idx_universes_dataset ON universes(dataset_id);

CREATE TABLE universe_listings (
    universe_id TEXT NOT NULL REFERENCES universes(id) ON DELETE RESTRICT,
    listing_id TEXT NOT NULL,
    ordinal INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (universe_id, listing_id)
);

CREATE INDEX idx_universe_listings_listing ON universe_listings(listing_id);

-- Immutability on universes and universe_listings
CREATE TRIGGER prevent_universes_update
BEFORE UPDATE ON universes
FOR EACH ROW
BEGIN
    SELECT RAISE(FAIL, 'Cannot update immutable universe version');
END;

CREATE TRIGGER prevent_universes_delete
BEFORE DELETE ON universes
FOR EACH ROW
BEGIN
    SELECT RAISE(FAIL, 'Cannot delete immutable universe version');
END;

CREATE TRIGGER prevent_universe_listings_update
BEFORE UPDATE ON universe_listings
FOR EACH ROW
BEGIN
    SELECT RAISE(FAIL, 'Cannot update immutable universe listing');
END;

CREATE TRIGGER prevent_universe_listings_delete
BEFORE DELETE ON universe_listings
FOR EACH ROW
BEGIN
    SELECT RAISE(FAIL, 'Cannot delete immutable universe listing');
END;

-- 2. Strategy Versions
CREATE TABLE strategy_versions (
    strategy_id TEXT NOT NULL,
    strategy_version TEXT NOT NULL,
    name TEXT NOT NULL,
    description TEXT,
    parameters_schema_json TEXT,
    calculation_policy_version TEXT NOT NULL DEFAULT 'v1-half-even',
    decision_schedule TEXT NOT NULL DEFAULT 'MONTH_END_CLOSE',
    created_at TEXT NOT NULL,
    PRIMARY KEY (strategy_id, strategy_version)
);

CREATE TRIGGER prevent_strategy_versions_update
BEFORE UPDATE ON strategy_versions
FOR EACH ROW
BEGIN
    SELECT RAISE(FAIL, 'Cannot update immutable strategy version');
END;

CREATE TRIGGER prevent_strategy_versions_delete
BEFORE DELETE ON strategy_versions
FOR EACH ROW
BEGIN
    SELECT RAISE(FAIL, 'Cannot delete immutable strategy version');
END;

-- Seed known M4 strategy versions
INSERT INTO strategy_versions (strategy_id, strategy_version, name, description, parameters_schema_json, calculation_policy_version, decision_schedule, created_at)
VALUES 
('ETF_BUY_HOLD_V1', '1.0.0', 'Global ETF Buy and Hold', 'Single-asset global ETF buy-and-hold baseline with reinvestment of paid distributions', '{"type":"object","properties":{}}', 'v1-half-even', 'MONTH_END_CLOSE', '2026-09-14T00:00:00Z'),
('ETF_MOMENTUM_12_1_V1', '1.0.0', 'Monthly ETF Momentum (12-1)', 'ETF rotation strategy ranking universe by 12-1 total-return momentum, selecting top K with equal target weights 1/K', '{"type":"object","properties":{"k":{"type":"integer","minimum":1,"default":3}}}', 'v1-half-even', 'MONTH_END_CLOSE', '2026-09-14T00:00:00Z'),
('ETF_TREND_10M_V1', '1.0.0', 'Global ETF Trend Filter (10-Month SMA)', 'Trend-following strategy on global ETF comparing current month-end total-return index against 10-month SMA, selecting 100% ETF or 100% cash', '{"type":"object","properties":{"lookbackMonths":{"type":"integer","default":10}}}', 'v1-half-even', 'MONTH_END_CLOSE', '2026-09-14T00:00:00Z');

-- 3. Experiments & Holdout Exposure Tracking
CREATE TABLE experiments (
    id TEXT PRIMARY KEY,
    owner_id TEXT NOT NULL DEFAULT 'default',
    name TEXT NOT NULL,
    version INTEGER NOT NULL DEFAULT 1,
    strategy_id TEXT NOT NULL,
    strategy_version TEXT NOT NULL,
    dataset_id TEXT NOT NULL REFERENCES datasets(id),
    universe_id TEXT REFERENCES universes(id),
    candidate_listing_id TEXT,
    benchmark_listing_id TEXT NOT NULL,
    development_start_date TEXT NOT NULL,
    development_end_date TEXT NOT NULL,
    holdout_start_date TEXT NOT NULL,
    holdout_end_date TEXT NOT NULL,
    declared_holdout_status TEXT NOT NULL CHECK(declared_holdout_status IN ('UNEXAMINED', 'EXAMINED', 'UNKNOWN')),
    parameters_json TEXT,
    created_at TEXT NOT NULL,
    UNIQUE (owner_id, name, version)
);

CREATE INDEX idx_experiments_owner ON experiments(owner_id);

CREATE TRIGGER prevent_experiments_update
BEFORE UPDATE ON experiments
FOR EACH ROW
BEGIN
    SELECT RAISE(FAIL, 'Cannot update immutable experiment definition');
END;

CREATE TRIGGER prevent_experiments_delete
BEFORE DELETE ON experiments
FOR EACH ROW
BEGIN
    SELECT RAISE(FAIL, 'Cannot delete immutable experiment definition');
END;

CREATE TABLE experiment_exposure_events (
    id TEXT PRIMARY KEY,
    experiment_id TEXT NOT NULL REFERENCES experiments(id),
    run_id TEXT,
    access_type TEXT NOT NULL CHECK(access_type IN ('VIEW_DETAIL', 'EXPORT_DOWNLOAD', 'COMPARISON_VIEW', 'SUMMARY_VIEW')),
    exposed_by TEXT NOT NULL,
    exposed_at TEXT NOT NULL,
    details_json TEXT
);

CREATE INDEX idx_exposure_events_exp ON experiment_exposure_events(experiment_id, exposed_at);

CREATE TRIGGER prevent_exposure_events_update
BEFORE UPDATE ON experiment_exposure_events
FOR EACH ROW
BEGIN
    SELECT RAISE(FAIL, 'Experiment exposure events are strictly append-only');
END;

CREATE TRIGGER prevent_exposure_events_delete
BEFORE DELETE ON experiment_exposure_events
FOR EACH ROW
BEGIN
    SELECT RAISE(FAIL, 'Experiment exposure events are strictly append-only');
END;

-- 4. Extend backtest_runs with universe_id, parameters_json, experiment_id without table rebuild
ALTER TABLE backtest_runs ADD COLUMN universe_id TEXT REFERENCES universes(id);
ALTER TABLE backtest_runs ADD COLUMN parameters_json TEXT;
ALTER TABLE backtest_runs ADD COLUMN experiment_id TEXT REFERENCES experiments(id);

CREATE INDEX IF NOT EXISTS idx_backtest_runs_universe ON backtest_runs(universe_id);

-- 5. Rebuild backtest_orders to support REBALANCE_BUY and REBALANCE_SELL
PRAGMA legacy_alter_table = ON;

CREATE TABLE backtest_orders_new (
    id TEXT PRIMARY KEY,
    run_id TEXT NOT NULL REFERENCES backtest_runs(id),
    series_type TEXT NOT NULL CHECK(series_type IN ('CANDIDATE', 'BENCHMARK')),
    order_type TEXT NOT NULL CHECK(order_type IN ('INITIAL_BUY', 'REINVEST', 'REBALANCE_BUY', 'REBALANCE_SELL')),
    listing_id TEXT NOT NULL,
    session_date TEXT NOT NULL,
    requested_quantity TEXT NOT NULL,
    executed_quantity TEXT NOT NULL,
    raw_open TEXT NOT NULL,
    fill_price TEXT NOT NULL,
    commission TEXT NOT NULL,
    spread_cost TEXT NOT NULL,
    slippage_cost TEXT NOT NULL,
    status TEXT NOT NULL CHECK(status IN ('FILLED', 'SKIPPED')),
    skip_reason TEXT,
    created_at TEXT NOT NULL
);

INSERT INTO backtest_orders_new (
    id, run_id, series_type, order_type, listing_id, session_date,
    requested_quantity, executed_quantity, raw_open, fill_price,
    commission, spread_cost, slippage_cost, status, skip_reason, created_at
)
SELECT
    id, run_id, series_type, order_type, listing_id, session_date,
    requested_quantity, executed_quantity, raw_open, fill_price,
    commission, spread_cost, slippage_cost, status, skip_reason, created_at
FROM backtest_orders;

DROP TABLE backtest_orders;
ALTER TABLE backtest_orders_new RENAME TO backtest_orders;

PRAGMA legacy_alter_table = OFF;

CREATE INDEX IF NOT EXISTS idx_backtest_orders_run ON backtest_orders(run_id, series_type, session_date);

CREATE TRIGGER prevent_completed_orders_insert
BEFORE INSERT ON backtest_orders
FOR EACH ROW
WHEN (SELECT status FROM backtest_runs WHERE id = NEW.run_id) IN ('COMPLETED', 'FAILED', 'CANCELLED', 'INTERRUPTED')
BEGIN
    SELECT RAISE(FAIL, 'Cannot insert orders for a terminated backtest run');
END;

CREATE TRIGGER prevent_completed_orders_update
BEFORE UPDATE ON backtest_orders
FOR EACH ROW
WHEN (SELECT status FROM backtest_runs WHERE id = OLD.run_id) IN ('COMPLETED', 'FAILED', 'CANCELLED', 'INTERRUPTED')
BEGIN
    SELECT RAISE(FAIL, 'Cannot update orders of a terminated backtest run');
END;

CREATE TRIGGER prevent_completed_orders_delete
BEFORE DELETE ON backtest_orders
FOR EACH ROW
WHEN (SELECT status FROM backtest_runs WHERE id = OLD.run_id) IN ('COMPLETED', 'FAILED', 'CANCELLED', 'INTERRUPTED')
BEGIN
    SELECT RAISE(FAIL, 'Cannot delete orders of a terminated backtest run');
END;

-- 6. Auditable Backtest Signals
CREATE TABLE backtest_signals (
    id TEXT PRIMARY KEY,
    run_id TEXT NOT NULL REFERENCES backtest_runs(id),
    strategy_id TEXT NOT NULL,
    strategy_version TEXT NOT NULL,
    universe_id TEXT REFERENCES universes(id),
    evaluation_date TEXT NOT NULL,
    evaluation_time TEXT NOT NULL,
    decision_instant TEXT NOT NULL,
    scheduled_execution_date TEXT NOT NULL,
    target_allocation_summary TEXT NOT NULL,
    status TEXT NOT NULL CHECK(status IN ('SCHEDULED', 'EXECUTED', 'UNEXECUTED', 'BLOCKED', 'SKIPPED')),
    reason_code TEXT NOT NULL,
    details_json TEXT NOT NULL,
    created_at TEXT NOT NULL
);

CREATE INDEX idx_backtest_signals_run ON backtest_signals(run_id, evaluation_date);

CREATE TABLE backtest_signal_items (
    id TEXT PRIMARY KEY,
    signal_id TEXT NOT NULL REFERENCES backtest_signals(id) ON DELETE CASCADE,
    listing_id TEXT NOT NULL,
    score TEXT,
    index_value TEXT,
    sma_value TEXT,
    rank INTEGER,
    eligible INTEGER NOT NULL DEFAULT 1,
    selected INTEGER NOT NULL DEFAULT 0,
    target_weight TEXT NOT NULL,
    reason_code TEXT NOT NULL,
    UNIQUE (signal_id, listing_id)
);

CREATE INDEX idx_backtest_signal_items_signal ON backtest_signal_items(signal_id);

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

-- 7. Backtest Comparisons
CREATE TABLE backtest_comparisons (
    id TEXT PRIMARY KEY,
    owner_id TEXT NOT NULL DEFAULT 'default',
    idempotency_key TEXT NOT NULL,
    name TEXT NOT NULL,
    benchmark_listing_id TEXT NOT NULL,
    dataset_id TEXT NOT NULL REFERENCES datasets(id),
    effective_start_date TEXT NOT NULL,
    effective_end_date TEXT NOT NULL,
    initial_cash TEXT NOT NULL,
    currency TEXT NOT NULL DEFAULT 'EUR',
    status TEXT NOT NULL CHECK(status IN ('MATCHED', 'MISMATCHED', 'ERROR')),
    mismatch_reasons_json TEXT,
    summary_json TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    UNIQUE (owner_id, idempotency_key)
);

CREATE INDEX idx_backtest_comparisons_owner ON backtest_comparisons(owner_id);

CREATE TABLE backtest_comparison_items (
    comparison_id TEXT NOT NULL REFERENCES backtest_comparisons(id) ON DELETE CASCADE,
    run_id TEXT NOT NULL REFERENCES backtest_runs(id) ON DELETE RESTRICT,
    role TEXT NOT NULL,
    ordinal INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (comparison_id, run_id)
);

CREATE TRIGGER prevent_comparisons_delete
BEFORE DELETE ON backtest_comparisons
FOR EACH ROW
BEGIN
    SELECT RAISE(FAIL, 'Cannot delete persisted backtest comparison');
END;
