package com.bergnerd.signalforge.app.research.backtest;

import com.bergnerd.signalforge.app.accounting.AccountingCore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;

/**
 * Authoritative calculator for daily total-return signal indices and month-end observations.
 * Independent of portfolio cash and holdings.
 *
 * Formula:
 * T(d) = T(previous session) * splitMultiplier(d) * (rawClose(d) + distributionPerPostSplitUnit(d)) / rawClose(previous session)
 *
 * Initial base: 100.0.
 */
@Slf4j
@Component
public class TotalReturnSignalIndexCalculator {

    public static final BigDecimal INITIAL_INDEX_BASE = new BigDecimal("100.00000000");

    public record DailyIndexPoint(
            String sessionDate,
            BigDecimal rawClose,
            BigDecimal splitMultiplier,
            BigDecimal distributionAmount,
            BigDecimal indexValue
    ) {}

    public record MonthObservation(
            YearMonth yearMonth,
            String sessionDate,
            BigDecimal rawClose,
            BigDecimal indexValue
    ) {}

    public record ListingSignalIndexSeries(
            String listingId,
            Map<String, DailyIndexPoint> dailyPoints, // sessionDate -> DailyIndexPoint
            List<DailyIndexPoint> orderedPoints,
            Map<YearMonth, MonthObservation> monthEndObservations,
            List<MonthObservation> orderedMonthEnds
    ) {
        public DailyIndexPoint getPoint(String sessionDate) {
            return dailyPoints.get(sessionDate);
        }

        public MonthObservation getMonthEnd(YearMonth ym) {
            return monthEndObservations.get(ym);
        }
    }

    /**
     * Compute continuous total-return index points for a listing across ordered trading sessions.
     */
    public ListingSignalIndexSeries calculateSeries(
            String listingId,
            List<BacktestDataReader.SessionRecord> tradingSessions,
            Map<String, BacktestDataReader.BarRecord> bars,
            List<BacktestDataReader.ActionRecord> actions
    ) {
        return calculateSeries(listingId, tradingSessions, bars, actions, null);
    }

