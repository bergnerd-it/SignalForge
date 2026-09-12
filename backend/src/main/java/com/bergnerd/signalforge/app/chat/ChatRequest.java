package com.bergnerd.signalforge.app.chat;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ChatRequest(
        @NotBlank(message = "Message is required")
        String message,

        String idempotencyKey
) {
    public ChatRequest(String message) {
        this(message, null);
    }
}
