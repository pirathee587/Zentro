package com.zentro.notificationintegration.assistant.dto.response;

import java.util.UUID;

public record AssistantChatResponse(String reply, UUID conversationId) { }
