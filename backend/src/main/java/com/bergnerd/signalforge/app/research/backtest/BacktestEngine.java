package com.bergnerd.signalforge.app.research.backtest;

import com.bergnerd.signalforge.app.accounting.AccountingCore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.*;

@Slf4j
@Component
public class BacktestEngine {

    public record SimulationResult(
            BacktestDtos.SeriesType seriesType,
            String listingId,
            List<BacktestDtos.DailyEquityPoint> dailyEquity,
            List<BacktestDtos.BacktestOrderDto> orders,
            List<BacktestDtos.BacktestEventDto> events,
            BacktestDtos.BacktestHoldingsDto finalHoldings,
            BigDecimal initialCash,
            BigDecimal finalEquity,
            BigDecimal totalCommissions,
            BigDecimal totalSpreadSlippageEstimate,
            BigDecimal totalDistributionsRecognized,
            int fillCount,
            List<PendingDistribution> unpaidReceivables
    ) {
        public SimulationResult(
                BacktestDtos.SeriesType seriesType,
                String listingId,
                List<BacktestDtos.DailyEquityPoint> dailyEquity,
                List<BacktestDtos.BacktestOrderDto> orders,
                List<BacktestDtos.BacktestEventDto> events,
                BacktestDtos.BacktestHoldingsDto finalHoldings,
                BigDecimal initialCash,
                BigDecimal finalEquity,
                BigDecimal totalCommissions,
                BigDecimal totalSpreadSlippageEstimate,
                BigDecimal totalDistributionsRecognized,
                int fillCount
        ) {
            this(seriesType, listingId, dailyEquity, orders, events, finalHoldings,
                 initialCash, finalEquity, totalCommissions, totalSpreadSlippageEstimate,
                 totalDistributionsRecognized, fillCount, List.of());
        }
    }

    public record PendingDistribution(
            String actionId,
            BigDecimal amount,
            String paymentDate,
            String paymentInstant,
            String entitlementDate,
            String entitlementTime
    ) {}

    public SimulationResult runS1(
            String runId,
            BacktestDtos.SeriesType seriesType,
            String listingId,
            BigDecimal initialCash,
            BigDecimal commissionPerFill,
            BigDecimal spreadBps,
            BigDecimal slippageBps,
            List<BacktestDataReader.SessionRecord> sessions,
            Map<String, BacktestDataReader.BarRecord> bars,
            List<BacktestDataReader.ActionRecord> actions
    ) {
        return runS1(runId, seriesType, listingId, initialCash, commissionPerFill, spreadBps, slippageBps, null, sessions, bars, actions);
    }

