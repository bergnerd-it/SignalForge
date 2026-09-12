package com.bergnerd.signalforge.app.research.historical;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;

@Slf4j
@Component
public class HistoricalDataValidator {

    public HistoricalDtos.ValidationResult validate(HistoricalDtos.ParsedBundle bundle) {
        List<HistoricalDtos.ValidationFinding> findings = new ArrayList<>();

        // 1. Validate Manifest
        validateManifest(bundle.manifest(), findings);

        // 2. Validate Instruments & Listings
        Map<String, HistoricalDtos.ParsedInstrumentRecord> listingMap = validateInstruments(bundle.instruments(), findings);

        // 3. Validate Sessions & Calendars
        Map<String, Map<String, HistoricalDtos.ParsedSessionRecord>> calendarSessions = validateSessions(bundle.sessions(), findings);

        // 4. Validate Prices
        validatePrices(bundle.prices(), listingMap, calendarSessions, findings);

        // 5. Validate Corporate Actions
        validateActions(bundle.actions(), bundle.manifest(), listingMap, findings);

        // 6. Check Coverage (Trading sessions vs Bars)
        checkCoverage(bundle.manifest(), bundle.prices(), listingMap, calendarSessions, findings);

        // Determine Status & Quality Label
        boolean hasError = findings.stream().anyMatch(f -> "ERROR".equals(f.severity()));
        boolean hasWarning = findings.stream().anyMatch(f -> "WARNING".equals(f.severity()));

        String status = hasError ? "REJECTED" : (hasWarning ? "WARNINGS" : "VALID");

        String qualityLabel;
        if (bundle.manifest() != null && "SYNTHETIC".equalsIgnoreCase(bundle.manifest().classification())) {
            qualityLabel = "SYNTHETIC";
        } else if (bundle.manifest() != null && bundle.manifest().availabilityAssumptions() != null && !bundle.manifest().availabilityAssumptions().isBlank()) {
            qualityLabel = "REVISED_HISTORY";
        } else {
            qualityLabel = "VERIFIED";
        }

        return new HistoricalDtos.ValidationResult(status, qualityLabel, findings);
    }

    private void validateManifest(HistoricalDtos.ManifestDto manifest, List<HistoricalDtos.ValidationFinding> findings) {
        if (manifest == null) {
            findings.add(new HistoricalDtos.ValidationFinding("MANIFEST_MISSING", "ERROR", "manifest.json", 1, null, null, "Manifest is null or missing"));
            return;
        }

        if (manifest.schemaVersion() == null || !manifest.schemaVersion().startsWith("1.")) {
            findings.add(new HistoricalDtos.ValidationFinding("INVALID_SCHEMA_VERSION", "ERROR", "manifest.json", 1, null, null, "Unsupported schema_version: " + manifest.schemaVersion()));
        }

        if (manifest.source() == null || manifest.source().isBlank()) {
            findings.add(new HistoricalDtos.ValidationFinding("MISSING_SOURCE", "ERROR", "manifest.json", 1, null, null, "source identifier is required"));
        }

        if (manifest.retrievedAt() == null || !isValidInstant(manifest.retrievedAt())) {
            findings.add(new HistoricalDtos.ValidationFinding("INVALID_RETRIEVAL_TIMESTAMP", "ERROR", "manifest.json", 1, null, null, "retrieved_at must be an ISO-8601 instant"));
        }

        if (manifest.coverage() == null || manifest.coverage().startDate() == null || manifest.coverage().endDate() == null) {
            findings.add(new HistoricalDtos.ValidationFinding("MISSING_COVERAGE", "ERROR", "manifest.json", 1, null, null, "declared coverage (start_date and end_date) is required"));
        } else {
            if (!isValidDate(manifest.coverage().startDate()) || !isValidDate(manifest.coverage().endDate())) {
                findings.add(new HistoricalDtos.ValidationFinding("INVALID_COVERAGE_DATES", "ERROR", "manifest.json", 1, null, null, "coverage dates must be YYYY-MM-DD"));
            } else if (manifest.coverage().startDate().compareTo(manifest.coverage().endDate()) > 0) {
                findings.add(new HistoricalDtos.ValidationFinding("COVERAGE_INVERTED", "ERROR", "manifest.json", 1, null, null, "coverage start_date must be <= end_date"));
            }
        }

        if (manifest.classification() == null || (!"SYNTHETIC".equalsIgnoreCase(manifest.classification()) && !"HISTORICAL".equalsIgnoreCase(manifest.classification()))) {
            findings.add(new HistoricalDtos.ValidationFinding("INVALID_CLASSIFICATION", "ERROR", "manifest.json", 1, null, null, "classification must be SYNTHETIC or HISTORICAL"));
        }

        if (manifest.priceConvention() == null || !"RAW".equalsIgnoreCase(manifest.priceConvention())) {
            findings.add(new HistoricalDtos.ValidationFinding("UNSUPPORTED_PRICE_CONVENTION", "ERROR", "manifest.json", 1, null, null, "price_convention must be RAW"));
        }
    }

