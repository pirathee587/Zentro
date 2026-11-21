package com.zentro.notificationintegration.assistant.dto.response;

import java.time.Instant;

public record AssistantMessageResponse(String role, String text, Instant createdAt) { }
