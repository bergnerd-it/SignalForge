-- V4__backtest_engine.sql
-- SignalForge Milestone M3: Historical Backtest Engine, Daily Equity Series, Orders, Events, and Immutability Triggers

CREATE TABLE backtest_runs (
    id TEXT PRIMARY KEY,
    owner_id TEXT NOT NULL DEFAULT 'default',
    idempotency_key TEXT NOT NULL,
    canonical_hash TEXT NOT NULL,
    strategy_id TEXT NOT NULL,
    strategy_version TEXT NOT NULL,
    dataset_id TEXT NOT NULL REFERENCES datasets(id),
    candidate_listing_id TEXT NOT NULL,
    benchmark_listing_id TEXT NOT NULL,
    initial_cash TEXT NOT NULL,
    currency TEXT NOT NULL DEFAULT 'EUR',
    evaluation_cutoff TEXT NOT NULL,
    requested_start_date TEXT NOT NULL,
    requested_end_date TEXT NOT NULL,
    effective_start_date TEXT,
    effective_end_date TEXT,
    commission_per_fill TEXT NOT NULL,
    spread_bps TEXT NOT NULL,
    slippage_bps TEXT NOT NULL,
    status TEXT NOT NULL CHECK(status IN ('QUEUED', 'RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED', 'INTERRUPTED')),
    progress_pct INTEGER NOT NULL DEFAULT 0,
    failure_reason TEXT,
    cancel_requested INTEGER NOT NULL DEFAULT 0,
    config_json TEXT NOT NULL,
    summary_json TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    completed_at TEXT,
    UNIQUE (owner_id, idempotency_key)
);

CREATE INDEX idx_backtest_runs_owner_status ON backtest_runs(owner_id, status);
CREATE INDEX idx_backtest_runs_dataset ON backtest_runs(dataset_id);

CREATE TABLE backtest_daily_equity (
    run_id TEXT NOT NULL REFERENCES backtest_runs(id),
    series_type TEXT NOT NULL CHECK(series_type IN ('CANDIDATE', 'BENCHMARK')),
    session_date TEXT NOT NULL,
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
    PRIMARY KEY (run_id, series_type, session_date)
);

CREATE INDEX idx_backtest_daily_equity_lookup ON backtest_daily_equity(run_id, series_type, session_date);

CREATE TABLE backtest_orders (
    id TEXT PRIMARY KEY,
    run_id TEXT NOT NULL REFERENCES backtest_runs(id),
    series_type TEXT NOT NULL CHECK(series_type IN ('CANDIDATE', 'BENCHMARK')),
    order_type TEXT NOT NULL CHECK(order_type IN ('INITIAL_BUY', 'REINVEST')),
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

CREATE INDEX idx_backtest_orders_run ON backtest_orders(run_id, series_type, session_date);

CREATE TABLE backtest_events (
    id TEXT PRIMARY KEY,
    run_id TEXT NOT NULL REFERENCES backtest_runs(id),
    series_type TEXT NOT NULL CHECK(series_type IN ('CANDIDATE', 'BENCHMARK')),
    event_seq INTEGER NOT NULL,
    event_type TEXT NOT NULL CHECK(event_type IN ('FUNDING', 'SPLIT', 'ENTITLEMENT', 'PAYMENT', 'EXECUTION', 'CLOSING_MARK')),
    event_date TEXT NOT NULL,
    event_time TEXT NOT NULL,
    description TEXT NOT NULL,
    details_json TEXT,
    cash_delta TEXT NOT NULL,
    units_delta TEXT NOT NULL,
    basis_delta TEXT NOT NULL,
    receivable_delta TEXT NOT NULL,
    created_at TEXT NOT NULL,
    UNIQUE (run_id, series_type, event_seq)
);

CREATE INDEX idx_backtest_events_run ON backtest_events(run_id, series_type, event_seq);

CREATE TABLE backtest_holdings (
    run_id TEXT NOT NULL REFERENCES backtest_runs(id),
    series_type TEXT NOT NULL CHECK(series_type IN ('CANDIDATE', 'BENCHMARK')),
    listing_id TEXT NOT NULL,
    units TEXT NOT NULL,
    total_cost_basis TEXT NOT NULL,
    average_cost TEXT NOT NULL,
    current_price TEXT NOT NULL,
    market_value TEXT NOT NULL,
    unrealized_gain TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    PRIMARY KEY (run_id, series_type, listing_id)
);

-- Immutability triggers preventing modification of completed runs
CREATE TRIGGER prevent_completed_backtest_run_update
BEFORE UPDATE ON backtest_runs
FOR EACH ROW
WHEN OLD.status IN ('COMPLETED', 'FAILED', 'CANCELLED', 'INTERRUPTED')
BEGIN
    SELECT RAISE(FAIL, 'Cannot update a terminated backtest run');
END;

CREATE TRIGGER prevent_completed_backtest_run_delete
BEFORE DELETE ON backtest_runs
FOR EACH ROW
WHEN OLD.status IN ('COMPLETED', 'FAILED', 'CANCELLED', 'INTERRUPTED')
BEGIN
    SELECT RAISE(FAIL, 'Cannot delete a terminated backtest run');
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

CREATE TRIGGER prevent_completed_events_update
BEFORE UPDATE ON backtest_events
FOR EACH ROW
WHEN (SELECT status FROM backtest_runs WHERE id = OLD.run_id) IN ('COMPLETED', 'FAILED', 'CANCELLED', 'INTERRUPTED')
BEGIN
    SELECT RAISE(FAIL, 'Cannot update events of a terminated backtest run');
END;

CREATE TRIGGER prevent_completed_events_delete
BEFORE DELETE ON backtest_events
FOR EACH ROW
WHEN (SELECT status FROM backtest_runs WHERE id = OLD.run_id) IN ('COMPLETED', 'FAILED', 'CANCELLED', 'INTERRUPTED')
BEGIN
    SELECT RAISE(FAIL, 'Cannot delete events of a terminated backtest run');
END;

CREATE TRIGGER prevent_completed_holdings_update
BEFORE UPDATE ON backtest_holdings
FOR EACH ROW
WHEN (SELECT status FROM backtest_runs WHERE id = OLD.run_id) IN ('COMPLETED', 'FAILED', 'CANCELLED', 'INTERRUPTED')
BEGIN
    SELECT RAISE(FAIL, 'Cannot update holdings of a terminated backtest run');
END;

CREATE TRIGGER prevent_completed_holdings_delete
BEFORE DELETE ON backtest_holdings
FOR EACH ROW
WHEN (SELECT status FROM backtest_runs WHERE id = OLD.run_id) IN ('COMPLETED', 'FAILED', 'CANCELLED', 'INTERRUPTED')
BEGIN
    SELECT RAISE(FAIL, 'Cannot delete holdings of a terminated backtest run');
END;
