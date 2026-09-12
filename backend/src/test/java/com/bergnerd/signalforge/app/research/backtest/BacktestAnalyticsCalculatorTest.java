package com.bergnerd.signalforge.app.research.backtest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BacktestAnalyticsCalculatorTest {

    private BacktestAnalyticsCalculator calculator;

    @BeforeEach
    void setUp() {
        calculator = new BacktestAnalyticsCalculator();
    }

    @Test
    @DisplayName("Short sample (< 365 days) suppresses CAGR but provides cumulative return")
    void testShortSampleSuppressesCagr() {
        List<BacktestDtos.DailyEquityPoint> points = List.of(
                new BacktestDtos.DailyEquityPoint("2024-02-01", "CANDIDATE", "100.00", "900.00", "0.00", "1000.00", 0.0, 0.0, "1000.00", "10.00", "900.00", "90.00"),
                new BacktestDtos.DailyEquityPoint("2024-02-07", "CANDIDATE", "18.00", "1000.00", "0.00", "1018.00", 0.018, 0.0, "1018.00", "20.00", "1000.00", "50.00")
        );

        BacktestEngine.SimulationResult simResult = new BacktestEngine.SimulationResult(
                BacktestDtos.SeriesType.CANDIDATE, "listing-1", points, List.of(), List.of(), null,
                new BigDecimal("1000.00"), new BigDecimal("1018.00"),
                new BigDecimal("2.00"), BigDecimal.ZERO, new BigDecimal("18.00"), 2
        );

        BacktestDtos.BacktestAnalyticsSummary summary = calculator.calculateSummary(simResult, null);

        assertNotNull(summary);
        assertEquals(0.018, summary.cumulativeReturn(), 0.0001);
        assertNull(summary.cagr(), "CAGR must be suppressed for periods shorter than 1 calendar year (365 days)");
    }

    @Test
    @DisplayName("Multi-year period calculates CAGR correctly per specification formula")
    void testMultiYearCagrCalculation() {
        // From 2022-01-01 to 2024-01-01: 730 days
        // starting 1000, ending 1210 -> (1210/1000)^(365.25 / 730) - 1 ≈ 1.21^0.500342 - 1 ≈ 10.02%
        List<BacktestDtos.DailyEquityPoint> points = List.of(
                new BacktestDtos.DailyEquityPoint("2022-01-01", "CANDIDATE", "100.00", "900.00", "0.00", "1000.00", 0.0, 0.0, "1000.00", "10.00", "900.00", "90.00"),
                new BacktestDtos.DailyEquityPoint("2024-01-01", "CANDIDATE", "100.00", "1110.00", "0.00", "1210.00", 0.05, 0.0, "1210.00", "10.00", "900.00", "111.00")
        );

        BacktestEngine.SimulationResult simResult = new BacktestEngine.SimulationResult(
                BacktestDtos.SeriesType.CANDIDATE, "listing-1", points, List.of(), List.of(), null,
                new BigDecimal("1000.00"), new BigDecimal("1210.00"),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 1
        );

        BacktestDtos.BacktestAnalyticsSummary summary = calculator.calculateSummary(simResult, null);

        assertNotNull(summary);
        assertNotNull(summary.cagr());
        assertEquals(0.1002, summary.cagr(), 0.005);
    }

    @Test
    @DisplayName("Max drawdown detects peak, trough, recovery date and underwater duration")
    void testDrawdownAndRecoveryTracking() {
        // Equity path: 1000 -> 1200 (peak) -> 900 (trough) -> 1100 -> 1250 (recovered)
        List<BacktestDtos.DailyEquityPoint> points = List.of(
                new BacktestDtos.DailyEquityPoint("2024-01-02", "CANDIDATE", "100", "900", "0", "1000.00", 0.0, 0.0, "1000.00", "10", "900", "90"),
                new BacktestDtos.DailyEquityPoint("2024-01-03", "CANDIDATE", "100", "1100", "0", "1200.00", 0.20, 0.0, "1200.00", "10", "900", "110"),
                new BacktestDtos.DailyEquityPoint("2024-01-04", "CANDIDATE", "100", "800", "0", "900.00", -0.25, -0.25, "1200.00", "10", "900", "80"),
                new BacktestDtos.DailyEquityPoint("2024-01-05", "CANDIDATE", "100", "1000", "0", "1100.00", 0.222, -0.083, "1200.00", "10", "900", "100"),
                new BacktestDtos.DailyEquityPoint("2024-01-08", "CANDIDATE", "100", "1150", "0", "1250.00", 0.136, 0.0, "1250.00", "10", "900", "115")
        );

        BacktestEngine.SimulationResult simResult = new BacktestEngine.SimulationResult(
                BacktestDtos.SeriesType.CANDIDATE, "listing-1", points, List.of(), List.of(), null,
                new BigDecimal("1000.00"), new BigDecimal("1250.00"),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 1
        );

        BacktestDtos.BacktestAnalyticsSummary summary = calculator.calculateSummary(simResult, null);

        assertEquals(-0.25, summary.maxDrawdown(), 0.001);
        assertEquals("2024-01-03", summary.peakDate());
        assertEquals("2024-01-04", summary.troughDate());
        assertEquals("2024-01-08", summary.recoveryDate());
        assertTrue(summary.isRecovered());
        assertEquals(5, summary.underwaterDurationDays()); // Jan 3 to Jan 8 = 5 days
    }

    @Test
    @DisplayName("Unrecovered drawdown labels ongoing status and recoveryDate as null")
    void testUnrecoveredDrawdown() {
        // Equity: 1000 -> 1200 -> 900 -> 950 (ends underwater)
        List<BacktestDtos.DailyEquityPoint> points = List.of(
                new BacktestDtos.DailyEquityPoint("2024-01-02", "CANDIDATE", "100", "900", "0", "1000.00", 0.0, 0.0, "1000.00", "10", "900", "90"),
                new BacktestDtos.DailyEquityPoint("2024-01-03", "CANDIDATE", "100", "1100", "0", "1200.00", 0.20, 0.0, "1200.00", "10", "900", "110"),
                new BacktestDtos.DailyEquityPoint("2024-01-04", "CANDIDATE", "100", "800", "0", "900.00", -0.25, -0.25, "1200.00", "10", "900", "80"),
                new BacktestDtos.DailyEquityPoint("2024-01-05", "CANDIDATE", "100", "850", "0", "950.00", 0.055, -0.208, "1200.00", "10", "900", "85")
        );

        BacktestEngine.SimulationResult simResult = new BacktestEngine.SimulationResult(
                BacktestDtos.SeriesType.CANDIDATE, "listing-1", points, List.of(), List.of(), null,
                new BigDecimal("1000.00"), new BigDecimal("950.00"),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 1
        );

        BacktestDtos.BacktestAnalyticsSummary summary = calculator.calculateSummary(simResult, null);

        assertEquals(-0.25, summary.maxDrawdown(), 0.001);
        assertNull(summary.recoveryDate(), "Recovery date must be null for ongoing drawdown");
        assertFalse(summary.isRecovered());
        assertEquals(2, summary.underwaterDurationDays()); // Jan 3 to Jan 5 = 2 days
    }

    @Test
    @DisplayName("Sample daily return volatility annualized with 252 days")
    void testAnnualizedVolatility() {
        // Daily returns: 0.01, -0.01, 0.02, 0.00
        List<BacktestDtos.DailyEquityPoint> points = List.of(
                new BacktestDtos.DailyEquityPoint("2024-01-02", "CANDIDATE", "100", "900", "0", "1000.00", 0.01, 0.0, "1000.00", "10", "900", "90"),
                new BacktestDtos.DailyEquityPoint("2024-01-03", "CANDIDATE", "100", "890", "0", "990.00", -0.01, -0.01, "1000.00", "10", "900", "89"),
                new BacktestDtos.DailyEquityPoint("2024-01-04", "CANDIDATE", "100", "910", "0", "1010.00", 0.02, 0.0, "1010.00", "10", "900", "91"),
                new BacktestDtos.DailyEquityPoint("2024-01-05", "CANDIDATE", "100", "910", "0", "1010.00", 0.00, 0.0, "1010.00", "10", "900", "91")
        );

        BacktestEngine.SimulationResult simResult = new BacktestEngine.SimulationResult(
                BacktestDtos.SeriesType.CANDIDATE, "listing-1", points, List.of(), List.of(), null,
                new BigDecimal("1000.00"), new BigDecimal("1010.00"),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 1
        );

        BacktestDtos.BacktestAnalyticsSummary summary = calculator.calculateSummary(simResult, null);

        assertNotNull(summary.annualizedVolatility());
        assertTrue(summary.annualizedVolatility() > 0.0);
    }
}
