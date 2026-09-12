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
}
