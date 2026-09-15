package com.bergnerd.signalforge.app.research.paper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaperExecutionCoordinator {

    private final JdbcTemplate jdbcTemplate;
    private final PaperPortfolioService paperPortfolioService;
    private final Clock clock;
    private volatile boolean initialized = false;

    @EventListener(ApplicationReadyEvent.class)
    public synchronized void onApplicationReady() {
        log.info("PaperExecutionCoordinator initializing downtime recovery...");
        performDowntimeRecovery();
        initialized = true;
    }

    public synchronized void performDowntimeRecovery() {
        String now = clock.instant().toString();
        // 1. Find pending intents whose scheduled open has passed during downtime without observation
        List<Map<String, Object>> pending = jdbcTemplate.queryForList(
                "SELECT id, portfolio_id, scheduled_session_date, scheduled_open_instant FROM paper_execution_intents WHERE status = 'PENDING'"
        );
        for (Map<String, Object> intent : pending) {
            String intentId = (String) intent.get("id");
            String openInstant = (String) intent.get("scheduled_open_instant");
            if (now.compareTo(openInstant) >= 0) {
                // Update to WAITING_FOR_OBSERVATION
                jdbcTemplate.update(
                        "UPDATE paper_execution_intents SET status = 'WAITING_FOR_OBSERVATION' WHERE id = ?",
                        intentId
                );
                jdbcTemplate.update(
                        "INSERT INTO paper_intent_transitions (id, intent_id, from_status, to_status, trigger_type, transition_instant, notes) " +
                                "VALUES (?, ?, 'PENDING', 'WAITING_FOR_OBSERVATION', 'SYSTEM_DOWNTIME_RECOVERY', ?, 'Downtime recovery transition to waiting for observation')",
                        "trans-" + UUID.randomUUID(), intentId, now
                );
            }
        }

        // 2. Process events for all active paper portfolios
        List<String> activePortfolios = jdbcTemplate.queryForList(
                "SELECT DISTINCT p.id FROM portfolios p JOIN paper_portfolio_segments s ON p.id = s.portfolio_id WHERE p.mode = 'PAPER' AND s.status = 'ACTIVE'",
                String.class
        );
        for (String portId : activePortfolios) {
            try {
                paperPortfolioService.processPortfolioEvents(portId, "default", "recovery-" + UUID.randomUUID());
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
                paperPortfolioService.processPortfolioEvents(portId, ownerId, "coord-proc-" + UUID.randomUUID());
            } catch (Exception e) {
                log.debug("Auto paper coordinator cycle error for {}: {}", portId, e.getMessage());
            }
        }
    }
}
