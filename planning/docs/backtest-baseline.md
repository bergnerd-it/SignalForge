# SignalForge M3 — Backtest Baseline & Independent Hand Calculation

**Document:** `planning/docs/backtest-baseline.md`  
**Milestone:** M3 (Backtest Engine and Buy-and-Hold Baseline)  
**Strategy:** `ETF_BUY_HOLD_V1` (S1) and Identically Accounted Benchmark  

---

## 1. Accounting Conventions and Invariants

1. **Monetary Precision & Rounding:**
   - Authoritative cash and cost basis values use `BigDecimal` with two decimal places (`scale = 2`) and `RoundingMode.HALF_EVEN`.
   - Prices and quantities support up to eight decimal places (`scale = 8`).
   - Intermediate division operations use `MathContext.DECIMAL128`.
   - Quantities for strategy buy orders are strictly integer whole units (`floor`).

2. **Cost Model:**
   - Raw Opening Price: $P_{open}$
   - Commission: Fixed EUR $C$ per nonzero executed fill (default EUR 1.00; skipped zero-unit orders incur EUR 0.00 fee).
   - Bid/Ask Spread: Full spread $s$ (default 10 bps = 0.0010); half spread $s/2$ (5 bps = 0.0005) incurred per side.
   - Slippage: Adverse slippage $l$ (default 5 bps = 0.0005) per side.
   - Buy Fill Price: $P_{buy} = P_{open} \times (1 + s/2 + l)$.
   - Spread and slippage are embedded in the fill price and are not deducted again. Commission is a separate booked cash ledger cost that increases cost basis on buys.

3. **Information Boundary & Daily Event Sequence:**
   Within each calendar trading session $d$:
   1. **08:55 Session Start Actions (Pre-Trading):**
      - Corporate actions effective on date $d$ (splits, dividends) must have `available_at <= session_start`.
      - Same-day splits are applied first, updating holdings count while leaving total cost basis unchanged.
      - Ex-date distribution entitlements are calculated using pre-trade holdings (post-split). A receivable is created:
        $$\text{Receivable} += \text{Units} \times \text{DistributionAmount}$$
        This receivable contributes to total equity but is **not** spendable cash.
   2. **09:00 Opening Executions:**
      - Only raw open prices are visible to the execution adapter; high, low, close are inaccessible.
      - Spendable cash determines affordability. Uncollected receivables are excluded.
      - S1 targets 100% at the initial session after the month-end evaluation cutoff.
      - On subsequent sessions, reinvestment occurs only if spendable cash can afford at least 1 whole unit + commission + spread/slippage.
   3. **17:30 Session Close & Post-Close Events:**
      - Closing valuation uses raw closing prices: $\text{HoldingsValue} = \text{roundCash}(\text{Units} \times P_{close})$.
      - Date-only distribution payments (without a timestamp) conservatively arrive after the session close:
        $$\text{Cash} += \text{ReceivableAmount}, \quad \text{Receivable} -= \text{ReceivableAmount}$$
        Total equity is unchanged by cash arrival.
      - Daily equity snapshot: $\text{Equity} = \text{Cash} + \text{HoldingsValue} + \text{Receivable}$.
      - Daily return: $R_d = \frac{\text{Equity}_d - \text{Equity}_{d-1}}{\text{Equity}_{d-1}}$.
        *(For day 1, $\text{Equity}_0 = \text{InitialFundedCash}$, so Day 1 return captures initial transaction costs).*

---

## 2. Independent Reference Scenario Hand Calculation

This reference scenario validates the exact sequence required by `PROMPT-SIGNALFORGE-M3.md` Section 9.

### Parameters
- **Dataset:** Synthetic ETF dataset with EUR quote currency and calendar `CAL_EUR`.
- **Candidate Listing:** `LISTING-SYN-ETF` (or `listing-eur-syn-1`).
- **Benchmark Listing:** Identical listing (for 1:1 mathematical identity check).
- **Initial Funding:** EUR 1,000.00 funded immediately prior to the 2024-02-01 open.
- **Evaluation Cutoff:** 2024-01-31T23:59:59Z (month-end close completed).
- **Costs for this fixture:** Fixed Commission = EUR 1.00; Spread = 0 bps; Slippage = 0 bps.

---

### Step-by-Step Ledger Walkthrough

