package com.bergnerd.signalforge.app.research.historical;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Slf4j
@Component
public class HistoricalBundleParser {

    public static final String PARSER_VERSION = "2.0.0-rfc4180";
    public static final long MAX_COMPRESSED_BYTES = 20 * 1024 * 1024; // 20 MB
    public static final long MAX_UNCOMPRESSED_BYTES = 100 * 1024 * 1024; // 100 MB
    public static final int MAX_FILE_COUNT = 10;
    public static final int MAX_ROWS_PER_FILE = 500_000;

    public static final Set<String> ALLOWED_FILES = Set.of(
            "manifest.json",
            "instruments.csv",
            "sessions.csv",
            "prices.csv",
            "actions.csv"
    );

    private final ObjectMapper objectMapper = new ObjectMapper();

    public static class BundleParseException extends RuntimeException {
        public BundleParseException(String message) {
            super(message);
        }

        public BundleParseException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public HistoricalDtos.ParsedBundle parseBundle(byte[] zipBytes) {
        if (zipBytes == null || zipBytes.length == 0) {
            throw new BundleParseException("Uploaded zip archive is empty");
        }
        if (zipBytes.length > MAX_COMPRESSED_BYTES) {
            throw new BundleParseException(String.format(
                    "Compressed size %d bytes exceeds maximum allowed limit of %d bytes",
                    zipBytes.length, MAX_COMPRESSED_BYTES
            ));
        }

        Map<String, byte[]> unzippedFiles = extractZip(zipBytes);

        // Check required files
        for (String required : ALLOWED_FILES) {
            if (!unzippedFiles.containsKey(required)) {
                throw new BundleParseException("Required root file missing from archive: " + required);
            }
        }

        // Parse manifest.json
        String manifestJson = new String(unzippedFiles.get("manifest.json"), StandardCharsets.UTF_8);
        HistoricalDtos.ManifestDto manifest;
        try {
            manifest = objectMapper.readValue(manifestJson, HistoricalDtos.ManifestDto.class);
        } catch (Exception e) {
            throw new BundleParseException("Failed to parse manifest.json: " + e.getMessage(), e);
        }
        if (manifest == null) {
            throw new BundleParseException("manifest.json yielded null manifest");
        }

        // Parse instruments.csv
        List<HistoricalDtos.ParsedInstrumentRecord> instruments = parseInstruments(
                new String(unzippedFiles.get("instruments.csv"), StandardCharsets.UTF_8)
        );

        // Parse sessions.csv
        List<HistoricalDtos.ParsedSessionRecord> sessions = parseSessions(
                new String(unzippedFiles.get("sessions.csv"), StandardCharsets.UTF_8)
        );

        // Parse prices.csv
        List<HistoricalDtos.ParsedPriceRecord> prices = parsePrices(
                new String(unzippedFiles.get("prices.csv"), StandardCharsets.UTF_8)
        );

        // Parse actions.csv
        List<HistoricalDtos.ParsedActionRecord> actions = parseActions(
                new String(unzippedFiles.get("actions.csv"), StandardCharsets.UTF_8)
        );

        return new HistoricalDtos.ParsedBundle(
                manifest,
                manifestJson,
                instruments,
                sessions,
                prices,
                actions
        );
    }

    private Map<String, byte[]> extractZip(byte[] zipBytes) {
        Map<String, byte[]> files = new HashMap<>();
        long totalUncompressedBytes = 0;
        int fileCount = 0;

        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                fileCount++;
                if (fileCount > MAX_FILE_COUNT) {
                    throw new BundleParseException("Archive contains more than " + MAX_FILE_COUNT + " entries");
                }

                String name = entry.getName();

                // Path traversal check
                if (name.contains("..") || name.startsWith("/") || name.startsWith("\\")) {
                    throw new BundleParseException("Archive entry has forbidden path traversal or absolute path: " + name);
                }

                // Ignore directories if any, but require root filenames only
                if (entry.isDirectory()) {
                    throw new BundleParseException("Archive contains forbidden directory entry: " + name);
                }

                if (name.contains("/") || name.contains("\\")) {
                    throw new BundleParseException("Archive entries must be at root; nested paths forbidden: " + name);
                }

                if (!ALLOWED_FILES.contains(name)) {
                    throw new BundleParseException("Unexpected file in archive: " + name);
                }

                if (files.containsKey(name)) {
                    throw new BundleParseException("Duplicate entry in archive: " + name);
                }

                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int read;
                long entryBytes = 0;
                while ((read = zis.read(buffer)) != -1) {
                    entryBytes += read;
                    totalUncompressedBytes += read;
                    if (totalUncompressedBytes > MAX_UNCOMPRESSED_BYTES) {
                        throw new BundleParseException(String.format(
                                "Total uncompressed size exceeds maximum allowed limit of %d bytes",
                                MAX_UNCOMPRESSED_BYTES
                        ));
                    }
                    bos.write(buffer, 0, read);
                }
                files.put(name, bos.toByteArray());
                zis.closeEntry();
            }
        } catch (IOException e) {
            throw new BundleParseException("Failed to decompress archive: " + e.getMessage(), e);
        }

