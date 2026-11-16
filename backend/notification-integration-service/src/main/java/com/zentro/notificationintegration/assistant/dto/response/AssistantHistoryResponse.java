package com.zentro.notificationintegration.assistant.dto.response;

import java.util.UUID;
import java.util.List;

public record AssistantHistoryResponse(UUID id, List<AssistantMessageResponse> messages) { }
