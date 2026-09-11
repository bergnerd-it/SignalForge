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
    public ResponseEntity<ChatResponse> sendMessage(@Valid @RequestBody ChatRequest request) {
        return ResponseEntity.ok(chatService.processUserMessage("default", request.message()));
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
