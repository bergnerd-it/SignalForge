package com.bergnerd.signalforge.app.research.assistant;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/research/assistant")
@RequiredArgsConstructor
public class ResearchAssistantController {

    private final ResearchAssistantService assistantService;

    @PostMapping("/chat")
    public ResponseEntity<ResearchAssistantDtos.ChatResponse> chat(
            @RequestHeader(value = "X-User-Id", required = false, defaultValue = "default") String ownerId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody ResearchAssistantDtos.ChatRequest request
    ) {
        ResearchAssistantDtos.ChatResponse response = assistantService.processQuery(ownerId, idempotencyKey, request);
        return ResponseEntity.ok(response);
    }
}
