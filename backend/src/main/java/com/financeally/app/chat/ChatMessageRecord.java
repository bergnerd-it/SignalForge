package com.financeally.app.chat;

public record ChatMessageRecord(
        String id,
        String role,
        String content,
        String actions,
        String createdAt
) {}
