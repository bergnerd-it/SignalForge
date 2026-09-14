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
import java.nio.file.Files;
import java.nio.file.Path;

@Slf4j
@RestController
@RequestMapping("/api/research/comparisons")
@RequiredArgsConstructor
public class ComparisonController {

    private final ComparisonService comparisonService;
    private final BacktestExportService exportService;

    @PostMapping
    public ResponseEntity<BacktestDtos.BacktestComparisonDto> createComparison(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "X-User-Id", required = false, defaultValue = "default") String ownerId,
            @RequestBody BacktestDtos.CreateComparisonRequest request
    ) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Idempotency-Key header is required");
        }
        BacktestDtos.BacktestComparisonDto created = comparisonService.createComparison(ownerId, idempotencyKey.trim(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping
    public BacktestDtos.PagedResponse<BacktestDtos.BacktestComparisonDto> listComparisons(
            @RequestHeader(value = "X-User-Id", required = false, defaultValue = "default") String ownerId,
            @RequestParam(value = "limit", defaultValue = "50") int limit,
            @RequestParam(value = "offset", defaultValue = "0") int offset
    ) {
        return comparisonService.listComparisons(ownerId, limit, offset);
    }

    @GetMapping("/{id}")
    public BacktestDtos.BacktestComparisonDto getComparison(
            @PathVariable("id") String id,
            @RequestHeader(value = "X-User-Id", required = false, defaultValue = "default") String ownerId
    ) {
        return comparisonService.getComparison(id, ownerId);
    }

    @GetMapping(value = "/{id}/export", produces = "application/zip")
    public ResponseEntity<org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody> exportComparison(
            @PathVariable("id") String id,
            @RequestHeader(value = "X-User-Id", required = false, defaultValue = "default") String ownerId
    ) {
        try {
            Path preparedZip = exportService.prepareComparisonZip(id, ownerId);
            String safeFilename = "comparison-" + id.replaceAll("[^a-zA-Z0-9_-]", "_") + "-export.zip";
            org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody responseBody = outputStream -> {
                try {
                    Files.copy(preparedZip, outputStream);
                } finally {
                    Files.deleteIfExists(preparedZip);
                }
            };

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + safeFilename + "\"")
                    .contentType(MediaType.parseMediaType("application/zip"))
                    .contentLength(Files.size(preparedZip))
                    .body(responseBody);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not prepare comparison export", e);
        }
    }
}
