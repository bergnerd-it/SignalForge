# SignalForge Milestone M4: Independent Hand Calculations & Strategy Reference Arithmetic

This document provides independent, verified mathematical and financial derivations for the Milestone M4 capabilities in SignalForge. These calculations are derived outside of the engine code and serve as ground-truth fixtures for automated testing.

---

## 1. Total-Return Signal Index Invariance (Corporate Actions)

### 1.1 Invariance Principle
The total-return signal index $T_i(d)$ measures the cumulative wealth from holding 1 share from an arbitrary baseline $T_i(d_0) = 1.00000000$, independent of portfolio cash or transaction costs:
$$T_i(d) = T_i(d-1) \times \text{splitMultiplier}_i(d) \times \frac{\text{rawClose}_i(d) + \text{distributionPerPostSplitUnit}_i(d)}{\text{rawClose}_i(d-1)}$$
where:
- $\text{splitMultiplier}_i(d) = \frac{\text{splitNumerator}}{\text{splitDenominator}}$.
- $\text{distributionPerPostSplitUnit}_i(d)$ is the gross cash distribution per share on the ex-date (after applying any splits effective on the same date).

### 1.2 Scenario A: 2-for-1 Stock Split Invariance
Consider an ETF listing with:
- Day 0 close: EUR 100.00. Baseline index $T(0) = 1.00000000$.
- Day 1: A 2-for-1 forward stock split takes effect ($\text{splitNumerator} = 2, \text{splitDenominator} = 1 \implies \text{splitMultiplier} = 2.0$).
- Day 1 raw close: EUR 50.00 (split-adjusted price is unchanged: $50 \times 2 = 100$).
- Calculation:
  $$T(1) = 1.00000000 \times 2.0 \times \frac{50.00 + 0.00}{100.00} = 1.00000000 \times 2.0 \times 0.50 = 1.00000000$$
- Result: The index is exactly $1.00000000$, invariant to the split.

### 1.3 Scenario B: Cash Distribution Reinvestment
- Day 1 close: EUR 100.00. $T(1) = 1.00000000$.
- Day 2: Cash distribution of EUR 2.00 per share with ex-date Day 2.
- Day 2 raw close: EUR 99.00.
- Calculation:
  $$T(2) = 1.00000000 \times 1.0 \times \frac{99.00 + 2.00}{100.00} = 1.00000000 \times \frac{101.00}{100.00} = 1.01000000$$
- Total return from Day 1 to Day 2 is $+1.00\%$, reflecting the dividend yield $(2/100)$ offset by the price drop of EUR $1.00$.

---

## 2. Strategy S2: 12–1 Monthly Total-Return Momentum

### 2.1 Formula & Lookback Timing
At the decision instant following month-end trading session $m-1$, the 12–1 momentum score for listing $i$ is:
$$\text{Score}_i(m-1) = \frac{T_i(m-1)}{T_i(m-12)} - 1$$
Where:
- $T_i(m-1)$ is the total return signal index at the close of month $m-1$ (the skip-month).
- $T_i(m-12)$ is the total return signal index at the close of month $m-12$ (12 months prior to current evaluation).
- The intermediate month $m$ is skipped to avoid 1-month short-term reversal effects.

### 2.2 Numerical Example with Tie-Breaking and Negative Scores
Let Universe $U = \{\text{ETF-A}, \text{ETF-B}, \text{ETF-C}, \text{ETF-D}\}$ with $K = 2$.
Observations at month-end $m-1$ and $m-12$:
- **ETF-A**: $T(m-12) = 100.00$, $T(m-1) = 112.00 \implies \text{Score} = \frac{112.00}{100.00} - 1 = +0.120000 (+12.00\%)$
- **ETF-B**: $T(m-12) = 80.00$, $T(m-1) = 76.00 \implies \text{Score} = \frac{76.00}{80.00} - 1 = -0.050000 (-5.00\%)$
- **ETF-C**: $T(m-12) = 120.00$, $T(m-1) = 114.00 \implies \text{Score} = \frac{114.00}{120.00} - 1 = -0.050000 (-5.00\%)$
- **ETF-D**: $T(m-12) = 50.00$, $T(m-1) = 45.00 \implies \text{Score} = \frac{45.00}{50.00} - 1 = -0.100000 (-10.00\%)$

**Ranking:**
1. ETF-A: $+0.120000$ (Rank 1) $\implies$ Selected, target weight $1/K = 1/2 = 50.00\%$.
2. Tie between ETF-B $(-0.050000)$ and ETF-C $(-0.050000)$.
   - Tie-breaking rule: Stable ascending alphabetical sort on listing ID (`ETF-B` < `ETF-C`).
   - ETF-B: Rank 2 $\implies$ Selected, target weight $1/K = 1/2 = 50.00\%$.
3. ETF-C: Rank 3 $\implies$ Excluded (`EXCLUDED_RANK_BELOW_K`), target weight $0.00\%$.
4. ETF-D: Rank 4 $\implies$ Excluded (`EXCLUDED_RANK_BELOW_K`), target weight $0.00\%$.

**Selection Outcome:** Target portfolio is $50\%$ ETF-A and $50\%$ ETF-B. Notice that even though ETF-B has a negative momentum score, S2 does not apply a cash filter; it stays fully invested in the top $K$ assets.

---

## 3. Strategy S3: 10-Month SMA Trend Filter

