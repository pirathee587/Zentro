package com.zentro.notificationintegration.support.dto;
import jakarta.validation.constraints.*;
public record SupportTicketRequest(@NotBlank @Size(max=200) String subject,
    @NotBlank @Pattern(regexp="delivery|billing|account|general") String category,
    @Pattern(regexp="[A-Za-z0-9_-]{6,32}") String trackingRef,
    @NotBlank @Size(min=10,max=4000) String message) {}
