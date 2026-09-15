package com.bergnerd.signalforge.app.research;

import com.bergnerd.signalforge.app.accounting.AccountingCore;
import com.bergnerd.signalforge.app.operation.IdempotencyExceptions;
import com.bergnerd.signalforge.app.operation.OperationService;
import com.bergnerd.signalforge.app.research.paper.PaperDataReadinessService;
import com.bergnerd.signalforge.app.research.paper.PaperExportService;
import com.bergnerd.signalforge.app.research.paper.PaperPortfolioService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/research")
@RequiredArgsConstructor
public class ResearchPortfolioController {

    private final OperationService operationService;
    private final JdbcTemplate jdbcTemplate;
    private final PaperPortfolioService paperPortfolioService;
    private final PaperDataReadinessService dataReadinessService;
    private final PaperExportService paperExportService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @PostMapping("/portfolios")
    public ResponseEntity<ResearchDtos.ResearchPortfolioDetail> createPortfolio(
            @RequestHeader(value = "X-User-Id", required = false, defaultValue = "default") String ownerId,
            @RequestHeader(value = "Idempotency-Key", required = false) String headerKey,
            @Valid @RequestBody ResearchDtos.CreatePortfolioRequest request
    ) {
        String uid = normalizeUser(ownerId);
        String effectiveKey = (headerKey != null && !headerKey.isBlank())
                ? headerKey.trim()
                : (request.idempotencyKey() != null ? request.idempotencyKey().trim() : null);

        if (effectiveKey == null || effectiveKey.isBlank()) {
            throw new IdempotencyExceptions.MissingIdempotencyKeyException(
                    "Idempotency-Key header or body property is required for portfolio creation"
            );
        }
        if (!"PAPER".equals(request.mode()) || !"EUR".equals(request.baseCurrency())) {
            throw new IllegalArgumentException("Research portfolios require explicit PAPER mode and EUR currency");
        }

        BigDecimal initialCash;
        try {
            initialCash = AccountingCore.normalizeStartingCash(
                    new BigDecimal(request.initialCash())
            );
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid decimal format for initialCash: " + request.initialCash());
        }

        OperationService.PortfolioCreationResult result = operationService.createPortfolio(
                uid,
                request.name(),
                request.mode(),
                request.baseCurrency(),
                initialCash,
                effectiveKey
        );

        OperationService.PortfolioView view = operationService.getPortfolioView(result.portfolioId());
        ResearchDtos.ResearchPortfolioDetail detail = toDetail(view, result.createdAt());

        HttpStatus status = result.isRetry() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(detail);
    }