    private Map<String, HistoricalDtos.ParsedInstrumentRecord> validateInstruments(
            List<HistoricalDtos.ParsedInstrumentRecord> instruments,
            List<HistoricalDtos.ValidationFinding> findings
    ) {
        Map<String, HistoricalDtos.ParsedInstrumentRecord> listingMap = new HashMap<>();
        Set<String> seenInstrumentIds = new HashSet<>();
        Set<String> seenListings = new HashSet<>();

        int rowNum = 1;
        for (HistoricalDtos.ParsedInstrumentRecord inst : instruments) {
            rowNum++;
            if (inst.listingId() == null || inst.listingId().isBlank()) {
                findings.add(new HistoricalDtos.ValidationFinding("MISSING_LISTING_ID", "ERROR", "instruments.csv", rowNum, null, null, "listing_id is required"));
                continue;
            }

            if (seenListings.contains(inst.listingId())) {
                findings.add(new HistoricalDtos.ValidationFinding("DUPLICATE_LISTING_ID", "ERROR", "instruments.csv", rowNum, inst.listingId(), null, "Duplicate listing_id: " + inst.listingId()));
            }
            seenListings.add(inst.listingId());

            if (!"EUR".equalsIgnoreCase(inst.quoteCurrency())) {
                findings.add(new HistoricalDtos.ValidationFinding("UNSUPPORTED_FX_CURRENCY", "ERROR", "instruments.csv", rowNum, inst.listingId(), null, "SignalForge V1 research requires quote_currency EUR; found: " + inst.quoteCurrency()));
            }

            if (inst.inceptionDate() != null && !isValidDate(inst.inceptionDate())) {
                findings.add(new HistoricalDtos.ValidationFinding("INVALID_INCEPTION_DATE", "ERROR", "instruments.csv", rowNum, inst.listingId(), null, "inception_date must be YYYY-MM-DD"));
            }
            if (inst.terminationDate() != null && !isValidDate(inst.terminationDate())) {
                findings.add(new HistoricalDtos.ValidationFinding("INVALID_TERMINATION_DATE", "ERROR", "instruments.csv", rowNum, inst.listingId(), null, "termination_date must be YYYY-MM-DD"));
            }
            if (inst.inceptionDate() != null && inst.terminationDate() != null && inst.inceptionDate().compareTo(inst.terminationDate()) > 0) {
                findings.add(new HistoricalDtos.ValidationFinding("INVERTED_LIFETIME", "ERROR", "instruments.csv", rowNum, inst.listingId(), null, "inception_date must be <= termination_date"));
            }

            listingMap.put(inst.listingId(), inst);
            seenInstrumentIds.add(inst.instrumentId());
        }

        if (listingMap.isEmpty()) {
            findings.add(new HistoricalDtos.ValidationFinding("EMPTY_INSTRUMENTS", "ERROR", "instruments.csv", 1, null, null, "At least one instrument and listing is required"));
        }

        return listingMap;
    }

