package com.bergnerd.signalforge.app.research.paper;

import com.bergnerd.signalforge.app.research.paper.PaperDataReadinessService.ReadinessResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaperDataReadinessServiceTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private PaperAuditRecorder auditRecorder;

    @Mock
    private PaperMutationService mutationService;

    private Clock clock;
    private PaperDataReadinessService readinessService;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(Instant.parse("2026-09-15T12:00:00Z"), ZoneOffset.UTC);
        readinessService = new PaperDataReadinessService(jdbcTemplate, clock, auditRecorder, mutationService);
    }

    @Test
    void checkReadiness_whenNoActiveSegment_returnsNoActiveSegment() {
        when(jdbcTemplate.queryForList(anyString(), eq("port-1")))
                .thenReturn(List.of());

        ReadinessResult result = readinessService.checkReadiness("port-1");
        assertFalse(result.isReady());
        assertEquals("NO_ACTIVE_SEGMENT", result.status());
    }

    @Test
    void checkReadiness_whenNoAdoptedDataset_returnsNoAdoptedDataset() {
        when(jdbcTemplate.queryForList(anyString(), eq("port-1")))
                .thenReturn(List.of(Map.of(
                        "id", "seg-1",
                        "strategy_id", "ETF_BUY_HOLD_V1",
                        "universe_id", "uni-1"
                )));

        ReadinessResult result = readinessService.checkReadiness("port-1");
        assertFalse(result.isReady());
        assertEquals("NO_ADOPTED_DATASET", result.status());
    }

    @Test
    void checkReadiness_whenCalendarHasSessions_returnsReadyWithCompletedAndNextSession() {
        when(jdbcTemplate.queryForList(contains("paper_portfolio_segments"), eq("port-1")))
                .thenReturn(List.of(Map.of(
                        "id", "seg-1",
                        "strategy_id", "ETF_BUY_HOLD_V1",
                        "universe_id", "uni-1",
                        "benchmark_listing_id", "list-1",
                        "adopted_dataset_id", "ds-1"
                )));
        when(jdbcTemplate.queryForList(contains("dataset_sessions"), eq("ds-1")))
                .thenReturn(List.of(
                        Map.of("session_date", "2026-09-12", "open_time", "09:00:00", "close_time", "17:30:00"),
                        Map.of("session_date", "2026-09-15", "open_time", "09:00:00", "close_time", "17:30:00")
                ));
        when(jdbcTemplate.queryForList(contains("universe_listings"), eq(String.class), eq("uni-1")))
                .thenReturn(List.of("list-1"));
        when(jdbcTemplate.queryForObject(contains("historical_bars"), eq(Integer.class), eq("ds-1"), eq("list-1"), eq("2026-09-12")))
                .thenReturn(1);

        ReadinessResult result = readinessService.checkReadiness("port-1");
        assertTrue(result.isReady());
        assertEquals("READY", result.status());
        assertEquals("2026-09-12", result.latestCompletedSession());
        assertEquals("2026-09-15", result.nextScheduledSession());
    }

    @Test
    void computeTermsHash_isDeterministicAndDetectsChanges() {
        Map<String, Object> action1 = Map.of(
                "action_type", "CASH_DIVIDEND",
                "effective_date", "2026-09-15",
                "split_ratio_numerator", "1",
                "split_ratio_denominator", "1",
                "distribution_amount", "2.50",
                "distribution_currency", "EUR",
                "payment_date", "2026-09-20"
        );
        String hash1 = PaperDataReadinessService.computeTermsHash(action1);
        String hash1Repeat = PaperDataReadinessService.computeTermsHash(action1);
        assertEquals(hash1, hash1Repeat);

        Map<String, Object> action2 = Map.of(
                "action_type", "CASH_DIVIDEND",
                "effective_date", "2026-09-15",
                "split_ratio_numerator", "1",
                "split_ratio_denominator", "1",
                "distribution_amount", "3.00", // changed amount
                "distribution_currency", "EUR",
                "payment_date", "2026-09-20"
        );
        String hash2 = PaperDataReadinessService.computeTermsHash(action2);
        assertNotEquals(hash1, hash2);
    }

    @Test
    void adoptDataset_throwsBadRequestWhenNoActiveSegment() {
        when(mutationService.checkMutation(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(jdbcTemplate.queryForList(contains("paper_portfolio_segments"), eq("port-no-seg"), eq("default")))
                .thenReturn(List.of());

        assertThrows(ResponseStatusException.class, () ->
                readinessService.adoptDataset("port-no-seg", "ds-1"));
    }
}