    public SimulationResult runS1(
            String runId,
            BacktestDtos.SeriesType seriesType,
            String listingId,
            BigDecimal initialCash,
            BigDecimal commissionPerFill,
            BigDecimal spreadBps,
            BigDecimal slippageBps,
            BacktestDataReader.SessionRecord evalSession,
            List<BacktestDataReader.SessionRecord> sessions,
            Map<String, BacktestDataReader.BarRecord> bars,
            List<BacktestDataReader.ActionRecord> actions
    ) {
        // Normalization
        BigDecimal cash = AccountingCore.normalizeStartingCash(initialCash);
        BigDecimal commission = AccountingCore.normalizeCash(commissionPerFill, "commission");
        BigDecimal units = BigDecimal.ZERO.setScale(AccountingCore.QUANTITY_SCALE, AccountingCore.CASH_ROUNDING);
        BigDecimal totalBasis = BigDecimal.ZERO.setScale(AccountingCore.CASH_SCALE, AccountingCore.CASH_ROUNDING);
        BigDecimal receivables = BigDecimal.ZERO.setScale(AccountingCore.CASH_SCALE, AccountingCore.CASH_ROUNDING);
        BigDecimal peakEquity = cash;

        BigDecimal totalCommissions = BigDecimal.ZERO.setScale(AccountingCore.CASH_SCALE, AccountingCore.CASH_ROUNDING);
        BigDecimal totalSpreadSlippage = BigDecimal.ZERO.setScale(AccountingCore.CASH_SCALE, AccountingCore.CASH_ROUNDING);
        BigDecimal totalDistributions = BigDecimal.ZERO.setScale(AccountingCore.CASH_SCALE, AccountingCore.CASH_ROUNDING);
        int fillCount = 0;
        int orderSeq = 0;
        int eventSeq = 0;

        List<BacktestDtos.DailyEquityPoint> dailyEquityList = new ArrayList<>();
        List<BacktestDtos.BacktestOrderDto> ordersList = new ArrayList<>();
        List<BacktestDtos.BacktestEventDto> eventsList = new ArrayList<>();
        List<PendingDistribution> pendingDistributions = new ArrayList<>();

        boolean hasReinvestmentPending = false;
        BigDecimal prevSessionEquity = cash;

        // Day 0: Initial Funded Point at evaluation session (warmup/evaluation boundary)
        if (evalSession != null) {
            BacktestDataReader.BarRecord evalBar = bars.get(evalSession.sessionDate());
            String evalClose = evalBar != null ? evalBar.close().toPlainString() : "0.00";
            dailyEquityList.add(new BacktestDtos.DailyEquityPoint(
                    evalSession.sessionDate(),
                    seriesType.name(),
                    cash.toPlainString(),
                    "0.00",
                    "0.00",
                    cash.toPlainString(),
                    null,
                    0.0,
                    cash.toPlainString(),
                    "0.00000000",
                    "0.00",
                    evalClose
            ));
        }

        // Spread & slippage fractions
        BigDecimal halfSpreadFraction = spreadBps.divide(new BigDecimal("20000"), 8, RoundingMode.HALF_EVEN);
        BigDecimal slippageFraction = slippageBps.divide(new BigDecimal("10000"), 8, RoundingMode.HALF_EVEN);
        BigDecimal costMultiplier = BigDecimal.ONE.add(halfSpreadFraction).add(slippageFraction);

        for (int i = 0; i < sessions.size(); i++) {
            BacktestDataReader.SessionRecord session = sessions.get(i);
            String sDate = session.sessionDate();
            BacktestDataReader.BarRecord bar = bars.get(sDate);
            if (bar == null) {
                throw new IllegalStateException("Missing bar on session " + sDate + " for listing " + listingId);
            }

            // Session open instant
            java.time.Instant sessionOpenInstant = session.openTime() != null
                    ? java.time.Instant.parse(session.openTime())
                    : java.time.LocalDate.parse(sDate).atTime(9, 0, 0).atZone(java.time.ZoneOffset.UTC).toInstant();

            // --- 1. Initial Funding (immediately before first session open) ---
            if (i == 0) {
                eventSeq++;
                String eventId = runId + "-" + seriesType.name().toLowerCase() + "-evt-" + String.format("%04d", eventSeq);
                String fundingTime = sessionOpenInstant.minusSeconds(900).toString();
                eventsList.add(new BacktestDtos.BacktestEventDto(
                        eventId,
                        runId,
                        seriesType.name(),
                        eventSeq,
                        BacktestDtos.EventType.FUNDING.name(),
                        sDate,
                        fundingTime,
                        "Initial funding of " + cash.toPlainString() + " EUR",
                        "{\"initialCash\":\"" + cash.toPlainString() + "\",\"currency\":\"EUR\"}",
                        cash.toPlainString(),
                        "0.00000000",
                        "0.00",
                        "0.00",
                        fundingTime
                ));
            }

            // --- 2. Corporate Actions (pre-open, relative to session open): Splits first, then Entitlements ---
            String splitTime = sessionOpenInstant.minusSeconds(600).toString();
            for (BacktestDataReader.ActionRecord action : actions) {
                if (sDate.equals(action.effectiveDate()) && action.isSplit()) {
                    BigDecimal ratio = action.splitRatio();
                    BigDecimal newUnits = AccountingCore.normalizeQuantity(
                            units.multiply(ratio, AccountingCore.MATH_CONTEXT)
                    );
                    BigDecimal unitsDelta = newUnits.subtract(units);
                    units = newUnits;

                    eventSeq++;
                    String eventId = runId + "-" + seriesType.name().toLowerCase() + "-evt-" + String.format("%04d", eventSeq);
                    eventsList.add(new BacktestDtos.BacktestEventDto(
                            eventId,
                            runId,
                            seriesType.name(),
                            eventSeq,
                            BacktestDtos.EventType.SPLIT.name(),
                            sDate,
                            splitTime,
                            "Stock split " + action.splitNumerator() + ":" + action.splitDenominator() +
                                    ", units adjusted from " + units.subtract(unitsDelta).setScale(AccountingCore.QUANTITY_SCALE, AccountingCore.CASH_ROUNDING).toPlainString() +
                                    " to " + units.setScale(AccountingCore.QUANTITY_SCALE, AccountingCore.CASH_ROUNDING).toPlainString(),
                            "{\"actionId\":\"" + action.actionId() + "\",\"splitNumerator\":" + action.splitNumerator() + ",\"splitDenominator\":" + action.splitDenominator() + "}",
                            "0.00",
                            unitsDelta.setScale(AccountingCore.QUANTITY_SCALE, AccountingCore.CASH_ROUNDING).toPlainString(),
                            "0.00",
                            "0.00",
                            splitTime
                    ));
                }
            }

            // Process distribution entitlements (based on pre-trade holdings adjusted for same-day split)
            String entitlementTime = sessionOpenInstant.minusSeconds(300).toString();
            for (BacktestDataReader.ActionRecord action : actions) {
                if (sDate.equals(action.effectiveDate()) && action.isDistribution()) {
                    if (units.compareTo(BigDecimal.ZERO) > 0 && action.distributionAmount() != null) {
                        BigDecimal entitlement = AccountingCore.roundCash(
                                units.multiply(action.distributionAmount(), AccountingCore.MATH_CONTEXT)
                        );
                        receivables = receivables.add(entitlement);
                        totalDistributions = totalDistributions.add(entitlement);

                        pendingDistributions.add(new PendingDistribution(
                                action.actionId(),
                                entitlement,
                                action.paymentDate(),
                                action.paymentInstant(),
                                sDate,
                                entitlementTime
                        ));

                        eventSeq++;
                        String eventId = runId + "-" + seriesType.name().toLowerCase() + "-evt-" + String.format("%04d", eventSeq);
                        String payload = "{\"actionId\":\"" + action.actionId() + "\",\"amount\":\"" + entitlement.toPlainString() + "\"" +
                                ",\"paymentDate\":" + (action.paymentDate() != null ? "\"" + action.paymentDate() + "\"" : "null") +
                                ",\"paymentInstant\":" + (action.paymentInstant() != null ? "\"" + action.paymentInstant() + "\"" : "null") +
                                ",\"entitlementDate\":\"" + sDate + "\",\"entitlementTime\":\"" + entitlementTime + "\"}";
                        eventsList.add(new BacktestDtos.BacktestEventDto(
                                eventId,
                                runId,
                                seriesType.name(),
                                eventSeq,
                                BacktestDtos.EventType.ENTITLEMENT.name(),
                                sDate,
                                entitlementTime,
                                "Cash distribution entitlement of " + entitlement.toPlainString() + " EUR (" +
                                        action.distributionAmount().toPlainString() + " EUR/unit on " + units.toPlainString() + " units)",
                                payload,
                                "0.00",
                                "0.00000000",
                                "0.00",
                                entitlement.toPlainString(),
                                entitlementTime
                        ));
                    }
                }
            }

            // --- 3. Payments arriving strictly before this session open (intraday or earlier closed days) ---
            Iterator<PendingDistribution> pIter = pendingDistributions.iterator();
            while (pIter.hasNext()) {
                PendingDistribution p = pIter.next();
                boolean settlesBeforeOpen = false;
                String settlementTime = null;

                if (p.paymentInstant != null) {
                    java.time.Instant pInst = java.time.Instant.parse(p.paymentInstant);
                    java.time.Instant openInst = java.time.Instant.parse(session.openTime());
                    if (pInst.isBefore(openInst)) {
                        settlesBeforeOpen = true;
                        settlementTime = p.paymentInstant;
                    }
                } else if (p.paymentDate != null) {
                    java.time.LocalDate pDate = java.time.LocalDate.parse(p.paymentDate);
                    java.time.LocalDate sDateLocal = java.time.LocalDate.parse(sDate);
                    if (pDate.isBefore(sDateLocal)) {
                        settlesBeforeOpen = true;
                        settlementTime = p.paymentDate + "T23:59:59Z";
                    }
                }

                if (settlesBeforeOpen) {
                    cash = cash.add(p.amount);
                    receivables = receivables.subtract(p.amount);
                    hasReinvestmentPending = true;

                    eventSeq++;
                    String eventId = runId + "-" + seriesType.name().toLowerCase() + "-evt-" + String.format("%04d", eventSeq);
                    eventsList.add(new BacktestDtos.BacktestEventDto(
                            eventId,
                            runId,
                            seriesType.name(),
                            eventSeq,
                            BacktestDtos.EventType.PAYMENT.name(),
                            p.paymentDate != null ? p.paymentDate : sDate,
                            settlementTime != null ? settlementTime : session.openTime(),
                            "Distribution payment settled before open: " + p.amount.toPlainString() + " EUR (action " + p.actionId + ")",
                            "{\"actionId\":\"" + p.actionId + "\",\"amount\":\"" + p.amount.toPlainString() + "\"}",
                            p.amount.toPlainString(),
                            "0.00000000",
                            "0.00",
                            p.amount.negate().toPlainString(),
                            settlementTime != null ? settlementTime : session.openTime()
                    ));
                    pIter.remove();
                }
            }

            // --- 4. Opening Executions (09:00) ---
            boolean isInitialBuy = (i == 0);
            boolean isReinvest = (!isInitialBuy && hasReinvestmentPending);
            if (isReinvest) {
                hasReinvestmentPending = false;
            }

            if (isInitialBuy || isReinvest) {
                BacktestDtos.OrderType orderType = isInitialBuy
                        ? BacktestDtos.OrderType.INITIAL_BUY
                        : BacktestDtos.OrderType.REINVEST;

                BigDecimal rawOpen = bar.open();
                BigDecimal fillPrice = AccountingCore.normalizePrice(
                        rawOpen.multiply(costMultiplier, AccountingCore.MATH_CONTEXT).setScale(AccountingCore.PRICE_SCALE, RoundingMode.HALF_EVEN)
                );

                BigDecimal spreadCostPerUnit = rawOpen.multiply(halfSpreadFraction, AccountingCore.MATH_CONTEXT);
                BigDecimal slippageCostPerUnit = rawOpen.multiply(slippageFraction, AccountingCore.MATH_CONTEXT);

                // Sizing: find max whole integer units N >= 1 affordable with spendable cash
                // roundCash(N * fillPrice) + commission <= cash
                long affordableUnits = 0;
                if (cash.compareTo(commission) > 0) {
                    BigDecimal availableForShares = cash.subtract(commission);
                    BigDecimal rawTarget = availableForShares.divide(fillPrice, 8, RoundingMode.FLOOR);
                    long targetFloor = rawTarget.longValue();
                    for (long n = targetFloor; n >= 1; n--) {
                        BigDecimal gross = AccountingCore.roundCash(BigDecimal.valueOf(n).multiply(fillPrice, AccountingCore.MATH_CONTEXT));
                        if (gross.add(commission).compareTo(cash) <= 0) {
                            affordableUnits = n;
                            break;
                        }
                    }
                }

                orderSeq++;
                String orderId = runId + "-" + seriesType.name().toLowerCase() + "-ord-" + String.format("%04d", orderSeq);
                String marketTime = session.openTime() != null ? session.openTime() : sDate + "T09:00:00Z";

                if (affordableUnits >= 1) {
                    BigDecimal executedQty = BigDecimal.valueOf(affordableUnits).setScale(AccountingCore.QUANTITY_SCALE, AccountingCore.CASH_ROUNDING);
                    BigDecimal grossCost = AccountingCore.roundCash(executedQty.multiply(fillPrice, AccountingCore.MATH_CONTEXT));
                    BigDecimal totalCost = grossCost.add(commission);

                    BigDecimal spreadCost = AccountingCore.roundCash(executedQty.multiply(spreadCostPerUnit, AccountingCore.MATH_CONTEXT));
                    BigDecimal slippageCost = AccountingCore.roundCash(executedQty.multiply(slippageCostPerUnit, AccountingCore.MATH_CONTEXT));

                    cash = cash.subtract(totalCost);
                    units = units.add(executedQty);
                    totalBasis = totalBasis.add(totalCost);
                    totalCommissions = totalCommissions.add(commission);
                    totalSpreadSlippage = totalSpreadSlippage.add(spreadCost).add(slippageCost);
                    fillCount++;

                    ordersList.add(new BacktestDtos.BacktestOrderDto(
                            orderId,
                            runId,
                            seriesType.name(),
                            orderType.name(),
                            listingId,
                            sDate,
                            executedQty.toPlainString(),
                            executedQty.toPlainString(),
                            rawOpen.toPlainString(),
                            fillPrice.toPlainString(),
                            commission.toPlainString(),
                            spreadCost.toPlainString(),
                            slippageCost.toPlainString(),
                            totalCost.negate().toPlainString(),
                            BacktestDtos.OrderStatus.FILLED.name(),
                            null,
                            marketTime
                    ));

                    eventSeq++;
                    String eventId = runId + "-" + seriesType.name().toLowerCase() + "-evt-" + String.format("%04d", eventSeq);
                    eventsList.add(new BacktestDtos.BacktestEventDto(
                            eventId,
                            runId,
                            seriesType.name(),
                            eventSeq,
                            BacktestDtos.EventType.EXECUTION.name(),
                            sDate,
                            marketTime,
                            orderType + " filled: " + executedQty.toPlainString() + " units @ " + fillPrice.toPlainString() +
                                    " EUR (commission " + commission.toPlainString() + " EUR)",
                            "{\"orderId\":\"" + orderId + "\",\"units\":\"" + executedQty.toPlainString() + "\",\"fillPrice\":\"" + fillPrice.toPlainString() + "\"}",
                            totalCost.negate().toPlainString(),
                            executedQty.toPlainString(),
                            totalCost.toPlainString(),
                            "0.00",
                            marketTime
                    ));
                } else {
                    // Skipped order - cash preserved, fee = 0
                    ordersList.add(new BacktestDtos.BacktestOrderDto(
                            orderId,
                            runId,
                            seriesType.name(),
                            orderType.name(),
                            listingId,
                            sDate,
                            "1.00000000",
                            "0.00000000",
                            rawOpen.toPlainString(),
                            fillPrice.toPlainString(),
                            "0.00",
                            "0.00",
                            "0.00",
                            "0.00",
                            BacktestDtos.OrderStatus.SKIPPED.name(),
                            "INSUFFICIENT_CASH",
                            marketTime
                    ));
                }
            }

            // --- 5. Intraday Payments (at open or during trading up to close) ---
            pIter = pendingDistributions.iterator();
            while (pIter.hasNext()) {
                PendingDistribution p = pIter.next();
                boolean settlesIntraday = false;
                String settlementTime = null;

                if (p.paymentInstant != null) {
                    java.time.Instant pInst = java.time.Instant.parse(p.paymentInstant);
                    java.time.Instant openInst = java.time.Instant.parse(session.openTime());
                    java.time.Instant closeInst = java.time.Instant.parse(session.closeTime());
                    if (!pInst.isBefore(openInst) && !pInst.isAfter(closeInst)) {
                        settlesIntraday = true;
                        settlementTime = p.paymentInstant;
                    }
                }

                if (settlesIntraday) {
                    cash = cash.add(p.amount);
                    receivables = receivables.subtract(p.amount);
                    hasReinvestmentPending = true; // Armed for next session open

                    eventSeq++;
                    String eventId = runId + "-" + seriesType.name().toLowerCase() + "-evt-" + String.format("%04d", eventSeq);
                    eventsList.add(new BacktestDtos.BacktestEventDto(
                            eventId,
                            runId,
                            seriesType.name(),
                            eventSeq,
                            BacktestDtos.EventType.PAYMENT.name(),
                            sDate,
                            settlementTime,
                            "Intraday distribution payment settled: " + p.amount.toPlainString() + " EUR (action " + p.actionId + ")",
                            "{\"actionId\":\"" + p.actionId + "\",\"amount\":\"" + p.amount.toPlainString() + "\"}",
                            p.amount.toPlainString(),
                            "0.00000000",
                            "0.00",
                            p.amount.negate().toPlainString(),
                            settlementTime
                    ));
                    pIter.remove();
                }
            }

            // --- 6. Date-only / Post-close distribution payments on this session date ---
            pIter = pendingDistributions.iterator();
            while (pIter.hasNext()) {
                PendingDistribution p = pIter.next();
                if (p.paymentDate != null && p.paymentDate.equals(sDate) && p.paymentInstant == null) {
                    cash = cash.add(p.amount);
                    receivables = receivables.subtract(p.amount);
                    hasReinvestmentPending = true; // Armed for next session open

                    eventSeq++;
                    String eventId = runId + "-" + seriesType.name().toLowerCase() + "-evt-" + String.format("%04d", eventSeq);
                    eventsList.add(new BacktestDtos.BacktestEventDto(
                            eventId,
                            runId,
                            seriesType.name(),
                            eventSeq,
                            BacktestDtos.EventType.PAYMENT.name(),
                            sDate,
                            session.closeTime() != null ? session.closeTime() : sDate + "T17:30:00Z",
                            "Date-only distribution payment settled post-close: " + p.amount.toPlainString() + " EUR (action " + p.actionId + ")",
                            "{\"actionId\":\"" + p.actionId + "\",\"amount\":\"" + p.amount.toPlainString() + "\"}",
                            p.amount.toPlainString(),
                            "0.00000000",
                            "0.00",
                            p.amount.negate().toPlainString(),
                            session.closeTime() != null ? session.closeTime() : sDate + "T17:30:00Z"
                    ));
                    pIter.remove();
                }
            }

            // --- 7. Closing Valuation & Daily Equity Snapshot ---
            BigDecimal rawClose = bar.close();
            BigDecimal holdingsValue = AccountingCore.roundCash(
                    units.multiply(rawClose, AccountingCore.MATH_CONTEXT)
            );
            BigDecimal totalEquity = cash.add(holdingsValue).add(receivables);

            if (totalEquity.compareTo(peakEquity) > 0) {
                peakEquity = totalEquity;
            }

            // Drawdown: (totalEquity - peakEquity) / peakEquity
            BigDecimal ddVal = peakEquity.compareTo(BigDecimal.ZERO) > 0
                    ? totalEquity.subtract(peakEquity).divide(peakEquity, 6, RoundingMode.HALF_EVEN)
                    : BigDecimal.ZERO;
            double drawdown = ddVal.doubleValue();

            // Daily Return: (totalEquity - prevSessionEquity) / prevSessionEquity
            Double dailyReturn = null;
            if (prevSessionEquity != null && prevSessionEquity.compareTo(BigDecimal.ZERO) > 0) {
                dailyReturn = totalEquity.subtract(prevSessionEquity)
                        .divide(prevSessionEquity, 6, RoundingMode.HALF_EVEN)
                        .doubleValue();
            }

            eventSeq++;
            String closingEventId = runId + "-" + seriesType.name().toLowerCase() + "-evt-" + String.format("%04d", eventSeq);
            String closeTime = session.closeTime() != null ? session.closeTime() : sDate + "T17:30:00Z";
            eventsList.add(new BacktestDtos.BacktestEventDto(
                    closingEventId,
                    runId,
                    seriesType.name(),
                    eventSeq,
                    BacktestDtos.EventType.CLOSING_MARK.name(),
                    sDate,
                    closeTime,
                    "Closing mark: Equity=" + totalEquity.toPlainString() + " EUR (Cash=" + cash.toPlainString() +
                            ", Holdings=" + holdingsValue.toPlainString() + ", Receivables=" + receivables.toPlainString() + ")",
                    "{\"cash\":\"" + cash.toPlainString() + "\",\"holdingsValue\":\"" + holdingsValue.toPlainString() + "\",\"receivables\":\"" + receivables.toPlainString() + "\"}",
                    "0.00",
                    "0.00000000",
                    "0.00",
                    "0.00",
                    closeTime
            ));

            dailyEquityList.add(new BacktestDtos.DailyEquityPoint(
                    sDate,
                    seriesType.name(),
                    cash.toPlainString(),
                    holdingsValue.toPlainString(),
                    receivables.toPlainString(),
                    totalEquity.toPlainString(),
                    dailyReturn,
                    drawdown,
                    peakEquity.toPlainString(),
                    units.setScale(AccountingCore.QUANTITY_SCALE, AccountingCore.CASH_ROUNDING).toPlainString(),
                    totalBasis.toPlainString(),
                    rawClose.toPlainString()
            ));

            prevSessionEquity = totalEquity;
        }

        // Final Holdings
        BigDecimal lastClose = bars.get(sessions.get(sessions.size() - 1).sessionDate()).close();
        BigDecimal finalHoldingsValue = AccountingCore.roundCash(units.multiply(lastClose, AccountingCore.MATH_CONTEXT));
        BigDecimal unrealizedGain = finalHoldingsValue.subtract(totalBasis);
        BigDecimal avgCost = units.compareTo(BigDecimal.ZERO) > 0
                ? totalBasis.divide(units, 8, RoundingMode.HALF_EVEN)
                : BigDecimal.ZERO.setScale(AccountingCore.CASH_SCALE, AccountingCore.CASH_ROUNDING);

        String lastSessionClose = sessions.get(sessions.size() - 1).closeTime();
        BacktestDtos.BacktestHoldingsDto holdingsDto = new BacktestDtos.BacktestHoldingsDto(
                seriesType.name(),
                listingId,
                units.setScale(AccountingCore.QUANTITY_SCALE, AccountingCore.CASH_ROUNDING).toPlainString(),
                totalBasis.toPlainString(),
                avgCost.toPlainString(),
                lastClose.toPlainString(),
                finalHoldingsValue.toPlainString(),
                unrealizedGain.toPlainString(),
                lastSessionClose != null ? lastSessionClose : sessions.get(sessions.size() - 1).sessionDate() + "T17:30:00Z"
        );

        BigDecimal finalEquity = prevSessionEquity;

        return new SimulationResult(
                seriesType,
                listingId,
                dailyEquityList,
                ordersList,
                eventsList,
                holdingsDto,
                initialCash,
                finalEquity,
                totalCommissions,
                totalSpreadSlippage,
                totalDistributions,
                fillCount,
                new ArrayList<>(pendingDistributions)
        );
    }
}
