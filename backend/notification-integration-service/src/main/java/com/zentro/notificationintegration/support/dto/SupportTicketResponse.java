package com.zentro.notificationintegration.support.dto;
import java.time.OffsetDateTime;
import java.util.UUID;
public record SupportTicketResponse(UUID id,String ticketNumber,String subject,String category,
    String trackingRef,String message,String status,OffsetDateTime createdAt,String formattedCreatedAt) {}
