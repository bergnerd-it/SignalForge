package com.bergnerd.signalforge.app.accounting;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.List;
import java.util.Objects;

/**
 * Pure accounting domain core taking explicit decimal inputs and business time.
 * Invariant: Does not read wall clock, DB, HTTP, or LLM.
 * Uses exact BigDecimal arithmetic, two-decimal HALF_EVEN cash bookings,
 * DECIMAL128 for nonterminating divisions, and exact acquisition cost basis tracking.
 */
public final class AccountingCore {

    public static final MathContext MATH_CONTEXT = MathContext.DECIMAL128;
    public static final RoundingMode CASH_ROUNDING = RoundingMode.HALF_EVEN;
    public static final int CASH_SCALE = 2;
    public static final int PRICE_SCALE = 8;
    public static final int QUANTITY_SCALE = 8;
    public static final BigDecimal MAX_CASH = new BigDecimal("1000000000000000.00");
    public static final BigDecimal MAX_PRICE = new BigDecimal("1000000000000");
    public static final BigDecimal MAX_QUANTITY = new BigDecimal("1000000000000");
    public static final String POLICY_VERSION = "v2-decimal-grammar-half-even";

    public record AccountingState(
            BigDecimal cash,
            BigDecimal quantity,
            BigDecimal totalBasis
    ) {
        public AccountingState(BigDecimal cash, BigDecimal quantity, BigDecimal totalBasis) {
            Objects.requireNonNull(cash, "cash must not be null");
            Objects.requireNonNull(quantity, "quantity must not be null");
            Objects.requireNonNull(totalBasis, "totalBasis must not be null");
            this.cash = normalizeCash(cash, "cash");
            this.quantity = normalizeQuantity(quantity);
            this.totalBasis = normalizeCash(totalBasis, "totalBasis");
            if (this.cash.compareTo(BigDecimal.ZERO) < 0) {
                throw new InsufficientFundsException("Cash balance cannot be negative: " + this.cash.toPlainString());
            }
            if (this.quantity.compareTo(BigDecimal.ZERO) < 0) {
                throw new InsufficientSharesException("Position quantity cannot be negative: " + this.quantity.toPlainString());
            }
            if (this.totalBasis.compareTo(BigDecimal.ZERO) < 0) {
                throw new IllegalArgumentException("Total acquisition cost cannot be negative: " + this.totalBasis.toPlainString());
            }
        }

        public BigDecimal averageCost() {
            if (quantity.compareTo(BigDecimal.ZERO) == 0) {
                return BigDecimal.ZERO.setScale(CASH_SCALE, CASH_ROUNDING);
            }
            return totalBasis.divide(quantity, PRICE_SCALE, CASH_ROUNDING);
        }
    }

    public record AccountingDelta(
            AccountingState newState,
            BigDecimal cashDelta,
            BigDecimal quantityDelta,
            BigDecimal basisDelta,
            BigDecimal realizedGain,
            BigDecimal netProceedsOrCost
    ) {}

    public record LedgerReplayEntry(
            BigDecimal cashDelta,
            BigDecimal quantityDelta,
            BigDecimal basisDelta
    ) {}

    private AccountingCore() {}

    public static BigDecimal roundCash(BigDecimal amount) {
        Objects.requireNonNull(amount, "amount must not be null");
        return amount.setScale(CASH_SCALE, CASH_ROUNDING);
    }

    public static BigDecimal normalizeStartingCash(BigDecimal amount) {
        BigDecimal normalized = normalizeCash(amount, "initialCash");
        if (normalized.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Initial cash must be strictly positive");
        }
        return normalized;
    }

    public static BigDecimal normalizeCash(BigDecimal amount, String field) {
        Objects.requireNonNull(amount, field + " must not be null");
        BigDecimal canonical = canonical(amount);
        if (canonical.scale() > CASH_SCALE) {
            throw new IllegalArgumentException(field + " supports at most two decimal places");
        }
        if (canonical.abs().compareTo(MAX_CASH) > 0) {
            throw new IllegalArgumentException(field + " exceeds the supported bound");
        }
        return amount.setScale(CASH_SCALE, CASH_ROUNDING);
    }

    public static BigDecimal normalizeQuantity(BigDecimal quantity) {
        Objects.requireNonNull(quantity, "quantity must not be null");
        BigDecimal canonical = canonical(quantity);
        if (canonical.scale() > QUANTITY_SCALE) {
            throw new IllegalArgumentException("Quantity supports at most eight decimal places");
        }
        if (canonical.abs().compareTo(MAX_QUANTITY) > 0) {
            throw new IllegalArgumentException("Quantity exceeds the supported bound");
        }
        return canonical;
    }

