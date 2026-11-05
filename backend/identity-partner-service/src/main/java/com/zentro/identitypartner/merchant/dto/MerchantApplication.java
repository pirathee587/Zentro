package com.zentro.identitypartner.merchant.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record MerchantApplication(
    UUID id,
    UUID appUserId,
    String businessName,
    String businessRegNo,
    String contactPhone,
    String status,
    String rejectionReason,
    OffsetDateTime createdAt,
    OffsetDateTime reviewedAt) {}