#### **Session 1: 2024-02-01 (Thursday)**
- **Market Data:** Open = 100.00 EUR, Close = 100.00 EUR.
- **Pre-Open Funding:** Cash = 1000.00 EUR, Units = 0, Basis = 0.00 EUR, Receivable = 0.00 EUR.
- **Opening Sizing:**
  - Target: 100% allocation into candidate listing.
  - Price per unit = 100.00 EUR. Commission = 1.00 EUR.
  - Max whole units affordable:
    $$N \times 100.00 + 1.00 \le 1000.00 \implies N \le 9.99 \implies N = 9 \text{ units}.$$
    *(10 units would require $1000.00 + 1.00 = 1001.00 > 1000.00$, which is rejected as unaffordable).*
- **Opening Execution:**
  - Fill: Buy 9 units at 100.00 EUR.
  - Gross cost = $9 \times 100.00 = 900.00$ EUR.
  - Commission = 1.00 EUR. Total cost = 901.00 EUR.
  - Cash remaining = $1000.00 - 901.00 = 99.00$ EUR.
  - Units = 9.
  - Cost Basis = 901.00 EUR.
- **Closing Mark (17:30):**
  - Close = 100.00 EUR.
  - Holdings value = $9 \times 100.00 = 900.00$ EUR.
  - Cash = 99.00 EUR.
  - Receivable = 0.00 EUR.
  - **Closing Equity:** $99.00 + 900.00 = \mathbf{999.00\text{ EUR}}$.
  - **Daily Return:** $\frac{999.00 - 1000.00}{1000.00} = -0.0010 = \mathbf{-0.10\%}$.
  - **Drawdown:** $\frac{999.00 - 1000.00}{1000.00} = \mathbf{-0.10\%}$. Peak Equity = 1000.00 EUR.

---

#### **Session 2: 2024-02-02 (Friday)**
- **Corporate Action (08:55):** 2:1 Stock Split effective 2024-02-02.
  - Pre-split units = 9.
  - Split multiplier = 2.
  - Post-split units = $9 \times 2 = \mathbf{18\text{ units}}$.
  - Total cost basis = **901.00 EUR** (unchanged). Average cost per unit drops from 100.1111 to 50.0556 EUR.
  - Cash = 99.00 EUR (unchanged).
- **Market Data:** Open = 50.00 EUR, Close = 50.00 EUR.
- **Opening Execution (09:00):**
  - S1 does not rebalance on non-distribution days. No trade.
- **Closing Mark (17:30):**
  - Close = 50.00 EUR.
  - Holdings value = $18 \times 50.00 = 900.00$ EUR.
  - Cash = 99.00 EUR.
  - Receivable = 0.00 EUR.
  - **Closing Equity:** $99.00 + 900.00 = \mathbf{999.00\text{ EUR}}$.
  - **Daily Return:** $\frac{999.00 - 999.00}{999.00} = \mathbf{0.00\%}$.
  - **Drawdown:** $\mathbf{-0.10\%}$.

---

#### **Weekend: 2024-02-03 to 2024-02-04**
- Exchange closed. No trading or valuation events.

---

#### **Session 3: 2024-02-05 (Monday — Ex-Date)**
- **Corporate Action (08:55):** Cash Distribution of 1.00 EUR per post-split unit.
  - Eligible units = 18 (holdings before trading).
  - Entitlement = $18 \times 1.00\text{ EUR} = \mathbf{18.00\text{ EUR}}$.
  - Receivable booked: 18.00 EUR. Cash is **not** increased.
- **Market Data:** Open = 49.00 EUR, Close = 49.00 EUR.
- **Opening Execution (09:00):**
  - Spendable cash = 99.00 EUR (receivable of 18.00 EUR is not spendable).
  - S1 has no pending distribution payment trigger. No order generated.
- **Closing Mark (17:30):**
  - Close = 49.00 EUR.
  - Holdings value = $18 \times 49.00 = 882.00$ EUR.
  - Cash = 99.00 EUR.
  - Receivable = 18.00 EUR.
  - **Closing Equity:** $99.00 + 882.00 + 18.00 = \mathbf{999.00\text{ EUR}}$.
  - **Daily Return:** $\frac{999.00 - 999.00}{999.00} = \mathbf{0.00\%}$.
  - **Drawdown:** $\mathbf{-0.10\%}$.

