-- V5__backtest_engine_hardening.sql
-- SignalForge Milestone M3 Fixes: Hardened Result Immutability Insert Triggers and Listing Composite Validation

-- 1. Immutability triggers preventing insert into child tables of terminated backtest runs
CREATE TRIGGER prevent_completed_daily_equity_insert
BEFORE INSERT ON backtest_daily_equity
FOR EACH ROW
WHEN (SELECT status FROM backtest_runs WHERE id = NEW.run_id) IN ('COMPLETED', 'FAILED', 'CANCELLED', 'INTERRUPTED')
BEGIN
    SELECT RAISE(FAIL, 'Cannot insert daily equity into a terminated backtest run');
END;

CREATE TRIGGER prevent_completed_orders_insert
BEFORE INSERT ON backtest_orders
FOR EACH ROW
WHEN (SELECT status FROM backtest_runs WHERE id = NEW.run_id) IN ('COMPLETED', 'FAILED', 'CANCELLED', 'INTERRUPTED')
BEGIN
    SELECT RAISE(FAIL, 'Cannot insert orders into a terminated backtest run');
END;

CREATE TRIGGER prevent_completed_events_insert
BEFORE INSERT ON backtest_events
FOR EACH ROW
WHEN (SELECT status FROM backtest_runs WHERE id = NEW.run_id) IN ('COMPLETED', 'FAILED', 'CANCELLED', 'INTERRUPTED')
BEGIN
    SELECT RAISE(FAIL, 'Cannot insert events into a terminated backtest run');
END;

CREATE TRIGGER prevent_completed_holdings_insert
BEFORE INSERT ON backtest_holdings
FOR EACH ROW
WHEN (SELECT status FROM backtest_runs WHERE id = NEW.run_id) IN ('COMPLETED', 'FAILED', 'CANCELLED', 'INTERRUPTED')
BEGIN
    SELECT RAISE(FAIL, 'Cannot insert holdings into a terminated backtest run');
END;

-- 2. Composite validation trigger ensuring candidate and benchmark listings exist in dataset_listings
CREATE TRIGGER validate_backtest_run_listings_insert
BEFORE INSERT ON backtest_runs
FOR EACH ROW
BEGIN
    SELECT RAISE(ABORT, 'Candidate listing does not exist in dataset')
    WHERE NOT EXISTS (
        SELECT 1 FROM dataset_listings WHERE dataset_id = NEW.dataset_id AND listing_id = NEW.candidate_listing_id
    );
    SELECT RAISE(ABORT, 'Benchmark listing does not exist in dataset')
    WHERE NOT EXISTS (
        SELECT 1 FROM dataset_listings WHERE dataset_id = NEW.dataset_id AND listing_id = NEW.benchmark_listing_id
    );
END;
