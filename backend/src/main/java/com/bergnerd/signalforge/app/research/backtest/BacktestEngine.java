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
            int fillCount
    ) {}

    private record PendingDistribution(
            String actionId,
            BigDecimal amount,
            String paymentDate,
            String paymentInstant
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

        List<BacktestDtos.DailyEquityPoint> dailyEquityList = new ArrayList<>();
        List<BacktestDtos.BacktestOrderDto> ordersList = new ArrayList<>();
        List<BacktestDtos.BacktestEventDto> eventsList = new ArrayList<>();
        List<PendingDistribution> pendingDistributions = new ArrayList<>();

        int eventSeq = 0;
        boolean hasReinvestmentPending = false;
        BigDecimal prevSessionEquity = cash;

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

            // --- 1. Initial Funding (immediately before first session open) ---
            if (i == 0) {
                eventSeq++;
                eventsList.add(new BacktestDtos.BacktestEventDto(
                        UUID.randomUUID().toString(),
                        runId,
                        seriesType.name(),
                        eventSeq,
                        BacktestDtos.EventType.FUNDING.name(),
                        sDate,
                        session.openTime() != null ? session.openTime() : sDate + "T09:00:00Z",
                        "Initial funding of " + cash.toPlainString() + " EUR",
                        null,
                        cash.toPlainString(),
                        "0.00000000",
                        "0.00",
                        "0.00",
                        Instant.now().toString()
                ));
            }

            // --- 2. Session Start: Corporate Actions (08:55) ---
            // Process splits first
            for (BacktestDataReader.ActionRecord action : actions) {
                if (sDate.equals(action.effectiveDate()) && action.isSplit()) {
                    BigDecimal ratio = action.splitRatio();
                    BigDecimal newUnits = AccountingCore.normalizeQuantity(
                            units.multiply(ratio, AccountingCore.MATH_CONTEXT)
                    );
                    BigDecimal unitsDelta = newUnits.subtract(units);
                    units = newUnits;

                    eventSeq++;
                    eventsList.add(new BacktestDtos.BacktestEventDto(
                            UUID.randomUUID().toString(),
                            runId,
                            seriesType.name(),
                            eventSeq,
                            BacktestDtos.EventType.SPLIT.name(),
                            sDate,
                            sDate + "T08:55:00Z",
                            "Stock split " + action.splitNumerator() + ":" + action.splitDenominator() +
                                    ", units adjusted from " + units.subtract(unitsDelta).setScale(AccountingCore.QUANTITY_SCALE, AccountingCore.CASH_ROUNDING).toPlainString() +
                                    " to " + units.setScale(AccountingCore.QUANTITY_SCALE, AccountingCore.CASH_ROUNDING).toPlainString(),
                            null,
                            "0.00",
                            unitsDelta.setScale(AccountingCore.QUANTITY_SCALE, AccountingCore.CASH_ROUNDING).toPlainString(),
                            "0.00",
                            "0.00",
                            Instant.now().toString()
                    ));
                }
            }

            // Process distribution entitlements (based on pre-trade holdings)
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
                                action.paymentInstant()
                        ));

                        eventSeq++;
                        eventsList.add(new BacktestDtos.BacktestEventDto(
                                UUID.randomUUID().toString(),
                                runId,
                                seriesType.name(),
                                eventSeq,
                                BacktestDtos.EventType.ENTITLEMENT.name(),
                                sDate,
                                sDate + "T08:55:00Z",
                                "Cash distribution entitlement of " + entitlement.toPlainString() + " EUR (" +
                                        action.distributionAmount().toPlainString() + " EUR/unit on " + units.toPlainString() + " units)",
                                null,
                                "0.00",
                                "0.00000000",
                                "0.00",
                                entitlement.toPlainString(),
                                Instant.now().toString()
                        ));
                    }
                }
            }

            // --- 3. Distribution Payments arriving before open ---
            Iterator<PendingDistribution> pIter = pendingDistributions.iterator();
            while (pIter.hasNext()) {
                PendingDistribution p = pIter.next();
                if (p.paymentInstant != null && session.openTime() != null &&
                        p.paymentInstant.compareTo(session.openTime()) < 0 &&
                        (p.paymentDate == null || p.paymentDate.compareTo(sDate) <= 0)) {
                    cash = cash.add(p.amount);
                    receivables = receivables.subtract(p.amount);
                    hasReinvestmentPending = true;

                    eventSeq++;
                    eventsList.add(new BacktestDtos.BacktestEventDto(
                            UUID.randomUUID().toString(),
                            runId,
                            seriesType.name(),
                            eventSeq,
                            BacktestDtos.EventType.PAYMENT.name(),
                            sDate,
                            p.paymentInstant,
                            "Distribution payment received: " + p.amount.toPlainString() + " EUR",
                            null,
                            p.amount.toPlainString(),
                            "0.00000000",
                            "0.00",
                            p.amount.negate().toPlainString(),
                            Instant.now().toString()
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

                String orderId = UUID.randomUUID().toString();
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
                            BacktestDtos.OrderStatus.FILLED.name(),
                            null,
                            Instant.now().toString()
                    ));

                    eventSeq++;
                    eventsList.add(new BacktestDtos.BacktestEventDto(
                            UUID.randomUUID().toString(),
                            runId,
                            seriesType.name(),
                            eventSeq,
                            BacktestDtos.EventType.EXECUTION.name(),
                            sDate,
                            session.openTime() != null ? session.openTime() : sDate + "T09:00:00Z",
                            orderType + " filled: " + executedQty.toPlainString() + " units @ " + fillPrice.toPlainString() +
                                    " EUR (commission " + commission.toPlainString() + " EUR)",
                            null,
                            totalCost.negate().toPlainString(),
                            executedQty.toPlainString(),
                            totalCost.toPlainString(),
                            "0.00",
                            Instant.now().toString()
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
                            BacktestDtos.OrderStatus.SKIPPED.name(),
                            "INSUFFICIENT_CASH",
                            Instant.now().toString()
                    ));
                }
            }

            // --- 5. Date-only / Post-close distribution payments ---
            pIter = pendingDistributions.iterator();
            while (pIter.hasNext()) {
                PendingDistribution p = pIter.next();
                if (p.paymentDate != null && p.paymentDate.equals(sDate)) {
                    cash = cash.add(p.amount);
                    receivables = receivables.subtract(p.amount);
                    hasReinvestmentPending = true; // Armed for next session open

                    eventSeq++;
                    eventsList.add(new BacktestDtos.BacktestEventDto(
                            UUID.randomUUID().toString(),
                            runId,
                            seriesType.name(),
                            eventSeq,
                            BacktestDtos.EventType.PAYMENT.name(),
                            sDate,
                            session.closeTime() != null ? session.closeTime() : sDate + "T17:30:00Z",
                            "Date-only distribution payment settled post-close: " + p.amount.toPlainString() + " EUR",
                            null,
                            p.amount.toPlainString(),
                            "0.00000000",
                            "0.00",
                            p.amount.negate().toPlainString(),
                            Instant.now().toString()
                    ));
                    pIter.remove();
                }
            }

            // --- 6. Closing Valuation & Daily Equity Snapshot ---
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
            eventsList.add(new BacktestDtos.BacktestEventDto(
                    UUID.randomUUID().toString(),
                    runId,
                    seriesType.name(),
                    eventSeq,
                    BacktestDtos.EventType.CLOSING_MARK.name(),
                    sDate,
                    session.closeTime() != null ? session.closeTime() : sDate + "T17:30:00Z",
                    "Closing mark: Equity=" + totalEquity.toPlainString() + " EUR (Cash=" + cash.toPlainString() +
                            ", Holdings=" + holdingsValue.toPlainString() + ", Receivables=" + receivables.toPlainString() + ")",
                    null,
                    "0.00",
                    "0.00000000",
                    "0.00",
                    "0.00",
                    Instant.now().toString()
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

        BacktestDtos.BacktestHoldingsDto holdingsDto = new BacktestDtos.BacktestHoldingsDto(
                seriesType.name(),
                listingId,
                units.setScale(AccountingCore.QUANTITY_SCALE, AccountingCore.CASH_ROUNDING).toPlainString(),
                totalBasis.toPlainString(),
                avgCost.toPlainString(),
                lastClose.toPlainString(),
                finalHoldingsValue.toPlainString(),
                unrealizedGain.toPlainString(),
                Instant.now().toString()
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
                fillCount
        );
    }
}
