-- M5 corrective schema, applied after the immutable V12 checksum.

ALTER TABLE paper_proposals ADD COLUMN reinvestment_receivable_id TEXT REFERENCES paper_receivables(id);
ALTER TABLE paper_proposal_items RENAME COLUMN desired_units TO cutoff_estimated_units;

ALTER TABLE paper_receivables ADD COLUMN source_namespace TEXT;
ALTER TABLE paper_receivables ADD COLUMN payment_instant TEXT;
ALTER TABLE paper_receivables ADD COLUMN availability_instant TEXT;
ALTER TABLE paper_receivables ADD COLUMN dataset_id TEXT REFERENCES datasets(id);
ALTER TABLE paper_receivables ADD COLUMN dataset_checksum TEXT;
ALTER TABLE paper_receivables ADD COLUMN terms_hash TEXT;
CREATE UNIQUE INDEX idx_paper_receivables_source_identity
    ON paper_receivables(portfolio_id, source_namespace, listing_id, action_type, action_id);

DROP TRIGGER paper_execution_results_no_update;
DROP TRIGGER paper_execution_results_no_delete;
DROP INDEX idx_paper_exec_res_intent;
DROP INDEX idx_paper_exec_res_op;
CREATE TABLE paper_execution_results_v13 (
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
    booked_instant TEXT NOT NULL,
    UNIQUE(intent_id, listing_id, side)
);
INSERT INTO paper_execution_results_v13 (
    id, intent_id, proposal_id, reinvestment_receivable_id, operation_id, execution_id, listing_id, side,
    requested_quantity, executed_quantity, shortfall_reason, raw_open_price, fill_price, commission,
    spread_slippage_cost, cost_basis, realized_gain, dataset_id, dataset_checksum, market_effective_instant,
    observed_instant, booked_instant
)
SELECT id, intent_id, proposal_id, reinvestment_receivable_id, operation_id, execution_id, listing_id, side,
       requested_quantity, executed_quantity, shortfall_reason, raw_open_price, fill_price, commission,
       spread_slippage_cost, cost_basis, realized_gain, dataset_id, dataset_checksum, market_effective_instant,
       observed_instant, booked_instant
FROM paper_execution_results;
DROP TABLE paper_execution_results;
ALTER TABLE paper_execution_results_v13 RENAME TO paper_execution_results;
CREATE INDEX idx_paper_exec_res_intent ON paper_execution_results(intent_id);
CREATE INDEX idx_paper_exec_res_op ON paper_execution_results(operation_id);
CREATE TRIGGER paper_execution_results_no_update BEFORE UPDATE ON paper_execution_results
FOR EACH ROW BEGIN SELECT RAISE(ABORT, 'Execution results cannot be updated'); END;
CREATE TRIGGER paper_execution_results_no_delete BEFORE DELETE ON paper_execution_results
FOR EACH ROW BEGIN SELECT RAISE(ABORT, 'Execution results cannot be deleted'); END;

DROP TRIGGER paper_valuations_no_update;
DROP TRIGGER paper_valuations_no_delete;
DROP INDEX idx_paper_valuations_port;
CREATE TABLE paper_valuations_v13 (
    id TEXT PRIMARY KEY,
    portfolio_id TEXT NOT NULL REFERENCES portfolios(id),
    session_date TEXT NOT NULL,
    observation_kind TEXT NOT NULL CHECK(observation_kind IN ('OPENING', 'SESSION_CLOSE', 'CORPORATE_ACTION')),
    observation_instant TEXT NOT NULL,
    portfolio_state_revision INTEGER NOT NULL,
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
    UNIQUE(portfolio_id, session_date, observation_kind, observation_instant, portfolio_state_revision)
);
INSERT INTO paper_valuations_v13 (
    id, portfolio_id, session_date, observation_kind, observation_instant, portfolio_state_revision,
    cash_balance, positions_market_value, receivables_value, total_equity, cumulative_return,
    high_water_mark, drawdown, data_readiness_status, is_complete,
    last_supported_observation_instant, missing_requirements_detail, adopted_dataset_id, adopted_dataset_checksum
)
SELECT id, portfolio_id, session_date, observation_kind, observation_instant, 0,
       cash_balance, positions_market_value, receivables_value, total_equity, cumulative_return,
       high_water_mark, drawdown, data_readiness_status, is_complete,
       last_supported_observation_instant, missing_requirements_detail, adopted_dataset_id, adopted_dataset_checksum
FROM paper_valuations;
DROP TABLE paper_valuations;
ALTER TABLE paper_valuations_v13 RENAME TO paper_valuations;
CREATE INDEX idx_paper_valuations_port ON paper_valuations(portfolio_id);
CREATE TRIGGER paper_valuations_no_update BEFORE UPDATE ON paper_valuations
FOR EACH ROW BEGIN SELECT RAISE(ABORT, 'Valuations are immutable'); END;
CREATE TRIGGER paper_valuations_no_delete BEFORE DELETE ON paper_valuations
FOR EACH ROW BEGIN SELECT RAISE(ABORT, 'Valuations cannot be deleted'); END;

CREATE TRIGGER paper_intents_legal_status_transition
BEFORE UPDATE OF status ON paper_execution_intents
FOR EACH ROW
WHEN OLD.status != NEW.status AND NOT (
    (OLD.status = 'PENDING' AND NEW.status IN ('WAITING_FOR_OBSERVATION', 'EXECUTED', 'FAILED', 'MISSED', 'CANCELLED')) OR
    (OLD.status = 'WAITING_FOR_OBSERVATION' AND NEW.status IN ('EXECUTED', 'FAILED', 'MISSED', 'CANCELLED'))
)
BEGIN
    SELECT RAISE(ABORT, 'Invalid paper execution intent status transition');
END;
