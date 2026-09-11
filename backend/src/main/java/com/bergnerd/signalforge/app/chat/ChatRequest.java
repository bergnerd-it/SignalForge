package com.bergnerd.signalforge.app.chat;

import jakarta.validation.constraints.NotBlank;

public record ChatRequest(
        @NotBlank(message = "Message is required")
        String message
) {}
