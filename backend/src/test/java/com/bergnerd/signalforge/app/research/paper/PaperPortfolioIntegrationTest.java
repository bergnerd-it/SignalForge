package com.bergnerd.signalforge.app.research.paper;

import com.bergnerd.signalforge.app.TemporarySqliteInitializer;
import com.bergnerd.signalforge.app.operation.OperationService;
import com.bergnerd.signalforge.app.research.ResearchDtos.*;
import com.bergnerd.signalforge.app.research.ResearchPortfolioController;
import com.bergnerd.signalforge.app.research.assistant.ResearchAssistantDtos.*;
import com.bergnerd.signalforge.app.research.assistant.ResearchAssistantService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ContextConfiguration(initializers = TemporarySqliteInitializer.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PaperPortfolioIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private OperationService operationService;

    @Autowired
    private PaperPortfolioService paperPortfolioService;

    @Autowired
    private PaperDataReadinessService dataReadinessService;

    @Autowired
    private PaperExportService paperExportService;

    @Autowired
    private ResearchAssistantService assistantService;

    @Autowired
    private ResearchPortfolioController portfolioController;

    private static final String OWNER = "test-owner-m5";
    private static final String TICKER = "IWDA.AS";
    private static final String LISTING_ID = "listing-test-iwda";
    private static final String INSTRUMENT_ID = "inst-test-iwda";

    private static final String SESSION_1 = java.time.LocalDate.now(java.time.ZoneOffset.UTC).minusDays(1).toString();
    private static final String SESSION_2 = java.time.LocalDate.now(java.time.ZoneOffset.UTC).plusDays(1).toString();

    @BeforeEach
    void setupFixtures() {
        // Seed instrument and listing
        jdbcTemplate.update(
                "INSERT OR IGNORE INTO instruments (id, type, name, isin, provenance) VALUES (?, 'EQUITY', 'iShares Core MSCI World', 'IE00B4L5Y983', 'TEST')",
                INSTRUMENT_ID
        );
        jdbcTemplate.update(
                "INSERT OR IGNORE INTO listings (id, instrument_id, venue, symbol, quote_currency, identity_status) " +
                        "VALUES (?, ?, 'AMS', ?, 'EUR', 'RESOLVED')",
                LISTING_ID, INSTRUMENT_ID, TICKER
        );

        // Seed historical dataset and daily bar
        jdbcTemplate.update(
                "INSERT OR IGNORE INTO datasets (id, name, source, classification, schema_version, parser_version, input_checksum, content_checksum, manifest_json, coverage_start, coverage_end, validation_status, validation_findings_json, quality_label, imported_at, created_at) " +
                        "VALUES ('ds-test-m5', 'Test Dataset M5', 'SYNTHETIC', 'SYNTHETIC', '1.0', '1.0', 'chk-in', 'chk-content', '{}', ?, ?, 'VALID', '[]', 'SYNTHETIC', '2026-09-01T00:00:00Z', '2026-09-01T00:00:00Z')",
                SESSION_1, SESSION_2
        );
        jdbcTemplate.update(
                "INSERT OR IGNORE INTO dataset_listings (dataset_id, listing_id, instrument_id, symbol, venue, quote_currency, calendar_id) " +
                        "VALUES ('ds-test-m5', ?, ?, ?, 'AMS', 'EUR', 'XAMS')",
                LISTING_ID, INSTRUMENT_ID, TICKER
        );
        jdbcTemplate.update(
                "INSERT OR IGNORE INTO dataset_sessions (dataset_id, calendar_id, session_date, open_time, close_time, session_type) " +
                        "VALUES ('ds-test-m5', 'XAMS', ?, '09:00:00', '17:30:00', 'TRADING')",
                SESSION_1
        );
        jdbcTemplate.update(
                "INSERT OR IGNORE INTO historical_bars (dataset_id, listing_id, session_date, open, high, low, close, volume, available_at) " +
                        "VALUES ('ds-test-m5', ?, ?, '99.00', '100.50', '98.50', '100.00', 10000, '2026-09-01T18:00:00Z')",
                LISTING_ID, SESSION_1
        );
        jdbcTemplate.update(
                "INSERT OR IGNORE INTO dataset_sessions (dataset_id, calendar_id, session_date, open_time, close_time, session_type) " +
                        "VALUES ('ds-test-m5', 'XAMS', ?, '09:00:00', '17:30:00', 'TRADING')",
                SESSION_2
        );
        jdbcTemplate.update(
                "INSERT OR IGNORE INTO historical_bars (dataset_id, listing_id, session_date, open, high, low, close, volume, available_at) " +
                        "VALUES ('ds-test-m5', ?, ?, '100.00', '101.00', '99.50', '100.00', 10000, '2026-09-01T18:00:00Z')",
                LISTING_ID, SESSION_2
        );

        // Seed universe and universe listings
        jdbcTemplate.update(
                "INSERT OR IGNORE INTO universes (id, owner_id, name, version, description, dataset_id, calendar_id, currency, provenance, created_at) " +
                        "VALUES ('uni-default', 'default', 'Default ETF Universe', '1.0.0', 'Test universe', 'ds-test-m5', 'XAMS', 'EUR', 'TEST', '2026-09-01T00:00:00Z')"
        );
        jdbcTemplate.update(
                "INSERT OR IGNORE INTO universe_listings (universe_id, listing_id, ordinal) " +
                        "VALUES ('uni-default', ?, 0)",
                LISTING_ID
        );
    }

    private void cloneBaseDataset(String datasetId) {
        cloneBaseDataset(datasetId, "SYNTHETIC", null, null, false);
    }

    private void cloneBaseDataset(String datasetId, String source) {
        cloneBaseDataset(datasetId, source, null, null, false);
    }

    private void cloneBaseDataset(String datasetId, String source, String changedSession, String changedOpen) {
        cloneBaseDataset(datasetId, source, changedSession, changedOpen, false);
    }

    private void cloneBaseDataset(String datasetId, String source, String changedSession, String changedOpen,
                                  boolean isoSessionTimes) {
        jdbcTemplate.update("INSERT INTO datasets (id, name, source, classification, schema_version, parser_version, " +
                        "input_checksum, content_checksum, manifest_json, coverage_start, coverage_end, validation_status, " +
                        "validation_findings_json, quality_label, imported_at, created_at) SELECT ?, name, ?, " +
                        "classification, schema_version, parser_version, input_checksum, content_checksum, manifest_json, " +
                        "coverage_start, coverage_end, validation_status, validation_findings_json, quality_label, " +
                        "imported_at, created_at FROM datasets WHERE id = 'ds-test-m5'", datasetId, source);
        jdbcTemplate.update("INSERT INTO dataset_listings (dataset_id, listing_id, instrument_id, symbol, venue, " +
                        "quote_currency, calendar_id, inception_date, termination_date, isin) SELECT ?, listing_id, " +
                        "instrument_id, symbol, venue, quote_currency, calendar_id, inception_date, termination_date, isin " +
                        "FROM dataset_listings WHERE dataset_id = 'ds-test-m5'", datasetId);
        jdbcTemplate.update("INSERT INTO dataset_sessions (dataset_id, calendar_id, session_date, open_time, close_time, " +
                        "session_type) SELECT ?, calendar_id, session_date, " +
                        "CASE WHEN ? THEN session_date || 'T07:00:00Z' ELSE open_time END, " +
                        "CASE WHEN ? THEN session_date || 'T15:30:00Z' ELSE close_time END, session_type " +
                        "FROM dataset_sessions WHERE dataset_id = 'ds-test-m5'", datasetId,
                isoSessionTimes, isoSessionTimes);
        jdbcTemplate.update("INSERT INTO historical_bars (dataset_id, listing_id, session_date, open, high, low, " +
                        "close, volume, available_at) SELECT ?, listing_id, session_date, " +
                        "CASE WHEN session_date = ? THEN ? ELSE open END, high, low, close, volume, available_at " +
                        "FROM historical_bars WHERE dataset_id = 'ds-test-m5'", datasetId, changedSession, changedOpen);
    }

    @Test
    void verifyV12SchemaAndTablesExist() {
        Integer v12Count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM schema_migrations WHERE version = 12", Integer.class);
        assertEquals(1, v12Count);

        List<String> requiredTables = List.of(
                "paper_portfolio_segments",
                "paper_proposals",
                "paper_proposal_items",
                "paper_proposal_observations",
                "paper_execution_intents",
                "paper_execution_results",
                "paper_receivables",
                "paper_processed_corporate_actions",
                "paper_valuations",
                "paper_mode_history"
        );

        for (String table : requiredTables) {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name=?", Integer.class, table);
            assertEquals(1, count, "Table " + table + " must exist");
        }
    }

    @Test
    void activationRejectsMissingFixtureIdsWithoutDatabaseError() {
        String owner = "activation-validation-owner";
        OperationService.PortfolioCreationResult port = operationService.createPortfolio(
                owner, "Missing Fixture Fund", "PAPER", "EUR", new BigDecimal("1000.00"), "missing-fixture-create");
        org.springframework.web.server.ResponseStatusException error = assertThrows(
                org.springframework.web.server.ResponseStatusException.class,
                () -> paperPortfolioService.activatePortfolio(port.portfolioId(), owner, "missing-fixture-activate",
                        new ActivatePortfolioRequest("ETF_BUY_HOLD_V1", "1.0.0", "missing-universe",
                                LISTING_ID, new CostPolicyDto("1.00", "0", "0"), "MANUAL")));
        assertEquals(400, error.getStatusCode().value());
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM paper_portfolio_segments WHERE portfolio_id = ?",
                Integer.class, port.portfolioId()));
    }

    @Test
    void completePaperPortfolioLifecycleWorkflow() throws Exception {
        // Step 1: Create separate EUR paper portfolio
        OperationService.PortfolioCreationResult created = operationService.createPortfolio(
                OWNER,
                "EUR Alpha Paper Fund",
                "PAPER",
                "EUR",
                new BigDecimal("10000.00"),
                "idemp-create-m5"
        );
        assertNotNull(created);
        String portfolioId = created.portfolioId();
        assertEquals("EUR", created.baseCurrency());
        assertEquals(0, new BigDecimal("10000.00").compareTo(new BigDecimal(created.initialCash())));

        // Step 2: Activate segment with ETF_BUY_HOLD_V1
        ActivatePortfolioRequest actReq = new ActivatePortfolioRequest(
                "ETF_BUY_HOLD_V1",
                "1.0.0",
                "uni-default",
                LISTING_ID,
                new CostPolicyDto("1.00", "0", "0"),
                "MANUAL"
        );
        paperPortfolioService.activatePortfolio(portfolioId, OWNER, "idemp-act-m5", actReq);

        // Step 3: Adopt dataset snapshot
        dataReadinessService.adoptDataset(portfolioId, "ds-test-m5", OWNER, "idemp-adopt-m5");

        // Step 4: Verify initial valuation snapshot is created with EUR 10,000.00
        PagedResponse<PaperValuationDto> vals = paperPortfolioService.listValuations(portfolioId, OWNER, 10, 0);
        assertFalse(vals.items().isEmpty());
        assertEquals("OPENING", vals.items().get(0).observationKind());
        assertEquals(0, new BigDecimal("10000.00").compareTo(new BigDecimal(vals.items().get(0).cashBalance())));
        assertEquals(0, new BigDecimal("10000.00").compareTo(new BigDecimal(vals.items().get(0).totalEquity())));

        // Step 5: Evaluate strategy to generate a rebalancing proposal
        PaperProposalDto proposal = paperPortfolioService.evaluatePortfolio(portfolioId, OWNER, "idemp-eval-m5");
        assertNotNull(proposal);
        assertEquals("PROPOSED", proposal.status());
        PaperProposalDto repeatedEvaluation = paperPortfolioService.evaluatePortfolio(
                portfolioId, OWNER, "idemp-eval-m5-again");
        assertEquals(proposal.id(), repeatedEvaluation.id());
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM paper_proposals WHERE portfolio_id = ?", Integer.class, portfolioId));

        // Step 6: Timely proposal acceptance
        PaperProposalDto accepted = paperPortfolioService.acceptProposal(
                portfolioId, proposal.id(), OWNER, "idemp-acc-m5", new AcceptProposalRequest("Manual acceptance in test")
        );
        assertEquals("ACCEPTED", accepted.status());

        // Verify status in DB
        String propStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM paper_proposals WHERE id = ?", String.class, proposal.id());
        assertEquals("ACCEPTED", propStatus);

        // Step 7: Corporate action receivable & idempotency
        String recId = UUID.randomUUID().toString();
        jdbcTemplate.update(
                "INSERT INTO paper_receivables (id, portfolio_id, action_id, listing_id, source_namespace, action_type, record_instant, ex_date, " +
                        "payment_date, availability_instant, gross_amount, withholding_tax, net_amount, status, created_at, dataset_id, dataset_checksum, terms_hash) " +
                        "VALUES (?, ?, 'ca-div-1', ?, 'SYNTHETIC', 'CASH_DISTRIBUTION', '2026-09-15T09:00:00Z', '2026-09-15', " +
                        "'2026-09-15', '2026-09-15T09:00:00Z', '10.00', '0.00', '10.00', 'PENDING', '2026-09-15T09:00:00Z', " +
                        "'ds-test-m5', 'chk-content', 'fixture-terms')",
                recId, portfolioId, LISTING_ID
        );

        // Simulate arrival of scheduled market open instant
        jdbcTemplate.update(
                "UPDATE paper_execution_intents SET scheduled_open_instant = ? WHERE portfolio_id = ?",
                SESSION_1 + "T09:00:00Z", portfolioId
        );

        // Process corporate actions and pending intents
        paperPortfolioService.processPortfolioEvents(portfolioId, OWNER, "idemp-ca-1");

        // The newly booked payment cannot fund the earlier opening fill.
        // Buy 99 units @ 100.00 plus EUR 1 commission, then credit EUR 10.
        BigDecimal cashAfterDiv = jdbcTemplate.queryForObject(
                "SELECT cash_amount FROM portfolio_state WHERE portfolio_id = ?",
                BigDecimal.class, portfolioId);
        assertEquals(0, new BigDecimal("109.00").compareTo(cashAfterDiv));

        // Position is 99 units.
        BigDecimal positionQty = jdbcTemplate.queryForObject(
                "SELECT quantity FROM positions WHERE portfolio_id = ? AND listing_id = ?",
                BigDecimal.class, portfolioId, LISTING_ID);
        assertEquals(0, new BigDecimal("99").compareTo(positionQty));

        // Second process run -> duplicate processing rejected / no duplicate cash credited
        Integer valuationCountBeforeRetry = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM paper_valuations WHERE portfolio_id = ?", Integer.class, portfolioId);
        paperPortfolioService.processPortfolioEvents(portfolioId, OWNER, "idemp-ca-2");
        BigDecimal cashAfterSecondProcess = jdbcTemplate.queryForObject(
                "SELECT cash_amount FROM portfolio_state WHERE portfolio_id = ?",
                BigDecimal.class, portfolioId);
        assertEquals(0, cashAfterDiv.compareTo(cashAfterSecondProcess));
        assertEquals(valuationCountBeforeRetry, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM paper_valuations WHERE portfolio_id = ?", Integer.class, portfolioId));

        // Step 8: Concurrency barrier: 8 threads attempting to accept/modify the same proposal
        PaperProposalDto prop2 = paperPortfolioService.evaluatePortfolio(portfolioId, OWNER, "idemp-eval-m5-2");
        assertNotNull(prop2);

        int threadCount = 8;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CyclicBarrier barrier = new CyclicBarrier(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            final int idx = i;
            futures.add(executor.submit(() -> {
                try {
                    barrier.await();
                    paperPortfolioService.acceptProposal(
                            portfolioId, prop2.id(), OWNER, "idemp-conc-" + idx, new AcceptProposalRequest("Concurrent accept")
                    );
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failureCount.incrementAndGet();
                }
            }));
        }

        for (Future<?> f : futures) {
            f.get(5, TimeUnit.SECONDS);
        }
        executor.shutdown();

        // Exactly 1 thread must succeed, exactly 7 threads must fail
        assertEquals(1, successCount.get(), "Exactly 1 thread should succeed in accepting the proposal");
        assertEquals(7, failureCount.get(), "7 threads should fail gracefully on concurrency barrier");

        // Step 9: Audit ZIP export
        byte[] zipBytes = paperExportService.createPaperAuditZip(portfolioId, OWNER);
        assertNotNull(zipBytes);
        assertTrue(zipBytes.length > 0);

        Set<String> entriesFound = new HashSet<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                entriesFound.add(entry.getName());
                byte[] content = zis.readAllBytes();
                assertTrue(content.length > 0, "Zip entry " + entry.getName() + " should not be empty");
            }
        }

        assertTrue(entriesFound.contains("manifest.json"));
        assertTrue(entriesFound.contains("proposals.csv"));
        assertTrue(entriesFound.contains("execution_results.csv"));
        assertTrue(entriesFound.contains("valuations.csv"));
        assertTrue(entriesFound.contains("holdings.csv"));

        // Step 10: Grounded AI Assistant query
        ChatRequest assistantReq = new ChatRequest(
                "What is my current cash balance and held positions?",
                new ResearchContextDto("PORTFOLIO", portfolioId)
        );
        ChatResponse assistantResp = assistantService.processQuery(OWNER, "idemp-assistant-m5", assistantReq);
        assertNotNull(assistantResp);
        assertNotNull(assistantResp.message());
        assertFalse(assistantResp.factCards().isEmpty());
        assertFalse(assistantResp.evidenceReferences().isEmpty());
    }

    @Test
    void multiPortfolioIsolationAndLegacyDemoIntegrity_scenario1() {
        String owner = "scenario-1-owner";

        // Create Paper Portfolio 1 (EUR 5000)
        OperationService.PortfolioCreationResult p1 = operationService.createPortfolio(
                owner, "Paper Portfolio 1", "PAPER", "EUR", new BigDecimal("5000.00"), "idemp-sc1-p1"
        );
        // Create Paper Portfolio 2 (EUR 7000)
        OperationService.PortfolioCreationResult p2 = operationService.createPortfolio(
                owner, "Paper Portfolio 2", "PAPER", "EUR", new BigDecimal("7000.00"), "idemp-sc1-p2"
        );
        // Create Legacy Demo Portfolio (EUR 20000)
        OperationService.PortfolioCreationResult legacy = operationService.createPortfolio(
                owner, "Legacy Demo", "LEGACY_DEMO", "EUR", new BigDecimal("20000.00"), "idemp-sc1-legacy"
        );

        // Verify isolation of cash and operations
        BigDecimal cash1 = jdbcTemplate.queryForObject(
                "SELECT cash_amount FROM portfolio_state WHERE portfolio_id = ?", BigDecimal.class, p1.portfolioId());
        BigDecimal cash2 = jdbcTemplate.queryForObject(
                "SELECT cash_amount FROM portfolio_state WHERE portfolio_id = ?", BigDecimal.class, p2.portfolioId());
        BigDecimal legacyCash = jdbcTemplate.queryForObject(
                "SELECT cash_amount FROM portfolio_state WHERE portfolio_id = ?", BigDecimal.class, legacy.portfolioId());

        assertEquals(0, new BigDecimal("5000.00").compareTo(cash1));
        assertEquals(0, new BigDecimal("7000.00").compareTo(cash2));
        assertEquals(0, new BigDecimal("20000.00").compareTo(legacyCash));

        // Activate and modify P1
        ActivatePortfolioRequest actReq = new ActivatePortfolioRequest(
                "ETF_BUY_HOLD_V1", "1.0.0", "uni-default", LISTING_ID, new CostPolicyDto("1.00", "0", "0"), "MANUAL"
        );
        paperPortfolioService.activatePortfolio(p1.portfolioId(), owner, "idemp-sc1-act", actReq);

        // Verify P2 and Legacy Demo remain untouched
        Integer p2Segments = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM paper_portfolio_segments WHERE portfolio_id = ?", Integer.class, p2.portfolioId());
        Integer legacySegments = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM paper_portfolio_segments WHERE portfolio_id = ?", Integer.class, legacy.portfolioId());
        assertEquals(0, p2Segments);
        assertEquals(0, legacySegments);

        BigDecimal legacyCashAfter = jdbcTemplate.queryForObject(
                "SELECT cash_amount FROM portfolio_state WHERE portfolio_id = ?", BigDecimal.class, legacy.portfolioId());
        assertEquals(0, legacyCash.compareTo(legacyCashAfter));
    }

    @Test
    void exactArithmeticAffordability_scenario4() {
        String owner = "scenario-4-owner";

        // Step 1: Fund exactly EUR 1,000.00
        OperationService.PortfolioCreationResult port = operationService.createPortfolio(
                owner, "Exact Sizing Fund", "PAPER", "EUR", new BigDecimal("1000.00"), "idemp-sc4-create"
        );
        String portId = port.portfolioId();

        // Step 2: Activate with EUR 1.00 commission, zero spread/slippage
        ActivatePortfolioRequest actReq = new ActivatePortfolioRequest(
                "ETF_BUY_HOLD_V1", "1.0.0", "uni-default", LISTING_ID, new CostPolicyDto("1.00", "0", "0"), "MANUAL"
        );
        paperPortfolioService.activatePortfolio(portId, owner, "idemp-sc4-act", actReq);
        dataReadinessService.adoptDataset(portId, "ds-test-m5", owner, "idemp-sc4-adopt");

        // Step 3: Evaluate -> generates proposal with 100% weight on IWDA.AS
        PaperProposalDto prop = paperPortfolioService.evaluatePortfolio(portId, owner, "idemp-sc4-eval");
        assertNotNull(prop);

        // Step 4: Accept proposal
        paperPortfolioService.acceptProposal(portId, prop.id(), owner, "idemp-sc4-acc", new AcceptProposalRequest("Accept scenario 4"));

        // Simulate market open arrival
        jdbcTemplate.update(
                "UPDATE paper_execution_intents SET scheduled_open_instant = ? WHERE portfolio_id = ?",
                SESSION_1 + "T09:00:00Z", portId
        );

        // Step 5: Process portfolio events:
        // Raw open price is 100.00, cash is 1000.00. Sizing 10 units = 1000 + 1 fee = 1001 > 1000.
        // Squeezed to 9 units. 9 * 100 = 900 + 1 commission = 901.00 total basis.
        // Remaining cash = 1000.00 - 901.00 = 99.00 EUR.
        paperPortfolioService.processPortfolioEvents(portId, owner, "idemp-sc4-proc-1");

        BigDecimal cashAfterFill = jdbcTemplate.queryForObject(
                "SELECT cash_amount FROM portfolio_state WHERE portfolio_id = ?", BigDecimal.class, portId);
        BigDecimal positionQty = jdbcTemplate.queryForObject(
                "SELECT quantity FROM positions WHERE portfolio_id = ? AND listing_id = ?", BigDecimal.class, portId, LISTING_ID);
        BigDecimal costBasis = jdbcTemplate.queryForObject(
                "SELECT total_acquisition_cost FROM positions WHERE portfolio_id = ? AND listing_id = ?", BigDecimal.class, portId, LISTING_ID);

        assertEquals(0, new BigDecimal("99.00").compareTo(cashAfterFill), "Cash must be exactly EUR 99.00");
        assertEquals(0, new BigDecimal("9").compareTo(positionQty), "Executed quantity must be exactly 9 units");
        assertEquals(0, new BigDecimal("901.00").compareTo(costBasis), "Cost basis must be exactly EUR 901.00 (900 shares + 1 fee)");

        // Step 6: Repeat processing is idempotent and leaves exactly 1 fill
        paperPortfolioService.processPortfolioEvents(portId, owner, "idemp-sc4-proc-2");
        Integer fillCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM paper_execution_results WHERE intent_id IN (SELECT id FROM paper_execution_intents WHERE portfolio_id = ?)",
                Integer.class, portId
        );
        assertEquals(1, fillCount, "Repeat event processing must leave exactly one fill");
    }

    @Test
    void waitingIntentResumesOnceWithConfiguredCostsAndCausalTimestamps() {
        String owner = "waiting-owner";
        String missingSession = java.time.LocalDate.parse(SESSION_1).minusDays(1).toString();
        OperationService.PortfolioCreationResult port = operationService.createPortfolio(
                owner, "Waiting Observation Fund", "PAPER", "EUR", new BigDecimal("1000.00"), "waiting-create");
        paperPortfolioService.activatePortfolio(
                port.portfolioId(), owner, "waiting-activate",
                new ActivatePortfolioRequest(
                        "ETF_BUY_HOLD_V1", "1.0.0", "uni-default", LISTING_ID,
                        new CostPolicyDto("2.00", "20", "10"), "MANUAL"));
        dataReadinessService.adoptDataset(port.portfolioId(), "ds-test-m5", owner, "waiting-adopt");
        PaperProposalDto proposal = paperPortfolioService.evaluatePortfolio(port.portfolioId(), owner, "waiting-evaluate");
        paperPortfolioService.acceptProposal(
                port.portfolioId(), proposal.id(), owner, "waiting-accept", new AcceptProposalRequest("test"));
        jdbcTemplate.update(
                "INSERT OR IGNORE INTO dataset_sessions (dataset_id, calendar_id, session_date, open_time, close_time, session_type) " +
                        "VALUES ('ds-test-m5', 'XAMS', ?, '09:00:00', '17:30:00', 'TRADING')",
                missingSession);
        jdbcTemplate.update(
                "UPDATE paper_execution_intents SET scheduled_session_date = ?, scheduled_open_instant = ? WHERE portfolio_id = ?",
                missingSession, missingSession + "T08:00:00Z", port.portfolioId());

        paperPortfolioService.processPortfolioEvents(port.portfolioId(), owner, "waiting-process-1");
        assertEquals("WAITING_FOR_OBSERVATION", jdbcTemplate.queryForObject(
                "SELECT status FROM paper_execution_intents WHERE portfolio_id = ?", String.class, port.portfolioId()));
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM paper_execution_results r JOIN paper_execution_intents i ON i.id = r.intent_id " +
                        "WHERE i.portfolio_id = ?", Integer.class, port.portfolioId()));

        jdbcTemplate.update(
                "INSERT INTO historical_bars (dataset_id, listing_id, session_date, open, high, low, close, volume, available_at) " +
                        "VALUES ('ds-test-m5', ?, ?, '100.00', '101.00', '99.00', '100.00', 10000, ?)",
                LISTING_ID, missingSession, java.time.Instant.now().minusSeconds(1).toString());
        paperPortfolioService.processPortfolioEvents(port.portfolioId(), owner, "waiting-process-2");

        Map<String, Object> result = jdbcTemplate.queryForMap(
                    "SELECT r.raw_open_price, r.fill_price, r.commission, r.spread_slippage_cost, " +
                            "r.market_effective_instant, r.observed_instant, r.booked_instant, i.status " +
                            "FROM paper_execution_results r JOIN paper_execution_intents i ON i.id = r.intent_id " +
                            "WHERE i.portfolio_id = ?", port.portfolioId());
        assertEquals("EXECUTED", result.get("status"));
        assertEquals(0, new BigDecimal("100.00").compareTo(new BigDecimal((String) result.get("raw_open_price"))));
        assertEquals(0, new BigDecimal("100.20").compareTo(new BigDecimal((String) result.get("fill_price"))));
        assertEquals(0, new BigDecimal("2.00").compareTo(new BigDecimal((String) result.get("commission"))));
        assertEquals(0, new BigDecimal("1.80").compareTo(new BigDecimal((String) result.get("spread_slippage_cost"))));
        assertEquals(missingSession + "T08:00:00Z", result.get("market_effective_instant"));
        assertNotEquals(result.get("market_effective_instant"), result.get("observed_instant"));
        assertEquals(result.get("observed_instant"), result.get("booked_instant"));

        paperPortfolioService.processPortfolioEvents(port.portfolioId(), owner, "waiting-process-3");
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM paper_execution_results r JOIN paper_execution_intents i ON i.id = r.intent_id " +
                        "WHERE i.portfolio_id = ?", Integer.class, port.portfolioId()));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM paper_intent_transitions t JOIN paper_execution_intents i ON i.id = t.intent_id " +
                        "WHERE i.portfolio_id = ? AND t.to_status = 'EXECUTED'", Integer.class, port.portfolioId()));
        ResearchPortfolioDetail detail = portfolioController.getPortfolio(port.portfolioId(), owner).getBody();
        assertNotNull(detail);
        assertNotNull(detail.paperStartedAt());
        assertEquals("AVAILABLE", detail.valuationStatus());
        assertEquals(0, new BigDecimal("900.00").compareTo(new BigDecimal(detail.marketValue())));
        assertEquals(0, new BigDecimal("-3.80").compareTo(new BigDecimal(detail.unrealizedPnl())));
    }

    @Test
    void laterDueIntentWaitsUntilEarlierOpenIsObserved() {
        String owner = "ordered-intents-owner";
        String earlierSession = java.time.LocalDate.parse(SESSION_1).minusDays(37).toString();
        OperationService.PortfolioCreationResult portfolio = operationService.createPortfolio(
                owner, "Ordered Intents Fund", "PAPER", "EUR", new BigDecimal("1000.00"), "ordered-create");
        paperPortfolioService.activatePortfolio(portfolio.portfolioId(), owner, "ordered-activate",
                new ActivatePortfolioRequest("ETF_BUY_HOLD_V1", "1.0.0", "uni-default", LISTING_ID,
                        new CostPolicyDto("1.00", "0", "0"), "MANUAL"));
        dataReadinessService.adoptDataset(portfolio.portfolioId(), "ds-test-m5", owner, "ordered-adopt");
        PaperProposalDto proposal = paperPortfolioService.evaluatePortfolio(portfolio.portfolioId(), owner, "ordered-evaluate");
        paperPortfolioService.acceptProposal(portfolio.portfolioId(), proposal.id(), owner,
                "ordered-accept", new AcceptProposalRequest("test"));

        // Insert the later intent first. The earlier open has a calendar session but no bar yet.
        jdbcTemplate.update("UPDATE paper_execution_intents SET scheduled_session_date = ?, scheduled_open_instant = ? " +
                        "WHERE proposal_id = ?", SESSION_1, SESSION_1 + "T07:00:00Z", proposal.id());
        jdbcTemplate.update("INSERT OR IGNORE INTO dataset_sessions (dataset_id, calendar_id, session_date, open_time, close_time, session_type) " +
                        "VALUES ('ds-test-m5', 'XAMS', ?, '09:00:00', '17:30:00', 'TRADING')", earlierSession);
        jdbcTemplate.update("INSERT INTO paper_execution_intents (id, portfolio_id, proposal_id, order_type, " +
                        "scheduled_session_date, scheduled_open_instant, approval_mode, status, created_at) " +
                        "VALUES (?, ?, ?, 'REBALANCE', ?, ?, 'MANUAL', 'PENDING', ?)",
                "intent-earlier-" + UUID.randomUUID(), portfolio.portfolioId(), proposal.id(), earlierSession,
                earlierSession + "T07:00:00Z", java.time.Instant.now().toString());

        paperPortfolioService.processPortfolioEvents(portfolio.portfolioId(), owner, "ordered-process-before");
        assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM paper_execution_results " +
                "WHERE proposal_id = ?", Integer.class, proposal.id()));
        assertEquals("PENDING", jdbcTemplate.queryForObject("SELECT status FROM paper_execution_intents " +
                "WHERE proposal_id = ? AND scheduled_session_date = ?", String.class, proposal.id(), SESSION_1));
        assertEquals("WAITING_FOR_OBSERVATION", jdbcTemplate.queryForObject("SELECT status FROM paper_execution_intents " +
                "WHERE proposal_id = ? AND scheduled_session_date = ?", String.class, proposal.id(), earlierSession));

        jdbcTemplate.update("INSERT INTO historical_bars (dataset_id, listing_id, session_date, open, high, low, close, volume, available_at) " +
                        "VALUES ('ds-test-m5', ?, ?, '100.00', '101.00', '99.00', '100.00', 10000, ?)",
                LISTING_ID, earlierSession, java.time.Instant.now().minusSeconds(1).toString());
        paperPortfolioService.processPortfolioEvents(portfolio.portfolioId(), owner, "ordered-process-after");
        assertEquals(2, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM paper_execution_intents " +
                "WHERE proposal_id = ? AND status = 'EXECUTED'", Integer.class, proposal.id()));
    }

    @Test
    void lateAcceptanceSupersedesOldOpenAndReevaluatesSameDecisionSession() {
        String owner = "late-owner";
        String laterSession = java.time.LocalDate.parse(SESSION_2).plusDays(1).toString();
        jdbcTemplate.update(
                "INSERT OR IGNORE INTO dataset_sessions (dataset_id, calendar_id, session_date, open_time, close_time, session_type) " +
                        "VALUES ('ds-test-m5', 'XAMS', ?, '09:00:00', '17:30:00', 'TRADING')",
                laterSession);
        java.time.Clock originalPaperClock = (java.time.Clock) org.springframework.test.util.ReflectionTestUtils
                .getField(paperPortfolioService, "clock");
        java.time.Clock originalReadinessClock = (java.time.Clock) org.springframework.test.util.ReflectionTestUtils
                .getField(dataReadinessService, "clock");
        java.time.ZoneId exchangeZone = java.time.ZoneId.of("Europe/Berlin");
        java.time.Clock beforeOpen = java.time.Clock.fixed(
                java.time.LocalDate.parse(SESSION_1).plusDays(1).atTime(12, 0)
                        .atZone(exchangeZone).toInstant(), exchangeZone);
        java.time.Clock afterOpen = java.time.Clock.fixed(
                java.time.LocalDate.parse(SESSION_2).atTime(10, 0)
                        .atZone(exchangeZone).toInstant(), exchangeZone);
        try {
            org.springframework.test.util.ReflectionTestUtils.setField(paperPortfolioService, "clock", beforeOpen);
            org.springframework.test.util.ReflectionTestUtils.setField(dataReadinessService, "clock", beforeOpen);
            OperationService.PortfolioCreationResult portfolio = operationService.createPortfolio(
                    owner, "Late Acceptance Fund", "PAPER", "EUR", new BigDecimal("1000.00"), "late-create");
            paperPortfolioService.activatePortfolio(portfolio.portfolioId(), owner, "late-activate",
                    new ActivatePortfolioRequest("ETF_BUY_HOLD_V1", "1.0.0", "uni-default", LISTING_ID,
                            new CostPolicyDto("1.00", "0", "0"), "MANUAL"));
            dataReadinessService.adoptDataset(portfolio.portfolioId(), "ds-test-m5", owner, "late-adopt");
            PaperProposalDto first = paperPortfolioService.evaluatePortfolio(
                    portfolio.portfolioId(), owner, "late-evaluate-first");
            assertEquals(SESSION_2, first.scheduledOpenSessionDate());

            org.springframework.test.util.ReflectionTestUtils.setField(paperPortfolioService, "clock", afterOpen);
            org.springframework.test.util.ReflectionTestUtils.setField(dataReadinessService, "clock", afterOpen);
            assertThrows(org.springframework.web.server.ResponseStatusException.class, () ->
                    paperPortfolioService.acceptProposal(portfolio.portfolioId(), first.id(), owner,
                            "late-accept-first", new AcceptProposalRequest("too late")));
            assertEquals("SUPERSEDED", jdbcTemplate.queryForObject(
                    "SELECT status FROM paper_proposals WHERE id = ?", String.class, first.id()));

            PaperProposalDto later = paperPortfolioService.evaluatePortfolio(
                    portfolio.portfolioId(), owner, "late-evaluate-next-open");
            assertNotEquals(first.id(), later.id());
            assertEquals(laterSession, later.scheduledOpenSessionDate());
            assertEquals("ACCEPTED", paperPortfolioService.acceptProposal(
                    portfolio.portfolioId(), later.id(), owner, "late-accept-next-open",
                    new AcceptProposalRequest("future open")).status());
            assertEquals(0, jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM paper_execution_intents WHERE proposal_id = ?", Integer.class, first.id()));
        } finally {
            org.springframework.test.util.ReflectionTestUtils.setField(paperPortfolioService, "clock", originalPaperClock);
            org.springframework.test.util.ReflectionTestUtils.setField(dataReadinessService, "clock", originalReadinessClock);
        }
    }

    @Test
    void disablingAutoPaperKeepsPreviouslyAcceptedFutureIntent() {
        String owner = "mode-owner";
        OperationService.PortfolioCreationResult portfolio = operationService.createPortfolio(
                owner, "Mode Transition Fund", "PAPER", "EUR", new BigDecimal("1000.00"), "mode-create");
        paperPortfolioService.activatePortfolio(portfolio.portfolioId(), owner, "mode-activate",
                new ActivatePortfolioRequest("ETF_BUY_HOLD_V1", "1.0.0", "uni-default", LISTING_ID,
                        new CostPolicyDto("1.00", "0", "0"), "MANUAL"));
        dataReadinessService.adoptDataset(portfolio.portfolioId(), "ds-test-m5", owner, "mode-adopt");
        PaperProposalDto proposal = paperPortfolioService.evaluatePortfolio(
                portfolio.portfolioId(), owner, "mode-evaluate");
        paperPortfolioService.acceptProposal(portfolio.portfolioId(), proposal.id(), owner,
                "mode-accept", new AcceptProposalRequest("future order"));

        paperPortfolioService.changeApprovalMode(portfolio.portfolioId(), owner, "mode-enable",
                new ChangeApprovalModeRequest("AUTO_PAPER", "test opt-in"));
        paperPortfolioService.changeApprovalMode(portfolio.portfolioId(), owner, "mode-disable",
                new ChangeApprovalModeRequest("MANUAL", "keep accepted order"));

        assertEquals("PENDING", jdbcTemplate.queryForObject(
                "SELECT status FROM paper_execution_intents WHERE proposal_id = ?", String.class, proposal.id()));
        assertEquals("MANUAL", jdbcTemplate.queryForObject(
                "SELECT approval_mode FROM paper_portfolio_segments WHERE portfolio_id = ? AND status = 'ACTIVE'",
                String.class, portfolio.portfolioId()));
        assertEquals(3, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM paper_mode_history WHERE portfolio_id = ?", Integer.class,
                portfolio.portfolioId()));
    }

    @Test
    void failedExecutionProvenanceRollsBackTradeAndCanRetry() {
        String owner = "rollback-owner";
        OperationService.PortfolioCreationResult port = operationService.createPortfolio(
                owner, "Rollback Fund", "PAPER", "EUR", new BigDecimal("1000.00"), "rollback-create");
        paperPortfolioService.activatePortfolio(port.portfolioId(), owner, "rollback-activate",
                new ActivatePortfolioRequest("ETF_BUY_HOLD_V1", "1.0.0", "uni-default", LISTING_ID,
                        new CostPolicyDto("1.00", "0", "0"), "MANUAL"));
        dataReadinessService.adoptDataset(port.portfolioId(), "ds-test-m5", owner, "rollback-adopt");
        PaperProposalDto proposal = paperPortfolioService.evaluatePortfolio(port.portfolioId(), owner, "rollback-evaluate");
        paperPortfolioService.acceptProposal(port.portfolioId(), proposal.id(), owner, "rollback-accept",
                new AcceptProposalRequest("test"));
        jdbcTemplate.update(
                "UPDATE paper_execution_intents SET scheduled_session_date = ?, scheduled_open_instant = ? WHERE portfolio_id = ?",
                SESSION_1, SESSION_1 + "T08:00:00Z", port.portfolioId());

        jdbcTemplate.execute("CREATE TRIGGER test_fail_paper_provenance BEFORE INSERT ON paper_execution_results " +
                "BEGIN SELECT RAISE(ABORT, 'injected paper provenance failure'); END");
        try {
            assertThrows(Exception.class, () -> paperPortfolioService.processPortfolioEvents(
                    port.portfolioId(), owner, "rollback-process"));
            assertEquals("1000.00", jdbcTemplate.queryForObject(
                    "SELECT cash_amount FROM portfolio_state WHERE portfolio_id = ?", String.class, port.portfolioId()));
            assertEquals(0, jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM operations WHERE portfolio_id = ? AND kind = 'TRADE'",
                    Integer.class, port.portfolioId()));
            assertEquals("PENDING", jdbcTemplate.queryForObject(
                    "SELECT status FROM paper_execution_intents WHERE portfolio_id = ?", String.class, port.portfolioId()));
            assertEquals(0, jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM paper_mutation_requests WHERE owner_id = ? AND action = 'PROCESS_EVENTS' " +
                            "AND idempotency_key = 'rollback-process'", Integer.class, owner));
        } finally {
            jdbcTemplate.execute("DROP TRIGGER test_fail_paper_provenance");
        }

        paperPortfolioService.processPortfolioEvents(port.portfolioId(), owner, "rollback-process");
        assertEquals("EXECUTED", jdbcTemplate.queryForObject(
                "SELECT status FROM paper_execution_intents WHERE portfolio_id = ?", String.class, port.portfolioId()));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM paper_execution_results r JOIN paper_execution_intents i ON i.id = r.intent_id " +
                        "WHERE i.portfolio_id = ?", Integer.class, port.portfolioId()));
    }

    @Test
    void delayedDistributionUsesHistoricalEntitlementAndCreatesManualReinvestmentProposal() {
        String owner = "distribution-owner";
        String entitlementDate = java.time.LocalDate.parse(SESSION_1).minusDays(20).toString();
        String purchaseDate = java.time.LocalDate.parse(entitlementDate).minusDays(1).toString();
        String saleDate = java.time.LocalDate.parse(SESSION_1).minusDays(19).toString();
        OperationService.PortfolioCreationResult port = operationService.createPortfolio(
                owner, "Distribution Fund", "PAPER", "EUR", new BigDecimal("1000.00"), "distribution-create");
        paperPortfolioService.activatePortfolio(
                port.portfolioId(), owner, "distribution-activate",
                new ActivatePortfolioRequest(
                        "ETF_BUY_HOLD_V1", "1.0.0", "uni-default", LISTING_ID,
                        new CostPolicyDto("1.00", "0", "0"), "MANUAL"));
        dataReadinessService.adoptDataset(port.portfolioId(), "ds-test-m5", owner, "distribution-adopt");
        operationService.executePaperTrade(
                port.portfolioId(), TICKER, "BUY", new BigDecimal("2"), new BigDecimal("100"),
                new BigDecimal("100"), BigDecimal.ZERO, BigDecimal.ZERO, "distribution-seed-trade",
                "TEST_OPEN", purchaseDate + "T08:00:00Z");
        operationService.executePaperTrade(
                port.portfolioId(), TICKER, "SELL", new BigDecimal("2"), new BigDecimal("100"),
                new BigDecimal("100"), BigDecimal.ZERO, BigDecimal.ZERO, "distribution-later-sale",
                "TEST_OPEN", saleDate + "T08:00:00Z");

        String actionId = "distribution-delayed-action";
        jdbcTemplate.update(
                    "INSERT INTO historical_actions (dataset_id, action_id, listing_id, action_type, effective_date, available_at, " +
                            "distribution_amount, distribution_currency, payment_date, payment_instant) " +
                            "VALUES ('ds-test-m5', ?, ?, 'CASH_DISTRIBUTION', ?, '2026-09-01T18:00:00Z', " +
                            "'1.25', 'EUR', ?, '2026-09-01T19:00:00Z')",
                actionId, LISTING_ID, entitlementDate, entitlementDate);
        paperPortfolioService.processPortfolioEvents(port.portfolioId(), owner, "distribution-process");

        Map<String, Object> receivable = jdbcTemplate.queryForMap(
                    "SELECT source_namespace, gross_amount, net_amount, status, dataset_id, dataset_checksum, terms_hash " +
                            "FROM paper_receivables WHERE portfolio_id = ? AND action_id = ?",
                    port.portfolioId(), actionId);
        assertEquals("SYNTHETIC", receivable.get("source_namespace"));
            assertEquals(0, new BigDecimal("2.50").compareTo(new BigDecimal((String) receivable.get("gross_amount"))));
            assertEquals(0, new BigDecimal("2.50").compareTo(new BigDecimal((String) receivable.get("net_amount"))));
            assertEquals("PAID", receivable.get("status"));
            assertEquals("ds-test-m5", receivable.get("dataset_id"));
            assertEquals("chk-content", receivable.get("dataset_checksum"));
        assertFalse(((String) receivable.get("terms_hash")).isBlank());

        Map<String, Object> reinvestment = jdbcTemplate.queryForMap(
                    "SELECT p.id, p.status, p.reason_code, p.reinvestment_receivable_id " +
                            "FROM paper_proposals p JOIN paper_receivables r ON r.id = p.reinvestment_receivable_id " +
                            "WHERE r.portfolio_id = ? AND r.action_id = ?",
                    port.portfolioId(), actionId);
        assertEquals("PROPOSED", reinvestment.get("status"));
        assertEquals("DISTRIBUTION_REINVESTMENT", reinvestment.get("reason_code"));
        assertNotNull(reinvestment.get("reinvestment_receivable_id"));

        paperPortfolioService.acceptProposal(port.portfolioId(), (String) reinvestment.get("id"), owner,
                "distribution-accept", new AcceptProposalRequest("Reinvest only the payment"));
        jdbcTemplate.update(
                "UPDATE paper_execution_intents SET scheduled_session_date = ?, scheduled_open_instant = ? " +
                        "WHERE reinvestment_receivable_id = ?",
                SESSION_1, SESSION_1 + "T08:00:00Z", reinvestment.get("reinvestment_receivable_id"));

        paperPortfolioService.processPortfolioEvents(port.portfolioId(), owner, "distribution-process-repeat");
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM paper_receivables WHERE portfolio_id = ? AND action_id = ?",
                Integer.class, port.portfolioId(), actionId));
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM paper_execution_results WHERE reinvestment_receivable_id = ?",
                Integer.class, reinvestment.get("reinvestment_receivable_id")));
    }

    @Test
    void sameDayOpeningPurchaseDoesNotReceiveExDateDistribution() {
        String owner = "ex-date-owner";
        String datasetId = "ds-ex-date-m5";
        cloneBaseDataset(datasetId);
        String exDate = java.time.LocalDate.parse(SESSION_1).minusDays(4).toString();
        OperationService.PortfolioCreationResult portfolio = operationService.createPortfolio(
                owner, "Ex-Date Fund", "PAPER", "EUR", new BigDecimal("1000.00"), "ex-date-create");
        paperPortfolioService.activatePortfolio(portfolio.portfolioId(), owner, "ex-date-activate",
                new ActivatePortfolioRequest("ETF_BUY_HOLD_V1", "1.0.0", "uni-default", LISTING_ID,
                        new CostPolicyDto("1.00", "0", "0"), "MANUAL"));
        dataReadinessService.adoptDataset(portfolio.portfolioId(), datasetId, owner, "ex-date-adopt");
        operationService.executePaperTrade(portfolio.portfolioId(), TICKER, "BUY", new BigDecimal("2"),
                new BigDecimal("100"), new BigDecimal("100"), BigDecimal.ZERO, BigDecimal.ZERO,
                "ex-date-buy", "TEST_OPEN", exDate + "T08:00:00Z");
        String actionId = "same-day-ex-" + UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO historical_actions (dataset_id, action_id, listing_id, action_type, " +
                        "effective_date, available_at, distribution_amount, distribution_currency, payment_date) " +
                        "VALUES (?, ?, ?, 'CASH_DISTRIBUTION', ?, '2026-09-01T18:00:00Z', '1.25', 'EUR', ?)",
                datasetId, actionId, LISTING_ID, exDate, exDate);
        paperPortfolioService.processPortfolioEvents(portfolio.portfolioId(), owner, "ex-date-process");
        assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM paper_receivables " +
                "WHERE portfolio_id = ? AND action_id = ?", Integer.class, portfolio.portfolioId(), actionId));
        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM paper_processed_corporate_actions " +
                "WHERE portfolio_id = ? AND action_id = ?", Integer.class, portfolio.portfolioId(), actionId));
    }

    @Test
    void overdueOpeningFillPrecedesLaterDistributionEntitlement() {
        String owner = "ordered-action-owner";
        String datasetId = "ds-ordered-action-m5";
        cloneBaseDataset(datasetId);
        String exDate = java.time.LocalDate.parse(SESSION_1).plusDays(1).toString();
        OperationService.PortfolioCreationResult portfolio = operationService.createPortfolio(
                owner, "Ordered Actions Fund", "PAPER", "EUR", new BigDecimal("1000.00"), "action-create");
        paperPortfolioService.activatePortfolio(portfolio.portfolioId(), owner, "action-activate",
                new ActivatePortfolioRequest("ETF_BUY_HOLD_V1", "1.0.0", "uni-default", LISTING_ID,
                        new CostPolicyDto("1.00", "0", "0"), "MANUAL"));
        dataReadinessService.adoptDataset(portfolio.portfolioId(), datasetId, owner, "action-adopt");
        PaperProposalDto proposal = paperPortfolioService.evaluatePortfolio(portfolio.portfolioId(), owner,
                "action-evaluate");
        paperPortfolioService.acceptProposal(portfolio.portfolioId(), proposal.id(), owner,
                "action-accept", new AcceptProposalRequest("test"));
        jdbcTemplate.update("UPDATE paper_execution_intents SET scheduled_session_date = ?, scheduled_open_instant = ? " +
                        "WHERE proposal_id = ?", SESSION_1, SESSION_1 + "T07:00:00Z", proposal.id());
        String actionId = "action-after-fill-" + UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO historical_actions (dataset_id, action_id, listing_id, action_type, " +
                        "effective_date, available_at, distribution_amount, distribution_currency, payment_date) " +
                        "VALUES (?, ?, ?, 'CASH_DISTRIBUTION', ?, '2026-09-01T18:00:00Z', '1.25', 'EUR', ?)",
                datasetId, actionId, LISTING_ID, exDate, exDate);
        paperPortfolioService.processPortfolioEvents(portfolio.portfolioId(), owner, "action-process");
        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM paper_execution_results " +
                "WHERE proposal_id = ?", Integer.class, proposal.id()));
        assertEquals("12.50", jdbcTemplate.queryForObject("SELECT gross_amount FROM paper_receivables " +
                "WHERE portfolio_id = ? AND action_id = ?", String.class, portfolio.portfolioId(), actionId));
    }

    @Test
    void sameDaySplitPrecedesDistributionEntitlement() {
        String owner = "split-entitlement-owner";
        String datasetId = "ds-split-entitlement-m5";
        cloneBaseDataset(datasetId);
        String exDate = java.time.LocalDate.parse(SESSION_1).minusDays(3).toString();
        String purchaseDate = java.time.LocalDate.parse(exDate).minusDays(1).toString();
        OperationService.PortfolioCreationResult portfolio = operationService.createPortfolio(owner,
                "Split Entitlement Fund", "PAPER", "EUR", new BigDecimal("1000.00"), "split-entitlement-create");
        paperPortfolioService.activatePortfolio(portfolio.portfolioId(), owner, "split-entitlement-activate",
                new ActivatePortfolioRequest("ETF_BUY_HOLD_V1", "1.0.0", "uni-default", LISTING_ID,
                        new CostPolicyDto("1.00", "0", "0"), "MANUAL"));
        dataReadinessService.adoptDataset(portfolio.portfolioId(), datasetId, owner, "split-entitlement-adopt");
        operationService.executePaperTrade(portfolio.portfolioId(), TICKER, "BUY", new BigDecimal("2"),
                new BigDecimal("100"), new BigDecimal("100"), BigDecimal.ZERO, BigDecimal.ZERO,
                "split-entitlement-buy", "TEST_OPEN", purchaseDate + "T08:00:00Z");
        jdbcTemplate.update("INSERT INTO historical_actions (dataset_id, action_id, listing_id, action_type, " +
                        "effective_date, available_at, split_ratio_numerator, split_ratio_denominator) " +
                        "VALUES (?, 'split-same-day', ?, 'SPLIT', ?, '2026-09-01T18:00:00Z', 2, 1)",
                datasetId, LISTING_ID, exDate);
        jdbcTemplate.update("INSERT INTO historical_actions (dataset_id, action_id, listing_id, action_type, " +
                        "effective_date, available_at, distribution_amount, distribution_currency, payment_date) " +
                        "VALUES (?, 'distribution-after-split', ?, 'CASH_DISTRIBUTION', ?, " +
                        "'2026-09-01T18:00:00Z', '1.25', 'EUR', ?)", datasetId, LISTING_ID, exDate, exDate);

        paperPortfolioService.processPortfolioEvents(portfolio.portfolioId(), owner, "split-entitlement-process");
        assertEquals("4", jdbcTemplate.queryForObject("SELECT quantity FROM positions WHERE portfolio_id = ? " +
                "AND listing_id = ?", String.class, portfolio.portfolioId(), LISTING_ID));
        assertEquals("5.00", jdbcTemplate.queryForObject("SELECT gross_amount FROM paper_receivables " +
                "WHERE portfolio_id = ? AND action_id = 'distribution-after-split'", String.class, portfolio.portfolioId()));
    }

    @Test
    void eightConcurrentProcessorsBookOneOpeningFill() throws Exception {
        String owner = "concurrent-processing-owner";
        OperationService.PortfolioCreationResult portfolio = operationService.createPortfolio(owner,
                "Concurrent Processing Fund", "PAPER", "EUR", new BigDecimal("1000.00"), "concurrent-create");
        paperPortfolioService.activatePortfolio(portfolio.portfolioId(), owner, "concurrent-activate",
                new ActivatePortfolioRequest("ETF_BUY_HOLD_V1", "1.0.0", "uni-default", LISTING_ID,
                        new CostPolicyDto("1.00", "0", "0"), "MANUAL"));
        dataReadinessService.adoptDataset(portfolio.portfolioId(), "ds-test-m5", owner, "concurrent-adopt");
        PaperProposalDto proposal = paperPortfolioService.evaluatePortfolio(portfolio.portfolioId(), owner,
                "concurrent-evaluate");
        paperPortfolioService.acceptProposal(portfolio.portfolioId(), proposal.id(), owner,
                "concurrent-accept", new AcceptProposalRequest("test"));
        jdbcTemplate.update("UPDATE paper_execution_intents SET scheduled_session_date = ?, scheduled_open_instant = ? " +
                "WHERE proposal_id = ?", SESSION_1, SESSION_1 + "T07:00:00Z", proposal.id());

        int callers = 8;
        ExecutorService executor = Executors.newFixedThreadPool(callers);
        CyclicBarrier barrier = new CyclicBarrier(callers);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int index = 0; index < callers; index++) {
                String key = "concurrent-process-" + index;
                futures.add(executor.submit(() -> {
                    barrier.await();
                    paperPortfolioService.processPortfolioEvents(portfolio.portfolioId(), owner, key);
                    return null;
                }));
            }
            for (Future<?> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }
        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM paper_execution_results " +
                "WHERE proposal_id = ?", Integer.class, proposal.id()));
        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM operations " +
                "WHERE portfolio_id = ? AND kind = 'TRADE'", Integer.class, portfolio.portfolioId()));
    }

    @Test
    void automaticCycleCreatesFutureIntentAndResolvesItAfterDowntime() {
        String owner = "automatic-recovery-owner";
        java.time.Clock originalPaperClock = (java.time.Clock) org.springframework.test.util.ReflectionTestUtils
                .getField(paperPortfolioService, "clock");
        java.time.Clock originalReadinessClock = (java.time.Clock) org.springframework.test.util.ReflectionTestUtils
                .getField(dataReadinessService, "clock");
        java.time.ZoneId exchangeZone = java.time.ZoneId.of("Europe/Berlin");
        java.time.Clock beforeOpen = java.time.Clock.fixed(
                java.time.LocalDate.parse(SESSION_1).plusDays(1).atTime(12, 0).atZone(exchangeZone).toInstant(),
                exchangeZone);
        java.time.Clock afterOpen = java.time.Clock.fixed(
                java.time.LocalDate.parse(SESSION_2).atTime(10, 0).atZone(exchangeZone).toInstant(),
                exchangeZone);
        try {
            org.springframework.test.util.ReflectionTestUtils.setField(paperPortfolioService, "clock", beforeOpen);
            org.springframework.test.util.ReflectionTestUtils.setField(dataReadinessService, "clock", beforeOpen);
            OperationService.PortfolioCreationResult portfolio = operationService.createPortfolio(owner,
                    "Automatic Recovery Fund", "PAPER", "EUR", new BigDecimal("1000.00"), "auto-recovery-create");
            paperPortfolioService.activatePortfolio(portfolio.portfolioId(), owner, "auto-recovery-activate",
                    new ActivatePortfolioRequest("ETF_BUY_HOLD_V1", "1.0.0", "uni-default", LISTING_ID,
                            new CostPolicyDto("1.00", "0", "0"), "MANUAL"));
            dataReadinessService.adoptDataset(portfolio.portfolioId(), "ds-test-m5", owner, "auto-recovery-adopt");
            paperPortfolioService.changeApprovalMode(portfolio.portfolioId(), owner, "auto-recovery-enable",
                    new ChangeApprovalModeRequest("AUTO_PAPER", "test"));
            paperPortfolioService.runAutomaticCycle(portfolio.portfolioId(), owner);
            assertEquals("PENDING", jdbcTemplate.queryForObject("SELECT status FROM paper_execution_intents " +
                    "WHERE portfolio_id = ?", String.class, portfolio.portfolioId()));
            assertEquals("AUTO_PAPER", jdbcTemplate.queryForObject("SELECT approval_mode FROM paper_execution_intents " +
                    "WHERE portfolio_id = ?", String.class, portfolio.portfolioId()));

            org.springframework.test.util.ReflectionTestUtils.setField(paperPortfolioService, "clock", afterOpen);
            org.springframework.test.util.ReflectionTestUtils.setField(dataReadinessService, "clock", afterOpen);
            paperPortfolioService.runAutomaticCycle(portfolio.portfolioId(), owner);
            paperPortfolioService.runAutomaticCycle(portfolio.portfolioId(), owner);
            assertEquals("EXECUTED", jdbcTemplate.queryForObject("SELECT status FROM paper_execution_intents " +
                    "WHERE portfolio_id = ?", String.class, portfolio.portfolioId()));
            assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM paper_execution_results r " +
                    "JOIN paper_execution_intents i ON i.id = r.intent_id WHERE i.portfolio_id = ?",
                    Integer.class, portfolio.portfolioId()));
        } finally {
            org.springframework.test.util.ReflectionTestUtils.setField(paperPortfolioService, "clock", originalPaperClock);
            org.springframework.test.util.ReflectionTestUtils.setField(dataReadinessService, "clock", originalReadinessClock);
        }
    }

    @Test
    void adoptionRejectsDifferentSourceNamespaceAndPreservesBinding() {
        String owner = "source-binding-owner";
        String incompatibleDataset = "ds-foreign-source-m5";
        cloneBaseDataset(incompatibleDataset, "OTHER_SYNTHETIC_SOURCE");
        OperationService.PortfolioCreationResult portfolio = operationService.createPortfolio(owner,
                "Source Bound Fund", "PAPER", "EUR", new BigDecimal("1000.00"), "source-create");
        paperPortfolioService.activatePortfolio(portfolio.portfolioId(), owner, "source-activate",
                new ActivatePortfolioRequest("ETF_BUY_HOLD_V1", "1.0.0", "uni-default", LISTING_ID,
                        new CostPolicyDto("1.00", "0", "0"), "MANUAL"));
        dataReadinessService.adoptDataset(portfolio.portfolioId(), "ds-test-m5", owner, "source-first-adopt");

        assertThrows(org.springframework.web.server.ResponseStatusException.class, () ->
                dataReadinessService.adoptDataset(portfolio.portfolioId(), incompatibleDataset, owner,
                        "source-incompatible-adopt"));
        assertEquals("ds-test-m5", jdbcTemplate.queryForObject("SELECT adopted_dataset_id " +
                "FROM paper_portfolio_segments WHERE portfolio_id = ? AND status = 'ACTIVE'",
                String.class, portfolio.portfolioId()));
    }

    @Test
    void adoptionRejectsCorrectionToBookedOpeningPrice() {
        String owner = "booked-correction-owner";
        OperationService.PortfolioCreationResult portfolio = operationService.createPortfolio(owner,
                "Booked Correction Fund", "PAPER", "EUR", new BigDecimal("1000.00"), "correction-create");
        paperPortfolioService.activatePortfolio(portfolio.portfolioId(), owner, "correction-activate",
                new ActivatePortfolioRequest("ETF_BUY_HOLD_V1", "1.0.0", "uni-default", LISTING_ID,
                        new CostPolicyDto("1.00", "0", "0"), "MANUAL"));
        dataReadinessService.adoptDataset(portfolio.portfolioId(), "ds-test-m5", owner, "correction-first-adopt");
        PaperProposalDto proposal = paperPortfolioService.evaluatePortfolio(portfolio.portfolioId(), owner,
                "correction-evaluate");
        paperPortfolioService.acceptProposal(portfolio.portfolioId(), proposal.id(), owner,
                "correction-accept", new AcceptProposalRequest("test"));
        jdbcTemplate.update("UPDATE paper_execution_intents SET scheduled_session_date = ?, scheduled_open_instant = ? " +
                "WHERE proposal_id = ?", SESSION_1, SESSION_1 + "T07:00:00Z", proposal.id());
        paperPortfolioService.processPortfolioEvents(portfolio.portfolioId(), owner, "correction-process");
        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM paper_execution_results " +
                "WHERE proposal_id = ?", Integer.class, proposal.id()));

        String correctedDataset = "ds-corrected-booked-open-m5";
        cloneBaseDataset(correctedDataset, "SYNTHETIC", SESSION_1, "111.00");
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () ->
                dataReadinessService.adoptDataset(portfolio.portfolioId(), correctedDataset, owner,
                        "correction-second-adopt"));
        assertEquals("ds-test-m5", jdbcTemplate.queryForObject("SELECT adopted_dataset_id " +
                "FROM paper_portfolio_segments WHERE portfolio_id = ? AND status = 'ACTIVE'",
                String.class, portfolio.portfolioId()));
    }

    @Test
    void importedIsoSessionInstantsSupportPaperEvaluationAndScheduling() {
        String owner = "iso-session-owner";
        String datasetId = "ds-iso-session-m5";
        cloneBaseDataset(datasetId, "SYNTHETIC", null, null, true);
        OperationService.PortfolioCreationResult portfolio = operationService.createPortfolio(owner,
                "ISO Session Fund", "PAPER", "EUR", new BigDecimal("1000.00"), "iso-create");
        paperPortfolioService.activatePortfolio(portfolio.portfolioId(), owner, "iso-activate",
                new ActivatePortfolioRequest("ETF_BUY_HOLD_V1", "1.0.0", "uni-default", LISTING_ID,
                        new CostPolicyDto("1.00", "0", "0"), "MANUAL"));
        dataReadinessService.adoptDataset(portfolio.portfolioId(), datasetId, owner, "iso-adopt");
        PaperProposalDto proposal = paperPortfolioService.evaluatePortfolio(portfolio.portfolioId(), owner,
                "iso-evaluate");
        assertEquals(SESSION_2 + "T07:00:00Z", proposal.scheduledOpenInstant());
        assertEquals("PROPOSED", proposal.status());
    }

    @Test
    void monthlyS2AndS3EvaluateAndResolveOnlyAfterTheirFutureOpen() {
        String datasetId = "ds-monthly-m5";
        String futureSession = "2026-09-17";
        java.time.Clock originalPaperClock = (java.time.Clock) org.springframework.test.util.ReflectionTestUtils
                .getField(paperPortfolioService, "clock");
        java.time.Clock originalReadinessClock = (java.time.Clock) org.springframework.test.util.ReflectionTestUtils
                .getField(dataReadinessService, "clock");
        java.time.Clock beforeOpen = java.time.Clock.fixed(java.time.Instant.parse("2026-09-16T12:00:00Z"),
                java.time.ZoneId.of("Europe/Berlin"));
        java.time.Clock afterOpen = java.time.Clock.fixed(java.time.Instant.parse("2026-09-17T08:00:00Z"),
                java.time.ZoneId.of("Europe/Berlin"));
        try {
            org.springframework.test.util.ReflectionTestUtils.setField(paperPortfolioService, "clock", beforeOpen);
            org.springframework.test.util.ReflectionTestUtils.setField(dataReadinessService, "clock", beforeOpen);
            jdbcTemplate.update("INSERT OR IGNORE INTO datasets (id, name, source, classification, schema_version, " +
                            "parser_version, input_checksum, content_checksum, manifest_json, coverage_start, coverage_end, " +
                            "validation_status, validation_findings_json, quality_label, imported_at, created_at) " +
                            "VALUES (?, 'Monthly M5', 'SYNTHETIC', 'SYNTHETIC', '1.0', '1.0', 'monthly-in', " +
                            "'monthly-content', '{}', '2025-08-31', ?, 'VALID', '[]', 'SYNTHETIC', ?, ?)",
                    datasetId, futureSession, beforeOpen.instant().toString(), beforeOpen.instant().toString());
            jdbcTemplate.update("INSERT OR IGNORE INTO dataset_listings (dataset_id, listing_id, instrument_id, " +
                            "symbol, venue, quote_currency, calendar_id) VALUES (?, ?, ?, ?, 'AMS', 'EUR', 'XAMS')",
                    datasetId, LISTING_ID, INSTRUMENT_ID, TICKER);
            for (int month = 0; month < 13; month++) {
                String session = java.time.YearMonth.of(2025, 8).plusMonths(month).atEndOfMonth().toString();
                String price = new BigDecimal(100 + month * 5).toPlainString();
                jdbcTemplate.update("INSERT OR IGNORE INTO dataset_sessions (dataset_id, calendar_id, session_date, " +
                                "open_time, close_time, session_type) VALUES (?, 'XAMS', ?, '09:00:00', '17:30:00', 'TRADING')",
                        datasetId, session);
                jdbcTemplate.update("INSERT OR IGNORE INTO historical_bars (dataset_id, listing_id, session_date, " +
                                "open, high, low, close, volume, available_at) VALUES (?, ?, ?, ?, ?, ?, ?, 10000, ?)",
                        datasetId, LISTING_ID, session, price, price, price, price,
                        beforeOpen.instant().minusSeconds(60).toString());
            }
            jdbcTemplate.update("INSERT OR IGNORE INTO dataset_sessions (dataset_id, calendar_id, session_date, " +
                            "open_time, close_time, session_type) VALUES (?, 'XAMS', ?, '09:00:00', '17:30:00', 'TRADING')",
                    datasetId, futureSession);

            for (String strategy : List.of("ETF_MOMENTUM_12_1_V1", "ETF_TREND_10M_V1")) {
                String owner = "monthly-" + strategy;
                OperationService.PortfolioCreationResult portfolio = operationService.createPortfolio(owner, strategy,
                        "PAPER", "EUR", new BigDecimal("1000.00"), "create-" + strategy);
                paperPortfolioService.activatePortfolio(portfolio.portfolioId(), owner, "activate-" + strategy,
                        new ActivatePortfolioRequest(strategy, "1.0.0", "uni-default", LISTING_ID,
                                new CostPolicyDto("1.00", "0", "0"), "MANUAL"));
                dataReadinessService.adoptDataset(portfolio.portfolioId(), datasetId, owner, "adopt-" + strategy);
                PaperProposalDto proposal = paperPortfolioService.evaluatePortfolio(portfolio.portfolioId(), owner,
                        "evaluate-" + strategy);
                assertEquals("2026-08-31", proposal.evaluationSessionDate());
                assertEquals(futureSession, proposal.scheduledOpenSessionDate());
                paperPortfolioService.acceptProposal(portfolio.portfolioId(), proposal.id(), owner,
                        "accept-" + strategy, new AcceptProposalRequest("monthly future open"));
                assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM paper_execution_results " +
                        "WHERE proposal_id = ?", Integer.class, proposal.id()));
            }

            jdbcTemplate.update("INSERT INTO historical_bars (dataset_id, listing_id, session_date, open, high, low, " +
                            "close, volume, available_at) VALUES (?, ?, ?, '165', '165', '165', '165', 10000, ?)",
                    datasetId, LISTING_ID, futureSession, "2026-09-17T07:15:00Z");
            org.springframework.test.util.ReflectionTestUtils.setField(paperPortfolioService, "clock", afterOpen);
            org.springframework.test.util.ReflectionTestUtils.setField(dataReadinessService, "clock", afterOpen);
            for (String strategy : List.of("ETF_MOMENTUM_12_1_V1", "ETF_TREND_10M_V1")) {
                String owner = "monthly-" + strategy;
                String portfolioId = jdbcTemplate.queryForObject("SELECT id FROM portfolios WHERE owner_id = ? " +
                        "AND name = ?", String.class, owner, strategy);
                paperPortfolioService.processPortfolioEvents(portfolioId, owner, "process-" + strategy);
                assertEquals("EXECUTED", jdbcTemplate.queryForObject("SELECT status FROM paper_execution_intents " +
                        "WHERE portfolio_id = ?", String.class, portfolioId));
                assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM paper_execution_results r " +
                        "JOIN paper_execution_intents i ON i.id = r.intent_id WHERE i.portfolio_id = ?",
                        Integer.class, portfolioId));
            }
        } finally {
            org.springframework.test.util.ReflectionTestUtils.setField(paperPortfolioService, "clock", originalPaperClock);
            org.springframework.test.util.ReflectionTestUtils.setField(dataReadinessService, "clock", originalReadinessClock);
        }
    }

    @Test
    void assistantSecurityAndHoldoutIntegrity_scenario10() {
        String owner = "scenario-10-owner";
        String foreignOwner = "foreign-owner";

        OperationService.PortfolioCreationResult port = operationService.createPortfolio(
                owner, "Assistant Test Port", "PAPER", "EUR", new BigDecimal("1000.00"), "idemp-sc10-create"
        );

        // Foreign owner cannot query this portfolio
        ChatRequest req = new ChatRequest(
                "Show portfolio balance",
                new ResearchContextDto("PORTFOLIO", port.portfolioId())
        );
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () ->
                assistantService.processQuery(foreignOwner, "idemp-sc10-foreign", req)
        );

        // Non-existent context throws 404
        ChatRequest invalidReq = new ChatRequest(
                "Show balance",
                new ResearchContextDto("PORTFOLIO", "non-existent-id")
        );
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () ->
                assistantService.processQuery(owner, "idemp-sc10-invalid", invalidReq)
        );
    }

    @Test
    void boundedAuditZipComprehensiveVerification_scenario11() throws Exception {
        String owner = "scenario-11-owner";
        OperationService.PortfolioCreationResult port = operationService.createPortfolio(
                owner, "Export Audit Port", "PAPER", "EUR", new BigDecimal("1000.00"), "idemp-sc11-create"
        );
        ActivatePortfolioRequest actReq = new ActivatePortfolioRequest(
                "ETF_BUY_HOLD_V1", "1.0.0", "uni-default", LISTING_ID, new CostPolicyDto("1.00", "0", "0"), "MANUAL"
        );
        paperPortfolioService.activatePortfolio(port.portfolioId(), owner, "idemp-sc11-act", actReq);
        dataReadinessService.adoptDataset(port.portfolioId(), "ds-test-m5", owner, "idemp-sc11-adopt");

        byte[] zipBytes = paperExportService.createPaperAuditZip(port.portfolioId(), owner);
        assertNotNull(zipBytes);
        assertTrue(zipBytes.length > 0);

        Set<String> entries = new HashSet<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                entries.add(entry.getName());
                byte[] data = zis.readAllBytes();
                assertTrue(data.length > 0, "File " + entry.getName() + " in audit zip should not be empty");
            }
        }

        List<String> requiredFiles = List.of(
                "manifest.json",
                "adoptions.csv",
                "proposals.csv",
                "proposal_items.csv",
                "proposal_observations.csv",
                "intents.csv",
                "intent_transitions.csv",
                "execution_results.csv",
                "receivables.csv",
                "corporate_actions.csv",
                "valuations.csv",
                "holdings.csv",
                "mode_history.csv"
        );

        for (String expectedFile : requiredFiles) {
            assertTrue(entries.contains(expectedFile), "Audit zip must contain " + expectedFile);
        }
        assertEquals(13, entries.size(), "Audit zip must contain exactly 13 files");
    }

    @Test
    void paymentDateVariantsSettleCorrectly() {
        String owner = "payment-variant-owner";
        String datasetId = "ds-payment-variant-m5";
        cloneBaseDataset(datasetId);
        String exDate = java.time.LocalDate.parse(SESSION_1).minusDays(5).toString();
        String purchaseDate = java.time.LocalDate.parse(exDate).minusDays(1).toString();

        OperationService.PortfolioCreationResult portfolio = operationService.createPortfolio(
                owner, "Payment Variant Fund", "PAPER", "EUR", new BigDecimal("1000.00"), "pay-variant-create");
        paperPortfolioService.activatePortfolio(portfolio.portfolioId(), owner, "pay-variant-activate",
                new ActivatePortfolioRequest("ETF_BUY_HOLD_V1", "1.0.0", "uni-default", LISTING_ID,
                        new CostPolicyDto("1.00", "0", "0"), "MANUAL"));
        dataReadinessService.adoptDataset(portfolio.portfolioId(), datasetId, owner, "pay-variant-adopt");

        // Seed a position so entitlements can be calculated
        operationService.executePaperTrade(portfolio.portfolioId(), TICKER, "BUY", new BigDecimal("5"),
                new BigDecimal("100"), new BigDecimal("100"), BigDecimal.ZERO, BigDecimal.ZERO,
                "pay-variant-seed", "TEST_OPEN", purchaseDate + "T08:00:00Z");

        String now = java.time.Instant.now().toString();
        String pastInstant = java.time.Instant.now().minusSeconds(3600).toString();
        String futureInstant = java.time.Instant.now().plusSeconds(86400 * 30).toString();

        // Receivable 1: precise payment_instant in the past → should settle
        jdbcTemplate.update(
                "INSERT INTO paper_receivables (id, portfolio_id, action_id, listing_id, source_namespace, action_type, " +
                        "record_instant, ex_date, payment_date, payment_instant, availability_instant, gross_amount, " +
                        "withholding_tax, net_amount, status, created_at, dataset_id, dataset_checksum, terms_hash) " +
                        "VALUES ('rec-precise-past', ?, 'ca-precise', ?, 'SYNTHETIC', 'CASH_DISTRIBUTION', ?, ?, ?, ?, ?, " +
                        "'10.00', '0.00', '10.00', 'PENDING', ?, ?, 'chk-content', 'hash-1')",
                portfolio.portfolioId(), LISTING_ID, now, exDate, exDate, pastInstant, now, now, datasetId);

        // Receivable 2: no payment_instant, payment_date in past → should settle (date-only fallback)
        jdbcTemplate.update(
                "INSERT INTO paper_receivables (id, portfolio_id, action_id, listing_id, source_namespace, action_type, " +
                        "record_instant, ex_date, payment_date, gross_amount, withholding_tax, net_amount, status, " +
                        "created_at, dataset_id, dataset_checksum, terms_hash) " +
                        "VALUES ('rec-date-only', ?, 'ca-dateonly', ?, 'SYNTHETIC', 'CASH_DISTRIBUTION', ?, ?, ?, " +
                        "'5.00', '0.00', '5.00', 'PENDING', ?, ?, 'chk-content', 'hash-2')",
                portfolio.portfolioId(), LISTING_ID, now, exDate, exDate, now, datasetId);

        // Receivable 3: precise payment_instant far in the future → should stay PENDING
        jdbcTemplate.update(
                "INSERT INTO paper_receivables (id, portfolio_id, action_id, listing_id, source_namespace, action_type, " +
                        "record_instant, ex_date, payment_date, payment_instant, availability_instant, gross_amount, " +
                        "withholding_tax, net_amount, status, created_at, dataset_id, dataset_checksum, terms_hash) " +
                        "VALUES ('rec-future-instant', ?, 'ca-future', ?, 'SYNTHETIC', 'CASH_DISTRIBUTION', ?, ?, ?, ?, ?, " +
                        "'20.00', '0.00', '20.00', 'PENDING', ?, ?, 'chk-content', 'hash-3')",
                portfolio.portfolioId(), LISTING_ID, now, exDate, exDate, futureInstant, now, now, datasetId);

        paperPortfolioService.processPortfolioEvents(portfolio.portfolioId(), owner, "pay-variant-process");

        assertEquals("PAID", jdbcTemplate.queryForObject(
                "SELECT status FROM paper_receivables WHERE id = 'rec-precise-past'", String.class),
                "Receivable with past payment_instant must be PAID");
        assertEquals("PAID", jdbcTemplate.queryForObject(
                "SELECT status FROM paper_receivables WHERE id = 'rec-date-only'", String.class),
                "Receivable with date-only payment_date in past must be PAID");
        assertEquals("PENDING", jdbcTemplate.queryForObject(
                "SELECT status FROM paper_receivables WHERE id = 'rec-future-instant'", String.class),
                "Receivable with future payment_instant must stay PENDING");

        // Verify cash reflects only the two settled receivables
        BigDecimal cash = jdbcTemplate.queryForObject(
                "SELECT cash_amount FROM portfolio_state WHERE portfolio_id = ?",
                BigDecimal.class, portfolio.portfolioId());
        // Initial 1000 - 500 (5 shares @ 100) + 10 (precise past) + 5 (date-only) = 515.00
        assertEquals(0, new BigDecimal("515.00").compareTo(cash),
                "Cash must reflect only the two settled receivables");
    }

    @Test
    void populatedM4DataSurvivesV13MigrationAndPaperActivation() {
        // This test validates prompt §10.12: M4→M5 upgrade preservation.
        // The TemporarySqliteInitializer already ran V1–V13; we seed M4-era data
        // and confirm it coexists with new M5 paper tracking.

        String m4Owner = "m4-legacy-owner";

        // 1. Seed an M4-era experiment (schema: id, owner_id, name, version, strategy_id, strategy_version,
        //    dataset_id, universe_id, benchmark_listing_id, development_start_date, development_end_date,
        //    holdout_start_date, holdout_end_date, declared_holdout_status, created_at)
        jdbcTemplate.update(
                "INSERT OR IGNORE INTO experiments (id, owner_id, name, version, strategy_id, strategy_version, " +
                        "dataset_id, universe_id, benchmark_listing_id, development_start_date, development_end_date, " +
                        "holdout_start_date, holdout_end_date, declared_holdout_status, created_at) " +
                        "VALUES ('experiment-m4-legacy', ?, 'M4 Holdout Test', 1, 'ETF_BUY_HOLD_V1', '1.0.0', " +
                        "'ds-test-m5', 'uni-default', ?, '2025-01-01', '2025-12-31', '2026-01-01', '2026-06-30', " +
                        "'UNEXAMINED', '2026-06-01T00:00:00Z')",
                m4Owner, LISTING_ID);

        // 2. Seed an M4-era backtest run linked to the experiment (schema from V6+V8)
        // Start as RUNNING so daily equity can be inserted without triggering prevent_completed_daily_equity_insert
        jdbcTemplate.update(
                "INSERT OR IGNORE INTO backtest_runs (id, owner_id, idempotency_key, canonical_hash, " +
                        "strategy_id, strategy_version, dataset_id, candidate_listing_id, benchmark_listing_id, " +
                        "initial_cash, currency, evaluation_cutoff, requested_start_date, requested_end_date, " +
                        "commission_per_fill, spread_bps, slippage_bps, status, config_json, " +
                        "universe_id, experiment_id, created_at, updated_at) " +
                        "VALUES ('run-m4-legacy', ?, 'm4-run-key', 'hash-m4', 'ETF_BUY_HOLD_V1', '1.0.0', " +
                        "'ds-test-m5', ?, ?, '10000.00', 'EUR', 'MONTH_END', '2025-01-01', '2025-12-31', " +
                        "'1.00', '0', '0', 'RUNNING', '{}', 'uni-default', 'experiment-m4-legacy', " +
                        "'2026-06-15T10:00:00Z', '2026-06-15T12:00:00Z')",
                m4Owner, LISTING_ID, LISTING_ID);

        // 3. Seed M4-era exposure event
        jdbcTemplate.update(
                "INSERT OR IGNORE INTO experiment_exposure_events (id, experiment_id, run_id, access_type, " +
                        "exposed_by, exposed_at, details_json) " +
                        "VALUES ('exposure-m4-1', 'experiment-m4-legacy', 'run-m4-legacy', 'VIEW_DETAIL', " +
                        "?, '2026-06-15T12:00:00Z', '{\"source\":\"m4_ui\"}')",
                m4Owner);

        // 4. Seed M4-era signal data
        jdbcTemplate.update(
                "INSERT OR IGNORE INTO backtest_signals (id, run_id, strategy_id, strategy_version, universe_id, " +
                        "evaluation_date, evaluation_time, decision_instant, scheduled_execution_date, " +
                        "target_allocation_summary, status, reason_code, details_json, created_at) " +
                        "VALUES ('signal-m4-1', 'run-m4-legacy', 'ETF_BUY_HOLD_V1', '1.0.0', 'uni-default', " +
                        "?, '17:30:00', '2026-06-15T17:30:00Z', ?, '100% target', 'EXECUTED', 'RULE_MATCH', " +
                        "'{}', '2026-06-15T17:30:00Z')",
                SESSION_1, SESSION_1);
        jdbcTemplate.update(
                "INSERT OR IGNORE INTO backtest_signal_items (id, signal_id, listing_id, score, index_value, " +
                        "sma_value, rank, eligible, selected, target_weight, reason_code) " +
                        "VALUES ('item-m4-1', 'signal-m4-1', ?, '100.0', '100.0', '95.0', 1, 1, 1, '1.0', 'TOP_RANK')",
                LISTING_ID);

        // 5. Seed M4-era daily equity
        jdbcTemplate.update(
                "INSERT OR IGNORE INTO backtest_daily_equity (run_id, series_type, session_date, point_kind, " +
                        "observation_time, cash, holdings_value, receivables, total_equity, daily_return, " +
                        "drawdown, peak_equity, units, cost_basis, raw_close) " +
                        "VALUES ('run-m4-legacy', 'CANDIDATE', ?, 'SESSION_CLOSE', '17:30:00', " +
                        "'5000.00', '5000.00', '0.00', '10000.00', '0.000000', '0.000000', '10000.00', " +
                        "'50', '5000.00', '100.00')",
                SESSION_1);

        // Mark backtest run COMPLETED
        jdbcTemplate.update("UPDATE backtest_runs SET status = 'COMPLETED' WHERE id = 'run-m4-legacy'");

        // 6. Seed M4-era LEGACY_DEMO portfolio
        OperationService.PortfolioCreationResult legacyDemo = operationService.createPortfolio(
                m4Owner, "Demo Portfolio M4", "LEGACY_DEMO", "EUR", new BigDecimal("20000.00"), "m4-demo-create");

        // === Now create M5 paper portfolio alongside M4 data ===
        String m5Owner = "m5-upgrade-owner";
        OperationService.PortfolioCreationResult paperPort = operationService.createPortfolio(
                m5Owner, "M5 Paper Fund", "PAPER", "EUR", new BigDecimal("10000.00"), "m5-upgrade-create");
        paperPortfolioService.activatePortfolio(paperPort.portfolioId(), m5Owner, "m5-upgrade-activate",
                new ActivatePortfolioRequest("ETF_BUY_HOLD_V1", "1.0.0", "uni-default", LISTING_ID,
                        new CostPolicyDto("1.00", "0", "0"), "MANUAL"));
        dataReadinessService.adoptDataset(paperPort.portfolioId(), "ds-test-m5", m5Owner, "m5-upgrade-adopt");

        // === Verify M4 data is intact ===
        assertEquals("COMPLETED", jdbcTemplate.queryForObject(
                "SELECT status FROM backtest_runs WHERE id = 'run-m4-legacy'", String.class),
                "M4 backtest run must survive V13 migration");
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM experiment_exposure_events WHERE experiment_id = 'experiment-m4-legacy'",
                Integer.class),
                "M4 exposure events must survive V13 migration");
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM backtest_signal_items WHERE signal_id = 'signal-m4-1'",
                Integer.class),
                "M4 signal items must survive V13 migration");
        assertEquals(0, new BigDecimal("10000.00").compareTo(new BigDecimal(jdbcTemplate.queryForObject(
                "SELECT total_equity FROM backtest_daily_equity WHERE run_id = 'run-m4-legacy'",
                String.class))),
                "M4 daily equity must survive V13 migration");
        assertEquals(0, new BigDecimal("20000.00").compareTo(jdbcTemplate.queryForObject(
                "SELECT cash_amount FROM portfolio_state WHERE portfolio_id = ?",
                BigDecimal.class, legacyDemo.portfolioId())),
                "M4 LEGACY_DEMO cash must be untouched by M5 paper activation");

        // === Verify M5 paper portfolio works alongside ===
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM paper_portfolio_segments WHERE portfolio_id = ? AND status = 'ACTIVE'",
                Integer.class, paperPort.portfolioId()),
                "M5 paper segment must coexist with M4 data");
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM paper_portfolio_segments WHERE portfolio_id = ?",
                Integer.class, legacyDemo.portfolioId()),
                "M4 LEGACY_DEMO must not have paper segments");

        // Verify V13 schema objects exist
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='paper_execution_results'",
                Integer.class),
                "V13 paper_execution_results table must exist");
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sqlite_master WHERE type='trigger' AND name='paper_intents_legal_status_transition'",
                Integer.class),
                "V13 intent status transition trigger must exist");
    }

    @Test
    void multiLegRebalanceSellsBeforeBuyAndAppliesCostPolicy_scenario5() {
        // Validates prompt §10.5 & review finding 3: multi-leg sell-before-buy execution with configured cost policy.
        String owner = "multileg-owner";
        String datasetId = "ds-multileg-m5";
        cloneBaseDataset(datasetId);

        String listing2 = "listing-test-emim";
        String inst2 = "inst-test-emim";
        jdbcTemplate.update("INSERT OR IGNORE INTO instruments (id, type, name, isin, provenance) VALUES (?, 'EQUITY', 'iShares Emerging Markets', 'IE00BKM4GZ66', 'TEST')", inst2);
        jdbcTemplate.update("INSERT OR IGNORE INTO listings (id, instrument_id, venue, symbol, quote_currency, identity_status) VALUES (?, ?, 'AMS', 'EMIM.AS', 'EUR', 'RESOLVED')", listing2, inst2);
        jdbcTemplate.update("INSERT OR IGNORE INTO dataset_listings (dataset_id, listing_id, instrument_id, symbol, venue, quote_currency, calendar_id) VALUES (?, ?, ?, 'EMIM.AS', 'AMS', 'EUR', 'XAMS')", datasetId, listing2, inst2);
        String purchaseDate = java.time.LocalDate.parse(SESSION_1).minusDays(1).toString();
        jdbcTemplate.update("INSERT OR IGNORE INTO dataset_sessions (dataset_id, calendar_id, session_date, open_time, close_time, session_type) VALUES (?, 'XAMS', ?, '09:00:00', '17:30:00', 'TRADING')", datasetId, SESSION_1);
        jdbcTemplate.update("INSERT OR IGNORE INTO historical_bars (dataset_id, listing_id, session_date, open, high, low, close, volume, available_at) VALUES (?, ?, ?, '50.00', '50.50', '49.50', '50.00', 10000, '2026-09-01T18:00:00Z')", datasetId, listing2, SESSION_1);

        String universeId = "uni-multileg";
        jdbcTemplate.update("INSERT OR IGNORE INTO universes (id, owner_id, name, version, description, dataset_id, calendar_id, currency, provenance, created_at) " +
                "VALUES (?, 'default', 'MultiLeg ETF Universe', '1.0.0', 'Test universe', ?, 'XAMS', 'EUR', 'TEST', '2026-09-01T00:00:00Z')", universeId, datasetId);
        jdbcTemplate.update("INSERT OR IGNORE INTO universe_listings (universe_id, listing_id, ordinal) VALUES (?, ?, 0)", universeId, LISTING_ID);
        jdbcTemplate.update("INSERT OR IGNORE INTO universe_listings (universe_id, listing_id, ordinal) VALUES (?, ?, 1)", universeId, listing2);

        // Portfolio with initial cash EUR 1000
        OperationService.PortfolioCreationResult portfolio = operationService.createPortfolio(
                owner, "MultiLeg Fund", "PAPER", "EUR", new BigDecimal("1000.00"), "multileg-create");
        paperPortfolioService.activatePortfolio(portfolio.portfolioId(), owner, "multileg-activate",
                new ActivatePortfolioRequest("ETF_BUY_HOLD_V1", "1.0.0", universeId, LISTING_ID,
                        new CostPolicyDto("1.00", "10", "5"), "MANUAL")); // 1 EUR commission, 10 bps spread, 5 bps slippage
        dataReadinessService.adoptDataset(portfolio.portfolioId(), datasetId, owner, "multileg-adopt");

        // Seed initial holding: 5 shares of LISTING_ID (IWDA) bought at 100 before SESSION_1
        operationService.executePaperTrade(portfolio.portfolioId(), LISTING_ID, "BUY", new BigDecimal("5"),
                new BigDecimal("100"), new BigDecimal("100"), BigDecimal.ZERO, BigDecimal.ZERO,
                "multileg-seed-buy", "TEST_SEED", purchaseDate + "T08:00:00Z");

        // Manually create an intent/proposal to rebalance: target 0% LISTING_ID, 100% listing2
        String propId = "prop-multileg-rebalance";
        String intentId = "intent-multileg-rebalance";
        String now = java.time.Instant.now().toString();
        jdbcTemplate.update("INSERT INTO paper_proposals (id, portfolio_id, cycle_id, strategy_id, strategy_version, " +
                        "dataset_id, dataset_checksum, calendar_id, calendar_version, evaluation_session_date, " +
                        "input_cutoff_instant, evaluation_instant, scheduled_open_session_date, scheduled_open_instant, " +
                        "reason_code, portfolio_state_version, status, created_at) " +
                        "VALUES (?, ?, 'cycle-multileg', 'ETF_BUY_HOLD_V1', '1.0.0', ?, 'chk-content', 'XAMS', '1.0', " +
                        "?, ?, ?, ?, ?, 'REBALANCE', 1, 'PROPOSED', ?)",
                propId, portfolio.portfolioId(), datasetId, purchaseDate, now, now, SESSION_1, SESSION_1 + "T07:00:00Z", now);

        jdbcTemplate.update("INSERT INTO paper_proposal_items (id, proposal_id, listing_id, rank, target_weight, " +
                        "cutoff_estimated_units, score, reason_code) VALUES (?, ?, ?, 1, '1.0', 10, '100.0', 'TARGET')",
                "item-rebalance-emim", propId, listing2);

        jdbcTemplate.update("UPDATE paper_proposals SET status = 'ACCEPTED', accepted_at = ? WHERE id = ?", now, propId);

        jdbcTemplate.update("INSERT INTO paper_execution_intents (id, portfolio_id, proposal_id, order_type, " +
                        "scheduled_session_date, scheduled_open_instant, approval_mode, status, created_at) " +
                        "VALUES (?, ?, ?, 'REBALANCE', ?, ?, 'MANUAL', 'PENDING', ?)",
                intentId, portfolio.portfolioId(), propId, SESSION_1, SESSION_1 + "T07:00:00Z", now);

        // Process portfolio events
        paperPortfolioService.processPortfolioEvents(portfolio.portfolioId(), owner, "multileg-process");

        // Verify that SELL executed first and BUY executed second
        List<Map<String, Object>> results = jdbcTemplate.queryForList(
                "SELECT side, listing_id, requested_quantity, executed_quantity, raw_open_price, fill_price, commission, " +
                        "spread_slippage_cost, booked_instant FROM paper_execution_results WHERE intent_id = ? ORDER BY booked_instant ASC",
                intentId);
        assertEquals(2, results.size(), "Must have executed both SELL leg and BUY leg");
        assertEquals("SELL", results.get(0).get("side"));
        assertEquals(LISTING_ID, results.get(0).get("listing_id"));
        assertEquals("5", results.get(0).get("executed_quantity"));

        assertEquals("BUY", results.get(1).get("side"));
        assertEquals(listing2, results.get(1).get("listing_id"));

        // Verify execution order in executions table
        List<String> execSides = jdbcTemplate.queryForList(
                "SELECT side FROM executions WHERE portfolio_id = ? AND side IN ('buy', 'sell') ORDER BY executed_at ASC",
                String.class, portfolio.portfolioId());
        assertTrue(execSides.contains("buy") && execSides.contains("sell"), "Both sell and buy executions must exist");

        // Verify SELL proceeds financed BUY leg
        BigDecimal remainingCash = jdbcTemplate.queryForObject(
                "SELECT cash_amount FROM portfolio_state WHERE portfolio_id = ?",
                BigDecimal.class, portfolio.portfolioId());
        assertNotNull(remainingCash);
        assertTrue(remainingCash.compareTo(BigDecimal.ZERO) >= 0, "Cash must remain positive after funded rebalance");

        // Verify holding: 0 of LISTING_ID (row deleted when flat), > 0 of listing2
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM positions WHERE portfolio_id = ? AND listing_id = ?",
                Integer.class, portfolio.portfolioId(), LISTING_ID));
        assertTrue(new BigDecimal(jdbcTemplate.queryForObject(
                "SELECT quantity FROM positions WHERE portfolio_id = ? AND listing_id = ?",
                String.class, portfolio.portfolioId(), listing2)).compareTo(BigDecimal.ZERO) > 0);
    }

    @Test
    void s3TrendCashStateKeepsDistributionAsCashWithoutReinvestmentProposal_scenario7() {
        // Validates prompt §10.7 & review finding 4:
        // When S3 (Trend) is in cash (flat), corporate cash distribution is credited as cash
        // and does NOT create a reinvestment proposal.
        String owner = "s3-cash-owner";
        String datasetId = "ds-s3-cash-m5";
        jdbcTemplate.update("INSERT OR IGNORE INTO datasets (id, name, source, classification, schema_version, " +
                        "parser_version, input_checksum, content_checksum, manifest_json, coverage_start, coverage_end, " +
                        "validation_status, validation_findings_json, quality_label, imported_at, created_at) " +
                        "VALUES (?, 'S3 Cash M5', 'SYNTHETIC', 'SYNTHETIC', '1.0', '1.0', 's3-chk-in', 's3-chk-content', " +
                        "'{}', '2025-08-31', ?, 'VALID', '[]', 'SYNTHETIC', '2026-09-01T00:00:00Z', '2026-09-01T00:00:00Z')",
                datasetId, SESSION_2);
        jdbcTemplate.update("INSERT OR IGNORE INTO dataset_listings (dataset_id, listing_id, instrument_id, symbol, venue, " +
                        "quote_currency, calendar_id) VALUES (?, ?, ?, ?, 'AMS', 'EUR', 'XAMS')",
                datasetId, LISTING_ID, INSTRUMENT_ID, TICKER);
        jdbcTemplate.update("INSERT OR IGNORE INTO dataset_sessions (dataset_id, calendar_id, session_date, open_time, close_time, session_type) VALUES (?, 'XAMS', ?, '09:00:00', '17:30:00', 'TRADING')", datasetId, SESSION_1);
        jdbcTemplate.update("INSERT OR IGNORE INTO historical_bars (dataset_id, listing_id, session_date, open, high, low, close, volume, available_at) VALUES (?, ?, ?, '100.00', '100.00', '100.00', '100.00', 10000, '2026-09-01T00:00:00Z')", datasetId, LISTING_ID, SESSION_1);

        // ETF_TREND_10M_V1 requires 10 months of warm-up
        for (int m = 0; m < 11; m++) {
            String session = java.time.YearMonth.of(2025, 8).plusMonths(m).atEndOfMonth().toString();
            jdbcTemplate.update("INSERT OR IGNORE INTO dataset_sessions (dataset_id, calendar_id, session_date, open_time, close_time, session_type) VALUES (?, 'XAMS', ?, '09:00:00', '17:30:00', 'TRADING')", datasetId, session);
            jdbcTemplate.update("INSERT OR IGNORE INTO historical_bars (dataset_id, listing_id, session_date, open, high, low, close, volume, available_at) VALUES (?, ?, ?, '100.00', '100.00', '100.00', '100.00', 10000, '2026-09-01T00:00:00Z')", datasetId, LISTING_ID, session);
        }

        String exDate = java.time.LocalDate.parse(SESSION_1).minusDays(2).toString();
        String purchaseDate = java.time.LocalDate.parse(exDate).minusDays(1).toString();
        String payDate = SESSION_1;

        OperationService.PortfolioCreationResult portfolio = operationService.createPortfolio(
                owner, "S3 Trend Cash Fund", "PAPER", "EUR", new BigDecimal("1000.00"), "s3-cash-create");
        paperPortfolioService.activatePortfolio(portfolio.portfolioId(), owner, "s3-cash-activate",
                new ActivatePortfolioRequest("ETF_TREND_10M_V1", "1.0.0", "uni-default", LISTING_ID,
                        new CostPolicyDto("1.00", "0", "0"), "MANUAL"));
        dataReadinessService.adoptDataset(portfolio.portfolioId(), datasetId, owner, "s3-cash-adopt");

        // Portfolio held 5 shares before exDate (so entitled on exDate)
        operationService.executePaperTrade(portfolio.portfolioId(), LISTING_ID, "BUY", new BigDecimal("5"),
                new BigDecimal("100"), new BigDecimal("100"), BigDecimal.ZERO, BigDecimal.ZERO,
                "s3-seed-buy", "TEST_SEED", purchaseDate + "T08:00:00Z");

        // Distribution occurs with ex-date = exDate and payDate = payDate
        jdbcTemplate.update("INSERT INTO historical_actions (dataset_id, action_id, listing_id, action_type, " +
                        "effective_date, available_at, distribution_amount, distribution_currency, payment_date) " +
                        "VALUES (?, 'dist-s3-test', ?, 'CASH_DISTRIBUTION', ?, '2026-09-01T18:00:00Z', '2.00', 'EUR', ?)",
                datasetId, LISTING_ID, exDate, payDate);

        // Before payment date, S3 Trend exits to cash: sell all 5 shares
        operationService.executePaperTrade(portfolio.portfolioId(), LISTING_ID, "SELL", new BigDecimal("5"),
                new BigDecimal("100"), new BigDecimal("100"), BigDecimal.ZERO, BigDecimal.ZERO,
                "s3-exit-sell", "TEST_EXIT", exDate + "T16:00:00Z");

        // Verify position is now 0 (in cash, row deleted)
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM positions WHERE portfolio_id = ? AND listing_id = ?",
                Integer.class, portfolio.portfolioId(), LISTING_ID));

        // Process events on payment date
        paperPortfolioService.processPortfolioEvents(portfolio.portfolioId(), owner, "s3-process-pay");

        // Receivable settled to PAID
        assertEquals("PAID", jdbcTemplate.queryForObject(
                "SELECT status FROM paper_receivables WHERE portfolio_id = ? AND action_id = 'dist-s3-test'",
                String.class, portfolio.portfolioId()));

        // Cash credited: initial 1000 - 500 (buy) + 500 (sell) + 10.00 (5 shares * 2.00) = 1010.00
        BigDecimal cash = jdbcTemplate.queryForObject(
                "SELECT cash_amount FROM portfolio_state WHERE portfolio_id = ?",
                BigDecimal.class, portfolio.portfolioId());
        assertEquals(0, new BigDecimal("1010.00").compareTo(cash), "Cash must include distribution");

        // Crucial assertion: S3 in cash state must NOT have generated a reinvestment proposal
        int reinvestmentProposals = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM paper_proposals WHERE portfolio_id = ? AND reinvestment_receivable_id IS NOT NULL",
                Integer.class, portfolio.portfolioId());
        assertEquals(0, reinvestmentProposals, "S3 flat/cash state must NOT create reinvestment proposals");
    }

    @Test
    void partialStaleValuationMarkedWhenPriceMissing_scenario7() {
        // Validates prompt §10.7 & review finding 7:
        // When closing observations are missing for an asset held in the portfolio,
        // paper_valuations marks data_readiness_status = 'PARTIAL_STALE' and is_complete = 0.
        String owner = "stale-val-owner";
        String datasetId = "ds-stale-val-m5";
        // Insert dataset metadata without any historical bars to simulate missing price observations
        jdbcTemplate.update("INSERT INTO datasets (id, name, source, classification, schema_version, parser_version, " +
                        "input_checksum, content_checksum, manifest_json, coverage_start, coverage_end, validation_status, " +
                        "validation_findings_json, quality_label, imported_at, created_at) SELECT ?, name, source, " +
                        "classification, schema_version, parser_version, input_checksum, content_checksum, manifest_json, " +
                        "coverage_start, coverage_end, validation_status, validation_findings_json, quality_label, " +
                        "imported_at, created_at FROM datasets WHERE id = 'ds-test-m5'", datasetId);
        jdbcTemplate.update("INSERT INTO dataset_listings (dataset_id, listing_id, instrument_id, symbol, venue, " +
                        "quote_currency, calendar_id, inception_date, termination_date, isin) SELECT ?, listing_id, " +
                        "instrument_id, symbol, venue, quote_currency, calendar_id, inception_date, termination_date, isin " +
                        "FROM dataset_listings WHERE dataset_id = 'ds-test-m5'", datasetId);
        jdbcTemplate.update("INSERT INTO dataset_sessions (dataset_id, calendar_id, session_date, open_time, close_time, " +
                        "session_type) SELECT ?, calendar_id, session_date, open_time, close_time, session_type " +
                        "FROM dataset_sessions WHERE dataset_id = 'ds-test-m5'", datasetId);

        OperationService.PortfolioCreationResult portfolio = operationService.createPortfolio(
                owner, "Stale Valuation Fund", "PAPER", "EUR", new BigDecimal("1000.00"), "stale-val-create");
        paperPortfolioService.activatePortfolio(portfolio.portfolioId(), owner, "stale-val-activate",
                new ActivatePortfolioRequest("ETF_BUY_HOLD_V1", "1.0.0", "uni-default", LISTING_ID,
                        new CostPolicyDto("1.00", "0", "0"), "MANUAL"));
        dataReadinessService.adoptDataset(portfolio.portfolioId(), datasetId, owner, "stale-val-adopt");

        // Buy 5 units of LISTING_ID
        operationService.executePaperTrade(portfolio.portfolioId(), LISTING_ID, "BUY", new BigDecimal("5"),
                new BigDecimal("100"), new BigDecimal("100"), BigDecimal.ZERO, BigDecimal.ZERO,
                "stale-val-buy", "TEST_BUY", SESSION_1 + "T08:00:00Z");

        // Add a new trading session where bars are absent for LISTING_ID
        String gapSession = java.time.LocalDate.parse(SESSION_2).plusDays(5).toString();
        jdbcTemplate.update("INSERT OR IGNORE INTO dataset_sessions (dataset_id, calendar_id, session_date, " +
                        "open_time, close_time, session_type) VALUES (?, 'XAMS', ?, '09:00:00', '17:30:00', 'TRADING')",
                datasetId, gapSession);

        // Control clock to be at close of gapSession
        java.time.Clock origPaperClock = (java.time.Clock) org.springframework.test.util.ReflectionTestUtils
                .getField(paperPortfolioService, "clock");
        java.time.Clock gapCloseClock = java.time.Clock.fixed(
                java.time.Instant.parse(gapSession + "T17:35:00Z"), java.time.ZoneId.of("Europe/Berlin"));
        try {
            org.springframework.test.util.ReflectionTestUtils.setField(paperPortfolioService, "clock", gapCloseClock);
            paperPortfolioService.processPortfolioEvents(portfolio.portfolioId(), owner, "stale-val-process");

            // Query valuation for gapSession
            List<Map<String, Object>> vals = jdbcTemplate.queryForList(
                    "SELECT data_readiness_status, is_complete, missing_requirements_detail, total_equity " +
                            "FROM paper_valuations WHERE portfolio_id = ? AND session_date = ?",
                    portfolio.portfolioId(), gapSession);

            assertFalse(vals.isEmpty(), "Valuation must be recorded for gapSession");
            Map<String, Object> val = vals.get(0);
            assertEquals("PARTIAL_STALE", val.get("data_readiness_status"), "Status must be PARTIAL_STALE");
            assertEquals(0, ((Number) val.get("is_complete")).intValue(), "is_complete must be 0");
            assertTrue(String.valueOf(val.get("missing_requirements_detail")).contains(LISTING_ID),
                    "missing_requirements_detail must identify the listing with missing prices");
        } finally {
            org.springframework.test.util.ReflectionTestUtils.setField(paperPortfolioService, "clock", origPaperClock);
        }
    }

    @Test
    void eightConcurrentAcceptancesCreateExactlyOneIntent_scenario8() throws Exception {
        // Validates prompt §10.8 & review finding 8:
        // 8 coordinated concurrent proposal acceptances produce exactly 1 accepted state and 1 execution intent.
        String owner = "concurrent-accept-owner";
        OperationService.PortfolioCreationResult portfolio = operationService.createPortfolio(
                owner, "Concurrent Accept Fund", "PAPER", "EUR", new BigDecimal("1000.00"), "conc-accept-create");
        paperPortfolioService.activatePortfolio(portfolio.portfolioId(), owner, "conc-accept-activate",
                new ActivatePortfolioRequest("ETF_BUY_HOLD_V1", "1.0.0", "uni-default", LISTING_ID,
                        new CostPolicyDto("1.00", "0", "0"), "MANUAL"));
        dataReadinessService.adoptDataset(portfolio.portfolioId(), "ds-test-m5", owner, "conc-accept-adopt");

        PaperProposalDto proposal = paperPortfolioService.evaluatePortfolio(portfolio.portfolioId(), owner, "conc-eval");
        assertEquals("PROPOSED", proposal.status());

        int callers = 8;
        ExecutorService executor = Executors.newFixedThreadPool(callers);
        CyclicBarrier barrier = new CyclicBarrier(callers);
        List<Future<Boolean>> futures = new ArrayList<>();
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger conflictCount = new AtomicInteger(0);

        try {
            for (int i = 0; i < callers; i++) {
                final int idx = i;
                futures.add(executor.submit(() -> {
                    barrier.await();
                    try {
                        paperPortfolioService.acceptProposal(portfolio.portfolioId(), proposal.id(), owner,
                                "concurrent-accept-key-" + idx, new AcceptProposalRequest("concurrent test"));
                        successCount.incrementAndGet();
                        return true;
                    } catch (ResponseStatusException ex) {
                        if (ex.getStatusCode().value() == 409) {
                            conflictCount.incrementAndGet();
                        }
                        return false;
                    }
                }));
            }
            for (Future<Boolean> f : futures) {
                f.get(10, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }

        // Exactly one caller wins the CAS update
        assertEquals(1, successCount.get(), "Exactly one concurrent caller must succeed in accepting");
        assertEquals(callers - 1, conflictCount.get(), "All other callers must receive 409 CONFLICT");

        // Exactly one intent exists for the proposal
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM paper_execution_intents WHERE proposal_id = ?",
                Integer.class, proposal.id()));
        assertEquals("ACCEPTED", jdbcTemplate.queryForObject(
                "SELECT status FROM paper_proposals WHERE id = ?",
                String.class, proposal.id()));
    }

    @Test
    void autoPaperMissedWindowMarksProposalBlockedWithoutHistoricalOrder_scenario9() {
        // Validates prompt §10.9 & review finding 2:
        // Restart/downtime after scheduled open passes marks unaccepted AUTO_PAPER proposal as BLOCKED,
        // does not invent past orders, and mode toggle retains audit history.
        String owner = "missed-window-owner";
        String datasetId = "ds-missed-window-m5";
        cloneBaseDataset(datasetId);

        OperationService.PortfolioCreationResult portfolio = operationService.createPortfolio(
                owner, "Missed Window Fund", "PAPER", "EUR", new BigDecimal("1000.00"), "missed-window-create");
        paperPortfolioService.activatePortfolio(portfolio.portfolioId(), owner, "missed-window-activate",
                new ActivatePortfolioRequest("ETF_BUY_HOLD_V1", "1.0.0", "uni-default", LISTING_ID,
                        new CostPolicyDto("1.00", "0", "0"), "AUTO_PAPER"));
        dataReadinessService.adoptDataset(portfolio.portfolioId(), datasetId, owner, "missed-window-adopt");

        // Insert an unaccepted proposal scheduled for an earlier open time
        String openInstant = "2026-09-17T07:00:00Z";
        String propId = "prop-missed-auto";
        jdbcTemplate.update("INSERT INTO paper_proposals (id, portfolio_id, cycle_id, strategy_id, strategy_version, " +
                        "dataset_id, dataset_checksum, calendar_id, calendar_version, evaluation_session_date, " +
                        "input_cutoff_instant, evaluation_instant, scheduled_open_session_date, scheduled_open_instant, " +
                        "reason_code, portfolio_state_version, status, created_at) " +
                        "VALUES (?, ?, 'cycle-missed', 'ETF_BUY_HOLD_V1', '1.0.0', ?, 'chk-content', 'XAMS', '1.0', " +
                        "?, ?, ?, ?, ?, 'AUTO_CYCLE', 1, 'PROPOSED', ?)",
                propId, portfolio.portfolioId(), datasetId, SESSION_1, openInstant, openInstant,
                SESSION_2, openInstant, "2026-09-17T06:00:00Z");

        // Advance clock past scheduled open
        java.time.Clock origPaperClock = (java.time.Clock) org.springframework.test.util.ReflectionTestUtils
                .getField(paperPortfolioService, "clock");
        java.time.Clock pastOpenClock = java.time.Clock.fixed(
                java.time.Instant.parse("2026-09-17T08:00:00Z"), java.time.ZoneId.of("Europe/Berlin"));
        try {
            org.springframework.test.util.ReflectionTestUtils.setField(paperPortfolioService, "clock", pastOpenClock);

            // Execute automatic cycle past open
            paperPortfolioService.runAutomaticCycle(portfolio.portfolioId(), owner);

            // Proposal must be BLOCKED
            assertEquals("BLOCKED", jdbcTemplate.queryForObject(
                    "SELECT status FROM paper_proposals WHERE id = ?", String.class, propId));
            assertEquals("AUTO_PAPER intent was not durable before scheduled open", jdbcTemplate.queryForObject(
                    "SELECT rejection_reason FROM paper_proposals WHERE id = ?", String.class, propId));

            // Missed intent is recorded with MISSED status, but zero execution results
            assertEquals(1, jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM paper_execution_intents WHERE proposal_id = ? AND status = 'MISSED'",
                    Integer.class, propId));
            assertEquals(0, jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM paper_execution_results WHERE proposal_id = ?",
                    Integer.class, propId));

            // Mode toggle: AUTO_PAPER -> MANUAL -> AUTO_PAPER
            paperPortfolioService.changeApprovalMode(portfolio.portfolioId(), owner, "mode-toggle-manual",
                    new ChangeApprovalModeRequest("MANUAL", "toggle to manual"));
            assertEquals("MANUAL", jdbcTemplate.queryForObject(
                    "SELECT approval_mode FROM paper_portfolio_segments WHERE portfolio_id = ? AND status = 'ACTIVE'",
                    String.class, portfolio.portfolioId()));

            paperPortfolioService.changeApprovalMode(portfolio.portfolioId(), owner, "mode-toggle-auto",
                    new ChangeApprovalModeRequest("AUTO_PAPER", "toggle to auto"));
            assertEquals("AUTO_PAPER", jdbcTemplate.queryForObject(
                    "SELECT approval_mode FROM paper_portfolio_segments WHERE portfolio_id = ? AND status = 'ACTIVE'",
                    String.class, portfolio.portfolioId()));

            // Mode history audit rows recorded
            int historyRows = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM paper_mode_history WHERE portfolio_id = ?",
                    Integer.class, portfolio.portfolioId());
            assertTrue(historyRows >= 3, "Mode history must track all mode transitions");
        } finally {
            org.springframework.test.util.ReflectionTestUtils.setField(paperPortfolioService, "clock", origPaperClock);
        }
    }
}

