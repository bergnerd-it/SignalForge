package com.bergnerd.signalforge.app.research.historical;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/research")
@RequiredArgsConstructor
public class HistoricalDataController {

    private final HistoricalImportJobService importJobService;
    private final HistoricalHistoryService historyService;

    @PostMapping(value = "/imports", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<HistoricalDtos.ImportJobResponse> uploadBundle(
            @RequestParam("file") MultipartFile file,
            @RequestHeader(value = "Idempotency-Key", required = false) String headerKey,
            @RequestParam(value = "requestKey", required = false) String paramKey
    ) {
        String effectiveKey = (headerKey != null && !headerKey.isBlank())
                ? headerKey.trim()
                : (paramKey != null && !paramKey.isBlank() ? paramKey.trim() : null);

        if (effectiveKey == null || effectiveKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Idempotency-Key header is required for import");
        }

        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Upload file is empty or missing");
        }

        if (file.getSize() > HistoricalBundleParser.MAX_COMPRESSED_BYTES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, String.format(
                    "Compressed size %d bytes exceeds maximum allowed limit of %d bytes",
                    file.getSize(), HistoricalBundleParser.MAX_COMPRESSED_BYTES
            ));
        }

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to read uploaded file: " + e.getMessage(), e);
        }

        HistoricalDtos.ImportJobResponse response = importJobService.submitImport(effectiveKey, bytes);
        HttpStatus status = "QUEUED".equals(response.status()) || "RUNNING".equals(response.status())
                ? HttpStatus.ACCEPTED
                : HttpStatus.OK;

        return ResponseEntity.status(status).body(response);
    }

    @GetMapping("/jobs/{id}")
    public ResponseEntity<HistoricalDtos.ImportJobResponse> getJobStatus(@PathVariable("id") String id) {
        return ResponseEntity.ok(importJobService.getJob(id));
    }

    @GetMapping("/datasets")
    public ResponseEntity<HistoricalDtos.PagedResponse<HistoricalDtos.DatasetSummary>> listDatasets(
            @RequestParam(value = "limit", required = false, defaultValue = "50") int limit,
            @RequestParam(value = "offset", required = false, defaultValue = "0") int offset
    ) {
        return ResponseEntity.ok(historyService.listDatasets(limit, offset));
    }

    @GetMapping("/datasets/{id}")
    public ResponseEntity<HistoricalDtos.DatasetDetail> getDatasetDetail(@PathVariable("id") String id) {
        return ResponseEntity.ok(historyService.getDatasetDetail(id));
    }

    @GetMapping("/datasets/{id}/listings")
    public ResponseEntity<HistoricalDtos.PagedResponse<HistoricalDtos.DatasetListingDto>> getDatasetListings(
            @PathVariable("id") String id,
            @RequestParam(value = "limit", required = false, defaultValue = "100") int limit,
            @RequestParam(value = "offset", required = false, defaultValue = "0") int offset
    ) {
        return ResponseEntity.ok(historyService.getDatasetListings(id, limit, offset));
    }

    @GetMapping("/datasets/{id}/sessions")
    public ResponseEntity<HistoricalDtos.PagedResponse<HistoricalDtos.DatasetSessionDto>> getDatasetSessions(
            @PathVariable("id") String id,
            @RequestParam(value = "calendarId", required = false) String calendarId,
            @RequestParam(value = "limit", required = false, defaultValue = "500") int limit,
            @RequestParam(value = "offset", required = false, defaultValue = "0") int offset
    ) {
        return ResponseEntity.ok(historyService.getDatasetSessions(id, calendarId, limit, offset));
    }

    @GetMapping("/datasets/{id}/actions")
    public ResponseEntity<HistoricalDtos.PagedResponse<HistoricalDtos.HistoricalActionDto>> getDatasetActions(
            @PathVariable("id") String id,
            @RequestParam(value = "listingId", required = false) String listingId,
            @RequestParam(value = "limit", required = false, defaultValue = "100") int limit,
            @RequestParam(value = "offset", required = false, defaultValue = "0") int offset
    ) {
        return ResponseEntity.ok(historyService.getDatasetActions(id, listingId, limit, offset));
    }

    @GetMapping("/datasets/{id}/history/{listingId}")
    public ResponseEntity<HistoricalDtos.ListingHistoryResponse> getListingHistory(
            @PathVariable("id") String datasetId,
            @PathVariable("listingId") String listingId,
            @RequestParam(value = "start", required = false) String start,
            @RequestParam(value = "end", required = false) String end,
            @RequestParam(value = "asOf", required = false) String asOf,
            @RequestParam(value = "limit", required = false, defaultValue = "1000") int limit,
            @RequestParam(value = "offset", required = false, defaultValue = "0") int offset
    ) {
        return ResponseEntity.ok(historyService.getListingHistory(datasetId, listingId, start, end, asOf, limit, offset));
    }
}
