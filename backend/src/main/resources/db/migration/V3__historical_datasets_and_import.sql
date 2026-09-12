-- SignalForge M2: Historical datasets and import schema

CREATE TABLE IF NOT EXISTS datasets (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    source TEXT NOT NULL,
    classification TEXT NOT NULL CHECK(classification IN ('SYNTHETIC', 'HISTORICAL')),
    schema_version TEXT NOT NULL,
    parser_version TEXT NOT NULL,
    input_checksum TEXT NOT NULL,
    content_checksum TEXT NOT NULL,
    manifest_json TEXT NOT NULL,
    coverage_start TEXT NOT NULL,
    coverage_end TEXT NOT NULL,
    validation_status TEXT NOT NULL CHECK(validation_status IN ('VALID', 'WARNINGS', 'REJECTED')),
    validation_findings_json TEXT NOT NULL,
    quality_label TEXT NOT NULL CHECK(quality_label IN ('VERIFIED', 'REVISED_HISTORY', 'SYNTHETIC')),
    imported_at TEXT NOT NULL,
    created_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_datasets_input_checksum ON datasets(input_checksum);

CREATE TABLE IF NOT EXISTS dataset_listings (
    dataset_id TEXT NOT NULL REFERENCES datasets(id) ON DELETE RESTRICT,
    listing_id TEXT NOT NULL,
    instrument_id TEXT NOT NULL,
    symbol TEXT NOT NULL,
    venue TEXT,
    quote_currency TEXT NOT NULL,
    calendar_id TEXT NOT NULL,
    inception_date TEXT,
    termination_date TEXT,
    isin TEXT,
    PRIMARY KEY (dataset_id, listing_id)
);

CREATE TABLE IF NOT EXISTS dataset_sessions (
    dataset_id TEXT NOT NULL REFERENCES datasets(id) ON DELETE RESTRICT,
    calendar_id TEXT NOT NULL,
    session_date TEXT NOT NULL,
    open_time TEXT NOT NULL,
    close_time TEXT NOT NULL,
    session_type TEXT NOT NULL CHECK(session_type IN ('TRADING', 'CLOSED')),
    PRIMARY KEY (dataset_id, calendar_id, session_date)
);

CREATE TABLE IF NOT EXISTS historical_bars (
    dataset_id TEXT NOT NULL,
    listing_id TEXT NOT NULL,
    session_date TEXT NOT NULL,
    open TEXT NOT NULL,
    high TEXT NOT NULL,
    low TEXT NOT NULL,
    close TEXT NOT NULL,
    volume TEXT,
    available_at TEXT NOT NULL,
    PRIMARY KEY (dataset_id, listing_id, session_date),
    FOREIGN KEY (dataset_id, listing_id) REFERENCES dataset_listings(dataset_id, listing_id) ON DELETE RESTRICT
);

CREATE INDEX IF NOT EXISTS idx_historical_bars_lookup
    ON historical_bars(dataset_id, listing_id, session_date);

CREATE TABLE IF NOT EXISTS historical_actions (
    dataset_id TEXT NOT NULL,
    action_id TEXT NOT NULL,
    listing_id TEXT NOT NULL,
    action_type TEXT NOT NULL CHECK(action_type IN ('SPLIT', 'CASH_DISTRIBUTION')),
    effective_date TEXT NOT NULL,
    available_at TEXT NOT NULL,
    split_ratio_numerator INTEGER,
    split_ratio_denominator INTEGER,
    distribution_amount TEXT,
    distribution_currency TEXT,
    payment_date TEXT,
    payment_instant TEXT,
    PRIMARY KEY (dataset_id, action_id),
    FOREIGN KEY (dataset_id, listing_id) REFERENCES dataset_listings(dataset_id, listing_id) ON DELETE RESTRICT
);

CREATE INDEX IF NOT EXISTS idx_historical_actions_lookup
    ON historical_actions(dataset_id, listing_id, effective_date);

CREATE TABLE IF NOT EXISTS import_jobs (
    id TEXT PRIMARY KEY,
    request_key TEXT NOT NULL UNIQUE,
    input_checksum TEXT NOT NULL,
    status TEXT NOT NULL CHECK(status IN ('QUEUED', 'RUNNING', 'COMPLETED', 'FAILED', 'INTERRUPTED')),
    dataset_id TEXT REFERENCES datasets(id) ON DELETE RESTRICT,
    progress_pct INTEGER NOT NULL,
    message TEXT,
    error_detail TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_import_jobs_key ON import_jobs(request_key);
CREATE INDEX IF NOT EXISTS idx_import_jobs_checksum ON import_jobs(input_checksum);

-- Immutability triggers for datasets
CREATE TRIGGER IF NOT EXISTS prevent_dataset_update
BEFORE UPDATE ON datasets
BEGIN
    SELECT RAISE(ABORT, 'Historical datasets are immutable and cannot be updated');
END;

CREATE TRIGGER IF NOT EXISTS prevent_dataset_delete
BEFORE DELETE ON datasets
BEGIN
    SELECT RAISE(ABORT, 'Historical datasets are immutable and cannot be deleted');
END;

-- Immutability triggers for dataset_listings
CREATE TRIGGER IF NOT EXISTS prevent_dataset_listings_update
BEFORE UPDATE ON dataset_listings
BEGIN
    SELECT RAISE(ABORT, 'Dataset listings are immutable and cannot be updated');
END;

CREATE TRIGGER IF NOT EXISTS prevent_dataset_listings_delete
BEFORE DELETE ON dataset_listings
BEGIN
    SELECT RAISE(ABORT, 'Dataset listings are immutable and cannot be deleted');
END;

-- Immutability triggers for dataset_sessions
CREATE TRIGGER IF NOT EXISTS prevent_dataset_sessions_update
BEFORE UPDATE ON dataset_sessions
BEGIN
    SELECT RAISE(ABORT, 'Dataset sessions are immutable and cannot be updated');
END;

CREATE TRIGGER IF NOT EXISTS prevent_dataset_sessions_delete
BEFORE DELETE ON dataset_sessions
BEGIN
    SELECT RAISE(ABORT, 'Dataset sessions are immutable and cannot be deleted');
END;

-- Immutability triggers for historical_bars
CREATE TRIGGER IF NOT EXISTS prevent_historical_bars_update
BEFORE UPDATE ON historical_bars
BEGIN
    SELECT RAISE(ABORT, 'Historical bars are immutable and cannot be updated');
END;

CREATE TRIGGER IF NOT EXISTS prevent_historical_bars_delete
BEFORE DELETE ON historical_bars
BEGIN
    SELECT RAISE(ABORT, 'Historical bars are immutable and cannot be deleted');
END;

-- Immutability triggers for historical_actions
CREATE TRIGGER IF NOT EXISTS prevent_historical_actions_update
BEFORE UPDATE ON historical_actions
BEGIN
    SELECT RAISE(ABORT, 'Historical corporate actions are immutable and cannot be updated');
END;

CREATE TRIGGER IF NOT EXISTS prevent_historical_actions_delete
BEFORE DELETE ON historical_actions
BEGIN
    SELECT RAISE(ABORT, 'Historical corporate actions are immutable and cannot be deleted');
END;

-- Validation triggers for historical_bars
CREATE TRIGGER IF NOT EXISTS validate_historical_bars_insert
BEFORE INSERT ON historical_bars
WHEN typeof(NEW.open) <> 'text' OR NEW.open = '' OR NEW.open GLOB '*[^0-9.]*' OR NEW.open IN ('', '.')
  OR typeof(NEW.high) <> 'text' OR NEW.high = '' OR NEW.high GLOB '*[^0-9.]*' OR NEW.high IN ('', '.')
  OR typeof(NEW.low) <> 'text' OR NEW.low = '' OR NEW.low GLOB '*[^0-9.]*' OR NEW.low IN ('', '.')
  OR typeof(NEW.close) <> 'text' OR NEW.close = '' OR NEW.close GLOB '*[^0-9.]*' OR NEW.close IN ('', '.')
BEGIN
    SELECT RAISE(ABORT, 'Invalid OHLC decimal format in historical bars');
END;
