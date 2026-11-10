package com.zentro.identitypartner.merchant.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Read-only view of a merchant application and its current approval lifecycle state.
 *
 * <p>Status values: {@code PENDING}, {@code APPROVED}, {@code REJECTED}, {@code SUSPENDED}.
 * <ul>
 *   <li>{@code rejectionReason} is populated only when status is {@code REJECTED}.</li>
 *   <li>{@code suspensionReason} is populated only when status is {@code SUSPENDED}.</li>
 * </ul>
 */
public record MerchantApplication(
    UUID id,
    UUID appUserId,
    String businessName,
    String businessRegNo,
    String contactPhone,
    String status,
    String rejectionReason,
    String suspensionReason,
    OffsetDateTime createdAt,
    OffsetDateTime reviewedAt) {}
