package com.bergnerd.signalforge.app.research.backtest;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/research/experiments")
@RequiredArgsConstructor
public class ExperimentController {

    private final ExperimentService experimentService;

    @PostMapping
    public ResponseEntity<BacktestDtos.ExperimentDto> createExperiment(
            @RequestHeader(value = "X-User-Id", required = false, defaultValue = "default") String ownerId,
            @RequestBody BacktestDtos.CreateExperimentRequest request
    ) {
        BacktestDtos.ExperimentDto created = experimentService.createExperiment(ownerId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping
    public BacktestDtos.PagedResponse<BacktestDtos.ExperimentDto> listExperiments(
            @RequestHeader(value = "X-User-Id", required = false, defaultValue = "default") String ownerId,
            @RequestParam(value = "limit", defaultValue = "50") int limit,
            @RequestParam(value = "offset", defaultValue = "0") int offset
    ) {
        return experimentService.listExperiments(ownerId, limit, offset);
    }

    @GetMapping("/{id}")
    public BacktestDtos.ExperimentDto getExperiment(
            @PathVariable("id") String id,
            @RequestHeader(value = "X-User-Id", required = false, defaultValue = "default") String ownerId
    ) {
        return experimentService.getExperiment(id, ownerId);
    }
}
