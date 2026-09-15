package com.bergnerd.signalforge.app.research.backtest;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Component
public class BacktestAnalyticsCalculator {

    public BacktestDtos.BacktestAnalyticsSummary calculateSummary(
            BacktestEngine.SimulationResult result,
            BacktestEngine.SimulationResult benchmarkResult
    ) {
        return calculateSummary(result, benchmarkResult, List.of());
    }

    public BacktestDtos.BacktestAnalyticsSummary calculateSummary(
            BacktestEngine.SimulationResult result,
            BacktestEngine.SimulationResult benchmarkResult,
            List<BacktestDataReader.SessionRecord> calendarSessions
    ) {
        List<BacktestDtos.DailyEquityPoint> points = result.dailyEquity();
        if (points.isEmpty()) {
            return null;
        }

        BigDecimal initialEq = result.initialCash();
        BigDecimal finalEq = result.finalEquity();

        // 1. Cumulative net return
        BigDecimal cumRetBd = finalEq.subtract(initialEq).divide(initialEq, 6, RoundingMode.HALF_EVEN);
        double cumulativeReturn = cumRetBd.doubleValue();

        // 2. CAGR: (ending / starting)^(365.25 / elapsedDays) - 1. Suppress if < 365 days
        // Measure strictly across the execution interval, excluding pre-execution warmup/evaluation sessions
        LocalDate startDate = points.size() > 1 && points.get(0).dailyReturn() == null
                ? LocalDate.parse(points.get(1).sessionDate())
                : LocalDate.parse(points.get(0).sessionDate());
        LocalDate endDate = LocalDate.parse(points.get(points.size() - 1).sessionDate());
        long elapsedDays = ChronoUnit.DAYS.between(startDate, endDate);
        Double cagr = null;
        if (elapsedDays >= 365 && initialEq.compareTo(BigDecimal.ZERO) > 0 && finalEq.compareTo(BigDecimal.ZERO) > 0) {
            double ratio = finalEq.doubleValue() / initialEq.doubleValue();
            double exponent = 365.25 / (double) elapsedDays;
            cagr = Math.pow(ratio, exponent) - 1.0;
        }

        // 3. Max Drawdown, peak date, trough date, recovery date, underwater duration
        double maxDrawdown = 0.0;
        String maxDdPeakDate = points.get(0).sessionDate();
        double maxDdTrough = initialEq.doubleValue();
        String maxDdTroughDate = maxDdPeakDate;
        double maxDdPeak = initialEq.doubleValue();

        double currentPeak = initialEq.doubleValue();
        String currentPeakDate = points.get(0).sessionDate();

        for (int i = 0; i < points.size(); i++) {
            BacktestDtos.DailyEquityPoint pt = points.get(i);
            double eq = Double.parseDouble(pt.totalEquity());

            if (eq >= currentPeak) {
                currentPeak = eq;
                currentPeakDate = pt.sessionDate();
            } else {
                double dd = (eq - currentPeak) / currentPeak;
                if (dd < maxDrawdown) {
                    maxDrawdown = dd;
                    maxDdTrough = eq;
                    maxDdTroughDate = pt.sessionDate();
                    maxDdPeak = currentPeak;
                    maxDdPeakDate = currentPeakDate;
                }
            }
        }

        String peakDate = maxDdPeakDate;
        String troughDate = maxDdTroughDate;
        String recoveryDate = null;
        boolean isRecovered = true;
        Integer underwaterDays = 0;

        // Check recovery after trough
        if (maxDrawdown < 0.0) {
            boolean foundRecovery = false;
            LocalDate pDate = LocalDate.parse(peakDate);
            LocalDate tDate = LocalDate.parse(troughDate);

            for (BacktestDtos.DailyEquityPoint pt : points) {
                LocalDate curDate = LocalDate.parse(pt.sessionDate());
                if (curDate.isAfter(tDate)) {
                    double eq = Double.parseDouble(pt.totalEquity());
                    if (eq >= maxDdPeak) {
                        recoveryDate = pt.sessionDate();
                        foundRecovery = true;
                        underwaterDays = (int) ChronoUnit.DAYS.between(pDate, curDate);
                        break;
                    }
                }
            }

            if (!foundRecovery) {
                isRecovered = false;
                recoveryDate = null;
                underwaterDays = (int) ChronoUnit.DAYS.between(pDate, endDate);
            }
        }

        // 4. Annualized Volatility: sample std dev of daily returns * sqrt(252)
        List<Double> returns = new ArrayList<>();
        for (BacktestDtos.DailyEquityPoint pt : points) {
            if (pt.dailyReturn() != null) {
                returns.add(pt.dailyReturn());
            }
        }

        Double annualizedVolatility = null;
        if (returns.size() >= 2) {
            double mean = returns.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
            double variance = 0.0;
            for (double r : returns) {
                variance += Math.pow(r - mean, 2);
            }
            variance /= (returns.size() - 1);
            annualizedVolatility = Math.sqrt(variance) * Math.sqrt(252.0);
        }

        // 5. Benchmark return and difference
        Double benchmarkReturn = null;
        Double benchmarkDifference = null;
        if (benchmarkResult != null && benchmarkResult.initialCash().compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal bInitial = benchmarkResult.initialCash();
            BigDecimal bFinal = benchmarkResult.finalEquity();
            BigDecimal bRetBd = bFinal.subtract(bInitial).divide(bInitial, 6, RoundingMode.HALF_EVEN);
            benchmarkReturn = bRetBd.doubleValue();
            benchmarkDifference = cumulativeReturn - benchmarkReturn;
        }

        // 6. Turnover = total gross purchases / average equity
        double sumEquity = points.stream().mapToDouble(p -> Double.parseDouble(p.totalEquity())).sum();
        double avgEquity = points.isEmpty() ? initialEq.doubleValue() : sumEquity / points.size();
        double totalPurchases = 0.0;
        for (BacktestDtos.BacktestOrderDto order : result.orders()) {
            if (BacktestDtos.OrderStatus.FILLED.name().equals(order.status())) {
                totalPurchases += Double.parseDouble(order.executedQuantity()) * Double.parseDouble(order.fillPrice());
            }
        }
        Double turnover = avgEquity > 0.0 ? totalPurchases / avgEquity : 0.0;

        // 7. Annual Returns breakdown
        List<BacktestDtos.AnnualReturn> annualReturns = buildAnnualReturns(points,
                benchmarkResult != null ? benchmarkResult.dailyEquity() : null, calendarSessions);

        // 8. Ending state balances and portfolio weights
        BacktestDtos.DailyEquityPoint lastPoint = points.get(points.size() - 1);
        BigDecimal endCashBd = new BigDecimal(lastPoint.cash());
        BigDecimal endHoldingsBd = new BigDecimal(lastPoint.holdingsValue());
        BigDecimal endRecBd = new BigDecimal(lastPoint.receivables());
        BigDecimal totalEndEq = new BigDecimal(lastPoint.totalEquity());

        Double exposureWeight = totalEndEq.compareTo(BigDecimal.ZERO) > 0
                ? endHoldingsBd.divide(totalEndEq, 6, RoundingMode.HALF_EVEN).doubleValue()
                : 0.0;
        Double cashWeight = totalEndEq.compareTo(BigDecimal.ZERO) > 0
                ? endCashBd.divide(totalEndEq, 6, RoundingMode.HALF_EVEN).doubleValue()
                : 0.0;
        Double receivablesWeight = totalEndEq.compareTo(BigDecimal.ZERO) > 0
                ? endRecBd.divide(totalEndEq, 6, RoundingMode.HALF_EVEN).doubleValue()
                : 0.0;
        String turnoverFormula = "TOTAL_PURCHASES_DIVIDED_BY_AVERAGE_EQUITY";
        String receivableTreatment = "CONTRIBUTES_TO_EQUITY_UNSPENDABLE_UNTIL_PAYMENT";

        return new BacktestDtos.BacktestAnalyticsSummary(
                initialEq.setScale(2, RoundingMode.HALF_EVEN).toPlainString(),
                finalEq.setScale(2, RoundingMode.HALF_EVEN).toPlainString(),
                cumulativeReturn,
                cagr,
                benchmarkReturn,
                benchmarkDifference,
                maxDrawdown,
                peakDate,
                troughDate,
                recoveryDate,
                underwaterDays,
                isRecovered,
                annualizedVolatility,
                turnover,
                result.fillCount(),
                result.totalCommissions().setScale(2, RoundingMode.HALF_EVEN).toPlainString(),
                result.totalSpreadSlippageEstimate().setScale(2, RoundingMode.HALF_EVEN).toPlainString(),
                "0.00", // S1 buy-and-hold does not sell -> realized gain = 0
                result.finalHoldings() != null ? result.finalHoldings().unrealizedGain() : "0.00",
                lastPoint.cash(),
                lastPoint.receivables(),
                lastPoint.holdingsValue(),
                lastPoint.costBasis(),
                lastPoint.units(),
                exposureWeight,
                cashWeight,
                receivablesWeight,
                turnoverFormula,
                receivableTreatment,
                annualReturns,
                result.unpaidReceivables() != null
                        ? result.unpaidReceivables().stream().map(p -> new BacktestDtos.UnpaidReceivableDto(
                                p.actionId(),
                                p.amount().toPlainString(),
                                p.entitlementDate(),
                                p.entitlementTime(),
                                p.paymentDate(),
                                p.paymentInstant()
                        )).toList()
                        : Collections.emptyList()
        );
    }

