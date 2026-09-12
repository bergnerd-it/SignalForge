package com.bergnerd.signalforge.app.research;

import com.bergnerd.signalforge.app.operation.IdempotencyExceptions;
import com.bergnerd.signalforge.app.operation.OperationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

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

    @PostMapping("/portfolios")
    public ResponseEntity<ResearchDtos.ResearchPortfolioDetail> createPortfolio(
            @RequestHeader(value = "Idempotency-Key", required = false) String headerKey,
            @Valid @RequestBody ResearchDtos.CreatePortfolioRequest request
    ) {
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
            initialCash = com.bergnerd.signalforge.app.accounting.AccountingCore.normalizeStartingCash(
                    new BigDecimal(request.initialCash())
            );
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid decimal format for initialCash: " + request.initialCash());
        }

        OperationService.PortfolioCreationResult result = operationService.createPortfolio(
                "default",
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
    public ResponseEntity<List<ResearchDtos.ResearchPortfolioSummary>> listPortfolios() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT p.id, p.owner_id, p.name, p.mode, p.base_currency, s.cash_amount, s.revision, p.created_at, p.paper_started_at " +
                        "FROM portfolios p JOIN portfolio_state s ON p.id = s.portfolio_id " +
                        "WHERE p.owner_id = 'default' AND p.mode = 'PAPER' " +
                        "ORDER BY p.created_at DESC"
        );

        List<ResearchDtos.ResearchPortfolioSummary> summaries = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            String portId = (String) r.get("id");
            Integer posCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM positions WHERE portfolio_id = ?",
                    Integer.class,
                    portId
            );

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
                    "UNSTARTED",
                    posCount != null ? posCount : 0
            ));
        }

        return ResponseEntity.ok(summaries);
    }

    @GetMapping("/portfolios/{id}")
    public ResponseEntity<ResearchDtos.ResearchPortfolioDetail> getPortfolio(@PathVariable String id) {
        Integer inScope = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM portfolios WHERE id = ? AND owner_id = 'default' AND mode = 'PAPER'",
                Integer.class,
                id
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
                "UNSTARTED",
                "UNAVAILABLE",
                null,
                null,
                positions
        );
    }
}
