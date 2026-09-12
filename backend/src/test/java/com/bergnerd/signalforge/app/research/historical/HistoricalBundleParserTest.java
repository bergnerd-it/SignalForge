package com.bergnerd.signalforge.app.research.historical;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class HistoricalBundleParserTest {

    private final HistoricalBundleParser parser = new HistoricalBundleParser();

    @Test
    void parsesValidFixtureBundle() throws IOException {
        Path fixturePath = Path.of("../test/fixtures/historical/valid-sample-bundle.zip");
        if (!Files.exists(fixturePath)) {
            fixturePath = Path.of("test/fixtures/historical/valid-sample-bundle.zip");
        }
        byte[] bytes = Files.readAllBytes(fixturePath);

        HistoricalDtos.ParsedBundle bundle = parser.parseBundle(bytes);
        assertNotNull(bundle);
        assertNotNull(bundle.manifest());
        assertEquals("RAW", bundle.manifest().priceConvention());
        assertEquals(2, bundle.instruments().size());
        assertEquals(8, bundle.sessions().size());
        assertEquals(14, bundle.prices().size());
        assertEquals(2, bundle.actions().size());
    }

    @Test
    void rejectsPathTraversalEntry() throws IOException {
        Path fixturePath = Path.of("../test/fixtures/historical/invalid-traversal.zip");
        if (!Files.exists(fixturePath)) {
            fixturePath = Path.of("test/fixtures/historical/invalid-traversal.zip");
        }
        byte[] bytes = Files.readAllBytes(fixturePath);

        assertThrows(HistoricalBundleParser.BundleParseException.class, () -> parser.parseBundle(bytes));
    }

    @Test
    void rejectsEmptyZip() {
        assertThrows(HistoricalBundleParser.BundleParseException.class, () -> parser.parseBundle(new byte[0]));
    }

    @Test
    void parsesSplitRatioFormats() {
        assertArrayEquals(new int[]{2, 1}, HistoricalBundleParser.parseSplitRatio("2:1"));
        assertArrayEquals(new int[]{3, 2}, HistoricalBundleParser.parseSplitRatio("3/2"));
        assertArrayEquals(new int[]{4, 1}, HistoricalBundleParser.parseSplitRatio("4"));
        assertThrows(IllegalArgumentException.class, () -> HistoricalBundleParser.parseSplitRatio("0:1"));
        assertThrows(IllegalArgumentException.class, () -> HistoricalBundleParser.parseSplitRatio("-2:1"));
        assertThrows(IllegalArgumentException.class, () -> HistoricalBundleParser.parseSplitRatio("invalid"));
    }
}
