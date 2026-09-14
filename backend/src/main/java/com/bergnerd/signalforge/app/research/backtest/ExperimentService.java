package com.bergnerd.signalforge.app.research.backtest;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class ExperimentService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    @Transactional
    public BacktestDtos.ExperimentDto createExperiment(String ownerId, BacktestDtos.CreateExperimentRequest request) {
        String uid = (ownerId == null || ownerId.isBlank()) ? "default" : ownerId;
        if (request.name() == null || request.name().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name is required");
        }
        if (request.strategyId() == null || request.strategyId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "strategyId is required");
        }
        if (request.datasetId() == null || request.datasetId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "datasetId is required");
        }
        if (request.benchmarkListingId() == null || request.benchmarkListingId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "benchmarkListingId is required");
        }
        if (request.developmentStartDate() == null || request.developmentEndDate() == null ||
                request.holdoutStartDate() == null || request.holdoutEndDate() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Development and holdout dates are required");
        }

        LocalDate devStart, devEnd, holdStart, holdEnd;
        try {
            devStart = LocalDate.parse(request.developmentStartDate().trim());
            devEnd = LocalDate.parse(request.developmentEndDate().trim());
            holdStart = LocalDate.parse(request.holdoutStartDate().trim());
            holdEnd = LocalDate.parse(request.holdoutEndDate().trim());
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid date format: " + e.getMessage());
        }

        if (devStart.isAfter(devEnd)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "developmentStartDate cannot be after developmentEndDate");
        }
        if (!holdStart.isAfter(devEnd)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "holdoutStartDate must be strictly after developmentEndDate");
        }
        if (holdStart.isAfter(holdEnd)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "holdoutStartDate cannot be after holdoutEndDate");
        }

        // Validate dataset and coverage bounds
        List<Map<String, Object>> dsRows = jdbcTemplate.queryForList(
                "SELECT coverage_start, coverage_end FROM datasets WHERE id = ?", request.datasetId().trim()
        );
        if (dsRows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Dataset not found: " + request.datasetId());
        }
        String covStart = (String) dsRows.get(0).get("coverage_start");
        String covEnd = (String) dsRows.get(0).get("coverage_end");
        if (devStart.toString().compareTo(covStart) < 0 || holdEnd.toString().compareTo(covEnd) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Experiment date boundaries must fall within dataset coverage [" + covStart + ", " + covEnd + "]");
        }

        Integer sessionCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM dataset_sessions WHERE dataset_id = ?", Integer.class, request.datasetId().trim()
        );
        if (sessionCount == null || sessionCount == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Dataset has no trading sessions");
        }
        List<String> validDates = jdbcTemplate.queryForList(
                "SELECT session_date FROM dataset_sessions WHERE dataset_id = ? AND session_type = 'TRADING' " +
                        "AND calendar_id = (SELECT calendar_id FROM dataset_listings WHERE dataset_id = ? AND listing_id = ?) " +
                        "AND session_date IN (?, ?, ?, ?)",
                String.class, request.datasetId().trim(),
                request.datasetId().trim(), request.benchmarkListingId().trim(),
                devStart.toString(), devEnd.toString(), holdStart.toString(), holdEnd.toString()
        );
        if (validDates.size() < 4) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "One or more boundary dates are not trading sessions in dataset sessions");
        }

        // Validate strategy version
        String stratVer = request.strategyVersion() != null && !request.strategyVersion().isBlank()
                ? request.strategyVersion().trim() : "1.0.0";
        List<Map<String, Object>> svRows = jdbcTemplate.queryForList(
                "SELECT * FROM strategy_versions WHERE strategy_id = ? AND strategy_version = ?",
                request.strategyId().trim(), stratVer
        );
        if (svRows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown strategy " + request.strategyId() + " version " + stratVer);
        }
        if ("ETF_MOMENTUM_12_1_V1".equals(request.strategyId()) &&
                (request.universeId() == null || request.universeId().isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "S2 experiments require a universe");
        }
        if ("ETF_TREND_10M_V1".equals(request.strategyId()) &&
                (request.candidateListingId() == null || request.candidateListingId().isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "S3 experiments require a candidate listing");
        }

        // Validate universe if provided
        if (request.universeId() != null && !request.universeId().isBlank()) {
            List<Map<String, Object>> uRows = jdbcTemplate.queryForList(
                    "SELECT owner_id, dataset_id, calendar_id, currency FROM universes WHERE id = ?", request.universeId().trim()
            );
            if (uRows.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Universe not found: " + request.universeId());
            }
            if (!request.datasetId().trim().equals(uRows.get(0).get("dataset_id"))) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Universe dataset does not match experiment dataset");
            }
            String universeOwner = (String) uRows.get(0).get("owner_id");
            if (!uid.equals(universeOwner) && !"default".equals(universeOwner)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Universe belongs to another owner");
            }
            String benchmarkCalendar = jdbcTemplate.queryForObject(
                    "SELECT calendar_id FROM dataset_listings WHERE dataset_id = ? AND listing_id = ?",
                    String.class, request.datasetId().trim(), request.benchmarkListingId().trim());
            if (!java.util.Objects.equals(uRows.get(0).get("calendar_id"), benchmarkCalendar) ||
                    !"EUR".equals(uRows.get(0).get("currency"))) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Universe calendar/currency does not match the EUR benchmark");
            }
        }

        // Validate benchmark listing
        List<Map<String, Object>> bRows = jdbcTemplate.queryForList(
                "SELECT listing_id FROM dataset_listings WHERE dataset_id = ? AND listing_id = ?",
                request.datasetId().trim(), request.benchmarkListingId().trim()
        );
        if (bRows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Benchmark listing not found in dataset: " + request.benchmarkListingId());
        }

        // Validate candidate listing if provided
        if (request.candidateListingId() != null && !request.candidateListingId().isBlank()) {
            List<Map<String, Object>> cRows = jdbcTemplate.queryForList(
                    "SELECT listing_id FROM dataset_listings WHERE dataset_id = ? AND listing_id = ?",
                    request.datasetId().trim(), request.candidateListingId().trim()
            );
            if (cRows.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Candidate listing not found in dataset: " + request.candidateListingId());
            }
        }

        String status = (request.declaredHoldoutStatus() != null && !request.declaredHoldoutStatus().isBlank())
                ? request.declaredHoldoutStatus().trim().toUpperCase() : "UNKNOWN";
        if (!List.of("UNEXAMINED", "EXAMINED", "UNKNOWN").contains(status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid declaredHoldoutStatus: " + status);
        }

        // Determine next version for (owner_id, name)
        Integer maxVersion = jdbcTemplate.queryForObject(
                "SELECT MAX(version) FROM experiments WHERE owner_id = ? AND name = ?",
                Integer.class, uid, request.name().trim()
        );
        int version = (maxVersion != null) ? maxVersion + 1 : 1;

        String id = "exp-" + UUID.randomUUID();
        String now = Instant.now().toString();

        jdbcTemplate.update(
                "INSERT INTO experiments (" +
                        "id, owner_id, name, version, strategy_id, strategy_version, dataset_id, universe_id, " +
                        "candidate_listing_id, benchmark_listing_id, development_start_date, development_end_date, " +
                        "holdout_start_date, holdout_end_date, declared_holdout_status, parameters_json, created_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, uid, request.name().trim(), version, request.strategyId().trim(),
                stratVer, request.datasetId().trim(), request.universeId(), request.candidateListingId(),
                request.benchmarkListingId().trim(), devStart.toString(), devEnd.toString(),
                holdStart.toString(), holdEnd.toString(),
                status, request.parametersJson(), now
        );

        return getExperiment(id, uid);
    }

    public BacktestDtos.ExperimentDto getExperiment(String id, String ownerId) {
        String uid = (ownerId == null || ownerId.isBlank()) ? "default" : ownerId;
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT * FROM experiments WHERE id = ? AND (owner_id = ? OR owner_id = 'default')",
                id, uid
        );
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Experiment not found: " + id);
        }
        Map<String, Object> r = rows.get(0);

        List<BacktestDtos.ExperimentExposureEventDto> events = jdbcTemplate.query(
                "SELECT id, experiment_id, run_id, access_type, exposed_by, exposed_at, details_json " +
                        "FROM experiment_exposure_events WHERE experiment_id = ? ORDER BY exposed_at ASC",
                (rs, rowNum) -> new BacktestDtos.ExperimentExposureEventDto(
                        rs.getString("id"),
                        rs.getString("experiment_id"),
                        rs.getString("run_id"),
                        rs.getString("access_type"),
                        rs.getString("exposed_by"),
                        rs.getString("exposed_at"),
                        rs.getString("details_json")
                ),
                id
        );

        return new BacktestDtos.ExperimentDto(
                (String) r.get("id"),
                (String) r.get("owner_id"),
                (String) r.get("name"),
                ((Number) r.get("version")).intValue(),
                (String) r.get("strategy_id"),
                (String) r.get("strategy_version"),
                (String) r.get("dataset_id"),
                (String) r.get("universe_id"),
                (String) r.get("candidate_listing_id"),
                (String) r.get("benchmark_listing_id"),
                (String) r.get("development_start_date"),
                (String) r.get("development_end_date"),
                (String) r.get("holdout_start_date"),
                (String) r.get("holdout_end_date"),
                (String) r.get("declared_holdout_status"),
                (String) r.get("parameters_json"),
                events.size(),
                events,
                (String) r.get("created_at")
        );
    }

    public BacktestDtos.PagedResponse<BacktestDtos.ExperimentDto> listExperiments(String ownerId, int limit, int offset) {
        String uid = (ownerId == null || ownerId.isBlank()) ? "default" : ownerId;
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM experiments WHERE owner_id = ? OR owner_id = 'default'",
                Integer.class, uid
        );
        int total = count != null ? count : 0;

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT * FROM experiments WHERE owner_id = ? OR owner_id = 'default' " +
                        "ORDER BY created_at DESC LIMIT ? OFFSET ?",
                uid, limit, offset
        );

        List<BacktestDtos.ExperimentDto> list = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            String expId = (String) r.get("id");
            Integer evtCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM experiment_exposure_events WHERE experiment_id = ?",
                    Integer.class, expId
            );
            list.add(new BacktestDtos.ExperimentDto(
                    expId,
                    (String) r.get("owner_id"),
                    (String) r.get("name"),
                    ((Number) r.get("version")).intValue(),
                    (String) r.get("strategy_id"),
                    (String) r.get("strategy_version"),
                    (String) r.get("dataset_id"),
                    (String) r.get("universe_id"),
                    (String) r.get("candidate_listing_id"),
                    (String) r.get("benchmark_listing_id"),
                    (String) r.get("development_start_date"),
                    (String) r.get("development_end_date"),
                    (String) r.get("holdout_start_date"),
                    (String) r.get("holdout_end_date"),
                    (String) r.get("declared_holdout_status"),
                    (String) r.get("parameters_json"),
                    evtCount != null ? evtCount : 0,
                    List.of(),
                    (String) r.get("created_at")
            ));
        }

        return new BacktestDtos.PagedResponse<>(list, total, limit, offset, offset + list.size() < total);
    }

    /**
     * Records an immutable holdout exposure event before exposing holdout data.
     * Must fail closed if recording fails.
     */
    @Transactional
    public void recordExposure(String experimentId, String runId, String accessType, String exposedBy, String detailsJson) {
        if (experimentId == null || experimentId.isBlank()) {
            return;
        }
        String id = "exp-evt-" + UUID.randomUUID();
        String now = Instant.now().toString();
        try {
            jdbcTemplate.update(
                    "INSERT INTO experiment_exposure_events (" +
                            "id, experiment_id, run_id, access_type, exposed_by, exposed_at, details_json) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?)",
                    id, experimentId, runId, accessType,
                    (exposedBy != null && !exposedBy.isBlank()) ? exposedBy : "default",
                    now, detailsJson != null ? detailsJson : "{}"
            );
            log.info("Recorded holdout exposure event {} for experiment {} on run {}", id, experimentId, runId);
        } catch (Exception e) {
            log.error("Failed to record exposure event for experiment {}: {}", experimentId, e.getMessage());
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to record holdout exposure event: " + e.getMessage(), e);
        }
    }
}
