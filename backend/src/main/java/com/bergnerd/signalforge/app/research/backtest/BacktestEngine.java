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

    private final StrategyEvaluator strategyEvaluator;
    private final TotalReturnSignalIndexCalculator indexCalculator;

    public BacktestEngine() {
        this(new StrategyEvaluator(), new TotalReturnSignalIndexCalculator());
    }

    public BacktestEngine(StrategyEvaluator strategyEvaluator, TotalReturnSignalIndexCalculator indexCalculator) {
        this.strategyEvaluator = strategyEvaluator != null ? strategyEvaluator : new StrategyEvaluator();
        this.indexCalculator = indexCalculator != null ? indexCalculator : new TotalReturnSignalIndexCalculator();
    }

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
            List<PendingDistribution> unpaidReceivables,
            List<BacktestDtos.BacktestHoldingsDto> allHoldings,
            List<BacktestDtos.BacktestSignalDto> signals
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
                int fillCount,
                List<PendingDistribution> unpaidReceivables
        ) {
            this(seriesType, listingId, dailyEquity, orders, events, finalHoldings,
                 initialCash, finalEquity, totalCommissions, totalSpreadSlippageEstimate,
                 totalDistributionsRecognized, fillCount, unpaidReceivables,
                 finalHoldings != null ? List.of(finalHoldings) : List.of(), List.of());
        }

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
                 totalDistributionsRecognized, fillCount, List.of(),
                 finalHoldings != null ? List.of(finalHoldings) : List.of(), List.of());
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

        // The funded observation occurs before the first open, on the first execution date.
        if (!sessions.isEmpty() && evalSession != null) {
            BacktestDataReader.SessionRecord firstSession = sessions.get(0);
            String evalClose = "0.00";
            BacktestDataReader.BarRecord evalBar = bars.get(evalSession.sessionDate());
            if (evalBar != null) {
                evalClose = evalBar.close().toPlainString();
            }

            dailyEquityList.add(new BacktestDtos.DailyEquityPoint(
                    firstSession.sessionDate(),
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
                    evalClose,
                    "INITIAL_FUNDED",
                    (firstSession.openTime() != null
                            ? java.time.Instant.parse(firstSession.openTime())
                            : java.time.Instant.parse(firstSession.sessionDate() + "T09:00:00Z"))
                            .minusSeconds(900).toString()
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
                    rawClose.toPlainString(),
                    "SESSION_CLOSE",
                    closeTime
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

    public SimulationResult runStrategy(
            String runId,
            BacktestDtos.SeriesType seriesType,
            String strategyId,
            String strategyVersion,
            String universeId,
            List<String> targetListingIds,
            String candidateListingId,
            BigDecimal initialCash,
            BigDecimal commissionPerFill,
            BigDecimal spreadBps,
            BigDecimal slippageBps,
            BacktestDataReader.SessionRecord evalSession,
            List<BacktestDataReader.SessionRecord> sessions,
            List<BacktestDataReader.SessionRecord> allSessions,
            Map<String, Map<String, BacktestDataReader.BarRecord>> barsByListingAndDate,
            Map<String, List<BacktestDataReader.ActionRecord>> actionsByListing,
            Map<String, Object> parameters
    ) {
        if (seriesType == BacktestDtos.SeriesType.BENCHMARK || "ETF_BUY_HOLD_V1".equalsIgnoreCase(strategyId)) {
            String primaryListing = candidateListingId != null ? candidateListingId : (targetListingIds != null && !targetListingIds.isEmpty() ? targetListingIds.get(0) : "BENCHMARK");
            return runS1(runId, seriesType, primaryListing, initialCash, commissionPerFill, spreadBps, slippageBps,
                    evalSession, sessions, barsByListingAndDate.get(primaryListing), actionsByListing.getOrDefault(primaryListing, List.of()));
        }

        BigDecimal cash = AccountingCore.normalizeStartingCash(initialCash);
        BigDecimal commission = AccountingCore.normalizeCash(commissionPerFill, "commission");
        BigDecimal receivables = BigDecimal.ZERO.setScale(AccountingCore.CASH_SCALE, AccountingCore.CASH_ROUNDING);
        BigDecimal peakEquity = cash;

        BigDecimal totalCommissions = BigDecimal.ZERO.setScale(AccountingCore.CASH_SCALE, AccountingCore.CASH_ROUNDING);
        BigDecimal totalSpreadSlippage = BigDecimal.ZERO.setScale(AccountingCore.CASH_SCALE, AccountingCore.CASH_ROUNDING);
        BigDecimal totalDistributions = BigDecimal.ZERO.setScale(AccountingCore.CASH_SCALE, AccountingCore.CASH_ROUNDING);
        int fillCount = 0;
        int orderSeq = 0;
        int eventSeq = 0;
        int signalSeq = 0;

        List<BacktestDtos.DailyEquityPoint> dailyEquityList = new ArrayList<>();
        List<BacktestDtos.BacktestOrderDto> ordersList = new ArrayList<>();
        List<BacktestDtos.BacktestEventDto> eventsList = new ArrayList<>();
        List<BacktestDtos.BacktestSignalDto> signalsList = new ArrayList<>();
        List<PendingDistribution> pendingDistributions = new ArrayList<>();

        Map<String, BigDecimal> currentUnits = new HashMap<>();
        Map<String, BigDecimal> currentBasis = new HashMap<>();
        for (String lid : targetListingIds) {
            currentUnits.put(lid, BigDecimal.ZERO.setScale(AccountingCore.QUANTITY_SCALE, AccountingCore.CASH_ROUNDING));
            currentBasis.put(lid, BigDecimal.ZERO.setScale(AccountingCore.CASH_SCALE, AccountingCore.CASH_ROUNDING));
        }

        BigDecimal halfSpreadFraction = spreadBps.divide(new BigDecimal("20000"), 8, RoundingMode.HALF_EVEN);
        BigDecimal slippageFraction = slippageBps.divide(new BigDecimal("10000"), 8, RoundingMode.HALF_EVEN);
        BigDecimal buyPriceMultiplier = BigDecimal.ONE.add(halfSpreadFraction).add(slippageFraction);
        BigDecimal sellPriceMultiplier = BigDecimal.ONE.subtract(halfSpreadFraction).subtract(slippageFraction);

        List<BacktestDataReader.SessionRecord> allTradingSessions = allSessions.stream()
                .filter(BacktestDataReader.SessionRecord::isTrading)
                .toList();

        Map<String, TotalReturnSignalIndexCalculator.ListingSignalIndexSeries> indexSeriesMap = new HashMap<>();
        for (String lid : targetListingIds) {
            indexSeriesMap.put(lid, indexCalculator.calculateSeries(
                    lid,
                    allTradingSessions,
                    barsByListingAndDate.get(lid),
                    actionsByListing.getOrDefault(lid, List.of())
            ));
        }

        List<String> monthEndDates = TotalReturnSignalIndexCalculator.extractMonthEndSessions(allSessions);

        boolean isS2 = "ETF_MOMENTUM_12_1_V1".equalsIgnoreCase(strategyId);
        boolean isS3 = "ETF_TREND_10M_V1".equalsIgnoreCase(strategyId);

        int k = 3;
        if (isS2 && parameters != null && parameters.containsKey("k")) {
            k = ((Number) parameters.get("k")).intValue();
            if (k < 1) k = 1;
            if (k > targetListingIds.size()) k = targetListingIds.size();
        }

        BigDecimal prevSessionEquity = cash;
        BigDecimal prevS3TargetWeight = BigDecimal.ZERO;
        boolean s3HasSettledReinvestment = false;

        StrategyEvaluator.EvaluatedSignal pendingSignal = null;

        if (!sessions.isEmpty() && evalSession != null) {
            BacktestDataReader.SessionRecord firstSession = sessions.get(0);
            String fundingTime = (firstSession.openTime() != null
                    ? java.time.Instant.parse(firstSession.openTime())
                    : java.time.Instant.parse(firstSession.sessionDate() + "T09:00:00Z"))
                    .minusSeconds(900).toString();

            dailyEquityList.add(new BacktestDtos.DailyEquityPoint(
                    firstSession.sessionDate(),
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
                    "0.00",
                    "INITIAL_FUNDED",
                    fundingTime
            ));

            eventSeq++;
            String eventId = runId + "-" + seriesType.name().toLowerCase() + "-evt-" + String.format("%04d", eventSeq);
            eventsList.add(new BacktestDtos.BacktestEventDto(
                    eventId,
                    runId,
                    seriesType.name(),
                    eventSeq,
                    BacktestDtos.EventType.FUNDING.name(),
                    firstSession.sessionDate(),
                    fundingTime,
                    "Initial funding of " + cash.toPlainString() + " EUR",
                    "{\"initialCash\":\"" + cash.toPlainString() + "\",\"currency\":\"EUR\"}",
                    cash.toPlainString(),
                    "0.00000000",
                    "0.00",
                    "0.00",
                    fundingTime
            ));

            java.time.YearMonth evalYm = java.time.YearMonth.parse(evalSession.sessionDate().substring(0, 7));
            if (isS2) {
                pendingSignal = strategyEvaluator.evaluateS2(
                        runId, ++signalSeq, universeId, k, evalYm, evalSession, firstSession, targetListingIds, indexSeriesMap
                );
            } else if (isS3) {
                pendingSignal = strategyEvaluator.evaluateS3(
                        runId, ++signalSeq, candidateListingId, evalYm, evalSession, firstSession, indexSeriesMap.get(candidateListingId)
                );
            }
        }

        for (int i = 0; i < sessions.size(); i++) {
            BacktestDataReader.SessionRecord session = sessions.get(i);
            String sDate = session.sessionDate();

            java.time.Instant sessionOpenInstant = session.openTime() != null
                    ? java.time.Instant.parse(session.openTime())
                    : java.time.LocalDate.parse(sDate).atTime(9, 0, 0).atZone(java.time.ZoneOffset.UTC).toInstant();

            if (i > 0) {
                BacktestDataReader.SessionRecord prevSession = sessions.get(i - 1);
                if (monthEndDates.contains(prevSession.sessionDate())) {
                    java.time.YearMonth ym = java.time.YearMonth.parse(prevSession.sessionDate().substring(0, 7));
                    if (isS2) {
                        pendingSignal = strategyEvaluator.evaluateS2(
                                runId, ++signalSeq, universeId, k, ym, prevSession, session, targetListingIds, indexSeriesMap
                        );
                    } else if (isS3) {
                        pendingSignal = strategyEvaluator.evaluateS3(
                                runId, ++signalSeq, candidateListingId, ym, prevSession, session, indexSeriesMap.get(candidateListingId)
                        );
                    }
                }
            }

            // 1. Corporate Actions on held positions (splits then entitlements)
            String splitTime = sessionOpenInstant.minusSeconds(600).toString();
            for (String lid : targetListingIds) {
                BigDecimal units = currentUnits.get(lid);
                if (units.compareTo(BigDecimal.ZERO) > 0) {
                    List<BacktestDataReader.ActionRecord> acts = actionsByListing.getOrDefault(lid, List.of());
                    for (BacktestDataReader.ActionRecord action : acts) {
                        if (sDate.equals(action.effectiveDate()) && action.isSplit()) {
                            BigDecimal ratio = action.splitRatio();
                            BigDecimal newUnits = AccountingCore.normalizeQuantity(
                                    units.multiply(ratio, AccountingCore.MATH_CONTEXT)
                            );
                            BigDecimal unitsDelta = newUnits.subtract(units);
                            currentUnits.put(lid, newUnits);
                            units = newUnits;

                            eventSeq++;
                            String eventId = runId + "-" + seriesType.name().toLowerCase() + "-evt-" + String.format("%04d", eventSeq);
                            eventsList.add(new BacktestDtos.BacktestEventDto(
                                    eventId, runId, seriesType.name(), eventSeq,
                                    BacktestDtos.EventType.SPLIT.name(), sDate, splitTime,
                                    "Stock split " + action.splitNumerator() + ":" + action.splitDenominator() + " for " + lid +
                                            ", units adjusted from " + units.subtract(unitsDelta).toPlainString() +
                                            " to " + units.toPlainString(),
                                    "{\"actionId\":\"" + action.actionId() + "\",\"listingId\":\"" + lid + "\"}",
                                    "0.00", unitsDelta.toPlainString(), "0.00", "0.00", splitTime
                            ));
                        }
                    }
                }
            }

            String entitlementTime = sessionOpenInstant.minusSeconds(300).toString();
            for (String lid : targetListingIds) {
                BigDecimal units = currentUnits.get(lid);
                if (units.compareTo(BigDecimal.ZERO) > 0) {
                    List<BacktestDataReader.ActionRecord> acts = actionsByListing.getOrDefault(lid, List.of());
                    for (BacktestDataReader.ActionRecord action : acts) {
                        if (sDate.equals(action.effectiveDate()) && action.isDistribution()) {
                            if (action.distributionAmount() != null) {
                                BigDecimal entitlement = AccountingCore.roundCash(
                                        units.multiply(action.distributionAmount(), AccountingCore.MATH_CONTEXT)
                                );
                                receivables = receivables.add(entitlement);
                                totalDistributions = totalDistributions.add(entitlement);

                                pendingDistributions.add(new PendingDistribution(
                                        action.actionId(), entitlement, action.paymentDate(), action.paymentInstant(),
                                        sDate, entitlementTime
                                ));

                                eventSeq++;
                                String eventId = runId + "-" + seriesType.name().toLowerCase() + "-evt-" + String.format("%04d", eventSeq);
                                eventsList.add(new BacktestDtos.BacktestEventDto(
                                        eventId, runId, seriesType.name(), eventSeq,
                                        BacktestDtos.EventType.ENTITLEMENT.name(), sDate, entitlementTime,
                                        "Cash distribution entitlement of " + entitlement.toPlainString() + " EUR on " + lid,
                                        "{\"actionId\":\"" + action.actionId() + "\",\"listingId\":\"" + lid + "\",\"amount\":\"" + entitlement.toPlainString() + "\"}",
                                        "0.00", "0.00000000", "0.00", entitlement.toPlainString(), entitlementTime
                                ));
                            }
                        }
                    }
                }
            }

            // 2. Settle Payments strictly before session open
            Iterator<PendingDistribution> pIter = pendingDistributions.iterator();
            while (pIter.hasNext()) {
                PendingDistribution p = pIter.next();
                boolean settlesBeforeOpen = false;
                String settlementTime = null;

                if (p.paymentInstant != null) {
                    java.time.Instant pInst = java.time.Instant.parse(p.paymentInstant);
                    if (pInst.isBefore(sessionOpenInstant)) {
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
                    if (isS3 && prevS3TargetWeight.compareTo(BigDecimal.ONE) == 0) {
                        s3HasSettledReinvestment = true;
                    }

                    eventSeq++;
                    String eventId = runId + "-" + seriesType.name().toLowerCase() + "-evt-" + String.format("%04d", eventSeq);
                    eventsList.add(new BacktestDtos.BacktestEventDto(
                            eventId, runId, seriesType.name(), eventSeq,
                            BacktestDtos.EventType.PAYMENT.name(),
                            p.paymentDate != null ? p.paymentDate : sDate,
                            settlementTime != null ? settlementTime : session.openTime(),
                            "Distribution payment settled before open: " + p.amount.toPlainString() + " EUR (action " + p.actionId + ")",
                            "{\"actionId\":\"" + p.actionId + "\",\"amount\":\"" + p.amount.toPlainString() + "\"}",
                            p.amount.toPlainString(), "0.00000000", "0.00", p.amount.negate().toPlainString(),
                            settlementTime != null ? settlementTime : session.openTime()
                    ));
                    pIter.remove();
                }
            }

            // 3. Executions at Session Open (09:00)
            if (pendingSignal != null && sDate.equals(pendingSignal.scheduledExecutionDate())) {
                StrategyEvaluator.EvaluatedSignal sig = pendingSignal;
                pendingSignal = null;

                BigDecimal holdingsValAtOpen = BigDecimal.ZERO;
                Map<String, BigDecimal> opens = new HashMap<>();
                for (String lid : targetListingIds) {
                    BacktestDataReader.BarRecord bar = barsByListingAndDate.get(lid).get(sDate);
                    BigDecimal rawOpen = bar != null ? bar.open() : BigDecimal.ZERO;
                    opens.put(lid, rawOpen);
                    holdingsValAtOpen = holdingsValAtOpen.add(
                            currentUnits.get(lid).multiply(rawOpen, AccountingCore.MATH_CONTEXT)
                    );
                }
                BigDecimal preTradeEquity = cash.add(receivables).add(holdingsValAtOpen);

                Map<String, BigDecimal> targetUnitsMap = new HashMap<>();
                boolean skipDueToS3ChurnSuppression = false;

                if (isS3) {
                    BigDecimal s3Weight = sig.targetWeights().getOrDefault(candidateListingId, BigDecimal.ZERO);
                    if (prevS3TargetWeight.compareTo(BigDecimal.ONE) == 0 && s3Weight.compareTo(BigDecimal.ONE) == 0) {
                        skipDueToS3ChurnSuppression = true;
                        if (s3HasSettledReinvestment) {
                            s3HasSettledReinvestment = false;
                            BigDecimal rawOpen = opens.get(candidateListingId);
                            BigDecimal fillPrice = rawOpen.multiply(buyPriceMultiplier, AccountingCore.MATH_CONTEXT);
                            BigDecimal availableForReinvest = cash.subtract(commission);
                            if (availableForReinvest.compareTo(BigDecimal.ZERO) > 0) {
                                int addUnits = availableForReinvest.divide(fillPrice, 0, RoundingMode.FLOOR).intValue();
                                if (addUnits > 0) {
                                    BigDecimal tradeGross = AccountingCore.roundCash(BigDecimal.valueOf(addUnits).multiply(fillPrice, AccountingCore.MATH_CONTEXT));
                                    BigDecimal totalTradeCost = tradeGross.add(commission);
                                    if (totalTradeCost.compareTo(cash) <= 0) {
                                        cash = cash.subtract(totalTradeCost);
                                        totalCommissions = totalCommissions.add(commission);
                                        BigDecimal spreadSlip = AccountingCore.roundCash(BigDecimal.valueOf(addUnits).multiply(rawOpen, AccountingCore.MATH_CONTEXT).multiply(halfSpreadFraction.add(slippageFraction), AccountingCore.MATH_CONTEXT));
                                        totalSpreadSlippage = totalSpreadSlippage.add(spreadSlip);
                                        currentUnits.put(candidateListingId, currentUnits.get(candidateListingId).add(BigDecimal.valueOf(addUnits)));
                                        currentBasis.put(candidateListingId, currentBasis.get(candidateListingId).add(totalTradeCost));
                                        fillCount++;

                                        orderSeq++;
                                        ordersList.add(new BacktestDtos.BacktestOrderDto(
                                                runId + "-ord-" + String.format("%04d", orderSeq),
                                                runId, seriesType.name(), "REINVEST", candidateListingId, sDate,
                                                String.valueOf(addUnits), String.valueOf(addUnits),
                                                rawOpen.toPlainString(), fillPrice.setScale(AccountingCore.PRICE_SCALE, AccountingCore.CASH_ROUNDING).toPlainString(),
                                                commission.toPlainString(), "0.00", spreadSlip.toPlainString(),
                                                totalTradeCost.negate().toPlainString(), "FILLED", null, session.openTime()
                                        ));

                                        eventSeq++;
                                        eventsList.add(new BacktestDtos.BacktestEventDto(
                                                runId + "-" + seriesType.name().toLowerCase() + "-evt-" + String.format("%04d", eventSeq),
                                                runId, seriesType.name(), eventSeq,
                                                BacktestDtos.EventType.EXECUTION.name(), sDate, session.openTime(),
                                                "Reinvested " + addUnits + " units of " + candidateListingId,
                                                "{\"listingId\":\"" + candidateListingId + "\",\"units\":" + addUnits + "}",
                                                totalTradeCost.negate().toPlainString(), String.valueOf(addUnits), totalTradeCost.toPlainString(), "0.00", session.openTime()
                                        ));
                                    }
                                }
                            }
                        }
                    } else {
                        prevS3TargetWeight = s3Weight;
                        s3HasSettledReinvestment = false;
                        if (s3Weight.compareTo(BigDecimal.ONE) == 0) {
                            BigDecimal rawOpen = opens.get(candidateListingId);
                            BigDecimal fillPrice = rawOpen.multiply(buyPriceMultiplier, AccountingCore.MATH_CONTEXT);
                            BigDecimal spendable = cash.subtract(commission);
                            int tu = spendable.compareTo(BigDecimal.ZERO) > 0
                                    ? spendable.divide(fillPrice, 0, RoundingMode.FLOOR).intValue() : 0;
                            targetUnitsMap.put(candidateListingId, BigDecimal.valueOf(tu));
                        } else {
                            targetUnitsMap.put(candidateListingId, BigDecimal.ZERO);
                        }
                    }
                } else if (isS2) {
                    for (String lid : targetListingIds) {
                        BigDecimal w = sig.targetWeights().getOrDefault(lid, BigDecimal.ZERO);
                        if (w.compareTo(BigDecimal.ZERO) > 0) {
                            BigDecimal rawOpen = opens.get(lid);
                            BigDecimal targetDollar = preTradeEquity.multiply(w, AccountingCore.MATH_CONTEXT);
                            BigDecimal targetUnits = targetDollar.divide(rawOpen, 0, RoundingMode.FLOOR);
                            targetUnitsMap.put(lid, targetUnits);
                        } else {
                            targetUnitsMap.put(lid, BigDecimal.ZERO);
                        }
                    }
                }

                if (!skipDueToS3ChurnSuppression) {
                    // Sells first
                    for (String lid : targetListingIds) {
                        BigDecimal curU = currentUnits.get(lid);
                        BigDecimal tgtU = targetUnitsMap.getOrDefault(lid, BigDecimal.ZERO);
                        if (curU.compareTo(tgtU) > 0) {
                            BigDecimal sellQty = curU.subtract(tgtU);
                            BigDecimal rawOpen = opens.get(lid);
                            BigDecimal fillPrice = rawOpen.multiply(sellPriceMultiplier, AccountingCore.MATH_CONTEXT);
                            BigDecimal grossProceeds = AccountingCore.roundCash(sellQty.multiply(fillPrice, AccountingCore.MATH_CONTEXT));
                            BigDecimal netProceeds = grossProceeds.subtract(commission);

                            if (cash.add(netProceeds).compareTo(BigDecimal.ZERO) < 0) {
                                orderSeq++;
                                ordersList.add(new BacktestDtos.BacktestOrderDto(
                                        runId + "-ord-" + String.format("%04d", orderSeq),
                                        runId, seriesType.name(), "REBALANCE_SELL", lid, sDate,
                                        sellQty.toPlainString(), "0",
                                        rawOpen.toPlainString(), fillPrice.toPlainString(),
                                        "0.00", "0.00", "0.00", "0.00",
                                        "REJECTED", "INSUFFICIENT_CASH_FOR_COSTS", session.openTime()
                                ));
                            } else {
                                cash = cash.add(netProceeds);
                                totalCommissions = totalCommissions.add(commission);
                                BigDecimal spreadSlip = AccountingCore.roundCash(sellQty.multiply(rawOpen, AccountingCore.MATH_CONTEXT).multiply(halfSpreadFraction.add(slippageFraction), AccountingCore.MATH_CONTEXT));
                                totalSpreadSlippage = totalSpreadSlippage.add(spreadSlip);

                                BigDecimal basisRemoved;
                                if (tgtU.compareTo(BigDecimal.ZERO) == 0) {
                                    basisRemoved = currentBasis.get(lid);
                                    currentBasis.put(lid, BigDecimal.ZERO.setScale(AccountingCore.CASH_SCALE, AccountingCore.CASH_ROUNDING));
                                } else {
                                    basisRemoved = AccountingCore.roundCash(currentBasis.get(lid).multiply(sellQty.divide(curU, AccountingCore.MATH_CONTEXT), AccountingCore.MATH_CONTEXT));
                                    currentBasis.put(lid, currentBasis.get(lid).subtract(basisRemoved));
                                }
                                currentUnits.put(lid, tgtU);
                                fillCount++;

                                orderSeq++;
                                ordersList.add(new BacktestDtos.BacktestOrderDto(
                                        runId + "-ord-" + String.format("%04d", orderSeq),
                                        runId, seriesType.name(), "REBALANCE_SELL", lid, sDate,
                                        sellQty.toPlainString(), sellQty.toPlainString(),
                                        rawOpen.toPlainString(), fillPrice.setScale(AccountingCore.PRICE_SCALE, AccountingCore.CASH_ROUNDING).toPlainString(),
                                        commission.toPlainString(), "0.00", spreadSlip.toPlainString(),
                                        netProceeds.toPlainString(), "FILLED", null, session.openTime()
                                ));

                                eventSeq++;
                                eventsList.add(new BacktestDtos.BacktestEventDto(
                                        runId + "-" + seriesType.name().toLowerCase() + "-evt-" + String.format("%04d", eventSeq),
                                        runId, seriesType.name(), eventSeq,
                                        BacktestDtos.EventType.EXECUTION.name(), sDate, session.openTime(),
                                        "Sold " + sellQty.toPlainString() + " units of " + lid,
                                        "{\"listingId\":\"" + lid + "\",\"units\":" + sellQty.toPlainString() + "}",
                                        netProceeds.toPlainString(), sellQty.negate().toPlainString(), basisRemoved.negate().toPlainString(), "0.00", session.openTime()
                                ));
                            }
                        }
                    }

                    // Buys second with Proportional Affordability Allocator
                    List<String> buyListings = new ArrayList<>();
                    Map<String, BigDecimal> desiredUnits = new HashMap<>();
                    Map<String, BigDecimal> buyFillPrices = new HashMap<>();
                    BigDecimal totalBuyCashRequired = BigDecimal.ZERO;

                    for (String lid : targetListingIds) {
                        BigDecimal curU = currentUnits.get(lid);
                        BigDecimal tgtU = targetUnitsMap.getOrDefault(lid, BigDecimal.ZERO);
                        if (tgtU.compareTo(curU) > 0) {
                            BigDecimal qty = tgtU.subtract(curU);
                            BigDecimal rawOpen = opens.get(lid);
                            BigDecimal fillPrice = rawOpen.multiply(buyPriceMultiplier, AccountingCore.MATH_CONTEXT);
                            buyListings.add(lid);
                            desiredUnits.put(lid, qty);
                            buyFillPrices.put(lid, fillPrice);
                            BigDecimal estCost = qty.multiply(fillPrice, AccountingCore.MATH_CONTEXT).add(commission);
                            totalBuyCashRequired = totalBuyCashRequired.add(estCost);
                        }
                    }

                    if (!buyListings.isEmpty()) {
                        Map<String, Integer> executedQtyMap = new HashMap<>();
                        if (totalBuyCashRequired.compareTo(cash) <= 0) {
                            for (String lid : buyListings) {
                                executedQtyMap.put(lid, desiredUnits.get(lid).intValue());
                            }
                        } else {
                            BigDecimal alpha = cash.divide(totalBuyCashRequired, AccountingCore.MATH_CONTEXT);
                            for (String lid : buyListings) {
                                int q = desiredUnits.get(lid).multiply(alpha, AccountingCore.MATH_CONTEXT).intValue();
                                executedQtyMap.put(lid, q);
                            }

                            Collections.sort(buyListings);
                            while (true) {
                                BigDecimal batchCost = BigDecimal.ZERO;
                                for (String lid : buyListings) {
                                    int q = executedQtyMap.get(lid);
                                    if (q > 0) {
                                        BigDecimal c = BigDecimal.valueOf(q).multiply(buyFillPrices.get(lid), AccountingCore.MATH_CONTEXT).add(commission);
                                        batchCost = batchCost.add(c);
                                    }
                                }
                                if (batchCost.compareTo(cash) <= 0) {
                                    break;
                                }
                                boolean decremented = false;
                                for (String lid : buyListings) {
                                    if (executedQtyMap.get(lid) > 0) {
                                        executedQtyMap.put(lid, executedQtyMap.get(lid) - 1);
                                        decremented = true;
                                        break;
                                    }
                                }
                                if (!decremented) {
                                    break;
                                }
                            }
                        }

                        for (String lid : buyListings) {
                            int q = executedQtyMap.get(lid);
                            BigDecimal reqQ = desiredUnits.get(lid);
                            BigDecimal rawOpen = opens.get(lid);
                            BigDecimal fillPrice = buyFillPrices.get(lid);

                            if (q > 0) {
                                BigDecimal grossCost = AccountingCore.roundCash(BigDecimal.valueOf(q).multiply(fillPrice, AccountingCore.MATH_CONTEXT));
                                BigDecimal totalCost = grossCost.add(commission);
                                cash = cash.subtract(totalCost);
                                totalCommissions = totalCommissions.add(commission);
                                BigDecimal spreadSlip = AccountingCore.roundCash(BigDecimal.valueOf(q).multiply(rawOpen, AccountingCore.MATH_CONTEXT).multiply(halfSpreadFraction.add(slippageFraction), AccountingCore.MATH_CONTEXT));
                                totalSpreadSlippage = totalSpreadSlippage.add(spreadSlip);

                                currentUnits.put(lid, currentUnits.get(lid).add(BigDecimal.valueOf(q)));
                                currentBasis.put(lid, currentBasis.get(lid).add(totalCost));
                                fillCount++;

                                String orderType = (i == 0) ? "INITIAL_BUY" : "REBALANCE_BUY";
                                String orderStatus = (q == reqQ.intValue()) ? "FILLED" : "PARTIALLY_FILLED";

                                orderSeq++;
                                ordersList.add(new BacktestDtos.BacktestOrderDto(
                                        runId + "-ord-" + String.format("%04d", orderSeq),
                                        runId, seriesType.name(), orderType, lid, sDate,
                                        reqQ.toPlainString(), String.valueOf(q),
                                        rawOpen.toPlainString(), fillPrice.setScale(AccountingCore.PRICE_SCALE, AccountingCore.CASH_ROUNDING).toPlainString(),
                                        commission.toPlainString(), "0.00", spreadSlip.toPlainString(),
                                        totalCost.negate().toPlainString(), orderStatus, null, session.openTime()
                                ));

                                eventSeq++;
                                eventsList.add(new BacktestDtos.BacktestEventDto(
                                        runId + "-" + seriesType.name().toLowerCase() + "-evt-" + String.format("%04d", eventSeq),
                                        runId, seriesType.name(), eventSeq,
                                        BacktestDtos.EventType.EXECUTION.name(), sDate, session.openTime(),
                                        "Bought " + q + " units of " + lid,
                                        "{\"listingId\":\"" + lid + "\",\"units\":" + q + "}",
                                        totalCost.negate().toPlainString(), String.valueOf(q), totalCost.toPlainString(), "0.00", session.openTime()
                                ));
                            } else {
                                orderSeq++;
                                ordersList.add(new BacktestDtos.BacktestOrderDto(
                                        runId + "-ord-" + String.format("%04d", orderSeq),
                                        runId, seriesType.name(), "REBALANCE_BUY", lid, sDate,
                                        reqQ.toPlainString(), "0",
                                        rawOpen.toPlainString(), fillPrice.toPlainString(),
                                        "0.00", "0.00", "0.00", "0.00",
                                        "SKIPPED", "INSUFFICIENT_CASH", session.openTime()
                                ));
                            }
                        }
                    }
                }

                signalsList.add(toSignalDto(sig, "EXECUTED"));
            }

            // 4. Intraday Corporate Actions (settling after open)
            Iterator<PendingDistribution> intradayIter = pendingDistributions.iterator();
            while (intradayIter.hasNext()) {
                PendingDistribution p = intradayIter.next();
                boolean settlesToday = false;
                if (p.paymentDate != null && p.paymentDate.equals(sDate)) {
                    settlesToday = true;
                } else if (p.paymentInstant != null && p.paymentInstant.startsWith(sDate)) {
                    settlesToday = true;
                }

                if (settlesToday) {
                    cash = cash.add(p.amount);
                    receivables = receivables.subtract(p.amount);

                    eventSeq++;
                    String eventId = runId + "-" + seriesType.name().toLowerCase() + "-evt-" + String.format("%04d", eventSeq);
                    eventsList.add(new BacktestDtos.BacktestEventDto(
                            eventId, runId, seriesType.name(), eventSeq,
                            BacktestDtos.EventType.PAYMENT.name(), sDate, session.closeTime(),
                            "Distribution payment settled intraday: " + p.amount.toPlainString() + " EUR (action " + p.actionId + ")",
                            "{\"actionId\":\"" + p.actionId + "\",\"amount\":\"" + p.amount.toPlainString() + "\"}",
                            p.amount.toPlainString(), "0.00000000", "0.00", p.amount.negate().toPlainString(),
                            session.closeTime()
                    ));
                    intradayIter.remove();
                }
            }

            // 5. Closing Valuation
            BigDecimal holdingsValAtClose = BigDecimal.ZERO;
            for (String lid : targetListingIds) {
                BacktestDataReader.BarRecord bar = barsByListingAndDate.get(lid).get(sDate);
                BigDecimal rawClose = bar != null ? bar.close() : BigDecimal.ZERO;
                holdingsValAtClose = holdingsValAtClose.add(
                        currentUnits.get(lid).multiply(rawClose, AccountingCore.MATH_CONTEXT)
                );
            }

            BigDecimal totalEquity = cash.add(receivables).add(holdingsValAtClose);
            Double dailyReturn = null;
            if (prevSessionEquity.compareTo(BigDecimal.ZERO) > 0) {
                dailyReturn = totalEquity.subtract(prevSessionEquity)
                        .divide(prevSessionEquity, 8, RoundingMode.HALF_EVEN).doubleValue();
            }

            if (totalEquity.compareTo(peakEquity) > 0) {
                peakEquity = totalEquity;
            }
            Double drawdown = 0.0;
            if (peakEquity.compareTo(BigDecimal.ZERO) > 0) {
                drawdown = totalEquity.subtract(peakEquity)
                        .divide(peakEquity, 8, RoundingMode.HALF_EVEN).doubleValue();
            }

            BigDecimal totalHeldUnits = BigDecimal.ZERO;
            BigDecimal totalHeldBasis = BigDecimal.ZERO;
            for (String lid : targetListingIds) {
                totalHeldUnits = totalHeldUnits.add(currentUnits.get(lid));
                totalHeldBasis = totalHeldBasis.add(currentBasis.get(lid));
            }

            dailyEquityList.add(new BacktestDtos.DailyEquityPoint(
                    sDate,
                    seriesType.name(),
                    cash.toPlainString(),
                    holdingsValAtClose.setScale(AccountingCore.CASH_SCALE, AccountingCore.CASH_ROUNDING).toPlainString(),
                    receivables.toPlainString(),
                    totalEquity.setScale(AccountingCore.CASH_SCALE, AccountingCore.CASH_ROUNDING).toPlainString(),
                    dailyReturn,
                    drawdown,
                    peakEquity.setScale(AccountingCore.CASH_SCALE, AccountingCore.CASH_ROUNDING).toPlainString(),
                    totalHeldUnits.toPlainString(),
                    totalHeldBasis.toPlainString(),
                    "0.00",
                    "SESSION_CLOSE",
                    session.closeTime()
            ));

            prevSessionEquity = totalEquity;

            // Final session check
            if (i == sessions.size() - 1 && monthEndDates.contains(sDate)) {
                java.time.YearMonth ym = java.time.YearMonth.parse(sDate.substring(0, 7));
                StrategyEvaluator.EvaluatedSignal finalSig = null;
                if (isS2) {
                    finalSig = strategyEvaluator.evaluateS2(
                            runId, ++signalSeq, universeId, k, ym, session, null, targetListingIds, indexSeriesMap
                    );
                } else if (isS3) {
                    finalSig = strategyEvaluator.evaluateS3(
                            runId, ++signalSeq, candidateListingId, ym, session, null, indexSeriesMap.get(candidateListingId)
                    );
                }
                if (finalSig != null) {
                    signalsList.add(toSignalDto(finalSig, "UNEXECUTED"));
                }
            }
        }

        List<BacktestDtos.BacktestHoldingsDto> allHoldings = new ArrayList<>();
        String lastSessionClose = sessions.get(sessions.size() - 1).closeTime();
        for (String lid : targetListingIds) {
            BigDecimal u = currentUnits.get(lid);
            BigDecimal basis = currentBasis.get(lid);
            BacktestDataReader.BarRecord lastBar = barsByListingAndDate.get(lid).get(sessions.get(sessions.size() - 1).sessionDate());
            BigDecimal lastClose = lastBar != null ? lastBar.close() : BigDecimal.ZERO;
            BigDecimal mVal = AccountingCore.roundCash(u.multiply(lastClose, AccountingCore.MATH_CONTEXT));
            BigDecimal unGain = mVal.subtract(basis);
            BigDecimal avgCost = u.compareTo(BigDecimal.ZERO) > 0
                    ? basis.divide(u, AccountingCore.PRICE_SCALE, RoundingMode.HALF_EVEN) : BigDecimal.ZERO;

            allHoldings.add(new BacktestDtos.BacktestHoldingsDto(
                    seriesType.name(), lid, u.toPlainString(), basis.toPlainString(),
                    avgCost.toPlainString(), lastClose.toPlainString(), mVal.toPlainString(),
                    unGain.toPlainString(), lastSessionClose != null ? lastSessionClose : sessions.get(sessions.size() - 1).sessionDate() + "T17:30:00Z"
            ));
        }

        BacktestDtos.BacktestHoldingsDto primaryHolding = allHoldings.isEmpty() ? null : allHoldings.get(0);

        return new SimulationResult(
                seriesType,
                candidateListingId != null ? candidateListingId : (targetListingIds.isEmpty() ? "UNKNOWN" : targetListingIds.get(0)),
                dailyEquityList,
                ordersList,
                eventsList,
                primaryHolding,
                initialCash,
                prevSessionEquity,
                totalCommissions,
                totalSpreadSlippage,
                totalDistributions,
                fillCount,
                new ArrayList<>(pendingDistributions),
                allHoldings,
                signalsList
        );
    }

    private BacktestDtos.BacktestSignalDto toSignalDto(StrategyEvaluator.EvaluatedSignal sig, String statusOverride) {
        List<BacktestDtos.BacktestSignalItemDto> itemDtos = new ArrayList<>();
        if (sig.items() != null) {
            for (StrategyEvaluator.EvaluatedSignalItem item : sig.items()) {
                itemDtos.add(new BacktestDtos.BacktestSignalItemDto(
                        sig.id() + "-" + item.listingId(),
                        sig.id(),
                        item.listingId(),
                        item.score() != null ? item.score().toPlainString() : null,
                        item.indexValue() != null ? item.indexValue().toPlainString() : null,
                        item.smaValue() != null ? item.smaValue().toPlainString() : null,
                        item.rank(),
                        item.eligible(),
                        item.selected(),
                        item.targetWeight().toPlainString(),
                        item.reasonCode()
                ));
            }
        }
        return new BacktestDtos.BacktestSignalDto(
                sig.id(),
                sig.runId(),
                sig.strategyId(),
                sig.strategyVersion(),
                sig.universeId(),
                sig.evaluationDate(),
                sig.evaluationTime(),
                sig.decisionInstant(),
                sig.scheduledExecutionDate(),
                sig.targetAllocationSummary(),
                statusOverride != null ? statusOverride : sig.status(),
                sig.reasonCode(),
                sig.detailsJson(),
                itemDtos,
                sig.evaluationTime()
        );
    }
}
