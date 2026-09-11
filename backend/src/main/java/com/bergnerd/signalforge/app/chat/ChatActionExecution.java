package com.bergnerd.signalforge.app.chat;

public record ChatActionExecution(
        String type,
        String ticker,
        String details,
        boolean success,
        String error
) {}