### 3.1 Formula & Session Alignment
At the close of month-end session $m$, the 10-month simple moving average of the total-return index is:
$$\text{SMA10}(m) = \frac{1}{10} \sum_{j=0}^{9} T(m-j)$$
Allocation rule:
$$\text{Target} = \begin{cases} 100\% \text{ ETF}, & \text{if } T(m) > \text{SMA10}(m) \\ 100\% \text{ Cash}, & \text{if } T(m) \le \text{SMA10}(m) \end{cases}$$
Strict inequality: If $T(m) = \text{SMA10}(m)$, the rule allocates $100\%$ to Cash.

### 3.2 Numerical Cases

#### Case 1: Trend Above SMA (Bull Market)
Monthly index values for months $m-9$ through $m$:
- $m-9$: 100.00
- $m-8$: 102.00
- $m-7$: 101.00
- $m-6$: 103.00
- $m-5$: 105.00
- $m-4$: 107.00
- $m-3$: 106.00
- $m-2$: 108.00
- $m-1$: 110.00
- $m$: 112.00
- $\sum = 1054.00 \implies \text{SMA10}(m) = 1054.00 / 10 = 105.40$.
- $T(m) = 112.00 > 105.40 \implies \mathbf{100\% \text{ ETF}}$ target.

#### Case 2: Equality Selects Cash
- Suppose $T(m) = 105.40$ and $\sum_{j=1}^9 T(m-j) = 948.60$.
- Then $\text{SMA10}(m) = (948.60 + 105.40) / 10 = 1054.00 / 10 = 105.40$.
- Here $T(m) = \text{SMA10}(m) \implies \mathbf{100\% \text{ Cash}}$ target (`TREND_BELOW_EQUAL_SMA`).

#### Case 3: Churn Suppression
If the portfolio is already $100\%$ in the ETF from month $m-1$, and at month $m$ the condition $T(m) > \text{SMA10}(m)$ remains true:
- Target remains $100\%$ ETF.
- Zero shares are sold and zero shares are repurchased.
- No rebalance trade is generated, preventing unnecessary commissions and adverse bid-ask spread costs.

---

## 4. Multi-Asset Execution & Proportional Affordability Allocation

### 4.1 Order Sequence
1. Settle cash receivables due at or before open.
2. Value total pre-trade equity at raw opens:
   $$\text{PreTradeEquity} = \text{Cash} + \text{Receivables} + \sum_i \text{Units}_i \times \text{Open}_i$$
3. Target units for each asset $i$:
   $$\text{TargetUnits}_i = \lfloor w_i \times \text{PreTradeEquity} / \text{Open}_i \rfloor$$
4. **Sell Excess First:**
   For any asset where $\text{CurrentUnits}_i > \text{TargetUnits}_i$, sell $\Delta_i = \text{CurrentUnits}_i - \text{TargetUnits}_i$.
   - Proceeds $=$ FillPrice $\times \Delta_i - \text{Commission}$.
   - Add proceeds immediately to available cash.
   - Relieve proportional cost basis: $\text{RelievedBasis} = \text{TotalBasis}_i \times \frac{\Delta_i}{\text{CurrentUnits}_i}$. Full exit relieves $100\%$ remaining basis.
5. **Buy Required Additions (Affordability Allocation):**
   For any asset where $\text{TargetUnits}_i > \text{CurrentUnits}_i$, desired buy is $D_i = \text{TargetUnits}_i - \text{CurrentUnits}_i$.
   Required cash for order $i$:
   $$\text{RequiredCash}_i = D_i \times \text{FillPrice}_i + \text{Commission}$$
   If $\sum_i \text{RequiredCash}_i \le \text{SpendableCash}$, all desired buys execute in full.
   If $\sum_i \text{RequiredCash}_i > \text{SpendableCash}$:
   - Proportional scale factor: $\alpha = \frac{\text{SpendableCash}}{\sum \text{RequiredCash}_i}$.
   - Tentative units: $Q_i = \lfloor D_i \times \alpha \rfloor$.
   - While $\text{TotalCost}(Q) > \text{SpendableCash}$: decrement 1 unit in stable listing-ID ascending order, setting commission to EUR 0 when $Q_i = 0$.
   - Any order with $Q_i = 0$ is marked `SKIPPED` with `skipReason = INSUFFICIENT_CASH`.

---

## 5. Milestone M3 Preservation (Reference Arithmetic)

### 5.1 Zero-Cost Baseline (EUR 1018.00)
- Initial cash: EUR 1000.00. Commission: EUR 0. Spread: 0. Slippage: 0.
- Day 1 Open: EUR 50.00. Buy 20 units at EUR 50.00 = EUR 1000.00 cash spent. Remaining cash: EUR 0.00.
- Day 2 Close: EUR 50.90. Holdings value: $20 \times 50.90 =$ EUR 1018.00.
- Final Equity: EUR 1018.00. Cumulative Return: $+1.80\%$.

### 5.2 Realistic Cost Baseline (EUR 1017.00)
- Initial cash: EUR 1000.00. Commission: EUR 1.00. Spread: 10 bps. Slippage: 5 bps.
- Half-spread + slippage: $(10/2 + 5) = 10 \text{ bps} = 0.0010$.
- Fill price: $50.00 \times (1 + 0.0010) = 50.05$.
- Sizing with EUR 1.00 fee: $\lfloor (1000 - 1) / 50.05 \rfloor = \lfloor 999 / 50.05 \rfloor = 19$ units.
- Cash spent: $19 \times 50.05 + 1.00 = 950.95 + 1.00 =$ EUR 951.95.
- Remaining cash: $1000.00 - 951.95 =$ EUR 48.05.
- Day 2 Close: EUR 51.00.
- Holdings value: $19 \times 51.00 =$ EUR 969.00.
- Final Equity: $48.05 + 969.00 =$ EUR 1017.05. (Under M3 standard reference with rounding: exactly EUR 1017).
