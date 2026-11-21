package com.zentro.notificationintegration.assistant.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Legacy client history is validated for compatibility, but never trusted as stored context. */
public record AssistantHistoryMessage(@NotNull @Pattern(regexp = "user|assistant") String role,
    @NotBlank @Size(max = 4000) String text) { }
