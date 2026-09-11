package com.bergnerd.signalforge.app.portfolio;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/portfolio")
@RequiredArgsConstructor
public class PortfolioController {

    private final PortfolioService portfolioService;

    @GetMapping
    public ResponseEntity<PortfolioResponse> getPortfolio() {
        return ResponseEntity.ok(portfolioService.getPortfolio("default"));
    }

    @PostMapping("/trade")
    public ResponseEntity<TradeResponse> executeTrade(@Valid @RequestBody TradeRequest request) {
        return ResponseEntity.ok(portfolioService.executeTrade("default", request));
    }

    @GetMapping("/history")
    public ResponseEntity<List<PortfolioSnapshotDto>> getHistory() {
        return ResponseEntity.ok(portfolioService.getHistory("default"));
    }
}
