package com.financeally.app.watchlist;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/watchlist")
@RequiredArgsConstructor
public class WatchlistController {

    private final WatchlistService watchlistService;

    @GetMapping
    public ResponseEntity<List<WatchlistEntryDto>> getWatchlist() {
        return ResponseEntity.ok(watchlistService.getWatchlist("default"));
    }

    @PostMapping
    public ResponseEntity<WatchlistEntryDto> addTicker(@Valid @RequestBody WatchlistAddRequest request) {
        return ResponseEntity.ok(watchlistService.addTicker("default", request.ticker()));
    }

    @DeleteMapping("/{ticker}")
    public ResponseEntity<Map<String, Object>> removeTicker(@PathVariable String ticker) {
        boolean removed = watchlistService.removeTicker("default", ticker);
        return ResponseEntity.ok(Map.of("ticker", ticker.toUpperCase(), "removed", removed));
    }
}
