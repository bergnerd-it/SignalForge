package com.bergnerd.signalforge.app.operation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.bergnerd.signalforge.app.accounting.AccountingCore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Durable operation service managing atomic financial transactions and idempotency.
 * Coordinates writers through TransactionTemplate execution and commit/rollback.
 * Acquires SQLite write lock via initial operation insertion before financial read-modify-write.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OperationService {

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // Reentrant fair lock coordinating SQLite writers through full commit/rollback completion
    private final ReentrantLock writerLock = new ReentrantLock(true);
    private volatile Consumer<String> failureInjector = ignored -> {};

    public record PortfolioCreationResult(
            String portfolioId,
            String ownerId,
            String name,
            String mode,
            String baseCurrency,
            String initialCash,
            String createdAt,
            boolean isRetry
    ) {}

    public record TradeExecutionResult(
            String operationId,
            String executionId,
            String portfolioId,
            String listingId,
            String ticker,
            String side,
            String units,
            String fillPrice,
            String commission,
            String cashDelta,
            String basisDelta,
            String realizedGain,
            String remainingCash,
            String remainingUnits,
            String executedAt,
            boolean isRetry
    ) {
        public TradeExecutionResult asRetry() {
            return new TradeExecutionResult(
                    operationId, executionId, portfolioId, listingId, ticker, side, units,
                    fillPrice, commission, cashDelta, basisDelta, realizedGain,
                    remainingCash, remainingUnits, executedAt, true
            );
        }
    }

    public record PortfolioView(
            String id,
            String ownerId,
            String name,
            String mode,
            String baseCurrency,
            String cashBalance,
            int revision,
            List<PositionView> positions
    ) {}

    public record PositionView(
            String listingId,
            String ticker,
            String quantity,
            String totalAcquisitionCost,
            String averageCost,
            String updatedAt
    ) {}

    /**
     * Creates a new portfolio with owner-scoped idempotency.
     */
    public PortfolioCreationResult createPortfolio(
            String ownerId,
            String name,
            String mode,
            String baseCurrency,
            BigDecimal initialCash,
            String idempotencyKey
    ) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IdempotencyExceptions.MissingIdempotencyKeyException("Idempotency key is required for portfolio creation");
        }

        String upperMode = Objects.requireNonNull(mode, "mode must not be null").trim().toUpperCase();
        String upperCurrency = Objects.requireNonNull(baseCurrency, "baseCurrency must not be null").trim().toUpperCase();
        if (!"PAPER".equals(upperMode) && !"LEGACY_DEMO".equals(upperMode)) {
            throw new IllegalArgumentException("Unsupported portfolio mode: " + mode);
        }
        if ("PAPER".equals(upperMode) && !"EUR".equals(upperCurrency)) {
            throw new IllegalArgumentException("PAPER portfolios must have base currency EUR: " + baseCurrency);
        }
        BigDecimal cashNorm = AccountingCore.normalizeStartingCash(initialCash);
        String canonicalHash = CanonicalIntentHasher.hashPortfolioCreation(
                ownerId, name, upperMode, upperCurrency, cashNorm
        );

        try {
            Map<String, Object> existing = jdbcTemplate.queryForMap(
                    "SELECT portfolio_id, payload_hash FROM portfolio_creation_requests WHERE owner_id = ? AND idempotency_key = ?",
                    ownerId, idempotencyKey
            );
            String existingPortfolioId = (String) existing.get("portfolio_id");
            String recordedHash = (String) existing.get("payload_hash");

            if (!canonicalHash.equals(recordedHash)) {
                throw new IdempotencyExceptions.IdempotencyConflictException(
                        "Conflicting reuse of portfolio creation idempotency key: " + idempotencyKey
                );
            }

            Map<String, Object> p = jdbcTemplate.queryForMap(
                    "SELECT id, owner_id, name, mode, base_currency, initial_cash, created_at FROM portfolios WHERE id = ?",
                    existingPortfolioId
            );
            return new PortfolioCreationResult(
                    (String) p.get("id"),
                    (String) p.get("owner_id"),
                    (String) p.get("name"),
                    (String) p.get("mode"),
                    (String) p.get("base_currency"),
                    (String) p.get("initial_cash"),
                    (String) p.get("created_at"),
                    true
            );
        } catch (EmptyResultDataAccessException ignored) {
            // Not yet created
        }

        rejectOuterTransaction();
        writerLock.lock();
        try {
            return executeWithBusyRetry(() -> transactionTemplate.execute(status -> {
                // Recheck inside transaction
                try {
                    Map<String, Object> existing = jdbcTemplate.queryForMap(
                            "SELECT portfolio_id, payload_hash FROM portfolio_creation_requests WHERE owner_id = ? AND idempotency_key = ?",
                            ownerId, idempotencyKey
                    );
                    String recordedHash = (String) existing.get("payload_hash");
                    if (!canonicalHash.equals(recordedHash)) {
                        throw new IdempotencyExceptions.IdempotencyConflictException(
                                "Conflicting reuse of portfolio creation idempotency key: " + idempotencyKey
                        );
                    }
                    String existingPortfolioId = (String) existing.get("portfolio_id");
                    Map<String, Object> p = jdbcTemplate.queryForMap(
                            "SELECT id, owner_id, name, mode, base_currency, initial_cash, created_at FROM portfolios WHERE id = ?",
                            existingPortfolioId
                    );
                    return new PortfolioCreationResult(
                            (String) p.get("id"),
                            (String) p.get("owner_id"),
                            (String) p.get("name"),
                            (String) p.get("mode"),
                            (String) p.get("base_currency"),
                            (String) p.get("initial_cash"),
                            (String) p.get("created_at"),
                            true
                    );
                } catch (EmptyResultDataAccessException ignored) {
                }

                String portfolioId = "portfolio-" + UUID.randomUUID();
                String now = Instant.now().toString();
                jdbcTemplate.update(
                        "INSERT INTO portfolios (id, owner_id, name, mode, base_currency, initial_cash, created_at, paper_started_at, rounding_policy_version) " +
                                "VALUES (?, ?, ?, ?, ?, ?, ?, NULL, ?)",
                        portfolioId, ownerId, name != null ? name : "Research Portfolio", upperMode,
                        upperCurrency, cashNorm.toPlainString(), now, AccountingCore.POLICY_VERSION
                );
                failureInjector.accept("portfolio");

                jdbcTemplate.update(
                        "INSERT INTO portfolio_creation_requests (owner_id, idempotency_key, portfolio_id, payload_hash, created_at) VALUES (?, ?, ?, ?, ?)",
                        ownerId, idempotencyKey, portfolioId, canonicalHash, now
                );

                // Initial funding operation
                String opId = "op-" + UUID.randomUUID();
                jdbcTemplate.update(
                        "INSERT INTO operations (id, portfolio_id, kind, idempotency_key, payload_hash, result_json, business_at, created_at) " +
                                "VALUES (?, ?, 'INITIAL_FUNDING', 'funding-' || ?, ?, '{\"funded\":true}', ?, ?)",
                        opId, portfolioId, idempotencyKey, canonicalHash, now, now
                );
                failureInjector.accept("creation-ledger");

                // Initial funding ledger entry
                jdbcTemplate.update(
                        "INSERT INTO ledger_entries (id, portfolio_id, operation_id, sequence, entry_type, listing_id, signed_quantity_delta, signed_cash_delta, acquisition_cost_delta, currency, business_at, recorded_at, legacy_record_id) " +
                                "VALUES (?, ?, ?, 1, 'INITIAL_FUNDING', NULL, '0', ?, '0.00', ?, ?, ?, NULL)",
                        "ledger-" + UUID.randomUUID(), portfolioId, opId, cashNorm.toPlainString(), baseCurrency.trim().toUpperCase(), now, now
                );
                failureInjector.accept("creation-state");

                // Portfolio state projection
                jdbcTemplate.update(
                        "INSERT INTO portfolio_state (portfolio_id, cash_amount, revision) VALUES (?, ?, 1)",
                        portfolioId, cashNorm.toPlainString()
                );

                return new PortfolioCreationResult(
                        portfolioId, ownerId, name, upperMode, upperCurrency,
                        cashNorm.toPlainString(), now, false
                );
            }));
        } finally {
            writerLock.unlock();
        }
    }

    public Optional<TradeExecutionResult> findCompletedTrade(
            String portfolioId,
            String ticker,
            String side,
            BigDecimal quantity,
            String idempotencyKey
    ) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IdempotencyExceptions.MissingIdempotencyKeyException("Idempotency key is required for trade execution");
        }
        BigDecimal normalizedQuantity = AccountingCore.normalizeQuantity(quantity);
        String canonicalHash = CanonicalIntentHasher.hashTrade(
                portfolioId, ticker.trim().toUpperCase(), side.trim().toLowerCase(), normalizedQuantity
        );
        try {
            Map<String, Object> row = jdbcTemplate.queryForMap(
                    "SELECT payload_hash, result_json FROM operations WHERE portfolio_id = ? AND kind = 'TRADE' AND idempotency_key = ?",
                    portfolioId, idempotencyKey
            );
            if (!canonicalHash.equals(row.get("payload_hash"))) {
                throw new IdempotencyExceptions.IdempotencyConflictException(
                        "Conflicting reuse of trade idempotency key: " + idempotencyKey
                );
            }
            return Optional.of(deserializeTradeResult((String) row.get("result_json")).asRetry());
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    /**
     * Executes a trade transaction with portfolio-scoped idempotency.
     */
    public TradeExecutionResult executeTrade(
            String portfolioId,
            String ticker,
            String side,
            BigDecimal quantity,
            BigDecimal fillPrice,
            BigDecimal commission,
            String idempotencyKey,
            String executionModel
    ) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IdempotencyExceptions.MissingIdempotencyKeyException("Idempotency key is required for trade execution");
        }

        BigDecimal normalizedQuantity = AccountingCore.normalizeQuantity(quantity);
        BigDecimal normalizedFillPrice = AccountingCore.normalizePrice(fillPrice);
        BigDecimal normalizedCommission = AccountingCore.normalizeCash(commission, "commission");
        String symbol = ticker.trim().toUpperCase();
        String tradeSide = side.trim().toLowerCase();
        String canonicalHash = CanonicalIntentHasher.hashTrade(portfolioId, symbol, tradeSide, normalizedQuantity);

        // 1. Preliminary check: lookup existing operation
        try {
            Map<String, Object> opRow = jdbcTemplate.queryForMap(
                    "SELECT payload_hash, result_json FROM operations WHERE portfolio_id = ? AND kind = 'TRADE' AND idempotency_key = ?",
                    portfolioId, idempotencyKey
            );
            String recordedHash = (String) opRow.get("payload_hash");
            if (!canonicalHash.equals(recordedHash)) {
                throw new IdempotencyExceptions.IdempotencyConflictException(
                        "Conflicting reuse of trade idempotency key: " + idempotencyKey
                );
            }
            String resultJson = (String) opRow.get("result_json");
            try {
                return deserializeTradeResult(resultJson).asRetry();
            } catch (IllegalStateException e) {
                throw e;
            }
        } catch (EmptyResultDataAccessException ignored) {
            // Not previously executed
        }

        rejectOuterTransaction();
        writerLock.lock();
        try {
            return executeWithBusyRetry(() -> transactionTemplate.execute(status -> {
                // Recheck inside transaction
                try {
                    Map<String, Object> opRow = jdbcTemplate.queryForMap(
                            "SELECT payload_hash, result_json FROM operations WHERE portfolio_id = ? AND kind = 'TRADE' AND idempotency_key = ?",
                            portfolioId, idempotencyKey
                    );
                    String recordedHash = (String) opRow.get("payload_hash");
                    if (!canonicalHash.equals(recordedHash)) {
                        throw new IdempotencyExceptions.IdempotencyConflictException(
                                "Conflicting reuse of trade idempotency key: " + idempotencyKey
                        );
                    }
                    String resultJson = (String) opRow.get("result_json");
                    try {
                        return objectMapper.readValue(resultJson, TradeExecutionResult.class).asRetry();
                    } catch (JsonProcessingException e) {
                        throw new IllegalStateException("Failed to deserialize cached trade result: " + e.getMessage(), e);
                    }
                } catch (EmptyResultDataAccessException ignored) {
                }

                // Check portfolio exists and determine mode
                Map<String, Object> portRow = jdbcTemplate.queryForMap(
                        "SELECT mode, base_currency FROM portfolios WHERE id = ?",
                        portfolioId
                );
                String mode = (String) portRow.get("mode");
                String currency = (String) portRow.get("base_currency");

                // Resolve listing
                String listingId;
                if ("LEGACY_DEMO".equals(mode)) {
                    listingId = resolveOrCreateLegacyListing(symbol, currency);
                } else {
                    // PAPER research mode: Unresolved legacy listings are forbidden!
                    listingId = resolveResearchListing(symbol, currency);
                }

                String now = Instant.now().toString();
                String opId = "op-" + UUID.randomUUID();

                // 2. Insert operation row FIRST to acquire DB write lock & enforce uniqueness
                try {
                    jdbcTemplate.update(
                            "INSERT INTO operations (id, portfolio_id, kind, idempotency_key, payload_hash, result_json, business_at, created_at) " +
                                    "VALUES (?, ?, 'TRADE', ?, ?, '', ?, ?)",
                            opId, portfolioId, idempotencyKey, canonicalHash, now, now
                    );
                } catch (DuplicateKeyException e) {
                    throw new IdempotencyExceptions.IdempotencyConflictException(
                            "Concurrent trade with same idempotency key: " + idempotencyKey
                    );
                }

                // 3. Load cash balance and revision
                Map<String, Object> stateRow = jdbcTemplate.queryForMap(
                        "SELECT cash_amount, revision FROM portfolio_state WHERE portfolio_id = ?",
                        portfolioId
                );
                BigDecimal currentCash = new BigDecimal((String) stateRow.get("cash_amount"));
                int currentRevision = ((Number) stateRow.get("revision")).intValue();

                // 4. Load current position
                BigDecimal currentQty = BigDecimal.ZERO;
                BigDecimal currentBasis = BigDecimal.ZERO;
                boolean positionExists = false;
                try {
                    Map<String, Object> posRow = jdbcTemplate.queryForMap(
                            "SELECT quantity, total_acquisition_cost FROM positions WHERE portfolio_id = ? AND listing_id = ?",
                            portfolioId, listingId
                    );
                    currentQty = new BigDecimal((String) posRow.get("quantity"));
                    currentBasis = new BigDecimal((String) posRow.get("total_acquisition_cost"));
                    positionExists = true;
                } catch (EmptyResultDataAccessException ignored) {
                }

                AccountingCore.AccountingState currentState = new AccountingCore.AccountingState(
                        currentCash, currentQty, currentBasis
                );

                // 5. Authoritative accounting execution
                AccountingCore.AccountingDelta delta;
                if ("buy".equals(tradeSide)) {
                    delta = AccountingCore.buy(currentState, normalizedQuantity, normalizedFillPrice, normalizedCommission);
                } else if ("sell".equals(tradeSide)) {
                    delta = AccountingCore.sell(currentState, normalizedQuantity, normalizedFillPrice, normalizedCommission);
                } else {
                    throw new IllegalArgumentException("Invalid trade side: " + tradeSide);
                }

                // 6. Update portfolio_state projection
                jdbcTemplate.update(
                        "UPDATE portfolio_state SET cash_amount = ?, revision = ? WHERE portfolio_id = ?",
                        delta.newState().cash().toPlainString(), currentRevision + 1, portfolioId
                );
                failureInjector.accept("cash");

                // 7. Update positions projection
                if (delta.newState().quantity().compareTo(BigDecimal.ZERO) == 0) {
                    if (positionExists) {
                        jdbcTemplate.update(
                                "DELETE FROM positions WHERE portfolio_id = ? AND listing_id = ?",
                                portfolioId, listingId
                        );
                    }
                } else {
                    if (positionExists) {
                        jdbcTemplate.update(
                                "UPDATE positions SET quantity = ?, total_acquisition_cost = ?, updated_at = ? WHERE portfolio_id = ? AND listing_id = ?",
                                delta.newState().quantity().toPlainString(), delta.newState().totalBasis().toPlainString(), now, portfolioId, listingId
                        );
                    } else {
                        jdbcTemplate.update(
                                "INSERT INTO positions (portfolio_id, listing_id, quantity, total_acquisition_cost, updated_at) VALUES (?, ?, ?, ?, ?)",
                                portfolioId, listingId, delta.newState().quantity().toPlainString(), delta.newState().totalBasis().toPlainString(), now
                        );
                    }
                }
                failureInjector.accept("position");

                // 8. Append executions row
                String execId = "exec-" + UUID.randomUUID();
                jdbcTemplate.update(
                        "INSERT INTO executions (id, portfolio_id, operation_id, listing_id, side, units, reference_price, fill_price, commission, modeled_spread_slippage, executed_at, execution_model, sequence) " +
                                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, '0.00', ?, ?, 1)",
                        execId, portfolioId, opId, listingId, tradeSide, normalizedQuantity.toPlainString(),
                        normalizedFillPrice.toPlainString(), normalizedFillPrice.toPlainString(), normalizedCommission.toPlainString(),
                        now, executionModel != null ? executionModel : "SIMULATED_DEMO"
                );
                failureInjector.accept("execution");

                // 9. Append ledger_entries row
                String ledgerId = "ledger-" + UUID.randomUUID();
                jdbcTemplate.update(
                        "INSERT INTO ledger_entries (id, portfolio_id, operation_id, sequence, entry_type, listing_id, signed_quantity_delta, signed_cash_delta, acquisition_cost_delta, currency, business_at, recorded_at, legacy_record_id) " +
                                "VALUES (?, ?, ?, 1, 'TRADE', ?, ?, ?, ?, ?, ?, ?, NULL)",
                        ledgerId, portfolioId, opId, listingId,
                        delta.quantityDelta().stripTrailingZeros().toPlainString(),
                        delta.cashDelta().toPlainString(),
                        delta.basisDelta().toPlainString(),
                        currency, now, now
                );
                failureInjector.accept("ledger");

                TradeExecutionResult result = new TradeExecutionResult(
                        opId, execId, portfolioId, listingId, symbol, tradeSide,
                        normalizedQuantity.toPlainString(),
                        normalizedFillPrice.toPlainString(), normalizedCommission.toPlainString(),
                        delta.cashDelta().toPlainString(),
                        delta.basisDelta().toPlainString(),
                        delta.realizedGain().toPlainString(),
                        delta.newState().cash().toPlainString(),
                        delta.newState().quantity().toPlainString(),
                        now, false
                );

                // 10. Update operation result_json
                try {
                    String serialized = objectMapper.writeValueAsString(result);
                    jdbcTemplate.update(
                            "UPDATE operations SET result_json = ? WHERE id = ?",
                            serialized, opId
                    );
                    failureInjector.accept("operation-result");
                } catch (JsonProcessingException e) {
                    throw new IllegalStateException("Failed to serialize trade result: " + e.getMessage(), e);
                }

                // 11. Legacy demo compatibility updates (if legacy demo)
                if ("LEGACY_DEMO".equals(mode)) {
                    jdbcTemplate.update(
                            "INSERT INTO trades (id, user_id, ticker, side, quantity, price, executed_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                            execId, ownerIdForPortfolio(portfolioId), symbol, tradeSide,
                            normalizedQuantity.doubleValue(), normalizedFillPrice.doubleValue(), now
                    );
                    jdbcTemplate.update(
                            "UPDATE users_profile SET cash_balance = ? WHERE id = ?",
                            delta.newState().cash().doubleValue(), ownerIdForPortfolio(portfolioId)
                    );
                }

                return result;
            }));
        } finally {
            writerLock.unlock();
        }
    }

    /**
     * Reads a consistent multi-query view of a portfolio.
     */
    public PortfolioView getPortfolioView(String portfolioId) {
        return transactionTemplate.execute(status -> {
            Map<String, Object> p = jdbcTemplate.queryForMap(
                    "SELECT id, owner_id, name, mode, base_currency FROM portfolios WHERE id = ?",
                    portfolioId
            );

            Map<String, Object> state = jdbcTemplate.queryForMap(
                    "SELECT cash_amount, revision FROM portfolio_state WHERE portfolio_id = ?",
                    portfolioId
            );

            List<Map<String, Object>> posRows = jdbcTemplate.queryForList(
                    "SELECT p.listing_id, l.symbol, p.quantity, p.total_acquisition_cost, p.updated_at " +
                            "FROM positions p JOIN listings l ON p.listing_id = l.id WHERE p.portfolio_id = ? ORDER BY l.symbol ASC",
                    portfolioId
            );

            List<PositionView> positions = new ArrayList<>();
            for (Map<String, Object> row : posRows) {
                String listingId = (String) row.get("listing_id");
                String sym = (String) row.get("symbol");
                String qtyStr = (String) row.get("quantity");
                String basisStr = (String) row.get("total_acquisition_cost");
                String updated = (String) row.get("updated_at");

                BigDecimal q = new BigDecimal(qtyStr);
                BigDecimal b = new BigDecimal(basisStr);
                BigDecimal avgCost = (q.compareTo(BigDecimal.ZERO) > 0)
                        ? b.divide(q, AccountingCore.PRICE_SCALE, AccountingCore.CASH_ROUNDING)
                        : BigDecimal.ZERO;

                positions.add(new PositionView(listingId, sym, qtyStr, basisStr, avgCost.toPlainString(), updated));
            }

            return new PortfolioView(
                    (String) p.get("id"),
                    (String) p.get("owner_id"),
                    (String) p.get("name"),
                    (String) p.get("mode"),
                    (String) p.get("base_currency"),
                    (String) state.get("cash_amount"),
                    ((Number) state.get("revision")).intValue(),
                    positions
            );
        });
    }

    private String resolveOrCreateLegacyListing(String ticker, String currency) {
        String listingId = "listing-unresolved-" + ticker;
        String instrumentId = "inst-unresolved-" + ticker;

        jdbcTemplate.update(
                "INSERT OR IGNORE INTO instruments (id, type, name, isin, provenance) VALUES (?, 'EQUITY_UNRESOLVED', ?, NULL, 'LEGACY_DEMO')",
                instrumentId, ticker + " (Legacy Unresolved)"
        );

        jdbcTemplate.update(
                "INSERT OR IGNORE INTO listings (id, instrument_id, venue, symbol, quote_currency, calendar_id, inception_date, termination_date, identity_status) " +
                        "VALUES (?, ?, NULL, ?, ?, NULL, NULL, NULL, 'UNRESOLVED_LEGACY')",
                listingId, instrumentId, ticker, currency
        );

        return listingId;
    }

    private String resolveResearchListing(String tickerOrListingId, String currency) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT id FROM listings WHERE (symbol = ? OR id = ? OR LOWER(id) = LOWER(?)) AND quote_currency = ? AND identity_status = 'RESOLVED' LIMIT 1",
                    String.class,
                    tickerOrListingId, tickerOrListingId, tickerOrListingId, currency
            );
        } catch (EmptyResultDataAccessException e) {
            throw new IllegalArgumentException("Resolved research listing unavailable for " + tickerOrListingId + " " + currency);
        }
    }

    void setFailureInjector(Consumer<String> failureInjector) {
        this.failureInjector = failureInjector == null ? ignored -> {} : failureInjector;
    }

    public record SplitResult(
            String operationId,
            String portfolioId,
            String listingId,
            String splitRatio,
            String oldQuantity,
            String newQuantity,
            String totalBasis,
            String executedAt,
            boolean isRetry
    ) {
        public SplitResult asRetry() {
            return new SplitResult(operationId, portfolioId, listingId, splitRatio, oldQuantity, newQuantity, totalBasis, executedAt, true);
        }
    }

    public record CashDistributionResult(
            String operationId,
            String portfolioId,
            String listingId,
            String actionId,
            String amount,
            String remainingCash,
            String executedAt,
            boolean isRetry
    ) {
        public CashDistributionResult asRetry() {
            return new CashDistributionResult(operationId, portfolioId, listingId, actionId, amount, remainingCash, executedAt, true);
        }
    }

    public SplitResult applySplit(
            String portfolioId,
            String listingId,
            BigDecimal splitRatio,
            String idempotencyKey,
            String businessAt
    ) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IdempotencyExceptions.MissingIdempotencyKeyException("Idempotency key is required for split operation");
        }
        if (splitRatio == null || splitRatio.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Split ratio must be positive");
        }
        String canonicalHash = CanonicalIntentHasher.hashSplit(portfolioId, listingId, splitRatio);

        try {
            Map<String, Object> opRow = jdbcTemplate.queryForMap(
                    "SELECT payload_hash, result_json FROM operations WHERE portfolio_id = ? AND kind = 'SPLIT' AND idempotency_key = ?",
                    portfolioId, idempotencyKey
            );
            if (!canonicalHash.equals(opRow.get("payload_hash"))) {
                throw new IdempotencyExceptions.IdempotencyConflictException("Conflicting reuse of split idempotency key: " + idempotencyKey);
            }
            return deserializeSplitResult((String) opRow.get("result_json")).asRetry();
        } catch (EmptyResultDataAccessException ignored) {}

        writerLock.lock();
        try {
            return executeWithBusyRetry(() -> transactionTemplate.execute(status -> {
                try {
                    Map<String, Object> opRow = jdbcTemplate.queryForMap(
                            "SELECT payload_hash, result_json FROM operations WHERE portfolio_id = ? AND kind = 'SPLIT' AND idempotency_key = ?",
                            portfolioId, idempotencyKey
                    );
                    if (!canonicalHash.equals(opRow.get("payload_hash"))) {
                        throw new IdempotencyExceptions.IdempotencyConflictException("Conflicting reuse of split idempotency key: " + idempotencyKey);
                    }
                    return deserializeSplitResult((String) opRow.get("result_json")).asRetry();
                } catch (EmptyResultDataAccessException ignored) {}

                String opId = "op-" + UUID.randomUUID();
                String now = businessAt != null ? businessAt : Instant.now().toString();

                BigDecimal oldQty = BigDecimal.ZERO;
                BigDecimal basis = BigDecimal.ZERO;
                List<Map<String, Object>> posRows = jdbcTemplate.queryForList(
                        "SELECT quantity, total_acquisition_cost FROM positions WHERE portfolio_id = ? AND listing_id = ?",
                        portfolioId, listingId
                );
                if (!posRows.isEmpty()) {
                    oldQty = new BigDecimal((String) posRows.get(0).get("quantity"));
                    basis = new BigDecimal((String) posRows.get(0).get("total_acquisition_cost"));
                }

                AccountingCore.AccountingDelta delta = AccountingCore.split(
                        new AccountingCore.AccountingState(BigDecimal.ZERO, oldQty, basis), splitRatio
                );
                BigDecimal newQty = delta.newState().quantity();

                SplitResult result = new SplitResult(
                        opId, portfolioId, listingId, splitRatio.toPlainString(),
                        oldQty.toPlainString(), newQty.toPlainString(), basis.toPlainString(), now, false
                );

                String resultJson;
                try {
                    resultJson = objectMapper.writeValueAsString(result);
                } catch (Exception e) {
                    throw new IllegalStateException("Failed to serialize split result", e);
                }

                jdbcTemplate.update(
                        "INSERT INTO operations (id, portfolio_id, kind, idempotency_key, payload_hash, result_json, business_at, created_at) VALUES (?, ?, 'SPLIT', ?, ?, ?, ?, ?)",
                        opId, portfolioId, idempotencyKey, canonicalHash, resultJson, now, now
                );

                if (posRows.isEmpty()) {
                    jdbcTemplate.update(
                            "INSERT INTO positions (portfolio_id, listing_id, quantity, total_acquisition_cost, updated_at) VALUES (?, ?, ?, ?, ?)",
                            portfolioId, listingId, newQty.toPlainString(), basis.toPlainString(), now
                    );
                } else {
                    jdbcTemplate.update(
                            "UPDATE positions SET quantity = ?, updated_at = ? WHERE portfolio_id = ? AND listing_id = ?",
                            newQty.toPlainString(), now, portfolioId, listingId
                    );
                }

                jdbcTemplate.update(
                        "INSERT INTO ledger_entries (id, portfolio_id, operation_id, sequence, entry_type, listing_id, signed_quantity_delta, signed_cash_delta, acquisition_cost_delta, currency, business_at, recorded_at) VALUES (?, ?, ?, 1, 'SPLIT', ?, ?, '0.00', '0.00', 'EUR', ?, ?)",
                        "ledger-" + UUID.randomUUID(), portfolioId, opId, listingId, delta.quantityDelta().toPlainString(), now, now
                );

                return result;
            }));
        } finally {
            writerLock.unlock();
        }
    }

    public CashDistributionResult creditCashDistribution(
            String portfolioId,
            String listingId,
            String actionId,
            BigDecimal amount,
            String idempotencyKey,
            String businessAt
    ) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IdempotencyExceptions.MissingIdempotencyKeyException("Idempotency key is required for distribution operation");
        }
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Distribution amount must be positive");
        }
        String canonicalHash = CanonicalIntentHasher.hashDistribution(portfolioId, actionId, amount);

        try {
            Map<String, Object> opRow = jdbcTemplate.queryForMap(
                    "SELECT payload_hash, result_json FROM operations WHERE portfolio_id = ? AND kind = 'CASH_DISTRIBUTION' AND idempotency_key = ?",
                    portfolioId, idempotencyKey
            );
            if (!canonicalHash.equals(opRow.get("payload_hash"))) {
                throw new IdempotencyExceptions.IdempotencyConflictException("Conflicting reuse of distribution idempotency key: " + idempotencyKey);
            }
            return deserializeDistributionResult((String) opRow.get("result_json")).asRetry();
        } catch (EmptyResultDataAccessException ignored) {}

        writerLock.lock();
        try {
            return executeWithBusyRetry(() -> transactionTemplate.execute(status -> {
                try {
                    Map<String, Object> opRow = jdbcTemplate.queryForMap(
                            "SELECT payload_hash, result_json FROM operations WHERE portfolio_id = ? AND kind = 'CASH_DISTRIBUTION' AND idempotency_key = ?",
                            portfolioId, idempotencyKey
                    );
                    if (!canonicalHash.equals(opRow.get("payload_hash"))) {
                        throw new IdempotencyExceptions.IdempotencyConflictException("Conflicting reuse of distribution idempotency key: " + idempotencyKey);
                    }
                    return deserializeDistributionResult((String) opRow.get("result_json")).asRetry();
                } catch (EmptyResultDataAccessException ignored) {}

                String opId = "op-" + UUID.randomUUID();
                String now = businessAt != null ? businessAt : Instant.now().toString();

                Map<String, Object> stateRow = jdbcTemplate.queryForMap(
                        "SELECT cash_amount, revision FROM portfolio_state WHERE portfolio_id = ?", portfolioId
                );
                BigDecimal currentCash = new BigDecimal((String) stateRow.get("cash_amount"));
                int currentRevision = ((Number) stateRow.get("revision")).intValue();

                AccountingCore.AccountingDelta delta = AccountingCore.creditCashDistribution(
                        new AccountingCore.AccountingState(currentCash, BigDecimal.ZERO, BigDecimal.ZERO), amount
                );
                BigDecimal newCash = delta.newState().cash();

                CashDistributionResult result = new CashDistributionResult(
                        opId, portfolioId, listingId, actionId, amount.toPlainString(), newCash.toPlainString(), now, false
                );

                String resultJson;
                try {
                    resultJson = objectMapper.writeValueAsString(result);
                } catch (Exception e) {
                    throw new IllegalStateException("Failed to serialize distribution result", e);
                }

                jdbcTemplate.update(
                        "INSERT INTO operations (id, portfolio_id, kind, idempotency_key, payload_hash, result_json, business_at, created_at) VALUES (?, ?, 'CASH_DISTRIBUTION', ?, ?, ?, ?, ?)",
                        opId, portfolioId, idempotencyKey, canonicalHash, resultJson, now, now
                );

                int updatedState = jdbcTemplate.update(
                        "UPDATE portfolio_state SET cash_amount = ?, revision = revision + 1 WHERE portfolio_id = ? AND revision = ?",
                        newCash.toPlainString(), portfolioId, currentRevision
                );
                if (updatedState == 0) {
                    throw new IllegalStateException("Concurrent state conflict on portfolio " + portfolioId);
                }

                jdbcTemplate.update(
                        "INSERT INTO ledger_entries (id, portfolio_id, operation_id, sequence, entry_type, listing_id, signed_quantity_delta, signed_cash_delta, acquisition_cost_delta, currency, business_at, recorded_at) VALUES (?, ?, ?, 1, 'CASH_DISTRIBUTION', ?, '0', ?, '0.00', 'EUR', ?, ?)",
                        "ledger-" + UUID.randomUUID(), portfolioId, opId, listingId, delta.cashDelta().toPlainString(), now, now
                );

                return result;
            }));
        } finally {
            writerLock.unlock();
        }
    }

    private SplitResult deserializeSplitResult(String resultJson) {
        try {
            return objectMapper.readValue(resultJson, SplitResult.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize cached split result", e);
        }
    }

    private CashDistributionResult deserializeDistributionResult(String resultJson) {
        try {
            return objectMapper.readValue(resultJson, CashDistributionResult.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize cached distribution result", e);
        }
    }

    private TradeExecutionResult deserializeTradeResult(String resultJson) {
        try {
            return objectMapper.readValue(resultJson, TradeExecutionResult.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize cached trade result", e);
        }
    }

    private String ownerIdForPortfolio(String portfolioId) {
        return jdbcTemplate.queryForObject(
                "SELECT owner_id FROM portfolios WHERE id = ?", String.class, portfolioId
        );
    }

    private void rejectOuterTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Financial operations require their own top-level transaction");
        }
    }

    private <T> T executeWithBusyRetry(Supplier<T> operation) {
        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= 4; attempt++) {
            try {
                return operation.get();
            } catch (RuntimeException exception) {
                boolean retryable = isSqliteBusy(exception) || exception instanceof DuplicateKeyException;
                if (!retryable || attempt == 4) {
                    throw exception;
                }
                lastFailure = exception;
                try {
                    Thread.sleep(25L * attempt);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while retrying a busy SQLite operation", interrupted);
                }
            }
        }
        throw lastFailure;
    }

    private boolean isSqliteBusy(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && (message.contains("SQLITE_BUSY") || message.contains("database is locked"))) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