    public static BigDecimal normalizePrice(BigDecimal price) {
        Objects.requireNonNull(price, "price must not be null");
        BigDecimal canonical = canonical(price);
        if (canonical.scale() > PRICE_SCALE) {
            throw new IllegalArgumentException("Price supports at most eight decimal places");
        }
        if (canonical.abs().compareTo(MAX_PRICE) > 0) {
            throw new IllegalArgumentException("Price exceeds the supported bound");
        }
        return price.scale() < 0 ? price.setScale(0) : price;
    }

    /**
     * Initial funding operation.
     */
    public static AccountingDelta fund(AccountingState current, BigDecimal fundingAmount) {
        Objects.requireNonNull(current, "current state must not be null");
        Objects.requireNonNull(fundingAmount, "funding amount must not be null");
        if (fundingAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Funding amount must be strictly positive: " + fundingAmount);
        }

        BigDecimal cashDelta = normalizeStartingCash(fundingAmount);
        AccountingState newState = new AccountingState(
                current.cash().add(cashDelta),
                current.quantity(),
                current.totalBasis()
        );

        return new AccountingDelta(
                newState,
                cashDelta,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO.setScale(CASH_SCALE, CASH_ROUNDING),
                cashDelta
        );
    }

    /**
     * Exact Buy execution.
     * Total cost = (quantity * fillPrice).setScale(2, HALF_EVEN) + fee.
     * Buy fee increases cost basis.
     */
    public static AccountingDelta buy(
            AccountingState current,
            BigDecimal quantity,
            BigDecimal fillPrice,
            BigDecimal commission
    ) {
        quantity = validateInputs(quantity, fillPrice, commission);
        fillPrice = normalizePrice(fillPrice);
        commission = normalizeCash(commission, "commission");

        BigDecimal grossCost = roundCash(quantity.multiply(fillPrice, MATH_CONTEXT));
        BigDecimal fee = roundCash(commission);
        BigDecimal totalCost = grossCost.add(fee);

        if (current.cash().compareTo(totalCost) < 0) {
            throw new InsufficientFundsException(
                    String.format("Insufficient funds: required %s, available %s",
                            totalCost.toPlainString(), current.cash().toPlainString())
            );
        }

        BigDecimal newCash = current.cash().subtract(totalCost);
        BigDecimal newQty = current.quantity().add(quantity);
        BigDecimal newTotalBasis = current.totalBasis().add(totalCost);

        AccountingState newState = new AccountingState(newCash, newQty, newTotalBasis);

        return new AccountingDelta(
                newState,
                totalCost.negate(),
                quantity,
                totalCost,
                BigDecimal.ZERO.setScale(CASH_SCALE, CASH_ROUNDING),
                totalCost
        );
    }

    /**
     * Exact Sell execution.
     * Gross proceeds = (quantity * fillPrice).setScale(2, HALF_EVEN).
     * Net proceeds = gross proceeds - fee.
     * Sale fee reduces net proceeds.
     * Proportional basis removal, with full liquidation removing 100% of remaining basis.
     */
    public static AccountingDelta sell(
            AccountingState current,
            BigDecimal quantity,
            BigDecimal fillPrice,
            BigDecimal commission
    ) {
        quantity = validateInputs(quantity, fillPrice, commission);
        fillPrice = normalizePrice(fillPrice);
        commission = normalizeCash(commission, "commission");

        if (current.quantity().compareTo(quantity) < 0) {
            throw new InsufficientSharesException(
                    String.format("Insufficient shares: attempted to sell %s, owned %s",
                            quantity.toPlainString(), current.quantity().toPlainString())
            );
        }

        BigDecimal grossProceeds = roundCash(quantity.multiply(fillPrice, MATH_CONTEXT));
        BigDecimal fee = roundCash(commission);
        BigDecimal netProceeds = grossProceeds.subtract(fee);

        BigDecimal basisRemoved;
        if (current.quantity().compareTo(quantity) == 0) {
            // Full liquidation removes all remaining basis
            basisRemoved = current.totalBasis();
        } else {
            // Proportional basis removal
            BigDecimal portion = quantity.divide(current.quantity(), MATH_CONTEXT);
            basisRemoved = roundCash(current.totalBasis().multiply(portion, MATH_CONTEXT));
        }

        BigDecimal realizedGain = netProceeds.subtract(basisRemoved);

        BigDecimal newCash = current.cash().add(netProceeds);
        BigDecimal newQty = current.quantity().subtract(quantity);
        BigDecimal newTotalBasis = current.totalBasis().subtract(basisRemoved);

        AccountingState newState = new AccountingState(newCash, newQty, newTotalBasis);

        return new AccountingDelta(
                newState,
                netProceeds,
                quantity.negate(),
                basisRemoved.negate(),
                realizedGain,
                netProceeds
        );
    }

