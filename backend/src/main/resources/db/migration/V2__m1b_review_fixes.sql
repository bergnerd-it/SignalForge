-- SignalForge M1b review corrections. V1 remains immutable for candidate compatibility.

CREATE TABLE IF NOT EXISTS chat_requests (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL,
    idempotency_key TEXT NOT NULL,
    payload_hash TEXT NOT NULL,
    user_message TEXT NOT NULL,
    assistant_message TEXT,
    status TEXT NOT NULL CHECK(status IN ('INFERENCE_PENDING', 'PLAN_READY', 'EXECUTING', 'COMPLETED', 'FAILED_VALIDATION')),
    response_json TEXT,
    lease_owner TEXT,
    lease_until TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    UNIQUE(user_id, idempotency_key)
);

ALTER TABLE chat_actions ADD COLUMN chat_request_id TEXT REFERENCES chat_requests(id);
ALTER TABLE chat_actions ADD COLUMN action_index INTEGER;
ALTER TABLE chat_actions ADD COLUMN action_key TEXT;
ALTER TABLE chat_actions ADD COLUMN result_json TEXT;
ALTER TABLE chat_actions ADD COLUMN error_code TEXT;
ALTER TABLE chat_actions ADD COLUMN updated_at TEXT;

CREATE UNIQUE INDEX IF NOT EXISTS uq_chat_request_action
    ON chat_actions(chat_request_id, action_index)
    WHERE chat_request_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_chat_requests_user_key
    ON chat_requests(user_id, idempotency_key);

-- Quarantine identities produced by the reviewed candidate's removed fallback.
-- The provenance plus generated identifier shape avoids invalidating a genuine
-- instrument merely because it happens to use the copied ISIN.
UPDATE listings
SET identity_status = 'UNVERIFIED_CANDIDATE'
WHERE id LIKE 'listing-research-%'
  AND instrument_id LIKE 'inst-research-%'
  AND instrument_id IN (
      SELECT id FROM instruments
      WHERE provenance = 'RESEARCH_PROVIDER'
        AND isin = 'IE00B4L5Y983'
  );

UPDATE instruments
SET provenance = 'M1B_CANDIDATE_UNVERIFIED'
WHERE id LIKE 'inst-research-%'
  AND provenance = 'RESEARCH_PROVIDER'
  AND isin = 'IE00B4L5Y983';

CREATE TRIGGER IF NOT EXISTS validate_portfolio_insert
BEFORE INSERT ON portfolios
WHEN NEW.base_currency NOT IN ('EUR', 'USD')
  OR typeof(NEW.initial_cash) <> 'text'
  OR NEW.initial_cash = ''
  OR NEW.initial_cash GLOB '*[^0-9.]*'
  OR NEW.initial_cash IN ('', '.')
  OR length(replace(NEW.initial_cash, '.', '')) > 18
  OR (length(NEW.initial_cash) - length(replace(NEW.initial_cash, '.', ''))) > 1
  OR (instr(NEW.initial_cash, '.') > 0 AND length(NEW.initial_cash) - instr(NEW.initial_cash, '.') <> 2)
BEGIN
    SELECT RAISE(ABORT, 'Invalid portfolio decimal or currency');
END;

CREATE TRIGGER IF NOT EXISTS validate_portfolio_state_insert
BEFORE INSERT ON portfolio_state
WHEN typeof(NEW.cash_amount) <> 'text'
  OR NEW.cash_amount = ''
  OR NEW.cash_amount GLOB '*[^0-9.-]*'
  OR NEW.cash_amount LIKE '-%'
  OR NEW.cash_amount IN ('', '.')
  OR length(replace(NEW.cash_amount, '.', '')) > 18
  OR (length(NEW.cash_amount) - length(replace(NEW.cash_amount, '.', ''))) > 1
  OR (instr(NEW.cash_amount, '.') > 0 AND length(NEW.cash_amount) - instr(NEW.cash_amount, '.') <> 2)
BEGIN
    SELECT RAISE(ABORT, 'Invalid cash decimal');
END;

CREATE TRIGGER IF NOT EXISTS validate_portfolio_state_update
BEFORE UPDATE ON portfolio_state
WHEN typeof(NEW.cash_amount) <> 'text'
  OR NEW.cash_amount = ''
  OR NEW.cash_amount GLOB '*[^0-9.-]*'
  OR NEW.cash_amount LIKE '-%'
  OR NEW.cash_amount IN ('', '.')
  OR length(replace(NEW.cash_amount, '.', '')) > 18
  OR (length(NEW.cash_amount) - length(replace(NEW.cash_amount, '.', ''))) > 1
  OR (instr(NEW.cash_amount, '.') > 0 AND length(NEW.cash_amount) - instr(NEW.cash_amount, '.') <> 2)
BEGIN
    SELECT RAISE(ABORT, 'Invalid cash decimal');
END;