        return files;
    }

    private List<HistoricalDtos.ParsedInstrumentRecord> parseInstruments(String csvContent) {
        List<Map<String, String>> records = Rfc4180CsvParser.parseRecords(csvContent);
        if (records.size() > MAX_ROWS_PER_FILE) {
            throw new BundleParseException("instruments.csv exceeds maximum row limit of " + MAX_ROWS_PER_FILE);
        }

        List<HistoricalDtos.ParsedInstrumentRecord> list = new ArrayList<>();
        for (Map<String, String> row : records) {
            list.add(new HistoricalDtos.ParsedInstrumentRecord(
                    getRequired(row, "instrument_id"),
                    getRequired(row, "listing_id"),
                    getRequired(row, "type"),
                    getRequired(row, "name"),
                    getOptional(row, "isin"),
                    getOptional(row, "venue"),
                    getRequired(row, "symbol"),
                    getRequired(row, "quote_currency"),
                    getRequired(row, "calendar_id"),
                    getOptional(row, "inception_date"),
                    getOptional(row, "termination_date")
            ));
        }
        return list;
    }

    private List<HistoricalDtos.ParsedSessionRecord> parseSessions(String csvContent) {
        List<Map<String, String>> records = Rfc4180CsvParser.parseRecords(csvContent);
        if (records.size() > MAX_ROWS_PER_FILE) {
            throw new BundleParseException("sessions.csv exceeds maximum row limit of " + MAX_ROWS_PER_FILE);
        }

        List<HistoricalDtos.ParsedSessionRecord> list = new ArrayList<>();
        for (Map<String, String> row : records) {
            list.add(new HistoricalDtos.ParsedSessionRecord(
                    getRequired(row, "calendar_id"),
                    getRequired(row, "session_date"),
                    getRequired(row, "open_time"),
                    getRequired(row, "close_time"),
                    getRequired(row, "session_type")
            ));
        }
        return list;
    }

    private List<HistoricalDtos.ParsedPriceRecord> parsePrices(String csvContent) {
        List<Map<String, String>> records = Rfc4180CsvParser.parseRecords(csvContent);
        if (records.size() > MAX_ROWS_PER_FILE) {
            throw new BundleParseException("prices.csv exceeds maximum row limit of " + MAX_ROWS_PER_FILE);
        }

        List<HistoricalDtos.ParsedPriceRecord> list = new ArrayList<>();
        for (Map<String, String> row : records) {
            list.add(new HistoricalDtos.ParsedPriceRecord(
                    getRequired(row, "listing_id"),
                    getRequired(row, "session_date"),
                    getRequired(row, "open"),
                    getRequired(row, "high"),
                    getRequired(row, "low"),
                    getRequired(row, "close"),
                    getOptional(row, "volume"),
                    getRequired(row, "available_at")
            ));
        }
        return list;
    }

    private List<HistoricalDtos.ParsedActionRecord> parseActions(String csvContent) {
        List<Map<String, String>> records = Rfc4180CsvParser.parseRecords(csvContent);
        if (records.size() > MAX_ROWS_PER_FILE) {
            throw new BundleParseException("actions.csv exceeds maximum row limit of " + MAX_ROWS_PER_FILE);
        }

        List<HistoricalDtos.ParsedActionRecord> list = new ArrayList<>();
        for (Map<String, String> row : records) {
            String splitRatioStr = getOptional(row, "split_ratio");
            Integer num = null;
            Integer den = null;
            if (splitRatioStr != null && !splitRatioStr.isBlank()) {
                int[] parsedRatio = parseSplitRatio(splitRatioStr);
                num = parsedRatio[0];
                den = parsedRatio[1];
            }

            list.add(new HistoricalDtos.ParsedActionRecord(
                    getRequired(row, "action_id"),
                    getRequired(row, "listing_id"),
                    getRequired(row, "action_type"),
                    getRequired(row, "effective_date"),
                    getRequired(row, "available_at"),
                    num,
                    den,
                    getOptional(row, "distribution_amount"),
                    getOptional(row, "distribution_currency"),
                    getOptional(row, "payment_date"),
                    getOptional(row, "payment_instant")
            ));
        }
        return list;
    }

    public static int[] parseSplitRatio(String ratioStr) {
        String clean = ratioStr.trim();
        try {
            if (clean.contains(":")) {
                String[] parts = clean.split(":");
                int num = Integer.parseInt(parts[0].trim());
                int den = Integer.parseInt(parts[1].trim());
                if (num <= 0 || den <= 0) {
                    throw new IllegalArgumentException("Split ratio terms must be positive integers: " + ratioStr);
                }
                return new int[]{num, den};
            } else if (clean.contains("/")) {
                String[] parts = clean.split("/");
                int num = Integer.parseInt(parts[0].trim());
                int den = Integer.parseInt(parts[1].trim());
                if (num <= 0 || den <= 0) {
                    throw new IllegalArgumentException("Split ratio terms must be positive integers: " + ratioStr);
                }
                return new int[]{num, den};
            } else {
                int num = Integer.parseInt(clean);
                if (num <= 0) {
                    throw new IllegalArgumentException("Split ratio must be positive: " + ratioStr);
                }
                return new int[]{num, 1};
            }
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid integer format for split ratio: " + ratioStr, e);
        }
    }

    private String getRequired(Map<String, String> row, String key) {
        String val = row.get(key);
        if (val == null || val.isBlank()) {
            throw new BundleParseException("Required CSV column missing or empty: " + key);
        }
        return val.trim();
    }

    private String getOptional(Map<String, String> row, String key) {
        String val = row.get(key);
        if (val == null || val.isBlank()) {
            return null;
        }
        return val.trim();
    }
}
