package com.bergnerd.signalforge.app.research.assistant;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

public class ResearchAssistantDtos {

    public record ResearchContextDto(
            @NotBlank(message = "Context type is required (RUN, PORTFOLIO, COMPARISON, PROPOSAL)")
            String contextType,

            @NotBlank(message = "Context ID is required")
            String contextId
    ) {}

    public record ChatRequest(
            @NotBlank(message = "Message is required")
            String message,

            ResearchContextDto context
    ) {}

    public record FactCardDto(
            String title,
            String value,
            String description,
            String category
    ) {}

    public record EvidenceReferenceDto(
            String type,
            String id,
            String observationInstant,
            String description
    ) {}

    public record ChatResponse(
            String message,
            List<FactCardDto> factCards,
            List<EvidenceReferenceDto> evidenceReferences
    ) {}
}
