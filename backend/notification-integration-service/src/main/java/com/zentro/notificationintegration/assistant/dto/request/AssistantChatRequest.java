package com.zentro.notificationintegration.assistant.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

public record AssistantChatRequest(@NotBlank @Size(max = 4000) String message, UUID conversationId,
    @Size(max = 10) List<@NotNull @Valid AssistantHistoryMessage> history) { }