    private List<BacktestDtos.AnnualReturn> buildAnnualReturns(
            List<BacktestDtos.DailyEquityPoint> candidatePoints,
            List<BacktestDtos.DailyEquityPoint> benchmarkPoints,
            List<BacktestDataReader.SessionRecord> calendarSessions
    ) {
        Map<Integer, List<BacktestDtos.DailyEquityPoint>> byYear = new TreeMap<>();
        for (BacktestDtos.DailyEquityPoint pt : candidatePoints) {
            int year = LocalDate.parse(pt.sessionDate()).getYear();
            byYear.computeIfAbsent(year, k -> new ArrayList<>()).add(pt);
        }

        Map<Integer, List<BacktestDtos.DailyEquityPoint>> benchByYear = new TreeMap<>();
        if (benchmarkPoints != null) {
            for (BacktestDtos.DailyEquityPoint pt : benchmarkPoints) {
                int year = LocalDate.parse(pt.sessionDate()).getYear();
                benchByYear.computeIfAbsent(year, k -> new ArrayList<>()).add(pt);
            }
        }

        List<BacktestDtos.AnnualReturn> list = new ArrayList<>();
        Double prevYearEndCandidateEq = null;
        Double prevYearEndBenchEq = null;

        for (Map.Entry<Integer, List<BacktestDtos.DailyEquityPoint>> entry : byYear.entrySet()) {
            int year = entry.getKey();
            List<BacktestDtos.DailyEquityPoint> yearPts = entry.getValue();

            // Skip boundary year that only contains the initial funding point before the first execution session
            boolean onlyInitialFunding = yearPts.size() == 1 && yearPts.get(0).dailyReturn() == null;
            if (onlyInitialFunding) {
                prevYearEndCandidateEq = Double.parseDouble(yearPts.get(0).totalEquity());
                if (benchByYear.containsKey(year) && !benchByYear.get(year).isEmpty()) {
                    prevYearEndBenchEq = Double.parseDouble(benchByYear.get(year).get(0).totalEquity());
                }
                continue;
            }

            List<BacktestDtos.DailyEquityPoint> tradingPts = yearPts.stream()
                    .filter(p -> p.dailyReturn() != null)
                    .toList();
            if (tradingPts.isEmpty()) {
                tradingPts = yearPts;
            }

            LocalDate firstTradingDate = LocalDate.parse(tradingPts.get(0).sessionDate());
            LocalDate lastTradingDate = LocalDate.parse(tradingPts.get(tradingPts.size() - 1).sessionDate());

            List<LocalDate> yearCalendar = calendarSessions.stream()
                    .filter(BacktestDataReader.SessionRecord::isTrading)
                    .map(session -> LocalDate.parse(session.sessionDate()))
                    .filter(date -> date.getYear() == year)
                    .sorted()
                    .toList();
            boolean isPartial = yearCalendar.isEmpty()
                    || yearCalendar.get(0).getMonthValue() != 1
                    || yearCalendar.get(yearCalendar.size() - 1).getMonthValue() != 12
                    || firstTradingDate.isAfter(yearCalendar.get(0))
                    || lastTradingDate.isBefore(yearCalendar.get(yearCalendar.size() - 1));

            double startEq = prevYearEndCandidateEq != null
                    ? prevYearEndCandidateEq
                    : Double.parseDouble(yearPts.get(0).totalEquity());
            double endEq = Double.parseDouble(yearPts.get(yearPts.size() - 1).totalEquity());
            Double candRet = startEq > 0 ? (endEq - startEq) / startEq : 0.0;

            Double benchRet = null;
            if (benchByYear.containsKey(year)) {
                List<BacktestDtos.DailyEquityPoint> bPts = benchByYear.get(year);
                double bStart = prevYearEndBenchEq != null
                        ? prevYearEndBenchEq
                        : Double.parseDouble(bPts.get(0).totalEquity());
                double bEnd = Double.parseDouble(bPts.get(bPts.size() - 1).totalEquity());
                benchRet = bStart > 0 ? (bEnd - bStart) / bStart : 0.0;
                prevYearEndBenchEq = bEnd;
            }

            list.add(new BacktestDtos.AnnualReturn(
                    year,
                    candRet,
                    benchRet,
                    BigDecimal.valueOf(startEq).setScale(2, RoundingMode.HALF_EVEN).toPlainString(),
                    yearPts.get(yearPts.size() - 1).totalEquity(),
                    isPartial));
            prevYearEndCandidateEq = endEq;
        }

        return list;
    }

