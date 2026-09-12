-- SignalForge M1b Versioned Schema Migration V1

-- 1. Schema Migrations Table
CREATE TABLE IF NOT EXISTS schema_migrations (
    version INTEGER PRIMARY KEY,
    description TEXT NOT NULL,
    checksum TEXT NOT NULL,
    applied_at TEXT NOT NULL,
    code_version TEXT NOT NULL
);

-- 2. Portfolios Table
CREATE TABLE IF NOT EXISTS portfolios (
    id TEXT PRIMARY KEY,
    owner_id TEXT NOT NULL,
    name TEXT NOT NULL,
    mode TEXT NOT NULL CHECK(mode IN ('LEGACY_DEMO', 'PAPER', 'BACKTEST')),
    base_currency TEXT NOT NULL,
    initial_cash TEXT NOT NULL,
    created_at TEXT NOT NULL,
    paper_started_at TEXT,
    rounding_policy_version TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_portfolios_owner ON portfolios(owner_id);
CREATE INDEX IF NOT EXISTS idx_portfolios_mode ON portfolios(mode);

-- 3. Instruments Table
CREATE TABLE IF NOT EXISTS instruments (
    id TEXT PRIMARY KEY,
    type TEXT NOT NULL,
    name TEXT NOT NULL,
    isin TEXT,
    provenance TEXT NOT NULL
);

-- 4. Listings Table
CREATE TABLE IF NOT EXISTS listings (
    id TEXT PRIMARY KEY,
    instrument_id TEXT NOT NULL REFERENCES instruments(id),
    venue TEXT,
    symbol TEXT NOT NULL,
    quote_currency TEXT NOT NULL,
    calendar_id TEXT,
    inception_date TEXT,
    termination_date TEXT,
    identity_status TEXT NOT NULL,
    UNIQUE(venue, symbol, quote_currency)
);

CREATE INDEX IF NOT EXISTS idx_listings_symbol ON listings(symbol);

-- 5. Listing Aliases Table
CREATE TABLE IF NOT EXISTS listing_aliases (
    id TEXT PRIMARY KEY,
    listing_id TEXT NOT NULL REFERENCES listings(id),
    source TEXT NOT NULL,
    alias TEXT NOT NULL,
    effective_from TEXT NOT NULL,
    effective_to TEXT,
    UNIQUE(source, alias, effective_from)
);

-- 6. Portfolio Creation Requests (Owner-scoped Idempotency)
CREATE TABLE IF NOT EXISTS portfolio_creation_requests (
    owner_id TEXT NOT NULL,
    idempotency_key TEXT NOT NULL,
    portfolio_id TEXT NOT NULL REFERENCES portfolios(id),
    payload_hash TEXT NOT NULL,
    created_at TEXT NOT NULL,
    PRIMARY KEY (owner_id, idempotency_key)
);

-- 7. Portfolio State (Projections)
CREATE TABLE IF NOT EXISTS portfolio_state (
    portfolio_id TEXT PRIMARY KEY REFERENCES portfolios(id),
    cash_amount TEXT NOT NULL,
    revision INTEGER NOT NULL
);

-- 8. Positions Table
CREATE TABLE IF NOT EXISTS positions (
    portfolio_id TEXT NOT NULL REFERENCES portfolios(id),
    listing_id TEXT NOT NULL REFERENCES listings(id),
    quantity TEXT NOT NULL,
    total_acquisition_cost TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    PRIMARY KEY (portfolio_id, listing_id)
);

-- 9. Operations Table (Portfolio-scoped Idempotency)
CREATE TABLE IF NOT EXISTS operations (
    id TEXT PRIMARY KEY,
    portfolio_id TEXT NOT NULL REFERENCES portfolios(id),
    kind TEXT NOT NULL,
    idempotency_key TEXT NOT NULL,
    payload_hash TEXT NOT NULL,
    result_json TEXT NOT NULL,
    business_at TEXT NOT NULL,
    created_at TEXT NOT NULL,
    UNIQUE (portfolio_id, kind, idempotency_key),
    UNIQUE (portfolio_id, id)
);

CREATE INDEX IF NOT EXISTS idx_operations_portfolio ON operations(portfolio_id);

-- 10. Ledger Entries Table (Append-Only)
CREATE TABLE IF NOT EXISTS ledger_entries (
    id TEXT PRIMARY KEY,
    portfolio_id TEXT NOT NULL,
    operation_id TEXT NOT NULL,
    sequence INTEGER NOT NULL,
    entry_type TEXT NOT NULL,
    listing_id TEXT REFERENCES listings(id),
    signed_quantity_delta TEXT,
    signed_cash_delta TEXT,
    acquisition_cost_delta TEXT,
    currency TEXT NOT NULL,
    business_at TEXT NOT NULL,
    recorded_at TEXT NOT NULL,
    legacy_record_id TEXT,
    UNIQUE (operation_id, sequence),
    FOREIGN KEY (portfolio_id, operation_id) REFERENCES operations(portfolio_id, id) ON DELETE RESTRICT
);

CREATE INDEX IF NOT EXISTS idx_ledger_portfolio ON ledger_entries(portfolio_id);

-- 11. Executions Table
CREATE TABLE IF NOT EXISTS executions (
    id TEXT PRIMARY KEY,
    portfolio_id TEXT NOT NULL,
    operation_id TEXT NOT NULL,
    listing_id TEXT NOT NULL REFERENCES listings(id),
    side TEXT NOT NULL,
    units TEXT NOT NULL,
    reference_price TEXT NOT NULL,
    fill_price TEXT NOT NULL,
    commission TEXT NOT NULL,
    modeled_spread_slippage TEXT NOT NULL,
    executed_at TEXT NOT NULL,
    execution_model TEXT NOT NULL,
    sequence INTEGER NOT NULL,
    UNIQUE (operation_id, sequence),
    FOREIGN KEY (portfolio_id, operation_id) REFERENCES operations(portfolio_id, id)
);

-- 12. Valuations Table
CREATE TABLE IF NOT EXISTS valuations (
    id TEXT PRIMARY KEY,
    portfolio_id TEXT NOT NULL REFERENCES portfolios(id),
    business_at TEXT NOT NULL,
    valuation_sequence INTEGER NOT NULL,
    cash TEXT NOT NULL,
    positions_value TEXT NOT NULL,
    receivables_value TEXT NOT NULL,
    equity TEXT NOT NULL,
    source_quality TEXT NOT NULL,
    UNIQUE (portfolio_id, business_at, valuation_sequence)
);

CREATE INDEX IF NOT EXISTS idx_valuations_portfolio ON valuations(portfolio_id);

-- 13. Legacy Watchlist (Portfolio-Scoped)
CREATE TABLE IF NOT EXISTS legacy_watchlist (
    id TEXT PRIMARY KEY,
    portfolio_id TEXT NOT NULL REFERENCES portfolios(id),
    ticker TEXT NOT NULL,
    added_at TEXT NOT NULL,
    UNIQUE (portfolio_id, ticker)
);

-- 14. Chat Actions Table
CREATE TABLE IF NOT EXISTS chat_actions (
    id TEXT PRIMARY KEY,
    chat_message_id TEXT NOT NULL,
    operation_id TEXT REFERENCES operations(id),
    action_type TEXT NOT NULL,
    action_payload TEXT NOT NULL,
    status TEXT NOT NULL,
    created_at TEXT NOT NULL
);

-- 15. Migration Reconciliation Records
CREATE TABLE IF NOT EXISTS migration_reconciliations (
    id TEXT PRIMARY KEY,
    portfolio_id TEXT NOT NULL REFERENCES portfolios(id),
    owner_id TEXT NOT NULL,
    reconciliation_type TEXT NOT NULL,
    raw_value TEXT,
    converted_value TEXT,
    difference TEXT,
    notes TEXT,
    reconciled_at TEXT NOT NULL
);

-- 16. Immutability Triggers for Ledger Entries
CREATE TRIGGER IF NOT EXISTS prevent_ledger_update
BEFORE UPDATE ON ledger_entries
BEGIN
    SELECT RAISE(ABORT, 'Ledger entries are append-only and cannot be updated');
END;

CREATE TRIGGER IF NOT EXISTS prevent_ledger_delete
BEFORE DELETE ON ledger_entries
BEGIN
    SELECT RAISE(ABORT, 'Ledger entries are append-only and cannot be deleted');
END;

-- 17. Legacy Compatibility Tables (Preserved and maintained for demo/legacy REST compatibility)
CREATE TABLE IF NOT EXISTS users_profile (
    id TEXT PRIMARY KEY,
    cash_balance REAL NOT NULL DEFAULT 10000.0,
    created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS watchlist (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL DEFAULT 'default',
    ticker TEXT NOT NULL,
    added_at TEXT NOT NULL,
    UNIQUE(user_id, ticker)
);

CREATE TABLE IF NOT EXISTS trades (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL DEFAULT 'default',
    ticker TEXT NOT NULL,
    side TEXT NOT NULL,
    quantity REAL NOT NULL,
    price REAL NOT NULL,
    executed_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS portfolio_snapshots (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL DEFAULT 'default',
    total_value REAL NOT NULL,
    recorded_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS chat_messages (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL DEFAULT 'default',
    role TEXT NOT NULL,
    content TEXT NOT NULL,
    actions TEXT,
    created_at TEXT NOT NULL
);
