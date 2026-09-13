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
        LocalDate startDate = LocalDate.parse(points.get(0).sessionDate());
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
        List<BacktestDtos.AnnualReturn> annualReturns = buildAnnualReturns(points, benchmarkResult != null ? benchmarkResult.dailyEquity() : null);

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
            List<BacktestDtos.DailyEquityPoint> benchmarkPoints
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

            LocalDate firstDate = LocalDate.parse(yearPts.get(0).sessionDate());
            LocalDate lastDate = LocalDate.parse(yearPts.get(yearPts.size() - 1).sessionDate());

            // A calendar year is partial if first observation is after the first week of Jan or last observation is before late Dec
            boolean isPartial = (firstDate.getMonthValue() != 1 || firstDate.getDayOfMonth() > 7 ||
                    lastDate.getMonthValue() != 12 || lastDate.getDayOfMonth() < 24);

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

            list.add(new BacktestDtos.AnnualReturn(year, candRet, benchRet, isPartial));
            prevYearEndCandidateEq = endEq;
        }

        return list;
    }
}
