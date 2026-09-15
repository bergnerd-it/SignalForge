package com.bergnerd.signalforge.app.research.paper;

import com.bergnerd.signalforge.app.TemporarySqliteInitializer;
import com.bergnerd.signalforge.app.operation.OperationService;
import com.bergnerd.signalforge.app.research.ResearchDtos.*;
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
        dataReadinessService.adoptDataset(portfolioId, "ds-test-m5");

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
                "INSERT INTO paper_receivables (id, portfolio_id, action_id, listing_id, action_type, record_instant, ex_date, payment_date, gross_amount, withholding_tax, net_amount, status, created_at) " +
                        "VALUES (?, ?, 'ca-div-1', ?, 'CASH_DISTRIBUTION', '2026-09-15T09:00:00Z', '2026-09-15', '2026-09-15', '10.00', '0.00', '10.00', 'PENDING', '2026-09-15T09:00:00Z')",
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
        paperPortfolioService.processPortfolioEvents(portfolioId, OWNER, "idemp-ca-2");
        BigDecimal cashAfterSecondProcess = jdbcTemplate.queryForObject(
                "SELECT cash_amount FROM portfolio_state WHERE portfolio_id = ?",
                BigDecimal.class, portfolioId);
        assertEquals(0, cashAfterDiv.compareTo(cashAfterSecondProcess));

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
        assertTrue(entriesFound.contains("executions.csv"));
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
}