    public ListingSignalIndexSeries calculateSeries(
            String listingId,
            List<BacktestDataReader.SessionRecord> tradingSessions,
            Map<String, BacktestDataReader.BarRecord> bars,
            List<BacktestDataReader.ActionRecord> actions,
            java.time.Instant asOfInstant
    ) {
        if (tradingSessions.isEmpty()) {
            return new ListingSignalIndexSeries(listingId, Map.of(), List.of(), Map.of(), List.of());
        }

        // Group actions by effective date, strictly filtering out any actions unavailable at asOfInstant
        Map<String, List<BacktestDataReader.ActionRecord>> actionsByDate = new HashMap<>();
        if (actions != null) {
            for (BacktestDataReader.ActionRecord a : actions) {
                if (asOfInstant != null) {
                    if (a.availableAt() == null || a.availableAt().isBlank()) {
                        throw new IllegalArgumentException("Action " + a.actionId() + " has no availability time");
                    }
                    if (java.time.Instant.parse(a.availableAt().trim()).isAfter(asOfInstant)) {
                        continue;
                    }
                }
                actionsByDate.computeIfAbsent(a.effectiveDate(), k -> new ArrayList<>()).add(a);
            }
        }

        Map<String, DailyIndexPoint> dailyPoints = new LinkedHashMap<>();
        List<DailyIndexPoint> orderedPoints = new ArrayList<>();

        BigDecimal prevIndex = INITIAL_INDEX_BASE;
        BigDecimal prevClose = null;

        for (int i = 0; i < tradingSessions.size(); i++) {
            BacktestDataReader.SessionRecord session = tradingSessions.get(i);
            String sDate = session.sessionDate();
            BacktestDataReader.BarRecord bar = bars.get(sDate);
            if (bar == null) {
                throw new IllegalStateException("Missing bar on session " + sDate + " for listing " + listingId);
            }
            if (asOfInstant != null) {
                if (bar.availableAt() == null || bar.availableAt().isBlank()) {
                    throw new IllegalArgumentException("Bar for " + listingId + " on " + sDate + " has no availability time");
                }
                if (java.time.Instant.parse(bar.availableAt().trim()).isAfter(asOfInstant)) {
                    break;
                }
            }
            BigDecimal close = bar.close();

            // Corporate actions on effective date: splits first, then cash distributions
            List<BacktestDataReader.ActionRecord> sessionActions = actionsByDate.getOrDefault(sDate, List.of());
            BigDecimal splitMultiplier = BigDecimal.ONE;
            BigDecimal distributionAmount = BigDecimal.ZERO;

            for (BacktestDataReader.ActionRecord action : sessionActions) {
                if (action.isSplit()) {
                    splitMultiplier = splitMultiplier.multiply(action.splitRatio(), AccountingCore.MATH_CONTEXT);
                }
            }
            for (BacktestDataReader.ActionRecord action : sessionActions) {
                if (action.isDistribution() && action.distributionAmount() != null) {
                    distributionAmount = distributionAmount.add(action.distributionAmount());
                }
            }

            BigDecimal currentIndex;
            if (i == 0) {
                currentIndex = INITIAL_INDEX_BASE;
            } else {
                if (prevClose == null || prevClose.compareTo(BigDecimal.ZERO) <= 0) {
                    throw new IllegalStateException("Invalid previous close price on session " + sDate + " for listing " + listingId);
                }
                // T(d) = T(d-1) * splitMultiplier * (rawClose(d) + distribution) / rawClose(d-1)
                BigDecimal numerator = prevIndex
                        .multiply(splitMultiplier, AccountingCore.MATH_CONTEXT)
                        .multiply(close.add(distributionAmount), AccountingCore.MATH_CONTEXT);
                currentIndex = numerator.divide(prevClose, AccountingCore.MATH_CONTEXT);
            }

            DailyIndexPoint point = new DailyIndexPoint(
                    sDate, close, splitMultiplier, distributionAmount, currentIndex
            );
            dailyPoints.put(sDate, point);
            orderedPoints.add(point);

            prevIndex = currentIndex;
            prevClose = close;
        }

        // Extract month-end sessions: last trading session of each calendar month
        Map<YearMonth, MonthObservation> monthEndMap = new LinkedHashMap<>();
        List<MonthObservation> orderedMonthEnds = new ArrayList<>();

        for (int i = 0; i < tradingSessions.size(); i++) {
            BacktestDataReader.SessionRecord session = tradingSessions.get(i);
            LocalDate date = LocalDate.parse(session.sessionDate());
            YearMonth ym = YearMonth.from(date);

            boolean isLastTradingSessionOfMonth = true;
            if (i + 1 < tradingSessions.size()) {
                LocalDate nextDate = LocalDate.parse(tradingSessions.get(i + 1).sessionDate());
                if (YearMonth.from(nextDate).equals(ym)) {
                    isLastTradingSessionOfMonth = false;
                }
            }

            if (isLastTradingSessionOfMonth) {
                DailyIndexPoint pt = dailyPoints.get(session.sessionDate());
                if (pt != null) {
                    MonthObservation obs = new MonthObservation(ym, session.sessionDate(), pt.rawClose(), pt.indexValue());
                    monthEndMap.put(ym, obs);
                    orderedMonthEnds.add(obs);
                }
            }
        }

        return new ListingSignalIndexSeries(listingId, dailyPoints, orderedPoints, monthEndMap, orderedMonthEnds);
    }

    public static List<String> extractMonthEndSessions(List<BacktestDataReader.SessionRecord> allSessions) {
        List<String> monthEnds = new ArrayList<>();
        List<BacktestDataReader.SessionRecord> trading = allSessions.stream()
                .filter(BacktestDataReader.SessionRecord::isTrading)
                .toList();
        for (int i = 0; i < trading.size(); i++) {
            BacktestDataReader.SessionRecord current = trading.get(i);
            LocalDate curDate = LocalDate.parse(current.sessionDate());
            boolean isLast = true;
            if (i + 1 < trading.size()) {
                LocalDate nextDate = LocalDate.parse(trading.get(i + 1).sessionDate());
                if (curDate.getYear() == nextDate.getYear() && curDate.getMonth() == nextDate.getMonth()) {
                    isLast = false;
                }
            }
            if (isLast) {
                monthEnds.add(current.sessionDate());
            }
        }
        return monthEnds;
    }
}