    /**
     * Stock split adjustment.
     * Alters quantity and per-unit basis while preserving total acquisition cost and cash.
     */
    public static AccountingDelta split(
            AccountingState current,
            BigDecimal splitRatio
    ) {
        Objects.requireNonNull(current, "current state must not be null");
        Objects.requireNonNull(splitRatio, "split ratio must not be null");
        if (splitRatio.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Split ratio must be positive: " + splitRatio);
        }

        BigDecimal newQty = current.quantity().multiply(splitRatio, MATH_CONTEXT);
        BigDecimal qtyDelta = newQty.subtract(current.quantity());

        // Total basis remains unchanged
        AccountingState newState = new AccountingState(current.cash(), newQty, current.totalBasis());

        return new AccountingDelta(
                newState,
                BigDecimal.ZERO.setScale(CASH_SCALE, CASH_ROUNDING),
                qtyDelta,
                BigDecimal.ZERO.setScale(CASH_SCALE, CASH_ROUNDING),
                BigDecimal.ZERO.setScale(CASH_SCALE, CASH_ROUNDING),
                BigDecimal.ZERO.setScale(CASH_SCALE, CASH_ROUNDING)
        );
    }

    /**
     * Cash distribution (dividend) operation.
     * Cash increases by distributionAmount. Position and total basis remain unchanged.
     */
    public static AccountingDelta creditCashDistribution(
            AccountingState current,
            BigDecimal distributionAmount
    ) {
        Objects.requireNonNull(current, "current state must not be null");
        Objects.requireNonNull(distributionAmount, "distribution amount must not be null");
        if (distributionAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Distribution amount must be strictly positive: " + distributionAmount);
        }

        BigDecimal cashDelta = roundCash(normalizeCash(distributionAmount, "distributionAmount"));
        AccountingState newState = new AccountingState(
                current.cash().add(cashDelta),
                current.quantity(),
                current.totalBasis()
        );

        return new AccountingDelta(
                newState,
                cashDelta,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO.setScale(CASH_SCALE, CASH_ROUNDING),
                cashDelta
        );
    }

    /**
     * Rebuilds state from ledger entries.
     * Cash, units, and total basis are strictly accumulated.
     */
    public static AccountingState replayLedger(List<LedgerReplayEntry> entries) {
        BigDecimal cash = BigDecimal.ZERO.setScale(CASH_SCALE, CASH_ROUNDING);
        BigDecimal qty = BigDecimal.ZERO;
        BigDecimal basis = BigDecimal.ZERO.setScale(CASH_SCALE, CASH_ROUNDING);

        if (entries != null) {
            for (LedgerReplayEntry entry : entries) {
                if (entry.cashDelta() != null) {
                    cash = cash.add(entry.cashDelta());
                }
                if (entry.quantityDelta() != null) {
                    qty = qty.add(entry.quantityDelta());
                }
                if (entry.basisDelta() != null) {
                    basis = basis.add(entry.basisDelta());
                }
            }
        }

        return new AccountingState(cash, qty, basis);
    }

    private static BigDecimal validateInputs(BigDecimal quantity, BigDecimal fillPrice, BigDecimal commission) {
        Objects.requireNonNull(quantity, "quantity must not be null");
        Objects.requireNonNull(fillPrice, "fillPrice must not be null");
        Objects.requireNonNull(commission, "commission must not be null");

        if (quantity.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Quantity must be strictly positive: " + quantity);
        }
        if (fillPrice.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Fill price must be strictly positive: " + fillPrice);
        }
        if (commission.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Commission cannot be negative: " + commission);
        }
        return normalizeQuantity(quantity);
    }

    private static BigDecimal canonical(BigDecimal value) {
        BigDecimal canonical = value.stripTrailingZeros();
        return canonical.scale() < 0 ? canonical.setScale(0) : canonical;
    }

    public static class InsufficientFundsException extends RuntimeException {
        public InsufficientFundsException(String message) {
            super(message);
        }
    }

    public static class InsufficientSharesException extends RuntimeException {
        public InsufficientSharesException(String message) {
            super(message);
        }
    }
}
