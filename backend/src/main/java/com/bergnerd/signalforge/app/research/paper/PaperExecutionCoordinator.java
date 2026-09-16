package com.bergnerd.signalforge.app.research.paper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaperExecutionCoordinator {

    private final JdbcTemplate jdbcTemplate;
    private final PaperPortfolioService paperPortfolioService;
    private volatile boolean initialized = false;

    @EventListener(ApplicationReadyEvent.class)
    public synchronized void onApplicationReady() {
        log.info("PaperExecutionCoordinator initializing downtime recovery...");
        performDowntimeRecovery();
        initialized = true;
    }

    public synchronized void performDowntimeRecovery() {
        List<Map<String, Object>> activePortfolios = jdbcTemplate.queryForList(
                "SELECT DISTINCT p.id, p.owner_id, s.approval_mode FROM portfolios p " +
                        "JOIN paper_portfolio_segments s ON p.id = s.portfolio_id " +
                        "WHERE p.mode = 'PAPER' AND s.status = 'ACTIVE'"
        );
        for (Map<String, Object> portfolio : activePortfolios) {
            String portId = (String) portfolio.get("id");
            String ownerId = (String) portfolio.get("owner_id");
            try {
                if ("AUTO_PAPER".equals(portfolio.get("approval_mode"))) {
                    paperPortfolioService.runAutomaticCycle(portId, ownerId);
                } else {
                    paperPortfolioService.processPortfolioEvents(portId, ownerId, "recovery-" + UUID.randomUUID());
                }
            } catch (Exception e) {
                log.warn("Downtime event processing failed for portfolio {}: {}", portId, e.getMessage());
            }
        }
    }

    @Scheduled(fixedDelayString = "${signalforge.paper.coordinator.fixed-delay-ms:60000}")
    public void runScheduledCycle() {
        if (!initialized) return;

        List<Map<String, Object>> autoPortfolios = jdbcTemplate.queryForList(
                "SELECT p.id, p.owner_id FROM portfolios p JOIN paper_portfolio_segments s ON p.id = s.portfolio_id " +
                        "WHERE p.mode = 'PAPER' AND s.status = 'ACTIVE' AND s.approval_mode = 'AUTO_PAPER'"
        );

        for (Map<String, Object> row : autoPortfolios) {
            String portId = (String) row.get("id");
            String ownerId = (String) row.get("owner_id");
            try {
                paperPortfolioService.runAutomaticCycle(portId, ownerId);
            } catch (Exception e) {
                log.debug("Auto paper coordinator cycle error for {}: {}", portId, e.getMessage());
            }
        }
    }
}