    private Map<String, Map<String, HistoricalDtos.ParsedSessionRecord>> validateSessions(
            List<HistoricalDtos.ParsedSessionRecord> sessions,
            List<HistoricalDtos.ValidationFinding> findings
    ) {
        // Map: calendarId -> (sessionDate -> ParsedSessionRecord)
        Map<String, Map<String, HistoricalDtos.ParsedSessionRecord>> calendarSessions = new HashMap<>();

        int rowNum = 1;
        for (HistoricalDtos.ParsedSessionRecord s : sessions) {
            rowNum++;
            if (!isValidDate(s.sessionDate())) {
                findings.add(new HistoricalDtos.ValidationFinding("INVALID_SESSION_DATE", "ERROR", "sessions.csv", rowNum, null, s.sessionDate(), "session_date must be YYYY-MM-DD"));
                continue;
            }

            if (!"TRADING".equalsIgnoreCase(s.sessionType()) && !"CLOSED".equalsIgnoreCase(s.sessionType())) {
                findings.add(new HistoricalDtos.ValidationFinding("INVALID_SESSION_TYPE", "ERROR", "sessions.csv", rowNum, null, s.sessionDate(), "session_type must be TRADING or CLOSED"));
            }

            if (!isValidInstant(s.openTime()) || !isValidInstant(s.closeTime())) {
                findings.add(new HistoricalDtos.ValidationFinding("INVALID_SESSION_TIMES", "ERROR", "sessions.csv", rowNum, null, s.sessionDate(), "open_time and close_time must be ISO-8601 instants"));
            } else if (s.openTime().compareTo(s.closeTime()) >= 0) {
                findings.add(new HistoricalDtos.ValidationFinding("INVERTED_SESSION_TIMES", "ERROR", "sessions.csv", rowNum, null, s.sessionDate(), "open_time must be strictly earlier than close_time"));
            }

            Map<String, HistoricalDtos.ParsedSessionRecord> dates = calendarSessions.computeIfAbsent(s.calendarId(), k -> new HashMap<>());
            if (dates.containsKey(s.sessionDate())) {
                findings.add(new HistoricalDtos.ValidationFinding("DUPLICATE_SESSION", "ERROR", "sessions.csv", rowNum, null, s.sessionDate(), "Duplicate session for calendar " + s.calendarId() + " on " + s.sessionDate()));
            } else {
                dates.put(s.sessionDate(), s);
            }
        }

        return calendarSessions;
    }

