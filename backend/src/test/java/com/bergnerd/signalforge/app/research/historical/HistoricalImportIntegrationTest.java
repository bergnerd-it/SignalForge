package com.bergnerd.signalforge.app.research.historical;

import com.bergnerd.signalforge.app.TemporarySqliteInitializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ContextConfiguration(initializers = TemporarySqliteInitializer.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class HistoricalImportIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private HistoricalImportJobService jobService;

    private byte[] loadFixture(String name) throws IOException {
        Path path = Path.of("../test/fixtures/historical/" + name);
        if (!Files.exists(path)) {
            path = Path.of("test/fixtures/historical/" + name);
        }
        return Files.readAllBytes(path);
    }

    @Test
    void completeHistoricalImportLifecycleAndVerification() throws Exception {
        byte[] validBytes = loadFixture("valid-sample-bundle.zip");
        MockMultipartFile file = new MockMultipartFile("file", "valid-sample-bundle.zip", "application/zip", validBytes);

        // 1. Upload valid bundle
        String uploadResponse = mockMvc.perform(multipart("/api/research/imports")
                        .file(file)
                        .header("Origin", "http://localhost:4200")
                        .header("Idempotency-Key", "test-import-key-1"))
                .andExpect(status().is2xxSuccessful())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertTrue(uploadResponse.contains("test-import-key-1"));

        // Extract job ID from JSON
        String jobId = extractJsonField(uploadResponse, "id");
        assertNotNull(jobId);

        // Wait for job completion (up to 5 seconds)
        HistoricalDtos.ImportJobResponse job = null;
        for (int i = 0; i < 50; i++) {
            job = jobService.getJob(jobId);
            if ("COMPLETED".equals(job.status()) || "FAILED".equals(job.status())) {
                break;
            }
            Thread.sleep(100);
        }

        assertNotNull(job);
        assertEquals("COMPLETED", job.status(), "Job failed with detail: " + job.errorDetail());
        assertNotNull(job.datasetId());

        String datasetId = job.datasetId();

        // 2. Verify Database records
        int datasetCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM datasets WHERE id = ?", Integer.class, datasetId
        );
        assertEquals(1, datasetCount);

        int listingCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM dataset_listings WHERE dataset_id = ?", Integer.class, datasetId
        );
        assertEquals(2, listingCount);

        int sessionCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM dataset_sessions WHERE dataset_id = ?", Integer.class, datasetId
        );
        assertEquals(8, sessionCount);

        int barCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM historical_bars WHERE dataset_id = ?", Integer.class, datasetId
        );
        assertEquals(14, barCount);

        int actionCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM historical_actions WHERE dataset_id = ?", Integer.class, datasetId
        );
        assertEquals(2, actionCount);

        // 3. Verify Idempotent Replay (Same Key, Same Bytes)
        mockMvc.perform(multipart("/api/research/imports")
                        .file(file)
                        .header("Origin", "http://localhost:4200")
                        .header("Idempotency-Key", "test-import-key-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.datasetId").value(datasetId));

        // 4. Verify Conflict on Same Key with Different Bytes
        byte[] invalidBytes = loadFixture("invalid-missing-bar.zip");
        MockMultipartFile conflictingFile = new MockMultipartFile("file", "different.zip", "application/zip", invalidBytes);

        mockMvc.perform(multipart("/api/research/imports")
                        .file(conflictingFile)
                        .header("Origin", "http://localhost:4200")
                        .header("Idempotency-Key", "test-import-key-1"))
                .andExpect(status().isConflict());

        // 5. Verify Deduplication (Different Key, Same Bytes)
        String dedupeResponse = mockMvc.perform(multipart("/api/research/imports")
                        .file(file)
                        .header("Origin", "http://localhost:4200")
                        .header("Idempotency-Key", "test-import-key-2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.datasetId").value(datasetId))
                .andReturn().getResponse().getContentAsString();

        assertTrue(dedupeResponse.contains(datasetId));

        // Dataset count in DB should still be 1!
        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM datasets", Integer.class));

        // 6. Verify Rejected Import (Invalid Missing Bar)
        MockMultipartFile badFile = new MockMultipartFile("file", "missing-bar.zip", "application/zip", invalidBytes);
        String badResponse = mockMvc.perform(multipart("/api/research/imports")
                        .file(badFile)
                        .header("Origin", "http://localhost:4200")
                        .header("Idempotency-Key", "test-import-key-bad"))
                .andExpect(status().is2xxSuccessful())
                .andReturn().getResponse().getContentAsString();

        String badJobId = extractJsonField(badResponse, "id");
        for (int i = 0; i < 50; i++) {
            job = jobService.getJob(badJobId);
            if ("COMPLETED".equals(job.status()) || "FAILED".equals(job.status())) {
                break;
            }
            Thread.sleep(100);
        }
        assertEquals("FAILED", job.status());
        assertTrue(job.errorDetail().contains("MISSING_TRADING_BAR"));

        // Ensure no extra dataset rows
        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM datasets", Integer.class));

        // 7. Verify History Query & As-Of Knowledge-Time Filtering
        // Without asOf: gets all bars (7 bars for listing-eur-syn-1)
        mockMvc.perform(get("/api/research/datasets/" + datasetId + "/history/listing-eur-syn-1")
                        .param("start", "2024-01-01")
                        .param("end", "2024-01-10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bars.length()").value(7))
                .andExpect(jsonPath("$.actions.length()").value(1))
                .andExpect(jsonPath("$.qualityLabel").value("SYNTHETIC"));

        // With asOf cutoff before 2024-01-05: bar on 2024-01-05 was available at 2024-01-05T18:00:00Z, so cutoff at 2024-01-04T23:59:59Z must return exactly 3 bars (02, 03, 04)
        mockMvc.perform(get("/api/research/datasets/" + datasetId + "/history/listing-eur-syn-1")
                        .param("start", "2024-01-01")
                        .param("end", "2024-01-10")
                        .param("asOf", "2024-01-04T23:59:59Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bars.length()").value(3));

        // 8. Verify Immutability Triggers
        assertThrows(Exception.class, () -> jdbcTemplate.execute("UPDATE datasets SET name = 'Mutated'"));
        assertThrows(Exception.class, () -> jdbcTemplate.execute("DELETE FROM datasets"));
        assertThrows(Exception.class, () -> jdbcTemplate.execute("DELETE FROM historical_bars"));
        assertThrows(Exception.class, () -> jdbcTemplate.execute("UPDATE historical_bars SET close = '999.00'"));
        assertThrows(Exception.class, () -> jdbcTemplate.execute("DELETE FROM historical_actions"));

        // 9. Verify Restart Recovery
        jdbcTemplate.update(
                "INSERT INTO import_jobs (id, request_key, input_checksum, status, dataset_id, progress_pct, message, error_detail, created_at, updated_at) " +
                        "VALUES ('job-interrupted-test', 'key-interrupted', 'dummy', 'RUNNING', NULL, 50, 'Running', NULL, '2026-09-12T00:00:00Z', '2026-09-12T00:00:00Z')"
        );
        jobService.recoverInterruptedJobs();
        HistoricalDtos.ImportJobResponse recovered = jobService.getJob("job-interrupted-test");
        assertEquals("INTERRUPTED", recovered.status());

        // 10. Verify Pre-existing Financial State is Intact (M1b baseline)
        int portfolioCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM portfolios WHERE id = 'portfolio-legacy-demo-default'", Integer.class
        );
        assertEquals(1, portfolioCount);
        String cash = jdbcTemplate.queryForObject(
                "SELECT cash_amount FROM portfolio_state WHERE portfolio_id = 'portfolio-legacy-demo-default'", String.class
        );
        assertEquals("10000.00", cash);
    }

    private String extractJsonField(String json, String fieldName) {
        String search = "\"" + fieldName + "\":\"";
        int idx = json.indexOf(search);
        if (idx == -1) return null;
        int start = idx + search.length();
        int end = json.indexOf("\"", start);
        return json.substring(start, end);
    }
}
