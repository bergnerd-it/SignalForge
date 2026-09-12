package com.bergnerd.signalforge.app.research;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

public class ResearchDtos {

    public record CreatePortfolioRequest(
            @NotBlank(message = "Name is required")
            String name,

            @NotBlank(message = "Mode is required (must be PAPER)")
            String mode,

            @NotBlank(message = "Base currency is required (must be EUR)")
            String baseCurrency,

            @NotBlank(message = "Initial cash is required")
            String initialCash,

            String idempotencyKey
    ) {}

    public record ResearchPortfolioSummary(
            String id,
            String ownerId,
            String name,
            String mode,
            String baseCurrency,
            String cashBalance,
            int revision,
            String createdAt,
            String paperStartedAt,
            String strategyTracking,
            int positionCount
    ) {}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ResearchPortfolioDetail(
            String id,
            String ownerId,
            String name,
            String mode,
            String baseCurrency,
            String cashBalance,
            int revision,
            String createdAt,
            String paperStartedAt,
            String strategyTracking,
            String valuationStatus,
            String marketValue,
            String unrealizedPnl,
            List<ResearchPositionItem> positions
    ) {}

    public record ResearchPositionItem(
            String listingId,
            String ticker,
            String quantity,
            String totalAcquisitionCost,
            String averageCost,
            String updatedAt
    ) {}

    public record ListingDto(
            String id,
            String instrumentId,
            String venue,
            String symbol,
            String quoteCurrency,
            String identityStatus
    ) {}

    public record InstrumentDto(
            String id,
            String type,
            String name,
            String isin,
            String provenance,
            List<ListingDto> listings
    ) {}
}