    private void validatePrices(
            List<HistoricalDtos.ParsedPriceRecord> prices,
            Map<String, HistoricalDtos.ParsedInstrumentRecord> listings,
            Map<String, Map<String, HistoricalDtos.ParsedSessionRecord>> calendars,
            List<HistoricalDtos.ValidationFinding> findings
    ) {
        Set<String> seenListingSessions = new HashSet<>();

        int rowNum = 1;
        for (HistoricalDtos.ParsedPriceRecord p : prices) {
            rowNum++;
            HistoricalDtos.ParsedInstrumentRecord inst = listings.get(p.listingId());
            if (inst == null) {
                findings.add(new HistoricalDtos.ValidationFinding("UNKNOWN_LISTING_IN_PRICES", "ERROR", "prices.csv", rowNum, p.listingId(), p.sessionDate(), "Unknown listing_id in prices: " + p.listingId()));
                continue;
            }

            String key = p.listingId() + ":" + p.sessionDate();
            if (seenListingSessions.contains(key)) {
                findings.add(new HistoricalDtos.ValidationFinding("DUPLICATE_BAR", "ERROR", "prices.csv", rowNum, p.listingId(), p.sessionDate(), "Duplicate price bar for listing " + p.listingId() + " on date " + p.sessionDate()));
            }
            seenListingSessions.add(key);

            if (!isValidDate(p.sessionDate())) {
                findings.add(new HistoricalDtos.ValidationFinding("INVALID_PRICE_DATE", "ERROR", "prices.csv", rowNum, p.listingId(), p.sessionDate(), "session_date must be YYYY-MM-DD"));
                continue;
            }

            // Check session exists and is TRADING
            Map<String, HistoricalDtos.ParsedSessionRecord> sessions = calendars.get(inst.calendarId());
            if (sessions == null || !sessions.containsKey(p.sessionDate())) {
                findings.add(new HistoricalDtos.ValidationFinding("PRICE_FOR_NONEXISTENT_SESSION", "ERROR", "prices.csv", rowNum, p.listingId(), p.sessionDate(), "Price bar refers to nonexistent calendar session on " + p.sessionDate()));
            } else {
                HistoricalDtos.ParsedSessionRecord s = sessions.get(p.sessionDate());
                if ("CLOSED".equalsIgnoreCase(s.sessionType())) {
                    findings.add(new HistoricalDtos.ValidationFinding("PRICE_FOR_CLOSED_SESSION", "ERROR", "prices.csv", rowNum, p.listingId(), p.sessionDate(), "Price bar supplied for declared CLOSED session on " + p.sessionDate()));
                }

                // Knowledge time: available_at must be >= session close_time
                if (isValidInstant(p.availableAt()) && isValidInstant(s.closeTime())) {
                    if (p.availableAt().compareTo(s.closeTime()) < 0) {
                        findings.add(new HistoricalDtos.ValidationFinding("PREMATURE_DAILY_BAR_AVAILABILITY", "ERROR", "prices.csv", rowNum, p.listingId(), p.sessionDate(), "Daily bar available_at (" + p.availableAt() + ") cannot be earlier than session close_time (" + s.closeTime() + ")"));
                    }
                }
            }

            if (!isValidInstant(p.availableAt())) {
                findings.add(new HistoricalDtos.ValidationFinding("INVALID_PRICE_AVAILABILITY_TIME", "ERROR", "prices.csv", rowNum, p.listingId(), p.sessionDate(), "available_at must be an ISO-8601 instant"));
            }

            // Validate OHLC bounds and decimals
            try {
                BigDecimal open = new BigDecimal(p.open());
                BigDecimal high = new BigDecimal(p.high());
                BigDecimal low = new BigDecimal(p.low());
                BigDecimal close = new BigDecimal(p.close());

                if (open.compareTo(BigDecimal.ZERO) <= 0 || high.compareTo(BigDecimal.ZERO) <= 0
                        || low.compareTo(BigDecimal.ZERO) <= 0 || close.compareTo(BigDecimal.ZERO) <= 0) {
                    findings.add(new HistoricalDtos.ValidationFinding("NON_POSITIVE_OHLC", "ERROR", "prices.csv", rowNum, p.listingId(), p.sessionDate(), "OHLC values must be strictly positive"));
                }

                if (low.compareTo(open) > 0 || low.compareTo(close) > 0) {
                    findings.add(new HistoricalDtos.ValidationFinding("LOW_EXCEEDS_OPEN_OR_CLOSE", "ERROR", "prices.csv", rowNum, p.listingId(), p.sessionDate(), "Low price cannot exceed open or close"));
                }

                if (high.compareTo(open) < 0 || high.compareTo(close) < 0) {
                    findings.add(new HistoricalDtos.ValidationFinding("HIGH_BELOW_OPEN_OR_CLOSE", "ERROR", "prices.csv", rowNum, p.listingId(), p.sessionDate(), "High price cannot be less than open or close"));
                }

                if (low.compareTo(high) > 0) {
                    findings.add(new HistoricalDtos.ValidationFinding("LOW_EXCEEDS_HIGH", "ERROR", "prices.csv", rowNum, p.listingId(), p.sessionDate(), "Low price cannot exceed high price"));
                }

                if (open.scale() > 8 || high.scale() > 8 || low.scale() > 8 || close.scale() > 8) {
                    findings.add(new HistoricalDtos.ValidationFinding("EXCESSIVE_PRICE_SCALE", "ERROR", "prices.csv", rowNum, p.listingId(), p.sessionDate(), "Price scale cannot exceed 8 decimal places"));
                }
            } catch (NumberFormatException e) {
                findings.add(new HistoricalDtos.ValidationFinding("MALFORMED_PRICE_DECIMAL", "ERROR", "prices.csv", rowNum, p.listingId(), p.sessionDate(), "Invalid numeric format in OHLC values"));
            }

            if (p.volume() != null && !p.volume().isBlank()) {
                try {
                    BigDecimal vol = new BigDecimal(p.volume());
                    if (vol.compareTo(BigDecimal.ZERO) < 0) {
                        findings.add(new HistoricalDtos.ValidationFinding("NEGATIVE_VOLUME", "ERROR", "prices.csv", rowNum, p.listingId(), p.sessionDate(), "Volume must be nonnegative"));
                    }
                } catch (NumberFormatException e) {
                    findings.add(new HistoricalDtos.ValidationFinding("MALFORMED_VOLUME", "ERROR", "prices.csv", rowNum, p.listingId(), p.sessionDate(), "Invalid numeric format for volume: " + p.volume()));
                }
            }
        }
    }

