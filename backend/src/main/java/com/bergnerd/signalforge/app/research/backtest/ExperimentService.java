package com.bergnerd.signalforge.app.research.backtest;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class ExperimentService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

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

        String status = (request.declaredHoldoutStatus() != null && !request.declaredHoldoutStatus().isBlank())
                ? request.declaredHoldoutStatus().trim().toUpperCase() : "UNEXAMINED";
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
                request.strategyVersion() != null ? request.strategyVersion().trim() : "1.0.0",
                request.datasetId().trim(), request.universeId(), request.candidateListingId(),
                request.benchmarkListingId().trim(), request.developmentStartDate().trim(), request.developmentEndDate().trim(),
                request.holdoutStartDate().trim(), request.holdoutEndDate().trim(),
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
     */
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
            log.warn("Failed to record exposure event for experiment {}: {}", experimentId, e.getMessage());
        }
    }
}
