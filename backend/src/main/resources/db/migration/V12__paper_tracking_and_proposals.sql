-- SignalForge M5 Migration V12: Prospective Paper Tracking and Grounded AI Explanations

-- 1. Paper Portfolio Segments (Extension table for active tracking segment)
CREATE TABLE IF NOT EXISTS paper_portfolio_segments (
    id TEXT PRIMARY KEY,
    portfolio_id TEXT NOT NULL REFERENCES portfolios(id),
    strategy_id TEXT NOT NULL,
    strategy_version TEXT NOT NULL,
    universe_id TEXT NOT NULL REFERENCES universes(id),
    benchmark_listing_id TEXT NOT NULL REFERENCES listings(id),
    cost_policy_json TEXT NOT NULL,
    approval_mode TEXT NOT NULL CHECK(approval_mode IN ('MANUAL', 'AUTO_PAPER')),
    status TEXT NOT NULL CHECK(status IN ('ACTIVE', 'TERMINATED')),
    initial_equity TEXT NOT NULL,
    opening_observation_instant TEXT NOT NULL,
    adopted_dataset_id TEXT REFERENCES datasets(id),
    adopted_at TEXT,
    created_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_paper_segments_portfolio ON paper_portfolio_segments(portfolio_id);
CREATE INDEX IF NOT EXISTS idx_paper_segments_status ON paper_portfolio_segments(status);

-- 2. Paper Approval Mode History (Append-only audit)
CREATE TABLE IF NOT EXISTS paper_mode_history (
    id TEXT PRIMARY KEY,
    portfolio_id TEXT NOT NULL REFERENCES portfolios(id),
    from_mode TEXT NOT NULL CHECK(from_mode IN ('MANUAL', 'AUTO_PAPER')),
    to_mode TEXT NOT NULL CHECK(to_mode IN ('MANUAL', 'AUTO_PAPER')),
    transition_instant TEXT NOT NULL,
    trigger_type TEXT NOT NULL CHECK(trigger_type IN ('USER', 'SYSTEM_DOWNTIME_RECOVERY')),
    notes TEXT
);

CREATE INDEX IF NOT EXISTS idx_paper_mode_history_port ON paper_mode_history(portfolio_id);

-- 3. Paper Dataset Adoptions (Append-only history)
CREATE TABLE IF NOT EXISTS paper_dataset_adoptions (
    id TEXT PRIMARY KEY,
    portfolio_id TEXT NOT NULL REFERENCES portfolios(id),
    dataset_id TEXT NOT NULL REFERENCES datasets(id),
    dataset_checksum TEXT NOT NULL,
    coverage_start_session TEXT NOT NULL,
    coverage_end_session TEXT NOT NULL,
    validation_status TEXT NOT NULL CHECK(validation_status IN ('ADOPTED', 'REJECTED_INCOMPATIBLE', 'REJECTED_CONFLICT')),
    rejection_reason TEXT,
    adopted_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_paper_adoptions_port ON paper_dataset_adoptions(portfolio_id);

-- 4. Paper Proposals (Header table)
CREATE TABLE IF NOT EXISTS paper_proposals (
    id TEXT PRIMARY KEY,
    portfolio_id TEXT NOT NULL REFERENCES portfolios(id),
    cycle_id TEXT NOT NULL,
    strategy_id TEXT NOT NULL,
    strategy_version TEXT NOT NULL,
    dataset_id TEXT NOT NULL REFERENCES datasets(id),
    dataset_checksum TEXT NOT NULL,
    calendar_id TEXT NOT NULL,
    calendar_version TEXT NOT NULL,
    evaluation_session_date TEXT NOT NULL,
    input_cutoff_instant TEXT NOT NULL,
    evaluation_instant TEXT NOT NULL,
    scheduled_open_session_date TEXT NOT NULL,
    scheduled_open_instant TEXT NOT NULL,
    reason_code TEXT NOT NULL,
    portfolio_state_version INTEGER NOT NULL,
    status TEXT NOT NULL CHECK(status IN ('PROPOSED', 'ACCEPTED', 'REJECTED', 'SUPERSEDED', 'MISSED')),
    accepted_at TEXT,
    rejected_at TEXT,
    rejection_reason TEXT,
    superseding_proposal_id TEXT REFERENCES paper_proposals(id),
    created_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_paper_proposals_port_cycle ON paper_proposals(portfolio_id, cycle_id);
CREATE INDEX IF NOT EXISTS idx_paper_proposals_status ON paper_proposals(status);

-- 5. Paper Proposal Items (Normalized targets and ranking)
CREATE TABLE IF NOT EXISTS paper_proposal_items (
    id TEXT PRIMARY KEY,
    proposal_id TEXT NOT NULL REFERENCES paper_proposals(id),
    listing_id TEXT NOT NULL REFERENCES listings(id),
    rank INTEGER NOT NULL,
    target_weight TEXT NOT NULL,
    desired_units TEXT NOT NULL,
    score TEXT,
    reason_code TEXT NOT NULL,
    reason_description TEXT,
    raw_price_reference TEXT,
    UNIQUE(proposal_id, listing_id)
);

CREATE INDEX IF NOT EXISTS idx_paper_proposal_items_prop ON paper_proposal_items(proposal_id);

-- 6. Paper Proposal Observations (Input observation evidence)
CREATE TABLE IF NOT EXISTS paper_proposal_observations (
    id TEXT PRIMARY KEY,
    proposal_id TEXT NOT NULL REFERENCES paper_proposals(id),
    listing_id TEXT NOT NULL REFERENCES listings(id),
    observation_session_date TEXT NOT NULL,
    observation_type TEXT NOT NULL CHECK(observation_type IN ('CLOSE', 'SPLIT', 'DISTRIBUTION', 'SMA10')),
    observation_value TEXT NOT NULL,
    observed_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_paper_prop_obs_prop ON paper_proposal_observations(proposal_id);

-- 7. Paper Receivables (Durable dividend/distribution accounting)
CREATE TABLE IF NOT EXISTS paper_receivables (
    id TEXT PRIMARY KEY,
    portfolio_id TEXT NOT NULL REFERENCES portfolios(id),
    listing_id TEXT NOT NULL REFERENCES listings(id),
    action_id TEXT NOT NULL,
    action_type TEXT NOT NULL CHECK(action_type IN ('CASH_DISTRIBUTION')),
    record_instant TEXT NOT NULL,
    ex_date TEXT NOT NULL,
    payment_date TEXT NOT NULL,
    gross_amount TEXT NOT NULL,
    withholding_tax TEXT NOT NULL,
    net_amount TEXT NOT NULL,
    status TEXT NOT NULL CHECK(status IN ('PENDING', 'PAID', 'CANCELLED')),
    paid_operation_id TEXT REFERENCES operations(id),
    paid_at TEXT,
    created_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_paper_receivables_port ON paper_receivables(portfolio_id);
CREATE INDEX IF NOT EXISTS idx_paper_receivables_status ON paper_receivables(status);

-- 8. Paper Processed Corporate Actions (Cross-snapshot de-duplication)
CREATE TABLE IF NOT EXISTS paper_processed_corporate_actions (
    id TEXT PRIMARY KEY,
    portfolio_id TEXT NOT NULL REFERENCES portfolios(id),
    source_namespace TEXT NOT NULL,
    listing_id TEXT NOT NULL REFERENCES listings(id),
    action_id TEXT NOT NULL,
    action_type TEXT NOT NULL CHECK(action_type IN ('SPLIT', 'CASH_DISTRIBUTION')),
    terms_hash TEXT NOT NULL,
    effective_date TEXT NOT NULL,
    availability_instant TEXT NOT NULL,
    processing_instant TEXT NOT NULL,
    dataset_id TEXT NOT NULL REFERENCES datasets(id),
    dataset_checksum TEXT NOT NULL,
    status TEXT NOT NULL CHECK(status IN ('PROCESSED', 'CONFLICT')),
    linked_operation_id TEXT REFERENCES operations(id),
    linked_receivable_id TEXT REFERENCES paper_receivables(id),
    UNIQUE(portfolio_id, source_namespace, listing_id, action_type, action_id, terms_hash)
);

CREATE INDEX IF NOT EXISTS idx_paper_proc_actions_port ON paper_processed_corporate_actions(portfolio_id);

-- 9. Paper Execution Intents (Durable intent scheduled for future open)
CREATE TABLE IF NOT EXISTS paper_execution_intents (
    id TEXT PRIMARY KEY,
    portfolio_id TEXT NOT NULL REFERENCES portfolios(id),
    proposal_id TEXT REFERENCES paper_proposals(id),
    reinvestment_receivable_id TEXT REFERENCES paper_receivables(id),
    order_type TEXT NOT NULL CHECK(order_type IN ('INITIAL_ALLOCATION', 'REBALANCE', 'REINVESTMENT')),
    scheduled_session_date TEXT NOT NULL,
    scheduled_open_instant TEXT NOT NULL,
    approval_mode TEXT NOT NULL CHECK(approval_mode IN ('MANUAL', 'AUTO_PAPER')),
    status TEXT NOT NULL CHECK(status IN ('PENDING', 'WAITING_FOR_OBSERVATION', 'EXECUTED', 'MISSED', 'CANCELLED', 'FAILED')),
    created_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_paper_intents_port ON paper_execution_intents(portfolio_id);
CREATE INDEX IF NOT EXISTS idx_paper_intents_status ON paper_execution_intents(status);

-- 10. Paper Intent Transitions (Append-only audit)
CREATE TABLE IF NOT EXISTS paper_intent_transitions (
    id TEXT PRIMARY KEY,
    intent_id TEXT NOT NULL REFERENCES paper_execution_intents(id),
    from_status TEXT NOT NULL,
    to_status TEXT NOT NULL,
    trigger_type TEXT NOT NULL,
    transition_instant TEXT NOT NULL,
    notes TEXT
);

CREATE INDEX IF NOT EXISTS idx_paper_intent_transitions ON paper_intent_transitions(intent_id);

-- 11. Paper Execution Results (Paper execution provenance referencing core operation/execution)
CREATE TABLE IF NOT EXISTS paper_execution_results (
    id TEXT PRIMARY KEY,
    intent_id TEXT NOT NULL REFERENCES paper_execution_intents(id),
    proposal_id TEXT REFERENCES paper_proposals(id),
    reinvestment_receivable_id TEXT REFERENCES paper_receivables(id),
    operation_id TEXT NOT NULL REFERENCES operations(id),
    execution_id TEXT NOT NULL REFERENCES executions(id),
    listing_id TEXT NOT NULL REFERENCES listings(id),
    side TEXT NOT NULL CHECK(side IN ('BUY', 'SELL')),
    requested_quantity TEXT NOT NULL,
    executed_quantity TEXT NOT NULL,
    shortfall_reason TEXT,
    raw_open_price TEXT NOT NULL,
    fill_price TEXT NOT NULL,
    commission TEXT NOT NULL,
    spread_slippage_cost TEXT NOT NULL,
    cost_basis TEXT NOT NULL,
    realized_gain TEXT NOT NULL,
    dataset_id TEXT NOT NULL REFERENCES datasets(id),
    dataset_checksum TEXT NOT NULL,
    market_effective_instant TEXT NOT NULL,
    observed_instant TEXT NOT NULL,
    booked_instant TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_paper_exec_res_intent ON paper_execution_results(intent_id);
CREATE INDEX IF NOT EXISTS idx_paper_exec_res_op ON paper_execution_results(operation_id);

-- 12. Paper Valuations (Prospective valuations only)
CREATE TABLE IF NOT EXISTS paper_valuations (
    id TEXT PRIMARY KEY,
    portfolio_id TEXT NOT NULL REFERENCES portfolios(id),
    session_date TEXT NOT NULL,
    observation_kind TEXT NOT NULL CHECK(observation_kind IN ('OPENING', 'SESSION_CLOSE', 'CORPORATE_ACTION')),
    observation_instant TEXT NOT NULL,
    cash_balance TEXT NOT NULL,
    positions_market_value TEXT NOT NULL,
    receivables_value TEXT NOT NULL,
    total_equity TEXT,
    cumulative_return TEXT,
    high_water_mark TEXT,
    drawdown TEXT,
    data_readiness_status TEXT NOT NULL CHECK(data_readiness_status IN ('COMPLETE', 'PARTIAL_STALE', 'INSUFFICIENT_HISTORY')),
    is_complete INTEGER NOT NULL CHECK(is_complete IN (0, 1)),
    last_supported_observation_instant TEXT,
    missing_requirements_detail TEXT,
    adopted_dataset_id TEXT REFERENCES datasets(id),
    adopted_dataset_checksum TEXT NOT NULL,
    UNIQUE(portfolio_id, session_date, observation_kind)
);

CREATE INDEX IF NOT EXISTS idx_paper_valuations_port ON paper_valuations(portfolio_id);

-- 13. Immutability Triggers
CREATE TRIGGER IF NOT EXISTS paper_proposals_immutable_after_terminal
BEFORE UPDATE ON paper_proposals
FOR EACH ROW
WHEN OLD.status IN ('REJECTED', 'SUPERSEDED', 'MISSED')
BEGIN
    SELECT RAISE(ABORT, 'Cannot update terminal paper proposal');
END;

CREATE TRIGGER IF NOT EXISTS paper_proposal_items_no_update
BEFORE UPDATE ON paper_proposal_items
FOR EACH ROW
BEGIN
    SELECT RAISE(ABORT, 'Proposal items are immutable once created');
END;

CREATE TRIGGER IF NOT EXISTS paper_proposal_items_no_delete
BEFORE DELETE ON paper_proposal_items
FOR EACH ROW
BEGIN
    SELECT RAISE(ABORT, 'Proposal items cannot be deleted');
END;

CREATE TRIGGER IF NOT EXISTS paper_proposal_observations_no_update
BEFORE UPDATE ON paper_proposal_observations
FOR EACH ROW
BEGIN
    SELECT RAISE(ABORT, 'Proposal observations are immutable once created');
END;

CREATE TRIGGER IF NOT EXISTS paper_proposal_observations_no_delete
BEFORE DELETE ON paper_proposal_observations
FOR EACH ROW
BEGIN
    SELECT RAISE(ABORT, 'Proposal observations cannot be deleted');
END;

CREATE TRIGGER IF NOT EXISTS paper_execution_results_no_update
BEFORE UPDATE ON paper_execution_results
FOR EACH ROW
BEGIN
    SELECT RAISE(ABORT, 'Paper execution results are immutable once booked');
END;

CREATE TRIGGER IF NOT EXISTS paper_execution_results_no_delete
BEFORE DELETE ON paper_execution_results
FOR EACH ROW
BEGIN
    SELECT RAISE(ABORT, 'Paper execution results cannot be deleted');
END;

CREATE TRIGGER IF NOT EXISTS paper_segments_single_active
BEFORE INSERT ON paper_portfolio_segments
FOR EACH ROW
WHEN NEW.status = 'ACTIVE' AND (SELECT COUNT(*) FROM paper_portfolio_segments WHERE portfolio_id = NEW.portfolio_id AND status = 'ACTIVE') > 0
BEGIN
    SELECT RAISE(ABORT, 'Only one active paper portfolio segment is permitted per portfolio');
END;
