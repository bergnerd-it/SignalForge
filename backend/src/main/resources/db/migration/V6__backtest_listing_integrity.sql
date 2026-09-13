-- V6__backtest_listing_integrity.sql
-- SignalForge Milestone M3 Fixes: Enforce Backtest Listing Composite Foreign Keys and Integrity

-- 1. Pre-migration scan: Refuse inconsistent existing rows if candidate or benchmark listings are missing from dataset_listings
CREATE TEMP TABLE _listing_integrity_violations (
    run_id TEXT PRIMARY KEY,
    reason TEXT NOT NULL
);

CREATE TEMP TRIGGER _abort_on_inconsistent_run
BEFORE INSERT ON _listing_integrity_violations
BEGIN
    SELECT RAISE(ABORT, 'Existing backtest_run has invalid candidate or benchmark listing for its dataset');
END;

INSERT INTO _listing_integrity_violations (run_id, reason)
SELECT id, 'candidate_not_in_dataset'
FROM backtest_runs b
WHERE NOT EXISTS (
    SELECT 1 FROM dataset_listings dl
    WHERE dl.dataset_id = b.dataset_id AND dl.listing_id = b.candidate_listing_id
);

INSERT INTO _listing_integrity_violations (run_id, reason)
SELECT id, 'benchmark_not_in_dataset'
FROM backtest_runs b
WHERE NOT EXISTS (
    SELECT 1 FROM dataset_listings dl
    WHERE dl.dataset_id = b.dataset_id AND dl.listing_id = b.benchmark_listing_id
);

DROP TRIGGER _abort_on_inconsistent_run;
DROP TABLE _listing_integrity_violations;

-- 2. Rebuild backtest_runs with composite foreign keys to dataset_listings
PRAGMA legacy_alter_table = ON;

CREATE TABLE backtest_runs_new (
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
    UNIQUE (owner_id, idempotency_key),
    FOREIGN KEY (dataset_id, candidate_listing_id) REFERENCES dataset_listings(dataset_id, listing_id) ON DELETE RESTRICT,
    FOREIGN KEY (dataset_id, benchmark_listing_id) REFERENCES dataset_listings(dataset_id, listing_id) ON DELETE RESTRICT
);

INSERT INTO backtest_runs_new (
    id, owner_id, idempotency_key, canonical_hash, strategy_id, strategy_version,
    dataset_id, candidate_listing_id, benchmark_listing_id, initial_cash, currency,
    evaluation_cutoff, requested_start_date, requested_end_date, effective_start_date,
    effective_end_date, commission_per_fill, spread_bps, slippage_bps, status,
    progress_pct, failure_reason, cancel_requested, config_json, summary_json,
    created_at, updated_at, completed_at
)
SELECT
    id, owner_id, idempotency_key, canonical_hash, strategy_id, strategy_version,
    dataset_id, candidate_listing_id, benchmark_listing_id, initial_cash, currency,
    evaluation_cutoff, requested_start_date, requested_end_date, effective_start_date,
    effective_end_date, commission_per_fill, spread_bps, slippage_bps, status,
    progress_pct, failure_reason, cancel_requested, config_json, summary_json,
    created_at, updated_at, completed_at
FROM backtest_runs;

DROP TABLE backtest_runs;

ALTER TABLE backtest_runs_new RENAME TO backtest_runs;

PRAGMA legacy_alter_table = OFF;

-- 3. Recreate indexes on backtest_runs
CREATE INDEX IF NOT EXISTS idx_backtest_runs_owner_status ON backtest_runs(owner_id, status);
CREATE INDEX IF NOT EXISTS idx_backtest_runs_dataset ON backtest_runs(dataset_id);

-- 4. Recreate triggers on backtest_runs
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

CREATE TRIGGER validate_backtest_run_listings_update
BEFORE UPDATE OF dataset_id, candidate_listing_id, benchmark_listing_id ON backtest_runs
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