CREATE TRIGGER IF NOT EXISTS validate_position_insert
BEFORE INSERT ON positions
WHEN typeof(NEW.quantity) <> 'text'
  OR NEW.quantity = ''
  OR NEW.quantity GLOB '*[^0-9.]*'
  OR NEW.quantity IN ('', '.')
  OR length(replace(NEW.quantity, '.', '')) > 21
  OR (length(NEW.quantity) - length(replace(NEW.quantity, '.', ''))) > 1
  OR (instr(NEW.quantity, '.') > 0 AND length(NEW.quantity) - instr(NEW.quantity, '.') > 8)
  OR typeof(NEW.total_acquisition_cost) <> 'text'
  OR NEW.total_acquisition_cost GLOB '*[^0-9.]*'
  OR NEW.total_acquisition_cost IN ('', '.')
  OR length(replace(NEW.total_acquisition_cost, '.', '')) > 18
  OR (instr(NEW.total_acquisition_cost, '.') > 0 AND length(NEW.total_acquisition_cost) - instr(NEW.total_acquisition_cost, '.') <> 2)
BEGIN
    SELECT RAISE(ABORT, 'Invalid position decimal');
END;

CREATE TRIGGER IF NOT EXISTS validate_position_update
BEFORE UPDATE ON positions
WHEN typeof(NEW.quantity) <> 'text'
  OR NEW.quantity = ''
  OR NEW.quantity GLOB '*[^0-9.]*'
  OR NEW.quantity IN ('', '.')
  OR length(replace(NEW.quantity, '.', '')) > 21
  OR (length(NEW.quantity) - length(replace(NEW.quantity, '.', ''))) > 1
  OR (instr(NEW.quantity, '.') > 0 AND length(NEW.quantity) - instr(NEW.quantity, '.') > 8)
  OR typeof(NEW.total_acquisition_cost) <> 'text'
  OR NEW.total_acquisition_cost GLOB '*[^0-9.]*'
  OR NEW.total_acquisition_cost IN ('', '.')
  OR length(replace(NEW.total_acquisition_cost, '.', '')) > 18
  OR (instr(NEW.total_acquisition_cost, '.') > 0 AND length(NEW.total_acquisition_cost) - instr(NEW.total_acquisition_cost, '.') <> 2)
BEGIN
    SELECT RAISE(ABORT, 'Invalid position decimal');
END;

CREATE TRIGGER IF NOT EXISTS validate_listing_identity_insert
BEFORE INSERT ON listings
WHEN NEW.identity_status NOT IN ('RESOLVED', 'UNRESOLVED_LEGACY', 'UNVERIFIED_CANDIDATE')
BEGIN
    SELECT RAISE(ABORT, 'Invalid listing identity status');
END;

CREATE TRIGGER IF NOT EXISTS validate_operation_kind_insert
BEFORE INSERT ON operations
WHEN NEW.kind NOT IN ('INITIAL_FUNDING', 'MIGRATION_OPENING', 'TRADE', 'SPLIT', 'CORRECTION')
BEGIN
    SELECT RAISE(ABORT, 'Invalid operation kind');
END;

CREATE TRIGGER IF NOT EXISTS validate_ledger_type_insert
BEFORE INSERT ON ledger_entries
WHEN NEW.entry_type NOT IN ('INITIAL_FUNDING', 'MIGRATION_OPENING', 'TRADE', 'SPLIT', 'CORRECTION')
BEGIN
    SELECT RAISE(ABORT, 'Invalid ledger entry type');
END;

CREATE TRIGGER IF NOT EXISTS validate_execution_insert
BEFORE INSERT ON executions
WHEN NEW.side NOT IN ('buy', 'sell')
  OR typeof(NEW.units) <> 'text'
  OR NEW.units = ''
  OR NEW.units GLOB '*[^0-9.]*'
  OR NEW.units IN ('', '.')
  OR length(replace(NEW.units, '.', '')) > 21
  OR (instr(NEW.units, '.') > 0 AND length(NEW.units) - instr(NEW.units, '.') > 8)
  OR typeof(NEW.fill_price) <> 'text'
  OR NEW.fill_price GLOB '*[^0-9.]*'
  OR NEW.fill_price IN ('', '.')
  OR length(replace(NEW.fill_price, '.', '')) > 21
  OR (instr(NEW.fill_price, '.') > 0 AND length(NEW.fill_price) - instr(NEW.fill_price, '.') > 8)
BEGIN
    SELECT RAISE(ABORT, 'Invalid execution enum or decimal');
END;

CREATE TRIGGER IF NOT EXISTS prevent_chat_action_plan_change
BEFORE UPDATE OF chat_request_id, action_index, action_key, action_type, action_payload ON chat_actions
WHEN OLD.chat_request_id IS NOT NULL
BEGIN
    SELECT RAISE(ABORT, 'Persisted chat action plans are immutable');
END;
