package com.bergnerd.signalforge.app.research.historical;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class HistoricalDataValidatorTest {

    private final HistoricalBundleParser parser = new HistoricalBundleParser();
    private final HistoricalDataValidator validator = new HistoricalDataValidator();

    private byte[] loadFixture(String name) throws IOException {
        Path fixturePath = Path.of("../test/fixtures/historical/" + name);
        if (!Files.exists(fixturePath)) {
            fixturePath = Path.of("test/fixtures/historical/" + name);
        }
        return Files.readAllBytes(fixturePath);
    }

    @Test
    void validatesValidSampleBundle() throws IOException {
        byte[] bytes = loadFixture("valid-sample-bundle.zip");
        HistoricalDtos.ParsedBundle bundle = parser.parseBundle(bytes);
        HistoricalDtos.ValidationResult result = validator.validate(bundle);

        assertTrue(result.isValid());
        assertEquals("SYNTHETIC", result.qualityLabel());
        assertEquals("VALID", result.status());
    }

    @Test
    void rejectsMissingTradingBar() throws IOException {
        byte[] bytes = loadFixture("invalid-missing-bar.zip");
        HistoricalDtos.ParsedBundle bundle = parser.parseBundle(bytes);
        HistoricalDtos.ValidationResult result = validator.validate(bundle);

        assertFalse(result.isValid());
        assertEquals("REJECTED", result.status());
        assertTrue(result.findings().stream().anyMatch(f -> "MISSING_TRADING_BAR".equals(f.code())));
    }

    @Test
    void rejectsUnsupportedPriceConvention() throws IOException {
        byte[] bytes = loadFixture("invalid-adjusted-data.zip");
        HistoricalDtos.ParsedBundle bundle = parser.parseBundle(bytes);
        HistoricalDtos.ValidationResult result = validator.validate(bundle);

        assertFalse(result.isValid());
        assertEquals("REJECTED", result.status());
        assertTrue(result.findings().stream().anyMatch(f -> "UNSUPPORTED_PRICE_CONVENTION".equals(f.code())));
    }

    @Test
    void rejectsDuplicateBars() throws IOException {
        byte[] bytes = loadFixture("invalid-duplicate-keys.zip");
        HistoricalDtos.ParsedBundle bundle = parser.parseBundle(bytes);
        HistoricalDtos.ValidationResult result = validator.validate(bundle);

        assertFalse(result.isValid());
        assertEquals("REJECTED", result.status());
        assertTrue(result.findings().stream().anyMatch(f -> "DUPLICATE_BAR".equals(f.code())));
    }

    @Test
    void rejectsPaymentBeforeEffectiveDate() throws IOException {
        byte[] bytes = loadFixture("invalid-bad-actions.zip");
        HistoricalDtos.ParsedBundle bundle = parser.parseBundle(bytes);
        HistoricalDtos.ValidationResult result = validator.validate(bundle);

        assertFalse(result.isValid());
        assertEquals("REJECTED", result.status());
        assertTrue(result.findings().stream().anyMatch(f -> "PAYMENT_BEFORE_EFFECTIVE_DATE".equals(f.code())));
    }

    @Test
    void generatesSplitPriceDiscontinuityDiagnosticAsQualityFinding() throws IOException {
        byte[] bytes = loadFixture("valid-sample-bundle.zip");
        HistoricalDtos.ParsedBundle bundle = parser.parseBundle(bytes);
        HistoricalDtos.ValidationResult result = validator.validate(bundle);

        assertTrue(result.isValid());
        assertEquals("VALID", result.status());
        // Verify split price diagnostic finding is present as INFO
        HistoricalDtos.ValidationFinding splitDiag = result.findings().stream()
                .filter(f -> "SPLIT_PRICE_DISCONTINUITY_DIAGNOSTIC".equals(f.code()))
                .findFirst()
                .orElse(null);
        assertNotNull(splitDiag, "Expected split price discontinuity diagnostic finding");
        assertEquals("INFO", splitDiag.severity());
        assertTrue(splitDiag.detail().contains("Split 2:1 on 2024-01-05"));
    }

    @Test
    void rejectsInvalidOhlcBounds() {
        HistoricalDtos.ParsedPriceRecord badHigh = new HistoricalDtos.ParsedPriceRecord(
                "listing-1", "2024-01-02", "100.00", "95.00", "90.00", "98.00", "1000", "2024-01-02T18:00:00Z"
        );
        HistoricalDtos.ParsedBundle bundle = new HistoricalDtos.ParsedBundle(
                new HistoricalDtos.ManifestDto("1.0", "src", "2024-01-01T00:00:00Z", new HistoricalDtos.CoverageDto("2024-01-02", "2024-01-02"), null, "SYNTHETIC", "RAW", "COMPLETE", "COMPLETE", null, null),
                "{}",
                java.util.List.of(new HistoricalDtos.ParsedInstrumentRecord("inst-1", "listing-1", "EQUITY", "Test", null, "XETRA", "TEST", "EUR", "cal-1", "2024-01-01", null)),
                java.util.List.of(new HistoricalDtos.ParsedSessionRecord("cal-1", "2024-01-02", "2024-01-02T08:00:00Z", "2024-01-02T16:30:00Z", "TRADING")),
                java.util.List.of(badHigh),
                java.util.List.of()
        );

        HistoricalDtos.ValidationResult result = validator.validate(bundle);
        assertFalse(result.isValid());
        assertEquals("REJECTED", result.status());
        assertTrue(result.findings().stream().anyMatch(f -> "HIGH_BELOW_OPEN_OR_CLOSE".equals(f.code())));
    }

    @Test
    void rejectsLookaheadAvailability() {
        HistoricalDtos.ParsedPriceRecord lookahead = new HistoricalDtos.ParsedPriceRecord(
                "listing-1", "2024-01-02", "100.00", "105.00", "99.00", "102.00", "1000", "2024-01-02T12:00:00Z"
        );
        HistoricalDtos.ParsedBundle bundle = new HistoricalDtos.ParsedBundle(
                new HistoricalDtos.ManifestDto("1.0", "src", "2024-01-01T00:00:00Z", new HistoricalDtos.CoverageDto("2024-01-02", "2024-01-02"), null, "SYNTHETIC", "RAW", "COMPLETE", "COMPLETE", null, null),
                "{}",
                java.util.List.of(new HistoricalDtos.ParsedInstrumentRecord("inst-1", "listing-1", "EQUITY", "Test", null, "XETRA", "TEST", "EUR", "cal-1", "2024-01-01", null)),
                java.util.List.of(new HistoricalDtos.ParsedSessionRecord("cal-1", "2024-01-02", "2024-01-02T08:00:00Z", "2024-01-02T16:30:00Z", "TRADING")),
                java.util.List.of(lookahead),
                java.util.List.of()
        );

        HistoricalDtos.ValidationResult result = validator.validate(bundle);
        assertFalse(result.isValid());
        assertEquals("REJECTED", result.status());
        assertTrue(result.findings().stream().anyMatch(f -> "PREMATURE_DAILY_BAR_AVAILABILITY".equals(f.code())));
    }
}