---

#### **Session 4: 2024-02-06 (Tuesday — Payment Date)**
- **Market Data:** Open = 49.00 EUR, Close = 49.00 EUR.
- **Opening Execution (09:00):**
  - Date-only distribution payment has **not** arrived yet (conservatively scheduled after close).
  - Spendable cash = 99.00 EUR. No reinvestment order generated.
- **Session Close & Post-Close Cash Settlement (17:30):**
  - Holdings value = $18 \times 49.00 = 882.00$ EUR.
  - Distribution payment settles post-close:
    $$\text{Cash} = 99.00 + 18.00 = \mathbf{117.00\text{ EUR}}, \quad \text{Receivable} = 18.00 - 18.00 = \mathbf{0.00\text{ EUR}}.$$
  - Reinvestment trigger is armed for next session open.
  - **Closing Equity:** $117.00 + 882.00 + 0.00 = \mathbf{999.00\text{ EUR}}$.
  - **Daily Return:** $\mathbf{0.00\%}$.
  - **Drawdown:** $\mathbf{-0.10\%}$.

---

#### **Session 5: 2024-02-07 (Wednesday — Reinvestment Day)**
- **Market Data:** Open = 49.00 EUR, Close = 50.00 EUR.
- **Opening Reinvestment Execution (09:00):**
  - Available spendable cash = 117.00 EUR.
  - Unit price = 49.00 EUR (zero spread/slippage).
  - Commission = 1.00 EUR.
  - Max whole units affordable:
    $$N \times 49.00 + 1.00 \le 117.00 \implies N \le \frac{116.00}{49.00} \approx 2.367 \implies N = \mathbf{2\text{ units}}.$$
    *(3 units would cost $3 \times 49.00 + 1.00 = 148.00 > 117.00$ EUR).*
  - Fill: Buy 2 units at 49.00 EUR.
  - Gross cost = $2 \times 49.00 = 98.00$ EUR.
  - Commission = 1.00 EUR. Total cost = 99.00 EUR.
  - Cash remaining = $117.00 - 99.00 = \mathbf{18.00\text{ EUR}}$.
  - Total units = $18 + 2 = \mathbf{20\text{ units}}$.
  - Total Cost Basis = $901.00 + 99.00 = \mathbf{1000.00\text{ EUR}}$.
- **Closing Mark (17:30):**
  - Close = 50.00 EUR.
  - Holdings value = $20 \times 50.00 = 1000.00$ EUR.
  - Cash = 18.00 EUR.
  - Receivable = 0.00 EUR.
  - **Final Equity:** $18.00 + 1000.00 + 0.00 = \mathbf{1018.00\text{ EUR}}$.
  - **Daily Return:** $\frac{1018.00 - 999.00}{999.00} \approx +0.019019 = \mathbf{+1.9019\%}$.
  - **Cumulative Net Return:** $\frac{1018.00 - 1000.00}{1000.00} = +0.0180 = \mathbf{+1.80\%}$.
  - **Max Drawdown:** $\mathbf{-0.10\%}$ (occurred on Days 1–4, fully recovered on Day 5).

---

### Summary Verification Table

| Metric | Hand Calculation | Engine Expectation | Reconciled? |
|---|---|---|---|
| **Initial Equity** | EUR 1,000.00 | EUR 1,000.00 | **YES** |
| **Final Equity** | EUR 1,018.00 | EUR 1,018.00 | **YES** |
| **Ending Cash** | EUR 18.00 | EUR 18.00 | **YES** |
| **Ending Receivables** | EUR 0.00 | EUR 0.00 | **YES** |
| **Ending Units** | 20.00000000 | 20.00000000 | **YES** |
| **Total Cost Basis** | EUR 1,000.00 | EUR 1,000.00 | **YES** |
| **Total Commissions** | EUR 2.00 (2 fills @ EUR 1.00) | EUR 2.00 | **YES** |
| **Total Distributions** | EUR 18.00 | EUR 18.00 | **YES** |
| **Cumulative Return** | +1.80% | +1.80% | **YES** |
| **Max Drawdown** | -0.10% | -0.10% | **YES** |
| **Benchmark Match** | 100% identical when using same listing | Identical | **YES** |
