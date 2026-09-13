package com.bergnerd.signalforge.app.research.backtest;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;

@Slf4j
@RestController
@RequestMapping("/api/research/backtests")
@RequiredArgsConstructor
public class BacktestController {

    private final BacktestJobService jobService;
    private final BacktestExportService exportService;

    @PostMapping
    public ResponseEntity<BacktestDtos.BacktestSummaryResponse> createBacktest(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "X-User-Id", required = false, defaultValue = "default") String ownerId,
            @RequestBody BacktestDtos.CreateBacktestRequest request
    ) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Idempotency-Key header is required");
        }

        BacktestJobService.CreationResult result = jobService.createBacktest(ownerId, idempotencyKey.trim(), request);
        HttpStatus status = result.isNew() ? HttpStatus.ACCEPTED : HttpStatus.OK;
        return ResponseEntity.status(status).body(result.response());
    }

    @GetMapping
    public BacktestDtos.PagedResponse<BacktestDtos.BacktestSummaryResponse> listBacktests(
            @RequestHeader(value = "X-User-Id", required = false, defaultValue = "default") String ownerId,
            @RequestParam(value = "limit", defaultValue = "50") int limit,
            @RequestParam(value = "offset", defaultValue = "0") int offset
    ) {
        return jobService.listBacktests(ownerId, limit, offset);
    }

    @GetMapping("/{id}")
    public BacktestDtos.BacktestSummaryResponse getBacktest(
            @PathVariable("id") String id,
            @RequestHeader(value = "X-User-Id", required = false, defaultValue = "default") String ownerId
    ) {
        return jobService.getBacktestDetail(id, ownerId);
    }

    @PostMapping("/{id}/cancel")
    public BacktestDtos.BacktestSummaryResponse cancelBacktest(
            @PathVariable("id") String id,
            @RequestHeader(value = "X-User-Id", required = false, defaultValue = "default") String ownerId
    ) {
        return jobService.cancelBacktest(id, ownerId);
    }

    @GetMapping("/{id}/equity")
    public BacktestDtos.PagedResponse<BacktestDtos.DailyEquityPoint> getDailyEquity(
            @PathVariable("id") String id,
            @RequestHeader(value = "X-User-Id", required = false, defaultValue = "default") String ownerId,
            @RequestParam(value = "series", defaultValue = "CANDIDATE") String series,
            @RequestParam(value = "limit", defaultValue = "1000") int limit,
            @RequestParam(value = "offset", defaultValue = "0") int offset
    ) {
        return jobService.getDailyEquity(id, ownerId, series, limit, offset);
    }

    @GetMapping("/{id}/orders")
    public BacktestDtos.PagedResponse<BacktestDtos.BacktestOrderDto> getOrders(
            @PathVariable("id") String id,
            @RequestHeader(value = "X-User-Id", required = false, defaultValue = "default") String ownerId,
            @RequestParam(value = "series", required = false) String series,
            @RequestParam(value = "limit", defaultValue = "100") int limit,
            @RequestParam(value = "offset", defaultValue = "0") int offset
    ) {
        return jobService.getOrders(id, ownerId, series, limit, offset);
    }

    @GetMapping("/{id}/events")
    public BacktestDtos.PagedResponse<BacktestDtos.BacktestEventDto> getEvents(
            @PathVariable("id") String id,
            @RequestHeader(value = "X-User-Id", required = false, defaultValue = "default") String ownerId,
            @RequestParam(value = "series", required = false) String series,
            @RequestParam(value = "limit", defaultValue = "100") int limit,
            @RequestParam(value = "offset", defaultValue = "0") int offset
    ) {
        return jobService.getEvents(id, ownerId, series, limit, offset);
    }

    @GetMapping(value = "/{id}/export", produces = "application/zip")
    public ResponseEntity<org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody> exportBacktest(
            @PathVariable("id") String id,
            @RequestHeader(value = "X-User-Id", required = false, defaultValue = "default") String ownerId
    ) {
        try {
            exportService.validateExportable(id, ownerId);
            String safeFilename = "backtest-" + id.replaceAll("[^a-zA-Z0-9_-]", "_") + "-export.zip";
            org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody responseBody = outputStream -> {
                exportService.streamExportZip(id, ownerId, outputStream);
            };

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + safeFilename + "\"")
                    .contentType(MediaType.parseMediaType("application/zip"))
                    .body(responseBody);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }
}
