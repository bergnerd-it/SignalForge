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
import java.util.UUID;
import java.util.concurrent.TimeUnit;

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
    void testSimultaneousDuplicateSubmissionsAndConflictingRequestKeys() throws Exception {
        byte[] validBytes = loadFixture("valid-sample-bundle.zip");
        MockMultipartFile file = new MockMultipartFile("file", "valid-sample-bundle.zip", "application/zip", validBytes);
        String key = "concurrent-key-" + UUID.randomUUID();

        // Fire two simultaneous submissions
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.Future<org.springframework.test.web.servlet.MvcResult> f1 = pool.submit(() ->
                mockMvc.perform(multipart("/api/research/imports")
                                .file(file)
                                .header("Origin", "http://localhost:4200")
                                .header("Idempotency-Key", key))
                        .andReturn()
        );
        java.util.concurrent.Future<org.springframework.test.web.servlet.MvcResult> f2 = pool.submit(() ->
                mockMvc.perform(multipart("/api/research/imports")
                                .file(file)
                                .header("Origin", "http://localhost:4200")
                                .header("Idempotency-Key", key))
                        .andReturn()
        );

        org.springframework.test.web.servlet.MvcResult r1 = f1.get(5, TimeUnit.SECONDS);
        org.springframework.test.web.servlet.MvcResult r2 = f2.get(5, TimeUnit.SECONDS);
        pool.shutdown();

        assertTrue(r1.getResponse().getStatus() >= 200 && r1.getResponse().getStatus() < 300, "r1 status: " + r1.getResponse().getStatus());
        assertTrue(r2.getResponse().getStatus() >= 200 && r2.getResponse().getStatus() < 300, "r2 status: " + r2.getResponse().getStatus());

        String id1 = extractJsonField(r1.getResponse().getContentAsString(), "id");
        String id2 = extractJsonField(r2.getResponse().getContentAsString(), "id");
        assertEquals(id1, id2, "Both concurrent submissions must return the same job ID");

        // Wait for job completion
        waitForJobCompletion(id1);

        // Conflicting request key: same key with different bytes must return 409 Conflict
        byte[] invalidBytes = loadFixture("invalid-missing-bar.zip");
        MockMultipartFile conflictingFile = new MockMultipartFile("file", "conflict.zip", "application/zip", invalidBytes);

        mockMvc.perform(multipart("/api/research/imports")
                        .file(conflictingFile)
                        .header("Origin", "http://localhost:4200")
                        .header("Idempotency-Key", key))
                .andExpect(status().isConflict());
    }

    @Test
    void testCorrectedBytesCreatingNewImmutableDatasetVersionAndPreservingOldDataset() throws Exception {
        byte[] originalBytes = loadFixture("valid-sample-bundle.zip");
        MockMultipartFile fileA = new MockMultipartFile("file", "bundle-v1.zip", "application/zip", originalBytes);
        String keyA = "version-key-a-" + UUID.randomUUID();

        // 1. Upload original bundle -> creates dataset A
        String resA = mockMvc.perform(multipart("/api/research/imports")
                        .file(fileA)
                        .header("Origin", "http://localhost:4200")
                        .header("Idempotency-Key", keyA))
                .andExpect(status().is2xxSuccessful())
                .andReturn().getResponse().getContentAsString();
        String jobAId = extractJsonField(resA, "id");
        HistoricalDtos.ImportJobResponse jobA = waitForJobCompletion(jobAId);
        assertEquals("COMPLETED", jobA.status());
        String datasetAId = jobA.datasetId();
        assertNotNull(datasetAId);

        // Record dataset A history before revision
        String historyABefore = mockMvc.perform(get("/api/research/datasets/" + datasetAId + "/history/listing-eur-syn-1")
                        .param("start", "2024-01-01")
                        .param("end", "2024-01-10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bars.length()").value(7))
                .andExpect(jsonPath("$.bars[0].close").value("104.00"))
                .andReturn().getResponse().getContentAsString();

        // As-of query on dataset A
        String asOfABefore = mockMvc.perform(get("/api/research/datasets/" + datasetAId + "/history/listing-eur-syn-1")
                        .param("start", "2024-01-01")
                        .param("end", "2024-01-10")
                        .param("asOf", "2024-01-04T23:59:59Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bars.length()").value(3))
                .andReturn().getResponse().getContentAsString();

        // 2. Create corrected bytes: modify prices.csv in the zip (change 104.00 to 104.99)
        byte[] correctedBytes = modifyZipEntry(originalBytes, "prices.csv", content ->
                content.replace("104.00", "104.99")
        );
        MockMultipartFile fileB = new MockMultipartFile("file", "bundle-v2.zip", "application/zip", correctedBytes);
        String keyB = "version-key-b-" + UUID.randomUUID();

        // Upload corrected bundle -> creates dataset B
        String resB = mockMvc.perform(multipart("/api/research/imports")
                        .file(fileB)
                        .header("Origin", "http://localhost:4200")
                        .header("Idempotency-Key", keyB))
                .andExpect(status().is2xxSuccessful())
                .andReturn().getResponse().getContentAsString();
        String jobBId = extractJsonField(resB, "id");
        HistoricalDtos.ImportJobResponse jobB = waitForJobCompletion(jobBId);
        assertEquals("COMPLETED", jobB.status());
        String datasetBId = jobB.datasetId();
        assertNotNull(datasetBId);
        assertNotEquals(datasetAId, datasetBId, "Corrected bytes must create a distinct dataset version");

        // 3. Verify dataset B reflects corrected price
        mockMvc.perform(get("/api/research/datasets/" + datasetBId + "/history/listing-eur-syn-1")
                        .param("start", "2024-01-01")
                        .param("end", "2024-01-10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bars[0].close").value("104.99"));

        // 4. Verify old dataset A and as-of queries are COMPLETELY UNCHANGED
        String historyAAfter = mockMvc.perform(get("/api/research/datasets/" + datasetAId + "/history/listing-eur-syn-1")
                        .param("start", "2024-01-01")
                        .param("end", "2024-01-10"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertEquals(historyABefore, historyAAfter, "Dataset A history must remain strictly unchanged after Dataset B published");

        String asOfAAfter = mockMvc.perform(get("/api/research/datasets/" + datasetAId + "/history/listing-eur-syn-1")
                        .param("start", "2024-01-01")
                        .param("end", "2024-01-10")
                        .param("asOf", "2024-01-04T23:59:59Z"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertEquals(asOfABefore, asOfAAfter, "Dataset A as-of results must remain strictly unchanged");

        // 5. Verify database immutability triggers on both datasets
        assertThrows(Exception.class, () -> jdbcTemplate.execute("UPDATE datasets SET name = 'mutated' WHERE id = '" + datasetAId + "'"));
        assertThrows(Exception.class, () -> jdbcTemplate.execute("DELETE FROM datasets WHERE id = '" + datasetAId + "'"));
        assertThrows(Exception.class, () -> jdbcTemplate.execute("UPDATE historical_bars SET close = '999.00' WHERE dataset_id = '" + datasetBId + "'"));
    }

    @Test
    void testInjectedPublicationFailureLeavesNoPartiallyVisibleDataset() throws Exception {
        int initialDatasets = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM datasets", Integer.class);
        int initialBars = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM historical_bars", Integer.class);

        // Inject trigger that aborts transaction on insertion of a specific close price
        jdbcTemplate.execute("CREATE TRIGGER test_publication_failure_trigger BEFORE INSERT ON historical_bars " +
                "WHEN NEW.close = '777.77' BEGIN SELECT RAISE(FAIL, 'Injected publication transaction abort'); END;");

        try {
            byte[] originalBytes = loadFixture("valid-sample-bundle.zip");
            byte[] failingBytes = modifyZipEntry(originalBytes, "prices.csv", content ->
                    content.replace("105.00,98.50,104.00", "777.77,98.50,777.77")
            );
            MockMultipartFile file = new MockMultipartFile("file", "failing-bundle.zip", "application/zip", failingBytes);
            String key = "fail-inj-key-" + UUID.randomUUID();

            String res = mockMvc.perform(multipart("/api/research/imports")
                            .file(file)
                            .header("Origin", "http://localhost:4200")
                            .header("Idempotency-Key", key))
                    .andExpect(status().is2xxSuccessful())
                    .andReturn().getResponse().getContentAsString();
            String jobId = extractJsonField(res, "id");
            HistoricalDtos.ImportJobResponse job = waitForJobCompletion(jobId);

            // Job must be FAILED
            assertEquals("FAILED", job.status());
            assertTrue(job.errorDetail() != null && job.errorDetail().contains("Injected publication transaction abort"));

            // Entire publication was rolled back atomically: exactly zero partial rows in datasets or historical_bars!
            int currentDatasets = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM datasets", Integer.class);
            int currentBars = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM historical_bars", Integer.class);
            assertEquals(initialDatasets, currentDatasets, "Failed publication must commit zero datasets");
            assertEquals(initialBars, currentBars, "Failed publication must commit zero bars");
        } finally {
            jdbcTemplate.execute("DROP TRIGGER IF EXISTS test_publication_failure_trigger;");
        }
    }

    @Test
    void testRestartRecoveryAndDocumentedRetryBehavior() throws Exception {
        String dummyJobId = "job-interrupted-" + UUID.randomUUID();
        String dummyKey = "key-interrupted-" + UUID.randomUUID();

        // Simulate a job left in RUNNING status when JVM was stopped
        jdbcTemplate.update(
                "INSERT INTO import_jobs (id, request_key, input_checksum, status, dataset_id, progress_pct, message, error_detail, created_at, updated_at) " +
                        "VALUES (?, ?, 'dummychecksum', 'RUNNING', NULL, 60, 'Processing...', NULL, '2026-09-12T00:00:00Z', '2026-09-12T00:00:00Z')",
                dummyJobId, dummyKey
        );

        // Perform restart recovery
        jobService.recoverInterruptedJobs();

        // Verify status is honest INTERRUPTED
        HistoricalDtos.ImportJobResponse recovered = jobService.getJob(dummyJobId);
        assertEquals("INTERRUPTED", recovered.status());
        assertNull(recovered.datasetId());
        assertTrue(recovered.errorDetail().contains("Application restarted"));

        // Verify documented retry behavior: submitting a retry with a new intent key succeeds
        byte[] validBytes = loadFixture("valid-sample-bundle.zip");
        MockMultipartFile retryFile = new MockMultipartFile("file", "bundle.zip", "application/zip", validBytes);
        String retryKey = "key-retry-" + UUID.randomUUID();

        String retryRes = mockMvc.perform(multipart("/api/research/imports")
                        .file(retryFile)
                        .header("Origin", "http://localhost:4200")
                        .header("Idempotency-Key", retryKey))
                .andExpect(status().is2xxSuccessful())
                .andReturn().getResponse().getContentAsString();
        String retryJobId = extractJsonField(retryRes, "id");
        HistoricalDtos.ImportJobResponse retryJob = waitForJobCompletion(retryJobId);
        assertEquals("COMPLETED", retryJob.status());
        assertNotNull(retryJob.datasetId());
    }

    @Test
    void testExactDecimalStringsAndCorporateActionDateRatioPrecision() throws Exception {
        byte[] validBytes = loadFixture("valid-sample-bundle.zip");
        MockMultipartFile file = new MockMultipartFile("file", "valid-sample-bundle.zip", "application/zip", validBytes);
        String key = "decimal-key-" + UUID.randomUUID();

        String res = mockMvc.perform(multipart("/api/research/imports")
                        .file(file)
                        .header("Origin", "http://localhost:4200")
                        .header("Idempotency-Key", key))
                .andExpect(status().is2xxSuccessful())
                .andReturn().getResponse().getContentAsString();
        String jobId = extractJsonField(res, "id");
        HistoricalDtos.ImportJobResponse job = waitForJobCompletion(jobId);
        assertEquals("COMPLETED", job.status());
        String datasetId = job.datasetId();

        // 1. Verify exact price decimals on listing-eur-syn-1
        mockMvc.perform(get("/api/research/datasets/" + datasetId + "/history/listing-eur-syn-1")
                        .param("start", "2024-01-02")
                        .param("end", "2024-01-02"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bars[0].open").value("100.00"))
                .andExpect(jsonPath("$.bars[0].high").value("105.00"))
                .andExpect(jsonPath("$.bars[0].low").value("98.50"))
                .andExpect(jsonPath("$.bars[0].close").value("104.00"))
                .andExpect(jsonPath("$.bars[0].volume").value(15000))
                .andExpect(jsonPath("$.bars[0].availableAt").value("2024-01-02T18:00:00Z"));

        // 2. Verify corporate action split ratio and dates
        mockMvc.perform(get("/api/research/datasets/" + datasetId + "/history/listing-eur-syn-1")
                        .param("start", "2024-01-01")
                        .param("end", "2024-01-10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actions[0].actionId").value("act-syn-split-1"))
                .andExpect(jsonPath("$.actions[0].actionType").value("SPLIT"))
                .andExpect(jsonPath("$.actions[0].effectiveDate").value("2024-01-05"))
                .andExpect(jsonPath("$.actions[0].splitRatio").value("2:1"))
                .andExpect(jsonPath("$.actions[0].availableAt").value("2024-01-04T18:00:00Z"));

        // 3. Verify corporate action cash distribution amount and currency on listing-eur-syn-2
        mockMvc.perform(get("/api/research/datasets/" + datasetId + "/history/listing-eur-syn-2")
                        .param("start", "2024-01-01")
                        .param("end", "2024-01-10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actions[0].actionId").value("act-syn-div-2"))
                .andExpect(jsonPath("$.actions[0].actionType").value("CASH_DISTRIBUTION"))
                .andExpect(jsonPath("$.actions[0].effectiveDate").value("2024-01-08"))
                .andExpect(jsonPath("$.actions[0].distributionAmount").value("1.25"))
                .andExpect(jsonPath("$.actions[0].distributionCurrency").value("EUR"))
                .andExpect(jsonPath("$.actions[0].paymentDate").value("2024-01-10"))
                .andExpect(jsonPath("$.actions[0].availableAt").value("2024-01-07T18:00:00Z"));

        // 4. Verify pagination bounded response fields
        mockMvc.perform(get("/api/research/datasets/" + datasetId + "/history/listing-eur-syn-1")
                        .param("limit", "2")
                        .param("offset", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalBars").value(7))
                .andExpect(jsonPath("$.returnedBars").value(2))
                .andExpect(jsonPath("$.limit").value(2))
                .andExpect(jsonPath("$.offset").value(0))
                .andExpect(jsonPath("$.isTruncated").value(true))
                .andExpect(jsonPath("$.bars.length()").value(2));
    }

    private HistoricalDtos.ImportJobResponse waitForJobCompletion(String jobId) throws InterruptedException {
        HistoricalDtos.ImportJobResponse job = null;
        for (int i = 0; i < 50; i++) {
            job = jobService.getJob(jobId);
            if ("COMPLETED".equals(job.status()) || "FAILED".equals(job.status()) || "INTERRUPTED".equals(job.status())) {
                break;
            }
            Thread.sleep(100);
        }
        return job;
    }

    private byte[] modifyZipEntry(byte[] zipBytes, String entryToModify, java.util.function.UnaryOperator<String> modifier) throws IOException {
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        try (java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(zipBytes));
             java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(baos)) {
            java.util.zip.ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                zos.putNextEntry(new java.util.zip.ZipEntry(entry.getName()));
                byte[] content = zis.readAllBytes();
                if (entry.getName().equals(entryToModify)) {
                    String str = new String(content, java.nio.charset.StandardCharsets.UTF_8);
                    str = modifier.apply(str);
                    content = str.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                }
                zos.write(content);
                zos.closeEntry();
            }
        }
        return baos.toByteArray();
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
