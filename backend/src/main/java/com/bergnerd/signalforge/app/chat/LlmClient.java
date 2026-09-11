package com.bergnerd.signalforge.app.chat;

import java.util.List;

public interface LlmClient {
    LlmStructuredResponse generateResponse(String systemPrompt, List<ChatMessageRecord> history, String userMessage);
}