    private void validateActions(
            List<HistoricalDtos.ParsedActionRecord> actions,
            HistoricalDtos.ManifestDto manifest,
            Map<String, HistoricalDtos.ParsedInstrumentRecord> listings,
            List<HistoricalDtos.ValidationFinding> findings
    ) {
        if (actions.isEmpty()) {
            if (manifest == null || manifest.actionCompleteness() == null || manifest.actionCompleteness().isBlank()) {
                findings.add(new HistoricalDtos.ValidationFinding("EMPTY_ACTIONS_WITHOUT_DECLARATION", "ERROR", "actions.csv", 1, null, null, "Empty actions.csv is accepted only with an explicit action_completeness declaration in manifest.json"));
            }
            return;
        }

        Set<String> seenActionIds = new HashSet<>();
        int rowNum = 1;
        for (HistoricalDtos.ParsedActionRecord a : actions) {
            rowNum++;
            if (seenActionIds.contains(a.actionId())) {
                findings.add(new HistoricalDtos.ValidationFinding("DUPLICATE_ACTION_ID", "ERROR", "actions.csv", rowNum, a.listingId(), a.effectiveDate(), "Duplicate action_id: " + a.actionId()));
            }
            seenActionIds.add(a.actionId());

            HistoricalDtos.ParsedInstrumentRecord inst = listings.get(a.listingId());
            if (inst == null) {
                findings.add(new HistoricalDtos.ValidationFinding("UNKNOWN_LISTING_IN_ACTIONS", "ERROR", "actions.csv", rowNum, a.listingId(), a.effectiveDate(), "Unknown listing_id in corporate actions: " + a.listingId()));
                continue;
            }

            if (!"SPLIT".equalsIgnoreCase(a.actionType()) && !"CASH_DISTRIBUTION".equalsIgnoreCase(a.actionType())) {
                findings.add(new HistoricalDtos.ValidationFinding("UNSUPPORTED_ACTION_TYPE", "ERROR", "actions.csv", rowNum, a.listingId(), a.effectiveDate(), "Unsupported corporate action type: " + a.actionType()));
            }

            if (!isValidDate(a.effectiveDate())) {
                findings.add(new HistoricalDtos.ValidationFinding("INVALID_ACTION_EFFECTIVE_DATE", "ERROR", "actions.csv", rowNum, a.listingId(), a.effectiveDate(), "effective_date must be YYYY-MM-DD"));
            }

            if (!isValidInstant(a.availableAt())) {
                findings.add(new HistoricalDtos.ValidationFinding("INVALID_ACTION_AVAILABILITY_TIME", "ERROR", "actions.csv", rowNum, a.listingId(), a.effectiveDate(), "available_at must be an ISO-8601 instant"));
            }

            if ("SPLIT".equalsIgnoreCase(a.actionType())) {
                if (a.splitRatioNumerator() == null || a.splitRatioDenominator() == null
                        || a.splitRatioNumerator() <= 0 || a.splitRatioDenominator() <= 0) {
                    findings.add(new HistoricalDtos.ValidationFinding("INVALID_SPLIT_RATIO", "ERROR", "actions.csv", rowNum, a.listingId(), a.effectiveDate(), "Split ratio terms must be positive integers"));
                }
            } else if ("CASH_DISTRIBUTION".equalsIgnoreCase(a.actionType())) {
                if (a.distributionAmount() == null || a.distributionAmount().isBlank()) {
                    findings.add(new HistoricalDtos.ValidationFinding("MISSING_DISTRIBUTION_AMOUNT", "ERROR", "actions.csv", rowNum, a.listingId(), a.effectiveDate(), "distribution_amount is required for CASH_DISTRIBUTION"));
                } else {
                    try {
                        BigDecimal dist = new BigDecimal(a.distributionAmount());
                        if (dist.compareTo(BigDecimal.ZERO) <= 0) {
                            findings.add(new HistoricalDtos.ValidationFinding("NON_POSITIVE_DISTRIBUTION", "ERROR", "actions.csv", rowNum, a.listingId(), a.effectiveDate(), "distribution_amount must be strictly positive"));
                        }
                    } catch (NumberFormatException e) {
                        findings.add(new HistoricalDtos.ValidationFinding("MALFORMED_DISTRIBUTION_AMOUNT", "ERROR", "actions.csv", rowNum, a.listingId(), a.effectiveDate(), "Invalid decimal format for distribution_amount"));
                    }
                }

                if (a.distributionCurrency() != null && !"EUR".equalsIgnoreCase(a.distributionCurrency())) {
                    findings.add(new HistoricalDtos.ValidationFinding("UNSUPPORTED_DISTRIBUTION_CURRENCY", "ERROR", "actions.csv", rowNum, a.listingId(), a.effectiveDate(), "distribution_currency must be EUR for V1 research"));
                }

                if (a.paymentDate() != null) {
                    if (!isValidDate(a.paymentDate())) {
                        findings.add(new HistoricalDtos.ValidationFinding("INVALID_PAYMENT_DATE", "ERROR", "actions.csv", rowNum, a.listingId(), a.effectiveDate(), "payment_date must be YYYY-MM-DD"));
                    } else if (a.effectiveDate() != null && a.paymentDate().compareTo(a.effectiveDate()) < 0) {
                        findings.add(new HistoricalDtos.ValidationFinding("PAYMENT_BEFORE_EFFECTIVE_DATE", "ERROR", "actions.csv", rowNum, a.listingId(), a.effectiveDate(), "payment_date cannot be earlier than effective_date"));
                    }
                }
            }
        }
    }

