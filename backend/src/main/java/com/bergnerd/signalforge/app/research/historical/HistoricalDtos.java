package com.bergnerd.signalforge.app.research.historical;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public class HistoricalDtos {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ManifestDto(
            @JsonProperty("schema_version") String schemaVersion,
            @JsonProperty("source") String source,
            @JsonProperty("retrieved_at") String retrievedAt,
            @JsonProperty("coverage") CoverageDto coverage,
            @JsonProperty("license_note") String licenseNote,
            @JsonProperty("classification") String classification,
            @JsonProperty("price_convention") String priceConvention,
            @JsonProperty("calendar_completeness") String calendarCompleteness,
            @JsonProperty("action_completeness") String actionCompleteness,
            @JsonProperty("known_limitations") String knownLimitations,
            @JsonProperty("availability_assumptions") String availabilityAssumptions
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CoverageDto(
            @JsonProperty("start_date") String startDate,
            @JsonProperty("end_date") String endDate
    ) {}

    public record ParsedInstrumentRecord(
            String instrumentId,
            String listingId,
            String type,
            String name,
            String isin,
            String venue,
            String symbol,
            String quoteCurrency,
            String calendarId,
            String inceptionDate,
            String terminationDate
    ) {}

    public record ParsedSessionRecord(
            String calendarId,
            String sessionDate,
            String openTime,
            String closeTime,
            String sessionType
    ) {}

    public record ParsedPriceRecord(
            String listingId,
            String sessionDate,
            String open,
            String high,
            String low,
            String close,
            String volume,
            String availableAt
    ) {}

    public record ParsedActionRecord(
            String actionId,
            String listingId,
            String actionType,
            String effectiveDate,
            String availableAt,
            Integer splitRatioNumerator,
            Integer splitRatioDenominator,
            String distributionAmount,
            String distributionCurrency,
            String paymentDate,
            String paymentInstant
    ) {}

    public record ParsedBundle(
            ManifestDto manifest,
            String manifestRawJson,
            List<ParsedInstrumentRecord> instruments,
            List<ParsedSessionRecord> sessions,
            List<ParsedPriceRecord> prices,
            List<ParsedActionRecord> actions
    ) {}

    public record ValidationFinding(
            String code,
            String severity, // ERROR, WARNING, INFO
            String file,
            Integer row,
            String listingId,
            String sessionDate,
            String detail
    ) {}

    public record ValidationResult(
            String status, // VALID, WARNINGS, REJECTED
            String qualityLabel, // SYNTHETIC, REVISED_HISTORY, VERIFIED
            List<ValidationFinding> findings
    ) {
        public boolean isValid() {
            return !"REJECTED".equals(status);
        }
    }

    public record ImportJobResponse(
            String id,
            String requestKey,
            String inputChecksum,
            String status,
            int progressPct,
            String datasetId,
            String message,
            String errorDetail,
            String createdAt,
            String updatedAt
    ) {}

    public record DatasetSummary(
            String id,
            String name,
            String source,
            String classification,
            String qualityLabel,
            String coverageStart,
            String coverageEnd,
            String validationStatus,
            String importedAt,
            int listingCount,
            int barCount,
            int actionCount
    ) {}

    public record DatasetDetail(
            String id,
            String name,
            String source,
            String classification,
            String schemaVersion,
            String parserVersion,
            String inputChecksum,
            String contentChecksum,
            String coverageStart,
            String coverageEnd,
            String validationStatus,
            String qualityLabel,
            String importedAt,
            ManifestDto manifest,
            List<ValidationFinding> validationFindings,
            int listingCount,
            int barCount,
            int actionCount
    ) {}

    public record DatasetListingDto(
            String listingId,
            String instrumentId,
            String symbol,
            String venue,
            String quoteCurrency,
            String calendarId,
            String inceptionDate,
            String terminationDate,
            String isin,
            int barCount,
            String firstDate,
            String lastDate
    ) {}

    public record DatasetSessionDto(
            String calendarId,
            String sessionDate,
            String openTime,
            String closeTime,
            String sessionType
    ) {}

    public record HistoricalBarDto(
            String sessionDate,
            String open,
            String high,
            String low,
            String close,
            String volume,
            String availableAt
    ) {}

    public record HistoricalActionDto(
            String actionId,
            String listingId,
            String actionType,
            String effectiveDate,
            String availableAt,
            String splitRatio,
            String distributionAmount,
            String distributionCurrency,
            String paymentDate,
            String paymentInstant
    ) {}

    public record PagedResponse<T>(
            List<T> items,
            int totalCount,
            int limit,
            int offset,
            boolean hasMore
    ) {}

    public record ListingHistoryResponse(
            String datasetId,
            String listingId,
            String symbol,
            String requestedStart,
            String requestedEnd,
            String availableStart,
            String availableEnd,
            String asOfCutoff,
            String qualityLabel,
            List<HistoricalBarDto> bars,
            List<HistoricalActionDto> actions,
            String coverageNotes,
            int totalBars,
            int returnedBars,
            int limit,
            int offset,
            boolean isTruncated
    ) {}
}
