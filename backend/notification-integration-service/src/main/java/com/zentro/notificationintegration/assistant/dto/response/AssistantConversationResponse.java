package com.zentro.notificationintegration.assistant.dto.response;

import java.util.UUID;
import java.time.Instant;

public record AssistantConversationResponse(UUID id, Instant createdAt) { }