    public BacktestDtos.RollingWindowSummaryDto calculateRolling5YearWindows(
            List<BacktestDtos.DailyEquityPoint> points,
            List<BacktestDataReader.SessionRecord> allSessions
    ) {
        if (points == null || points.isEmpty() || allSessions == null || allSessions.isEmpty()) {
            return new BacktestDtos.RollingWindowSummaryDto(0, 0, 0, null, List.of(),
                    "Rolling 5-year historical compounded-return windows are descriptive statistics and not predictive of future performance. Overlapping windows introduce serial correlation.");
        }

        List<BacktestDtos.DailyEquityPoint> tradingPoints = points.stream()
                .filter(p -> p.dailyReturn() != null)
                .toList();
        if (tradingPoints.isEmpty()) {
            tradingPoints = points;
        }

        Map<String, List<BacktestDtos.DailyEquityPoint>> pointsByMonth = new LinkedHashMap<>();
        for (BacktestDtos.DailyEquityPoint pt : tradingPoints) {
            String ym = pt.sessionDate().substring(0, 7);
            pointsByMonth.computeIfAbsent(ym, k -> new ArrayList<>()).add(pt);
        }

        List<BacktestDtos.RollingWindowDto> windowList = new ArrayList<>();
        int windowIdx = 0;
        int positiveCount = 0;
        int completeCount = 0;

        List<String> sortedMonths = new ArrayList<>(pointsByMonth.keySet());
        BacktestDtos.DailyEquityPoint prevSessionPt = points.get(0);

        for (int m = 0; m < sortedMonths.size(); m++) {
            String ym = sortedMonths.get(m);
            List<BacktestDtos.DailyEquityPoint> monthPts = pointsByMonth.get(ym);
            BacktestDtos.DailyEquityPoint firstMonthPt = monthPts.get(0);

            String startingEquityStr;
            String startSessionDate = firstMonthPt.sessionDate();
            if (m == 0) {
                startingEquityStr = points.get(0).totalEquity();
            } else {
                startingEquityStr = prevSessionPt.totalEquity();
            }

            LocalDate firstDate = LocalDate.parse(firstMonthPt.sessionDate());
            LocalDate targetEndDate = firstDate.plusYears(5);
            String targetEndStr = targetEndDate.toString();

            LocalDate maxCalendarDate = allSessions != null && !allSessions.isEmpty()
                    ? LocalDate.parse(allSessions.get(allSessions.size() - 1).sessionDate())
                    : null;

            BacktestDataReader.SessionRecord expectedEndingSession = null;
            if (allSessions != null) {
                for (BacktestDataReader.SessionRecord s : allSessions) {
                    if (s.isTrading()) {
                        LocalDate sd = LocalDate.parse(s.sessionDate());
                        if (!sd.isAfter(targetEndDate)) {
                            expectedEndingSession = s;
                        }
                    }
                }
            }

            String lastRunSessionDate = tradingPoints.get(tradingPoints.size() - 1).sessionDate();
            LocalDate lastRunDate = LocalDate.parse(lastRunSessionDate);

            boolean hasCalendarCoverage = maxCalendarDate == null || !targetEndDate.isAfter(maxCalendarDate);
            boolean coveredInRun = hasCalendarCoverage && expectedEndingSession != null
                    && !LocalDate.parse(expectedEndingSession.sessionDate()).isAfter(lastRunDate);

            if (!coveredInRun) {
                windowIdx++;
                String reason = !hasCalendarCoverage
                        ? "INSUFFICIENT_CALENDAR_COVERAGE: Calendar ends on " + maxCalendarDate + ", before 5-year anniversary " + targetEndStr
                        : "INSUFFICIENT_HISTORY: Backtest ends on " + lastRunSessionDate + ", before 5-year anniversary " + targetEndStr;
                windowList.add(new BacktestDtos.RollingWindowDto(
                        windowIdx,
                        startSessionDate,
                        targetEndStr,
                        null,
                        startingEquityStr,
                        null,
                        null,
                        null,
                        0,
                        false,
                        reason
                ));
            } else {
                String expectedEndDate = expectedEndingSession.sessionDate();
                BacktestDtos.DailyEquityPoint endingPt = null;
                int obsCount = 0;
                for (BacktestDtos.DailyEquityPoint pt : tradingPoints) {
                    LocalDate d = LocalDate.parse(pt.sessionDate());
                    if (!d.isBefore(firstDate) && !d.isAfter(targetEndDate)) {
                        obsCount++;
                    }
                    if (pt.sessionDate().equals(expectedEndDate)) {
                        endingPt = pt;
                    }
                }

                if (endingPt != null) {
                    windowIdx++;
                    completeCount++;
                    BigDecimal startEq = new BigDecimal(startingEquityStr);
                    BigDecimal endEq = new BigDecimal(endingPt.totalEquity());
                    BigDecimal exactReturn = endEq.subtract(startEq)
                            .divide(startEq, 8, RoundingMode.HALF_EVEN);
                    double compoundedReturn = exactReturn.doubleValue();
                    String compoundedReturnExact = exactReturn.toPlainString();
                    if (compoundedReturn > 0.0) {
                        positiveCount++;
                    }

                    windowList.add(new BacktestDtos.RollingWindowDto(
                            windowIdx,
                            startSessionDate,
                            targetEndStr,
                            endingPt.sessionDate(),
                            startingEquityStr,
                            endingPt.totalEquity(),
                            compoundedReturn,
                            compoundedReturnExact,
                            obsCount,
                            true,
                            null
                    ));
                } else {
                    windowIdx++;
                    windowList.add(new BacktestDtos.RollingWindowDto(
                            windowIdx, startSessionDate, targetEndStr, expectedEndDate,
                            startingEquityStr, null, null, null, obsCount, false,
                            "MISSING_END_EQUITY: No portfolio equity at the declared anniversary session " + expectedEndDate));
                }
            }

            prevSessionPt = monthPts.get(monthPts.size() - 1);
        }

        Double positiveShare = completeCount > 0
                ? (double) positiveCount / (double) completeCount
                : null;

        return new BacktestDtos.RollingWindowSummaryDto(
                windowList.size(),
                completeCount,
                positiveCount,
                positiveShare,
                windowList,
                "Rolling 5-year historical compounded-return windows are descriptive statistics and not predictive of future performance. Overlapping windows introduce serial correlation."
        );
    }
}

