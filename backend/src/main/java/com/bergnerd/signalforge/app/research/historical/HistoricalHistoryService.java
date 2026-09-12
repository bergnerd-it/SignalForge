package com.bergnerd.signalforge.app.research.historical;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class HistoricalHistoryService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    private int clampLimit(int limit, int defaultLimit, int maxLimit) {
        if (limit <= 0) return defaultLimit;
        return Math.min(limit, maxLimit);
    }

    private int clampOffset(int offset) {
        return Math.max(offset, 0);
    }

    public HistoricalDtos.PagedResponse<HistoricalDtos.DatasetSummary> listDatasets(int rawLimit, int rawOffset) {
        int limit = clampLimit(rawLimit, 50, 200);
        int offset = clampOffset(rawOffset);

        Integer totalCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM datasets", Integer.class);
        int total = totalCount != null ? totalCount : 0;

        List<HistoricalDtos.DatasetSummary> items = jdbcTemplate.query(
                "SELECT d.id, d.name, d.source, d.classification, d.quality_label, d.coverage_start, d.coverage_end, " +
                        "d.validation_status, d.imported_at, " +
                        "(SELECT COUNT(*) FROM dataset_listings dl WHERE dl.dataset_id = d.id) AS listing_count, " +
                        "(SELECT COUNT(*) FROM historical_bars hb WHERE hb.dataset_id = d.id) AS bar_count, " +
                        "(SELECT COUNT(*) FROM historical_actions ha WHERE ha.dataset_id = d.id) AS action_count " +
                        "FROM datasets d ORDER BY d.imported_at DESC, d.id ASC LIMIT ? OFFSET ?",
                (rs, rowNum) -> new HistoricalDtos.DatasetSummary(
                        rs.getString("id"),
                        rs.getString("name"),
                        rs.getString("source"),
                        rs.getString("classification"),
                        rs.getString("quality_label"),
                        rs.getString("coverage_start"),
                        rs.getString("coverage_end"),
                        rs.getString("validation_status"),
                        rs.getString("imported_at"),
                        rs.getInt("listing_count"),
                        rs.getInt("bar_count"),
                        rs.getInt("action_count")
                ),
                limit, offset
        );

        return new HistoricalDtos.PagedResponse<>(items, total, limit, offset, offset + items.size() < total);
    }

    public List<HistoricalDtos.DatasetSummary> listDatasets() {
        return listDatasets(50, 0).items();
    }

    public HistoricalDtos.DatasetDetail getDatasetDetail(String datasetId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT d.id, d.name, d.source, d.classification, d.schema_version, d.parser_version, " +
                        "d.input_checksum, d.content_checksum, d.manifest_json, d.coverage_start, d.coverage_end, " +
                        "d.validation_status, d.validation_findings_json, d.quality_label, d.imported_at, " +
                        "(SELECT COUNT(*) FROM dataset_listings dl WHERE dl.dataset_id = d.id) AS listing_count, " +
                        "(SELECT COUNT(*) FROM historical_bars hb WHERE hb.dataset_id = d.id) AS bar_count, " +
                        "(SELECT COUNT(*) FROM historical_actions ha WHERE ha.dataset_id = d.id) AS action_count " +
                        "FROM datasets d WHERE d.id = ?",
                datasetId
        );

        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Dataset not found: " + datasetId);
        }

        Map<String, Object> r = rows.get(0);
        HistoricalDtos.ManifestDto manifest = null;
        try {
            manifest = objectMapper.readValue((String) r.get("manifest_json"), HistoricalDtos.ManifestDto.class);
        } catch (Exception e) {
            log.warn("Failed to parse manifest JSON for dataset {}: {}", datasetId, e.getMessage());
        }

        List<HistoricalDtos.ValidationFinding> findings = List.of();
        try {
            String findingsJson = (String) r.get("validation_findings_json");
            if (findingsJson != null && !findingsJson.isBlank()) {
                findings = objectMapper.readValue(findingsJson, new TypeReference<List<HistoricalDtos.ValidationFinding>>() {});
            }
        } catch (Exception e) {
            log.warn("Failed to parse validation findings JSON for dataset {}: {}", datasetId, e.getMessage());
        }

        return new HistoricalDtos.DatasetDetail(
                (String) r.get("id"),
                (String) r.get("name"),
                (String) r.get("source"),
                (String) r.get("classification"),
                (String) r.get("schema_version"),
                (String) r.get("parser_version"),
                (String) r.get("input_checksum"),
                (String) r.get("content_checksum"),
                (String) r.get("coverage_start"),
                (String) r.get("coverage_end"),
                (String) r.get("validation_status"),
                (String) r.get("quality_label"),
                (String) r.get("imported_at"),
                manifest,
                findings,
                ((Number) r.get("listing_count")).intValue(),
                ((Number) r.get("bar_count")).intValue(),
                ((Number) r.get("action_count")).intValue()
        );
    }

    public HistoricalDtos.PagedResponse<HistoricalDtos.DatasetListingDto> getDatasetListings(String datasetId, int rawLimit, int rawOffset) {
        ensureDatasetExists(datasetId);
        int limit = clampLimit(rawLimit, 100, 500);
        int offset = clampOffset(rawOffset);

        Integer totalCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM dataset_listings WHERE dataset_id = ?",
                Integer.class, datasetId
        );
        int total = totalCount != null ? totalCount : 0;

        List<HistoricalDtos.DatasetListingDto> items = jdbcTemplate.query(
                "SELECT dl.listing_id, dl.instrument_id, dl.symbol, dl.venue, dl.quote_currency, dl.calendar_id, " +
                        "dl.inception_date, dl.termination_date, dl.isin, " +
                        "COUNT(hb.session_date) AS bar_count, " +
                        "MIN(hb.session_date) AS first_date, " +
                        "MAX(hb.session_date) AS last_date " +
                        "FROM dataset_listings dl " +
                        "LEFT JOIN historical_bars hb ON hb.dataset_id = dl.dataset_id AND hb.listing_id = dl.listing_id " +
                        "WHERE dl.dataset_id = ? " +
                        "GROUP BY dl.listing_id ORDER BY dl.symbol ASC, dl.listing_id ASC LIMIT ? OFFSET ?",
                (rs, rowNum) -> new HistoricalDtos.DatasetListingDto(
                        rs.getString("listing_id"),
                        rs.getString("instrument_id"),
                        rs.getString("symbol"),
                        rs.getString("venue"),
                        rs.getString("quote_currency"),
                        rs.getString("calendar_id"),
                        rs.getString("inception_date"),
                        rs.getString("termination_date"),
                        rs.getString("isin"),
                        rs.getInt("bar_count"),
                        rs.getString("first_date"),
                        rs.getString("last_date")
                ),
                datasetId, limit, offset
        );

        return new HistoricalDtos.PagedResponse<>(items, total, limit, offset, offset + items.size() < total);
    }

    public List<HistoricalDtos.DatasetListingDto> getDatasetListings(String datasetId) {
        return getDatasetListings(datasetId, 100, 0).items();
    }

    public HistoricalDtos.PagedResponse<HistoricalDtos.DatasetSessionDto> getDatasetSessions(String datasetId, String calendarId, int rawLimit, int rawOffset) {
        ensureDatasetExists(datasetId);
        int limit = clampLimit(rawLimit, 500, 2000);
        int offset = clampOffset(rawOffset);

        StringBuilder countSql = new StringBuilder("SELECT COUNT(*) FROM dataset_sessions WHERE dataset_id = ? ");
        StringBuilder querySql = new StringBuilder(
                "SELECT calendar_id, session_date, open_time, close_time, session_type " +
                        "FROM dataset_sessions WHERE dataset_id = ? "
        );
        List<Object> countParams = new ArrayList<>();
        List<Object> queryParams = new ArrayList<>();
        countParams.add(datasetId);
        queryParams.add(datasetId);

        if (calendarId != null && !calendarId.isBlank()) {
            countSql.append("AND calendar_id = ? ");
            querySql.append("AND calendar_id = ? ");
            countParams.add(calendarId.trim());
            queryParams.add(calendarId.trim());
        }

        querySql.append("ORDER BY session_date ASC, calendar_id ASC LIMIT ? OFFSET ?");
        queryParams.add(limit);
        queryParams.add(offset);

        Integer totalCount = jdbcTemplate.queryForObject(countSql.toString(), Integer.class, countParams.toArray());
        int total = totalCount != null ? totalCount : 0;

        List<HistoricalDtos.DatasetSessionDto> items = jdbcTemplate.query(
                querySql.toString(),
                (rs, rowNum) -> new HistoricalDtos.DatasetSessionDto(
                        rs.getString("calendar_id"),
                        rs.getString("session_date"),
                        rs.getString("open_time"),
                        rs.getString("close_time"),
                        rs.getString("session_type")
                ),
                queryParams.toArray()
        );

        return new HistoricalDtos.PagedResponse<>(items, total, limit, offset, offset + items.size() < total);
    }

    public List<HistoricalDtos.DatasetSessionDto> getDatasetSessions(String datasetId) {
        return getDatasetSessions(datasetId, null, 500, 0).items();
    }

    public HistoricalDtos.PagedResponse<HistoricalDtos.HistoricalActionDto> getDatasetActions(String datasetId, String listingId, int rawLimit, int rawOffset) {
        ensureDatasetExists(datasetId);
        int limit = clampLimit(rawLimit, 100, 500);
        int offset = clampOffset(rawOffset);

        StringBuilder countSql = new StringBuilder("SELECT COUNT(*) FROM historical_actions WHERE dataset_id = ? ");
        StringBuilder querySql = new StringBuilder(
                "SELECT action_id, listing_id, action_type, effective_date, available_at, " +
                        "split_ratio_numerator, split_ratio_denominator, distribution_amount, distribution_currency, " +
                        "payment_date, payment_instant FROM historical_actions WHERE dataset_id = ? "
        );
        List<Object> countParams = new ArrayList<>();
        List<Object> queryParams = new ArrayList<>();
        countParams.add(datasetId);
        queryParams.add(datasetId);

        if (listingId != null && !listingId.isBlank()) {
            countSql.append("AND listing_id = ? ");
            querySql.append("AND listing_id = ? ");
            countParams.add(listingId.trim());
            queryParams.add(listingId.trim());
        }

        querySql.append("ORDER BY effective_date ASC, action_id ASC LIMIT ? OFFSET ?");
        queryParams.add(limit);
        queryParams.add(offset);

        Integer totalCount = jdbcTemplate.queryForObject(countSql.toString(), Integer.class, countParams.toArray());
        int total = totalCount != null ? totalCount : 0;

        List<HistoricalDtos.HistoricalActionDto> items = jdbcTemplate.query(
                querySql.toString(),
                (rs, rowNum) -> mapActionRow(rs),
                queryParams.toArray()
        );

        return new HistoricalDtos.PagedResponse<>(items, total, limit, offset, offset + items.size() < total);
    }

    public List<HistoricalDtos.HistoricalActionDto> getDatasetActions(String datasetId) {
        return getDatasetActions(datasetId, null, 100, 0).items();
    }

    public HistoricalDtos.ListingHistoryResponse getListingHistory(
            String datasetId,
            String listingId,
            String start,
            String end,
            String asOfCutoff,
            int rawLimit,
            int rawOffset
    ) {
        HistoricalDtos.DatasetDetail dataset = getDatasetDetail(datasetId);
        int limit = clampLimit(rawLimit, 1000, 5000);
        int offset = clampOffset(rawOffset);

        List<Map<String, Object>> listingRows = jdbcTemplate.queryForList(
                "SELECT symbol, quote_currency FROM dataset_listings WHERE dataset_id = ? AND listing_id = ?",
                datasetId, listingId
        );
        if (listingRows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Listing " + listingId + " not found in dataset " + datasetId);
        }
        String symbol = (String) listingRows.get(0).get("symbol");

        // Available coverage range for this listing
        Map<String, Object> bounds = jdbcTemplate.queryForMap(
                "SELECT MIN(session_date) AS min_date, MAX(session_date) AS max_date FROM historical_bars " +
                        "WHERE dataset_id = ? AND listing_id = ?",
                datasetId, listingId
        );
        String availableStart = (String) bounds.get("min_date");
        String availableEnd = (String) bounds.get("max_date");

        // Count total matching bars
        StringBuilder countSql = new StringBuilder(
                "SELECT COUNT(*) FROM historical_bars WHERE dataset_id = ? AND listing_id = ? "
        );
        List<Object> filterParams = new ArrayList<>();
        filterParams.add(datasetId);
        filterParams.add(listingId);

        if (start != null && !start.isBlank()) {
            countSql.append("AND session_date >= ? ");
            filterParams.add(start.trim());
        }
        if (end != null && !end.isBlank()) {
            countSql.append("AND session_date <= ? ");
            filterParams.add(end.trim());
        }
        if (asOfCutoff != null && !asOfCutoff.isBlank()) {
            countSql.append("AND available_at <= ? ");
            filterParams.add(asOfCutoff.trim());
        }

        Integer totalBarsCount = jdbcTemplate.queryForObject(countSql.toString(), Integer.class, filterParams.toArray());
        int totalBars = totalBarsCount != null ? totalBarsCount : 0;

        // Query bars page
        StringBuilder barSql = new StringBuilder(
                "SELECT session_date, open, high, low, close, volume, available_at FROM historical_bars " +
                        "WHERE dataset_id = ? AND listing_id = ? "
        );
        List<Object> barParams = new ArrayList<>(filterParams);

        if (start != null && !start.isBlank()) {
            barSql.append("AND session_date >= ? ");
        }
        if (end != null && !end.isBlank()) {
            barSql.append("AND session_date <= ? ");
        }
        if (asOfCutoff != null && !asOfCutoff.isBlank()) {
            barSql.append("AND available_at <= ? ");
        }
        barSql.append("ORDER BY session_date ASC LIMIT ? OFFSET ?");
        barParams.add(limit);
        barParams.add(offset);

        List<HistoricalDtos.HistoricalBarDto> bars = jdbcTemplate.query(
                barSql.toString(),
                (rs, rowNum) -> new HistoricalDtos.HistoricalBarDto(
                        rs.getString("session_date"),
                        rs.getString("open"),
                        rs.getString("high"),
                        rs.getString("low"),
                        rs.getString("close"),
                        rs.getString("volume"),
                        rs.getString("available_at")
                ),
                barParams.toArray()
        );

        // Query corporate actions for listing
        StringBuilder actSql = new StringBuilder(
                "SELECT action_id, listing_id, action_type, effective_date, available_at, " +
                        "split_ratio_numerator, split_ratio_denominator, distribution_amount, distribution_currency, " +
                        "payment_date, payment_instant FROM historical_actions " +
                        "WHERE dataset_id = ? AND listing_id = ? "
        );
        List<Object> actParams = new ArrayList<>();
        actParams.add(datasetId);
        actParams.add(listingId);

        if (start != null && !start.isBlank()) {
            actSql.append("AND effective_date >= ? ");
            actParams.add(start.trim());
        }
        if (end != null && !end.isBlank()) {
            actSql.append("AND effective_date <= ? ");
            actParams.add(end.trim());
        }
        if (asOfCutoff != null && !asOfCutoff.isBlank()) {
            actSql.append("AND available_at <= ? ");
            actParams.add(asOfCutoff.trim());
        }
        actSql.append("ORDER BY effective_date ASC, action_id ASC");

        List<HistoricalDtos.HistoricalActionDto> actions = jdbcTemplate.query(
                actSql.toString(),
                (rs, rowNum) -> mapActionRow(rs),
                actParams.toArray()
        );

        boolean isTruncated = totalBars > (offset + bars.size());

        String notes = null;
        if (start != null && availableStart != null && start.compareTo(availableStart) < 0) {
            notes = "Requested start date (" + start + ") is before available dataset start (" + availableStart + ")";
        }
        if (end != null && availableEnd != null && end.compareTo(availableEnd) > 0) {
            String endNote = "Requested end date (" + end + ") is after available dataset end (" + availableEnd + ")";
            notes = notes == null ? endNote : notes + "; " + endNote;
        }
        if (isTruncated) {
            String truncNote = "Response truncated: showing " + bars.size() + " of " + totalBars + " bars (limit=" + limit + ", offset=" + offset + ")";
            notes = notes == null ? truncNote : notes + "; " + truncNote;
        }

        return new HistoricalDtos.ListingHistoryResponse(
                datasetId,
                listingId,
                symbol,
                start,
                end,
                availableStart,
                availableEnd,
                asOfCutoff,
                dataset.qualityLabel(),
                bars,
                actions,
                notes,
                totalBars,
                bars.size(),
                limit,
                offset,
                isTruncated
        );
    }

    public HistoricalDtos.ListingHistoryResponse getListingHistory(
            String datasetId,
            String listingId,
            String start,
            String end,
            String asOfCutoff
    ) {
        return getListingHistory(datasetId, listingId, start, end, asOfCutoff, 1000, 0);
    }

    private void ensureDatasetExists(String datasetId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM datasets WHERE id = ?",
                Integer.class, datasetId
        );
        if (count == null || count == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Dataset not found: " + datasetId);
        }
    }

    private HistoricalDtos.HistoricalActionDto mapActionRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        Integer num = (Integer) rs.getObject("split_ratio_numerator");
        Integer den = (Integer) rs.getObject("split_ratio_denominator");
        String ratioStr = (num != null && den != null) ? num + ":" + den : null;

        return new HistoricalDtos.HistoricalActionDto(
                rs.getString("action_id"),
                rs.getString("listing_id"),
                rs.getString("action_type"),
                rs.getString("effective_date"),
                rs.getString("available_at"),
                ratioStr,
                rs.getString("distribution_amount"),
                rs.getString("distribution_currency"),
                rs.getString("payment_date"),
                rs.getString("payment_instant")
        );
    }
}
