package com.bergnerd.signalforge.app.research.backtest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class BacktestEngineTest {

    private BacktestEngine engine;

    @BeforeEach
    void setUp() {
        engine = new BacktestEngine();
    }

    @Test
    @DisplayName("Exact reference scenario reconciles to hand calculation in planning/docs/backtest-baseline.md")
    void testExactReferenceScenarioReconciliation() {
        // Build reference scenario data
        List<BacktestDataReader.SessionRecord> sessions = List.of(
                new BacktestDataReader.SessionRecord("2024-02-01", "2024-02-01T08:00:00Z", "2024-02-01T16:30:00Z", "TRADING"),
                new BacktestDataReader.SessionRecord("2024-02-02", "2024-02-02T08:00:00Z", "2024-02-02T16:30:00Z", "TRADING"),
                new BacktestDataReader.SessionRecord("2024-02-05", "2024-02-05T08:00:00Z", "2024-02-05T16:30:00Z", "TRADING"),
                new BacktestDataReader.SessionRecord("2024-02-06", "2024-02-06T08:00:00Z", "2024-02-06T16:30:00Z", "TRADING"),
                new BacktestDataReader.SessionRecord("2024-02-07", "2024-02-07T08:00:00Z", "2024-02-07T16:30:00Z", "TRADING")
        );

        Map<String, BacktestDataReader.BarRecord> bars = new HashMap<>();
        bars.put("2024-02-01", new BacktestDataReader.BarRecord("listing-1", "2024-02-01", new BigDecimal("100.00"), new BigDecimal("101.00"), new BigDecimal("99.00"), new BigDecimal("100.00"), 10000L, "2024-02-01T18:00:00Z"));
        bars.put("2024-02-02", new BacktestDataReader.BarRecord("listing-1", "2024-02-02", new BigDecimal("50.00"), new BigDecimal("51.00"), new BigDecimal("49.00"), new BigDecimal("50.00"), 20000L, "2024-02-02T18:00:00Z"));
        bars.put("2024-02-05", new BacktestDataReader.BarRecord("listing-1", "2024-02-05", new BigDecimal("49.00"), new BigDecimal("50.00"), new BigDecimal("48.00"), new BigDecimal("49.00"), 15000L, "2024-02-05T18:00:00Z"));
        bars.put("2024-02-06", new BacktestDataReader.BarRecord("listing-1", "2024-02-06", new BigDecimal("49.00"), new BigDecimal("50.00"), new BigDecimal("48.00"), new BigDecimal("49.00"), 15000L, "2024-02-06T18:00:00Z"));
        bars.put("2024-02-07", new BacktestDataReader.BarRecord("listing-1", "2024-02-07", new BigDecimal("49.00"), new BigDecimal("50.00"), new BigDecimal("49.00"), new BigDecimal("50.00"), 25000L, "2024-02-07T18:00:00Z"));

        List<BacktestDataReader.ActionRecord> actions = List.of(
                new BacktestDataReader.ActionRecord("split-1", "listing-1", "SPLIT", "2024-02-02", "2024-02-01T20:00:00Z", 2, 1, null, null, null, null),
                new BacktestDataReader.ActionRecord("div-1", "listing-1", "CASH_DISTRIBUTION", "2024-02-05", "2024-02-02T20:00:00Z", null, null, new BigDecimal("1.00"), "EUR", "2024-02-06", null)
        );

        BigDecimal initialCash = new BigDecimal("1000.00");
        BigDecimal commission = new BigDecimal("1.00");
        BigDecimal spreadBps = BigDecimal.ZERO;
        BigDecimal slippageBps = BigDecimal.ZERO;

        BacktestEngine.SimulationResult result = engine.runS1(
                "run-ref-1",
                BacktestDtos.SeriesType.CANDIDATE,
                "listing-1",
                initialCash,
                commission,
                spreadBps,
                slippageBps,
                sessions,
                bars,
                actions
        );

        // Assert exact financial totals
        assertEquals("1000.00", result.initialCash().toPlainString());
        assertEquals("1018.00", result.finalEquity().toPlainString());
        assertEquals("2.00", result.totalCommissions().toPlainString());
        assertEquals("18.00", result.totalDistributionsRecognized().toPlainString());
        assertEquals(2, result.fillCount());

        // Assert Day 1 (Feb 1): Buy 9 units @ 100.00, fee 1.00, cash 99.00, equity 999.00
        BacktestDtos.DailyEquityPoint day1 = result.dailyEquity().get(0);
        assertEquals("2024-02-01", day1.sessionDate());
        assertEquals("99.00", day1.cash());
        assertEquals("900.00", day1.holdingsValue());
        assertEquals("0.00", day1.receivables());
        assertEquals("999.00", day1.totalEquity());
        assertEquals("-0.001", day1.dailyReturn().toString());
        assertEquals("901.00", day1.costBasis());
        assertEquals("9.00000000", day1.units());

        // Assert Day 2 (Feb 2): 2:1 split -> 18 units, basis 901.00, cash 99.00, equity 999.00
        BacktestDtos.DailyEquityPoint day2 = result.dailyEquity().get(1);
        assertEquals("2024-02-02", day2.sessionDate());
        assertEquals("99.00", day2.cash());
        assertEquals("900.00", day2.holdingsValue());
        assertEquals("18.00000000", day2.units());
        assertEquals("901.00", day2.costBasis());
        assertEquals("999.00", day2.totalEquity());

        // Assert Day 3 (Feb 5): Ex-date -> receivable 18.00, cash 99.00, holdings 882.00, equity 999.00
        BacktestDtos.DailyEquityPoint day3 = result.dailyEquity().get(2);
        assertEquals("2024-02-05", day3.sessionDate());
        assertEquals("99.00", day3.cash());
        assertEquals("18.00", day3.receivables());
        assertEquals("882.00", day3.holdingsValue());
        assertEquals("999.00", day3.totalEquity());

        // Assert Day 4 (Feb 6): Payment date post-close -> receivable 0.00, cash 117.00, holdings 882.00, equity 999.00
        BacktestDtos.DailyEquityPoint day4 = result.dailyEquity().get(3);
        assertEquals("2024-02-06", day4.sessionDate());
        assertEquals("117.00", day4.cash());
        assertEquals("0.00", day4.receivables());
        assertEquals("882.00", day4.holdingsValue());
        assertEquals("999.00", day4.totalEquity());

        // Assert Day 5 (Feb 7): Reinvestment -> buy 2 units @ 49.00 + 1 fee = 99.00, cash 18.00, units 20, basis 1000.00, equity 1018.00
        BacktestDtos.DailyEquityPoint day5 = result.dailyEquity().get(4);
        assertEquals("2024-02-07", day5.sessionDate());
        assertEquals("18.00", day5.cash());
        assertEquals("1000.00", day5.holdingsValue());
        assertEquals("20.00000000", day5.units());
        assertEquals("1000.00", day5.costBasis());
        assertEquals("1018.00", day5.totalEquity());

        // Assert Final Holdings
        BacktestDtos.BacktestHoldingsDto holdings = result.finalHoldings();
        assertEquals("20.00000000", holdings.units());
        assertEquals("1000.00", holdings.totalCostBasis());
        assertEquals("50.00000000", holdings.averageCost());
        assertEquals("1000.00", holdings.marketValue());
        assertEquals("0.00", holdings.unrealizedGain());
    }

    @Test
    @DisplayName("No Lookahead: Mutating High/Low/Close does not affect earlier opening execution")
    void testNoLookaheadBiasFromIntradayOrFutureBarModifications() {
        List<BacktestDataReader.SessionRecord> sessions = List.of(
                new BacktestDataReader.SessionRecord("2024-02-01", "2024-02-01T08:00:00Z", "2024-02-01T16:30:00Z", "TRADING")
        );

        // Bar 1 with high 150, low 80, close 140
        Map<String, BacktestDataReader.BarRecord> barsA = new HashMap<>();
        barsA.put("2024-02-01", new BacktestDataReader.BarRecord("listing-1", "2024-02-01", new BigDecimal("100.00"), new BigDecimal("150.00"), new BigDecimal("80.00"), new BigDecimal("140.00"), 10000L, "2024-02-01T18:00:00Z"));

        // Bar 2 with high 101, low 99, close 100, but SAME open = 100.00
        Map<String, BacktestDataReader.BarRecord> barsB = new HashMap<>();
        barsB.put("2024-02-01", new BacktestDataReader.BarRecord("listing-1", "2024-02-01", new BigDecimal("100.00"), new BigDecimal("101.00"), new BigDecimal("99.00"), new BigDecimal("100.00"), 10000L, "2024-02-01T18:00:00Z"));

        BacktestEngine.SimulationResult resA = engine.runS1(
                "run-1", BacktestDtos.SeriesType.CANDIDATE, "listing-1",
                new BigDecimal("1000.00"), new BigDecimal("1.00"), BigDecimal.ZERO, BigDecimal.ZERO,
                sessions, barsA, List.of()
        );

        BacktestEngine.SimulationResult resB = engine.runS1(
                "run-2", BacktestDtos.SeriesType.CANDIDATE, "listing-1",
                new BigDecimal("1000.00"), new BigDecimal("1.00"), BigDecimal.ZERO, BigDecimal.ZERO,
                sessions, barsB, List.of()
        );

        // Opening order quantities and executed fill prices must be 100% identical
        assertEquals(resA.orders().get(0).executedQuantity(), resB.orders().get(0).executedQuantity());
        assertEquals(resA.orders().get(0).fillPrice(), resB.orders().get(0).fillPrice());
        assertEquals(resA.orders().get(0).commission(), resB.orders().get(0).commission());
    }

    @Test
    @DisplayName("Execution cost model: default 10 bps spread and 5 bps slippage increase buy fill price")
    void testExecutionCostModelSpreadAndSlippage() {
        List<BacktestDataReader.SessionRecord> sessions = List.of(
                new BacktestDataReader.SessionRecord("2024-02-01", "2024-02-01T08:00:00Z", "2024-02-01T16:30:00Z", "TRADING")
        );

        Map<String, BacktestDataReader.BarRecord> bars = new HashMap<>();
        bars.put("2024-02-01", new BacktestDataReader.BarRecord("listing-1", "2024-02-01", new BigDecimal("100.00"), new BigDecimal("101.00"), new BigDecimal("99.00"), new BigDecimal("100.00"), 10000L, "2024-02-01T18:00:00Z"));

        // Half spread = 5 bps = 0.0005, slippage = 5 bps = 0.0005 -> total multiplier = 1 + 0.0010 = 1.0010
        // Fill price = 100.00 * 1.0010 = 100.10 EUR
        BacktestEngine.SimulationResult res = engine.runS1(
                "run-costs", BacktestDtos.SeriesType.CANDIDATE, "listing-1",
                new BigDecimal("1000.00"), new BigDecimal("1.00"), new BigDecimal("10"), new BigDecimal("5"),
                sessions, bars, List.of()
        );

        BacktestDtos.BacktestOrderDto order = res.orders().get(0);
        assertEquals("100.10000000", order.fillPrice());
        assertEquals("1.00", order.commission());
        // Max units: 9 * 100.10 + 1.00 = 900.90 + 1.00 = 901.90 <= 1000
        assertEquals("9.00000000", order.executedQuantity());
    }

    @Test
    @DisplayName("Affordability boundary: Insufficient cash produces skipped order without fee")
    void testInsufficientCashSkipsOrderWithZeroFee() {
        List<BacktestDataReader.SessionRecord> sessions = List.of(
                new BacktestDataReader.SessionRecord("2024-02-01", "2024-02-01T08:00:00Z", "2024-02-01T16:30:00Z", "TRADING")
        );

        Map<String, BacktestDataReader.BarRecord> bars = new HashMap<>();
        // Open = 100.00, but initial cash is only 50.00 EUR
        bars.put("2024-02-01", new BacktestDataReader.BarRecord("listing-1", "2024-02-01", new BigDecimal("100.00"), new BigDecimal("101.00"), new BigDecimal("99.00"), new BigDecimal("100.00"), 10000L, "2024-02-01T18:00:00Z"));

        BacktestEngine.SimulationResult res = engine.runS1(
                "run-poor", BacktestDtos.SeriesType.CANDIDATE, "listing-1",
                new BigDecimal("50.00"), new BigDecimal("1.00"), BigDecimal.ZERO, BigDecimal.ZERO,
                sessions, bars, List.of()
        );

        assertEquals(0, res.fillCount());
        assertEquals("0.00", res.totalCommissions().toPlainString());
        assertEquals("50.00", res.finalEquity().toPlainString());

        BacktestDtos.BacktestOrderDto order = res.orders().get(0);
        assertEquals(BacktestDtos.OrderStatus.SKIPPED.name(), order.status());
        assertEquals("INSUFFICIENT_CASH", order.skipReason());
        assertEquals("0.00", order.commission());
    }

    @Test
    @DisplayName("Day 0 initial funding point is injected at evaluation session and first-year return is exact 1.80%")
    void testDay0InitialFundingPointAndFirstYearReturn() {
        BacktestDataReader.SessionRecord evalSession = new BacktestDataReader.SessionRecord(
                "2024-01-31", "2024-01-31T08:00:00Z", "2024-01-31T16:30:00Z", "TRADING"
        );

        List<BacktestDataReader.SessionRecord> sessions = List.of(
                new BacktestDataReader.SessionRecord("2024-02-01", "2024-02-01T08:00:00Z", "2024-02-01T16:30:00Z", "TRADING"),
                new BacktestDataReader.SessionRecord("2024-02-02", "2024-02-02T08:00:00Z", "2024-02-02T16:30:00Z", "TRADING"),
                new BacktestDataReader.SessionRecord("2024-02-05", "2024-02-05T08:00:00Z", "2024-02-05T16:30:00Z", "TRADING"),
                new BacktestDataReader.SessionRecord("2024-02-06", "2024-02-06T08:00:00Z", "2024-02-06T16:30:00Z", "TRADING"),
                new BacktestDataReader.SessionRecord("2024-02-07", "2024-02-07T08:00:00Z", "2024-02-07T16:30:00Z", "TRADING")
        );

        Map<String, BacktestDataReader.BarRecord> bars = new HashMap<>();
        bars.put("2024-02-01", new BacktestDataReader.BarRecord("listing-1", "2024-02-01", new BigDecimal("100.00"), new BigDecimal("101.00"), new BigDecimal("99.00"), new BigDecimal("100.00"), 10000L, "2024-02-01T18:00:00Z"));
        bars.put("2024-02-02", new BacktestDataReader.BarRecord("listing-1", "2024-02-02", new BigDecimal("50.00"), new BigDecimal("51.00"), new BigDecimal("49.00"), new BigDecimal("50.00"), 20000L, "2024-02-02T18:00:00Z"));
        bars.put("2024-02-05", new BacktestDataReader.BarRecord("listing-1", "2024-02-05", new BigDecimal("49.00"), new BigDecimal("50.00"), new BigDecimal("48.00"), new BigDecimal("49.00"), 15000L, "2024-02-05T18:00:00Z"));
        bars.put("2024-02-06", new BacktestDataReader.BarRecord("listing-1", "2024-02-06", new BigDecimal("49.00"), new BigDecimal("50.00"), new BigDecimal("48.00"), new BigDecimal("49.00"), 15000L, "2024-02-06T18:00:00Z"));
        bars.put("2024-02-07", new BacktestDataReader.BarRecord("listing-1", "2024-02-07", new BigDecimal("49.00"), new BigDecimal("50.00"), new BigDecimal("49.00"), new BigDecimal("50.00"), 25000L, "2024-02-07T18:00:00Z"));

        List<BacktestDataReader.ActionRecord> actions = List.of(
                new BacktestDataReader.ActionRecord("split-1", "listing-1", "SPLIT", "2024-02-02", "2024-02-01T20:00:00Z", 2, 1, null, null, null, null),
                new BacktestDataReader.ActionRecord("div-1", "listing-1", "CASH_DISTRIBUTION", "2024-02-05", "2024-02-02T20:00:00Z", null, null, new BigDecimal("1.00"), "EUR", "2024-02-06", null)
        );

        BacktestEngine.SimulationResult res = engine.runS1(
                "run-day0", BacktestDtos.SeriesType.CANDIDATE, "listing-1",
                new BigDecimal("1000.00"), new BigDecimal("1.00"), BigDecimal.ZERO, BigDecimal.ZERO,
                evalSession, sessions, bars, actions
        );

        // Daily equity series must have Day 0 at index 0
        assertEquals(6, res.dailyEquity().size());
        BacktestDtos.DailyEquityPoint day0 = res.dailyEquity().get(0);
        assertEquals("2024-01-31", day0.sessionDate());
        assertEquals("1000.00", day0.cash());
        assertEquals("0.00", day0.holdingsValue());
        assertEquals("0.00", day0.receivables());
        assertEquals("1000.00", day0.totalEquity());
        assertNull(day0.dailyReturn());
        assertEquals("0.00000000", day0.units());

        // Compute analytics summary
        BacktestAnalyticsCalculator calculator = new BacktestAnalyticsCalculator();
        BacktestDtos.BacktestAnalyticsSummary summary = calculator.calculateSummary(res, null);

        assertEquals("1000.00", summary.initialEquity());
        assertEquals("1018.00", summary.finalEquity());
        assertEquals(0.018, summary.cumulativeReturn(), 0.00001);

        // First year return must be exactly 1.80% (0.0180) because Day 0 initial funding is at 2024-01-31
        assertEquals(1, summary.annualReturns().size());
        BacktestDtos.AnnualReturn yr2024 = summary.annualReturns().get(0);
        assertEquals(2024, yr2024.year());
        assertEquals(0.018, yr2024.candidateReturn(), 0.00001);
    }
}
