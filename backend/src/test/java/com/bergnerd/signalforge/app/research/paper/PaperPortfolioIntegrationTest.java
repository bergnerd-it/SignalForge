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

        // Cash reflects trade fill: initial 10000 + 10 (div receivable) = 10010.
        // Buy 100 units @ 100.00 = 10000.00 + 1.00 commission = 10001.00.
        // Remaining cash = 10010.00 - 10001.00 = 9.00 EUR.
        BigDecimal cashAfterDiv = jdbcTemplate.queryForObject(
                "SELECT cash_amount FROM portfolio_state WHERE portfolio_id = ?",
                BigDecimal.class, portfolioId);
        assertEquals(0, new BigDecimal("9.00").compareTo(cashAfterDiv));

        // Position is 100 units
        BigDecimal positionQty = jdbcTemplate.queryForObject(
                "SELECT quantity FROM positions WHERE portfolio_id = ? AND listing_id = ?",
                BigDecimal.class, portfolioId, LISTING_ID);
        assertEquals(0, new BigDecimal("100").compareTo(positionQty));

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
                "TEST_OPEN", entitlementDate + "T08:00:00Z");
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
}
