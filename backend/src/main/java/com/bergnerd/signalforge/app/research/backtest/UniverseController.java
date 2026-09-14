package com.bergnerd.signalforge.app.research.backtest;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/research/universes")
@RequiredArgsConstructor
public class UniverseController {

    private final UniverseService universeService;

    @PostMapping
    public ResponseEntity<BacktestDtos.UniverseDto> createUniverse(
            @RequestHeader(value = "X-User-Id", required = false, defaultValue = "default") String ownerId,
            @RequestBody BacktestDtos.CreateUniverseRequest request
    ) {
        BacktestDtos.UniverseDto created = universeService.createUniverse(ownerId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping
    public List<BacktestDtos.UniverseDto> listUniverses(
            @RequestHeader(value = "X-User-Id", required = false, defaultValue = "default") String ownerId
    ) {
        return universeService.listUniverses(ownerId);
    }

    @GetMapping("/{id}")
    public BacktestDtos.UniverseDto getUniverse(
            @PathVariable("id") String id,
            @RequestHeader(value = "X-User-Id", required = false, defaultValue = "default") String ownerId
    ) {
        return universeService.getUniverse(id, ownerId);
    }
}