    private void checkCoverage(
            HistoricalDtos.ManifestDto manifest,
            List<HistoricalDtos.ParsedPriceRecord> prices,
            Map<String, HistoricalDtos.ParsedInstrumentRecord> listings,
            Map<String, Map<String, HistoricalDtos.ParsedSessionRecord>> calendars,
            List<HistoricalDtos.ValidationFinding> findings
    ) {
        if (manifest == null || manifest.coverage() == null) {
            return;
        }

        String covStart = manifest.coverage().startDate();
        String covEnd = manifest.coverage().endDate();
        if (covStart == null || covEnd == null) {
            return;
        }

        // Set of present price keys: listingId:date
        Set<String> presentBars = new HashSet<>();
        for (HistoricalDtos.ParsedPriceRecord p : prices) {
            presentBars.add(p.listingId() + ":" + p.sessionDate());
        }

        for (Map.Entry<String, HistoricalDtos.ParsedInstrumentRecord> entry : listings.entrySet()) {
            String listingId = entry.getKey();
            HistoricalDtos.ParsedInstrumentRecord inst = entry.getValue();

            String listStart = (inst.inceptionDate() != null && inst.inceptionDate().compareTo(covStart) > 0) ? inst.inceptionDate() : covStart;
            String listEnd = (inst.terminationDate() != null && inst.terminationDate().compareTo(covEnd) < 0) ? inst.terminationDate() : covEnd;

            Map<String, HistoricalDtos.ParsedSessionRecord> sessions = calendars.get(inst.calendarId());
            if (sessions == null) {
                findings.add(new HistoricalDtos.ValidationFinding("MISSING_CALENDAR", "ERROR", "instruments.csv", 1, listingId, null, "Calendar " + inst.calendarId() + " not defined in sessions.csv"));
                continue;
            }

            int missingTradingBars = 0;
            for (HistoricalDtos.ParsedSessionRecord s : sessions.values()) {
                if (s.sessionDate().compareTo(listStart) >= 0 && s.sessionDate().compareTo(listEnd) <= 0) {
                    if ("TRADING".equalsIgnoreCase(s.sessionType())) {
                        String key = listingId + ":" + s.sessionDate();
                        if (!presentBars.contains(key)) {
                            missingTradingBars++;
                            findings.add(new HistoricalDtos.ValidationFinding(
                                    "MISSING_TRADING_BAR",
                                    "ERROR",
                                    "prices.csv",
                                    null,
                                    listingId,
                                    s.sessionDate(),
                                    "Missing price bar for declared TRADING session on " + s.sessionDate()
                            ));
                        }
                    }
                }
            }
        }
    }

    private boolean isValidDate(String str) {
        if (str == null || str.length() != 10) return false;
        try {
            LocalDate.parse(str);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    private boolean isValidInstant(String str) {
        if (str == null || str.isBlank()) return false;
        try {
            Instant.parse(str);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }
}