    @GetMapping("/portfolios")
    public ResponseEntity<ResearchDtos.PagedResponse<ResearchDtos.ResearchPortfolioSummary>> listPortfolios(
            @RequestHeader(value = "X-User-Id", required = false, defaultValue = "default") String ownerId,
            @RequestParam(value = "limit", defaultValue = "50") int limit,
            @RequestParam(value = "offset", defaultValue = "0") int offset
    ) {
        String uid = normalizeUser(ownerId);

        Integer totalCountObj = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM portfolios WHERE owner_id = ? AND mode = 'PAPER'",
                Integer.class,
                uid
        );
        int total = totalCountObj != null ? totalCountObj : 0;

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT p.id, p.owner_id, p.name, p.mode, p.base_currency, s.cash_amount, s.revision, p.created_at, p.paper_started_at " +
                        "FROM portfolios p JOIN portfolio_state s ON p.id = s.portfolio_id " +
                        "WHERE p.owner_id = ? AND p.mode = 'PAPER' " +
                        "ORDER BY p.created_at DESC LIMIT ? OFFSET ?",
                uid, limit, offset
        );

        List<ResearchDtos.ResearchPortfolioSummary> summaries = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            String portId = (String) r.get("id");
            Integer posCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM positions WHERE portfolio_id = ?",
                    Integer.class,
                    portId
            );

            List<Map<String, Object>> segRows = jdbcTemplate.queryForList(
                    "SELECT strategy_id, universe_id, approval_mode, status, adopted_dataset_id FROM paper_portfolio_segments WHERE portfolio_id = ? AND status = 'ACTIVE'",
                    portId
            );
            String approvalMode = "MANUAL";
            String segStatus = "UNSTARTED";
            String strategyId = null;
            String universeId = null;
            String adoptedDatasetId = null;

            if (!segRows.isEmpty()) {
                Map<String, Object> seg = segRows.get(0);
                approvalMode = (String) seg.get("approval_mode");
                segStatus = (String) seg.get("status");
                strategyId = (String) seg.get("strategy_id");
                universeId = (String) seg.get("universe_id");
                adoptedDatasetId = (String) seg.get("adopted_dataset_id");
            }

            summaries.add(new ResearchDtos.ResearchPortfolioSummary(
                    portId,
                    (String) r.get("owner_id"),
                    (String) r.get("name"),
                    (String) r.get("mode"),
                    (String) r.get("base_currency"),
                    (String) r.get("cash_amount"),
                    ((Number) r.get("revision")).intValue(),
                    (String) r.get("created_at"),
                    (String) r.get("paper_started_at"),
                    segStatus,
                    posCount != null ? posCount : 0,
                    approvalMode,
                    segStatus,
                    strategyId,
                    universeId,
                    adoptedDatasetId
            ));
        }

        return ResponseEntity.ok(new ResearchDtos.PagedResponse<>(summaries, total, limit, offset, (offset + summaries.size()) >= total));
    }

    @GetMapping("/portfolios/{id}")
    public ResponseEntity<ResearchDtos.ResearchPortfolioDetail> getPortfolio(
            @PathVariable String id,
            @RequestHeader(value = "X-User-Id", required = false, defaultValue = "default") String ownerId
    ) {
        String uid = normalizeUser(ownerId);
        Integer inScope = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM portfolios WHERE id = ? AND owner_id = ? AND mode = 'PAPER'",
                Integer.class,
                id, uid
        );
        if (inScope == null || inScope == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Research portfolio not found");
        }
        OperationService.PortfolioView view = operationService.getPortfolioView(id);
        String createdAt = jdbcTemplate.queryForObject(
                "SELECT created_at FROM portfolios WHERE id = ?",
                String.class,
                id
        );
        return ResponseEntity.ok(toDetail(view, createdAt));
    }

    @PostMapping("/portfolios/{id}/activate")
    public ResponseEntity<ResearchDtos.PaperSegmentDto> activatePortfolio(
            @PathVariable String id,
            @RequestHeader(value = "X-User-Id", required = false, defaultValue = "default") String ownerId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody ResearchDtos.ActivatePortfolioRequest request
    ) {
        ResearchDtos.PaperSegmentDto segment = paperPortfolioService.activatePortfolio(id, ownerId, idempotencyKey, request);
        return ResponseEntity.ok(segment);
    }

    @PostMapping("/portfolios/{id}/adopt-dataset")
    public ResponseEntity<PaperDataReadinessService.DatasetAdoptionResult> adoptDataset(
            @PathVariable String id,
            @RequestHeader(value = "X-User-Id", required = false, defaultValue = "default") String ownerId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody ResearchDtos.AdoptDatasetRequest request
    ) {
        String uid = normalizeUser(ownerId);
        validatePortfolioOwnership(id, uid);
        PaperDataReadinessService.DatasetAdoptionResult result = dataReadinessService.adoptDataset(id, request.datasetId());
        return ResponseEntity.ok(result);
    }

    @PostMapping("/portfolios/{id}/evaluate")
    public ResponseEntity<ResearchDtos.PaperProposalDto> evaluatePortfolio(
            @PathVariable String id,
            @RequestHeader(value = "X-User-Id", required = false, defaultValue = "default") String ownerId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        ResearchDtos.PaperProposalDto proposal = paperPortfolioService.evaluatePortfolio(id, ownerId, idempotencyKey);
        return ResponseEntity.ok(proposal);
    }

    @GetMapping("/portfolios/{id}/proposals")
    public ResponseEntity<ResearchDtos.PagedResponse<ResearchDtos.PaperProposalDto>> listProposals(
            @PathVariable String id,
            @RequestHeader(value = "X-User-Id", required = false, defaultValue = "default") String ownerId,
            @RequestParam(value = "limit", defaultValue = "20") int limit,
            @RequestParam(value = "offset", defaultValue = "0") int offset
    ) {
        ResearchDtos.PagedResponse<ResearchDtos.PaperProposalDto> paged = paperPortfolioService.listProposals(id, ownerId, limit, offset);
        return ResponseEntity.ok(paged);
    }

    @GetMapping("/portfolios/{id}/proposals/{proposalId}")
    public ResponseEntity<ResearchDtos.PaperProposalDto> getProposal(
            @PathVariable String id,
            @PathVariable String proposalId,
            @RequestHeader(value = "X-User-Id", required = false, defaultValue = "default") String ownerId
    ) {
        ResearchDtos.PaperProposalDto proposal = paperPortfolioService.getProposal(id, proposalId, ownerId);
        return ResponseEntity.ok(proposal);
    }

    @PostMapping("/portfolios/{id}/proposals/{proposalId}/accept")
    public ResponseEntity<ResearchDtos.PaperProposalDto> acceptProposal(
            @PathVariable String id,
            @PathVariable String proposalId,
            @RequestHeader(value = "X-User-Id", required = false, defaultValue = "default") String ownerId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody(required = false) ResearchDtos.AcceptProposalRequest request
    ) {
        ResearchDtos.PaperProposalDto accepted = paperPortfolioService.acceptProposal(
                id, proposalId, ownerId, idempotencyKey, request != null ? request : new ResearchDtos.AcceptProposalRequest("Approved by user")
        );
        return ResponseEntity.ok(accepted);
    }

    @PostMapping("/portfolios/{id}/proposals/{proposalId}/reject")
    public ResponseEntity<ResearchDtos.PaperProposalDto> rejectProposal(
            @PathVariable String id,
            @PathVariable String proposalId,
            @RequestHeader(value = "X-User-Id", required = false, defaultValue = "default") String ownerId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody ResearchDtos.RejectProposalRequest request
    ) {
        ResearchDtos.PaperProposalDto rejected = paperPortfolioService.rejectProposal(
                id, proposalId, ownerId, idempotencyKey, request
        );
        return ResponseEntity.ok(rejected);
    }

    @PostMapping("/portfolios/{id}/mode")
    public ResponseEntity<Map<String, String>> changeApprovalMode(
            @PathVariable String id,
            @RequestHeader(value = "X-User-Id", required = false, defaultValue = "default") String ownerId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody ResearchDtos.ChangeApprovalModeRequest request
    ) {
        paperPortfolioService.changeApprovalMode(id, ownerId, idempotencyKey, request);
        return ResponseEntity.ok(Map.of("portfolioId", id, "approvalMode", request.approvalMode(), "status", "SUCCESS"));
    }

    @GetMapping("/portfolios/{id}/mode-history")
    public ResponseEntity<List<ResearchDtos.PaperModeHistoryDto>> getModeHistory(
            @PathVariable String id,
            @RequestHeader(value = "X-User-Id", required = false, defaultValue = "default") String ownerId
    ) {
        List<ResearchDtos.PaperModeHistoryDto> history = paperPortfolioService.getModeHistory(id, ownerId);
        return ResponseEntity.ok(history);
    }

    @PostMapping("/portfolios/{id}/process")
    public ResponseEntity<Map<String, String>> processPortfolioEvents(
            @PathVariable String id,
            @RequestHeader(value = "X-User-Id", required = false, defaultValue = "default") String ownerId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        paperPortfolioService.processPortfolioEvents(id, ownerId, idempotencyKey);
        return ResponseEntity.ok(Map.of("portfolioId", id, "status", "PROCESSED"));
    }

    @GetMapping("/portfolios/{id}/valuations")
    public ResponseEntity<ResearchDtos.PagedResponse<ResearchDtos.PaperValuationDto>> listValuations(
            @PathVariable String id,
            @RequestHeader(value = "X-User-Id", required = false, defaultValue = "default") String ownerId,
            @RequestParam(value = "limit", defaultValue = "100") int limit,
            @RequestParam(value = "offset", defaultValue = "0") int offset
    ) {
        ResearchDtos.PagedResponse<ResearchDtos.PaperValuationDto> valuations = paperPortfolioService.listValuations(id, ownerId, limit, offset);
        return ResponseEntity.ok(valuations);
    }

    @GetMapping({"/portfolios/{id}/export", "/portfolios/{id}/export.zip"})
    public ResponseEntity<byte[]> exportPaperPortfolio(
            @PathVariable String id,
            @RequestHeader(value = "X-User-Id", required = false, defaultValue = "default") String ownerId
    ) throws IOException {
        byte[] zipBytes = paperExportService.createPaperAuditZip(id, ownerId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"paper-portfolio-" + id + "-audit.zip\"")
                .contentType(MediaType.parseMediaType("application/zip"))
                .body(zipBytes);
    }

    @GetMapping("/instruments")
    public ResponseEntity<List<ResearchDtos.InstrumentDto>> listInstruments(
            @RequestParam(required = false) String symbol
    ) {
        String sql = (symbol != null && !symbol.isBlank())
                ? "SELECT DISTINCT i.id, i.type, i.name, i.isin, i.provenance FROM instruments i JOIN listings l ON i.id = l.instrument_id WHERE l.symbol = ?"
                : "SELECT id, type, name, isin, provenance FROM instruments ORDER BY name ASC";

        List<Map<String, Object>> instRows = (symbol != null && !symbol.isBlank())
                ? jdbcTemplate.queryForList(sql, symbol.trim().toUpperCase())
                : jdbcTemplate.queryForList(sql);

        List<ResearchDtos.InstrumentDto> result = new ArrayList<>();
        for (Map<String, Object> row : instRows) {
            String instId = (String) row.get("id");
            List<Map<String, Object>> listRows = jdbcTemplate.queryForList(
                    "SELECT id, instrument_id, venue, symbol, quote_currency, identity_status FROM listings WHERE instrument_id = ?",
                    instId
            );

            List<ResearchDtos.ListingDto> listings = new ArrayList<>();
            for (Map<String, Object> lr : listRows) {
                listings.add(new ResearchDtos.ListingDto(
                        (String) lr.get("id"),
                        (String) lr.get("instrument_id"),
                        (String) lr.get("venue"),
                        (String) lr.get("symbol"),
                        (String) lr.get("quote_currency"),
                        (String) lr.get("identity_status")
                ));
            }

            result.add(new ResearchDtos.InstrumentDto(
                    instId,
                    (String) row.get("type"),
                    (String) row.get("name"),
                    (String) row.get("isin"),
                    (String) row.get("provenance"),
                    listings
            ));
        }

        return ResponseEntity.ok(result);
    }

    private ResearchDtos.ResearchPortfolioDetail toDetail(OperationService.PortfolioView view, String createdAt) {
        List<ResearchDtos.ResearchPositionItem> positions = new ArrayList<>();
        for (OperationService.PositionView p : view.positions()) {
            positions.add(new ResearchDtos.ResearchPositionItem(
                    p.listingId(),
                    p.ticker(),
                    p.quantity(),
                    p.totalAcquisitionCost(),
                    p.averageCost(),
                    p.updatedAt()
            ));
        }

        List<Map<String, Object>> segRows = jdbcTemplate.queryForList(
                "SELECT id, portfolio_id, strategy_id, strategy_version, universe_id, benchmark_listing_id, " +
                        "cost_policy_json, approval_mode, status, initial_equity, opening_observation_instant, adopted_dataset_id, adopted_at, created_at " +
                        "FROM paper_portfolio_segments WHERE portfolio_id = ? AND status = 'ACTIVE'",
                view.id()
        );

        ResearchDtos.PaperSegmentDto activeSegment = null;
        String trackingStatus = "UNSTARTED";
        String readinessStatus = "UNAVAILABLE";

        if (!segRows.isEmpty()) {
            Map<String, Object> seg = segRows.get(0);
            trackingStatus = "ACTIVE";
            ResearchDtos.CostPolicyDto costPolicy = null;
            try {
                costPolicy = objectMapper.readValue((String) seg.get("cost_policy_json"), ResearchDtos.CostPolicyDto.class);
            } catch (Exception ignored) {}

            activeSegment = new ResearchDtos.PaperSegmentDto(
                    (String) seg.get("id"),
                    (String) seg.get("portfolio_id"),
                    (String) seg.get("strategy_id"),
                    (String) seg.get("strategy_version"),
                    (String) seg.get("universe_id"),
                    (String) seg.get("benchmark_listing_id"),
                    costPolicy,
                    (String) seg.get("approval_mode"),
                    (String) seg.get("status"),
                    (String) seg.get("initial_equity"),
                    (String) seg.get("opening_observation_instant"),
                    (String) seg.get("adopted_dataset_id"),
                    (String) seg.get("adopted_at"),
                    (String) seg.get("created_at")
            );

            PaperDataReadinessService.ReadinessResult ready = dataReadinessService.checkReadiness(view.id());
            readinessStatus = ready.status();
        }

        Integer pendingProps = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM paper_proposals WHERE portfolio_id = ? AND status = 'PROPOSED'",
                Integer.class,
                view.id()
        );
        Integer pendingIntents = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM paper_execution_intents WHERE portfolio_id = ? AND status IN ('PENDING', 'WAITING_FOR_OBSERVATION')",
                Integer.class,
                view.id()
        );

        return new ResearchDtos.ResearchPortfolioDetail(
                view.id(),
                view.ownerId(),
                view.name(),
                view.mode(),
                view.baseCurrency(),
                view.cashBalance(),
                view.revision(),
                createdAt,
                null,
                trackingStatus,
                readinessStatus,
                view.cashBalance(),
                "0.00",
                positions,
                activeSegment,
                "0.00",
                view.cashBalance(),
                readinessStatus,
                pendingProps != null ? pendingProps : 0,
                pendingIntents != null ? pendingIntents : 0
        );
    }

    private void validatePortfolioOwnership(String portfolioId, String ownerId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM portfolios WHERE id = ? AND owner_id = ? AND mode = 'PAPER'",
                Integer.class,
                portfolioId, ownerId
        );
        if (count == null || count == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Paper portfolio not found");
        }
    }

    private String normalizeUser(String ownerId) {
        return (ownerId == null || ownerId.isBlank()) ? "default" : ownerId.trim();
    }
}
