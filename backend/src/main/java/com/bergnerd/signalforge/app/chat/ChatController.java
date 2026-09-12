package com.bergnerd.signalforge.app.chat;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;

    @PostMapping
    public ResponseEntity<ChatResponse> sendMessage(
            @RequestHeader(value = "Idempotency-Key", required = false) String headerKey,
            @Valid @RequestBody ChatRequest request) {
        String effectiveKey = (headerKey != null && !headerKey.isBlank())
                ? headerKey.trim()
                : (request.idempotencyKey() != null && !request.idempotencyKey().isBlank() ? request.idempotencyKey().trim() : null);
        if (effectiveKey == null) {
            throw new com.bergnerd.signalforge.app.operation.IdempotencyExceptions.MissingIdempotencyKeyException(
                    "Idempotency-Key is required for chat requests"
            );
        }
        return ResponseEntity.ok(chatService.processUserMessage("default", request.message(), effectiveKey));
    }

    @GetMapping("/history")
    public ResponseEntity<List<ChatMessageRecord>> getHistory() {
        return ResponseEntity.ok(chatService.getChatHistory("default"));
    }

    @DeleteMapping("/history")
    public ResponseEntity<Void> clearHistory() {
        chatService.clearChatHistory("default");
        return ResponseEntity.noContent().build();
    }
}
