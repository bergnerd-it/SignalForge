package com.financeally.app.chat;

import java.util.List;

public record ChatResponse(
        String message,
        List<ChatActionExecution> actions,
        String createdAt
) {}
