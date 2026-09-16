package com.bergnerd.signalforge.app.operation;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

/**
 * Utility to compute canonical validated-intent hashes for durable idempotency.
 * Excludes request arrival timestamps, JSON key order, and retry metadata.
 * Normalizes equivalent decimal representations and string casings.
 */
public final class CanonicalIntentHasher {

    private CanonicalIntentHasher() {}

    public static String hashPortfolioCreation(
            String ownerId,
            String name,
            String mode,
            String baseCurrency,
            BigDecimal initialCash
    ) {
        Objects.requireNonNull(ownerId, "ownerId must not be null");
        Objects.requireNonNull(mode, "mode must not be null");
        Objects.requireNonNull(baseCurrency, "baseCurrency must not be null");
        Objects.requireNonNull(initialCash, "initialCash must not be null");

        String canonical = String.format(
                "CREATE_PORTFOLIO|owner:%s|name:%s|mode:%s|currency:%s|cash:%s",
                ownerId.trim(),
                name != null ? name.trim() : "",
                mode.trim().toUpperCase(),
                baseCurrency.trim().toUpperCase(),
                initialCash.stripTrailingZeros().toPlainString()
        );

        return sha256(canonical);
    }

    public static String hashTrade(
            String portfolioId,
            String ticker,
            String side,
            BigDecimal quantity
    ) {
        Objects.requireNonNull(portfolioId, "portfolioId must not be null");
        Objects.requireNonNull(ticker, "ticker must not be null");
        Objects.requireNonNull(side, "side must not be null");
        Objects.requireNonNull(quantity, "quantity must not be null");

        String canonical = String.format(
                "TRADE|portfolio:%s|ticker:%s|side:%s|quantity:%s",
                portfolioId.trim(),
                ticker.trim().toUpperCase(),
                side.trim().toLowerCase(),
                quantity.stripTrailingZeros().toPlainString()
        );

        return sha256(canonical);
    }

    public static String hashPaperTrade(
            String portfolioId,
            String listingId,
            String side,
            BigDecimal quantity,
            BigDecimal referencePrice,
            BigDecimal fillPrice,
            BigDecimal commission,
            BigDecimal modeledSpreadSlippage,
            String marketEffectiveInstant
    ) {
        String canonical = String.format(
                "PAPER_TRADE|portfolio:%s|listing:%s|side:%s|quantity:%s|reference:%s|fill:%s|commission:%s|modeled:%s|effective:%s",
                portfolioId.trim(), listingId.trim(), side.trim().toLowerCase(),
                quantity.stripTrailingZeros().toPlainString(), referencePrice.stripTrailingZeros().toPlainString(),
                fillPrice.stripTrailingZeros().toPlainString(), commission.stripTrailingZeros().toPlainString(),
                modeledSpreadSlippage.stripTrailingZeros().toPlainString(), marketEffectiveInstant);
        return sha256(canonical);
    }

    public static String hashSplit(
            String portfolioId,
            String listingId,
            BigDecimal splitRatio
    ) {
        Objects.requireNonNull(portfolioId, "portfolioId must not be null");
        Objects.requireNonNull(listingId, "listingId must not be null");
        Objects.requireNonNull(splitRatio, "splitRatio must not be null");

        String canonical = String.format(
                "SPLIT|portfolio:%s|listing:%s|ratio:%s",
                portfolioId.trim(),
                listingId.trim(),
                splitRatio.stripTrailingZeros().toPlainString()
        );

        return sha256(canonical);
    }

    public static String hashDistribution(
            String portfolioId,
            String actionId,
            BigDecimal amount
    ) {
        Objects.requireNonNull(portfolioId, "portfolioId must not be null");
        Objects.requireNonNull(actionId, "actionId must not be null");
        Objects.requireNonNull(amount, "amount must not be null");

        String canonical = String.format(
                "DISTRIBUTION|portfolio:%s|action:%s|amount:%s",
                portfolioId.trim(),
                actionId.trim(),
                amount.stripTrailingZeros().toPlainString()
        );

        return sha256(canonical);
    }

    private static String sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
