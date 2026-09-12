package com.bergnerd.signalforge.app.accounting;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AccountingCoreTest {

    @Test
    void rejectsUnsupportedDecimalPrecisionAndBounds() {
        assertThrows(IllegalArgumentException.class,
                () -> AccountingCore.normalizeStartingCash(new BigDecimal("1.001")));
        assertThrows(IllegalArgumentException.class,
                () -> AccountingCore.normalizeQuantity(new BigDecimal("0.000000001")));
        assertThrows(IllegalArgumentException.class,
                () -> AccountingCore.normalizePrice(new BigDecimal("1.000000001")));
        assertThrows(IllegalArgumentException.class,
                () -> AccountingCore.normalizePrice(new BigDecimal("1000000000001")));
        assertEquals(new BigDecimal("1.00"),
                AccountingCore.normalizeStartingCash(new BigDecimal("1.00")));
    }

    @Test
    @DisplayName("Verify independently specified accounting sequence from M1b Prompt Section 8")
    void testIndependentAccountingSequence() {
        // Step 1: Initial funding 1000.00
        AccountingCore.AccountingState state = new AccountingCore.AccountingState(
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO
        );
        AccountingCore.AccountingDelta step1 = AccountingCore.fund(state, new BigDecimal("1000.00"));
        state = step1.newState();
        assertEquals("1000.00", state.cash().toPlainString());
        assertEquals("0", state.quantity().toPlainString());
        assertEquals("0.00", state.totalBasis().toPlainString());

        // Step 2: Buy 2 at 100, fee 2 -> Cash 798.00, Units 2, Total Basis 202.00
        AccountingCore.AccountingDelta step2 = AccountingCore.buy(
                state, new BigDecimal("2"), new BigDecimal("100"), new BigDecimal("2")
        );
        state = step2.newState();
        assertEquals("798.00", state.cash().toPlainString());
        assertEquals("2", state.quantity().toPlainString());
        assertEquals("202.00", state.totalBasis().toPlainString());

        // Step 3: Buy 2 at 120, fee 2 -> Cash 556.00, Units 4, Total Basis 444.00
        AccountingCore.AccountingDelta step3 = AccountingCore.buy(
                state, new BigDecimal("2"), new BigDecimal("120"), new BigDecimal("2")
        );
        state = step3.newState();
        assertEquals("556.00", state.cash().toPlainString());
        assertEquals("4", state.quantity().toPlainString());
        assertEquals("444.00", state.totalBasis().toPlainString());

        // Step 4: Sell 1 at 130, fee 1 -> Cash 685.00, Units 3, Total Basis 333.00, Realized Gain 18.00
        AccountingCore.AccountingDelta step4 = AccountingCore.sell(
                state, new BigDecimal("1"), new BigDecimal("130"), new BigDecimal("1")
        );
        state = step4.newState();
        assertEquals("685.00", state.cash().toPlainString());
        assertEquals("3", state.quantity().toPlainString());
        assertEquals("333.00", state.totalBasis().toPlainString());
        assertEquals("18.00", step4.realizedGain().toPlainString());

        // Step 5: 3-for-2 split -> Cash 685.00, Units 4.5, Total Basis 333.00, Realized Gain 0.00
        AccountingCore.AccountingDelta step5 = AccountingCore.split(state, new BigDecimal("1.5"));
        state = step5.newState();
        assertEquals("685.00", state.cash().toPlainString());
        assertEquals("4.5", state.quantity().toPlainString());
        assertEquals("333.00", state.totalBasis().toPlainString());
        assertEquals("0.00", step5.realizedGain().toPlainString());

        // Step 6: Sell all 4.5 at 90, fee 1 -> Cash 1089.00, Units 0, Total Basis 0.00, Realized Gain 71.00
        AccountingCore.AccountingDelta step6 = AccountingCore.sell(
                state, new BigDecimal("4.5"), new BigDecimal("90"), new BigDecimal("1")
        );
        state = step6.newState();
        assertEquals("1089.00", state.cash().toPlainString());
        assertEquals("0", state.quantity().toPlainString());
        assertEquals("0.00", state.totalBasis().toPlainString());
        assertEquals("71.00", step6.realizedGain().toPlainString());

        // Total realized gain = 18.00 + 71.00 = 89.00
        BigDecimal totalRealizedGain = step4.realizedGain().add(step6.realizedGain());
        assertEquals("89.00", totalRealizedGain.toPlainString());

        // Matching final minus initial cash: 1089.00 - 1000.00 = 89.00
        BigDecimal cashDiff = state.cash().subtract(new BigDecimal("1000.00"));
        assertEquals(totalRealizedGain, cashDiff);
    }

    @Test
    @DisplayName("Verify M0 fractional arithmetic regression is resolved")
    void testM0ArithmeticRegression() {
        // Start cash 10000; buy 0.004 twice at 100, zero fees: units 0.008, cash 9999.20, basis 0.80, equity 10000.00
        AccountingCore.AccountingState state = new AccountingCore.AccountingState(
                new BigDecimal("10000.00"), BigDecimal.ZERO, BigDecimal.ZERO
        );

        // Buy 1
        AccountingCore.AccountingDelta buy1 = AccountingCore.buy(
                state, new BigDecimal("0.004"), new BigDecimal("100"), BigDecimal.ZERO
        );
        state = buy1.newState();
        assertEquals("9999.60", state.cash().toPlainString());
        assertEquals("0.004", state.quantity().toPlainString());
        assertEquals("0.40", state.totalBasis().toPlainString());

        // Buy 2
        AccountingCore.AccountingDelta buy2 = AccountingCore.buy(
                state, new BigDecimal("0.004"), new BigDecimal("100"), BigDecimal.ZERO
        );
        state = buy2.newState();
        assertEquals("9999.20", state.cash().toPlainString());
        assertEquals("0.008", state.quantity().toPlainString());
        assertEquals("0.80", state.totalBasis().toPlainString());

        // Equity valuation at fixed price 100.00
        BigDecimal fixedPrice = new BigDecimal("100.00");
        BigDecimal positionValue = state.quantity().multiply(fixedPrice).setScale(2, AccountingCore.CASH_ROUNDING);
        BigDecimal totalEquity = state.cash().add(positionValue);
        assertEquals("10000.00", totalEquity.toPlainString());
    }

    @Test
    @DisplayName("Verify full liquidation leaves 0.00 total basis after repeated rounded partial sales")
    void testFullLiquidationZeroBasis() {
        AccountingCore.AccountingState state = new AccountingCore.AccountingState(
                new BigDecimal("1000.00"), BigDecimal.ZERO, BigDecimal.ZERO
        );

        // Buy 3 shares at 10.00, fee 0.01 -> total basis 30.01
        AccountingCore.AccountingDelta buy = AccountingCore.buy(
                state, new BigDecimal("3"), new BigDecimal("10.00"), new BigDecimal("0.01")
        );
        state = buy.newState();
        assertEquals("30.01", state.totalBasis().toPlainString());

        // Sell 1 share at 15.00, fee 0 -> basis removed = 30.01 * (1/3) = 10.00 (half even)
        AccountingCore.AccountingDelta sell1 = AccountingCore.sell(
                state, new BigDecimal("1"), new BigDecimal("15.00"), BigDecimal.ZERO
        );
        state = sell1.newState();
        assertEquals("20.01", state.totalBasis().toPlainString());

        // Sell 1 share at 15.00, fee 0 -> basis removed = 20.01 * (1/2) = 10.00
        AccountingCore.AccountingDelta sell2 = AccountingCore.sell(
                state, new BigDecimal("1"), new BigDecimal("15.00"), BigDecimal.ZERO
        );
        state = sell2.newState();
        assertEquals("10.01", state.totalBasis().toPlainString());

        // Full liquidation of remaining 1 share -> must remove ALL remaining 10.01 basis!
        AccountingCore.AccountingDelta sell3 = AccountingCore.sell(
                state, new BigDecimal("1"), new BigDecimal("15.00"), BigDecimal.ZERO
        );
        state = sell3.newState();
        assertEquals("0.00", state.totalBasis().toPlainString());
        assertEquals("0", state.quantity().toPlainString());
    }

    @Test
    @DisplayName("Verify ledger replay reproduces projections exactly")
    void testLedgerReplay() {
        List<AccountingCore.LedgerReplayEntry> ledger = new ArrayList<>();
        ledger.add(new AccountingCore.LedgerReplayEntry(new BigDecimal("1000.00"), BigDecimal.ZERO, BigDecimal.ZERO)); // Funding
        ledger.add(new AccountingCore.LedgerReplayEntry(new BigDecimal("-202.00"), new BigDecimal("2"), new BigDecimal("202.00"))); // Buy
        ledger.add(new AccountingCore.LedgerReplayEntry(new BigDecimal("-242.00"), new BigDecimal("2"), new BigDecimal("242.00"))); // Buy
        ledger.add(new AccountingCore.LedgerReplayEntry(new BigDecimal("129.00"), new BigDecimal("-1"), new BigDecimal("-111.00"))); // Sell
        ledger.add(new AccountingCore.LedgerReplayEntry(BigDecimal.ZERO, new BigDecimal("1.5"), BigDecimal.ZERO)); // Split (+1.5 shares)
        ledger.add(new AccountingCore.LedgerReplayEntry(new BigDecimal("404.00"), new BigDecimal("-4.5"), new BigDecimal("-333.00"))); // Sell all

        AccountingCore.AccountingState replayed = AccountingCore.replayLedger(ledger);
        assertEquals("1089.00", replayed.cash().toPlainString());
        assertEquals("0", replayed.quantity().toPlainString());
        assertEquals("0.00", replayed.totalBasis().toPlainString());
    }
}
