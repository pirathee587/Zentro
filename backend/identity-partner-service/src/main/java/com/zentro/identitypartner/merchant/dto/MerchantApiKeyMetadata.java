package com.zentro.identitypartner.merchant.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record MerchantApiKeyMetadata(
    UUID id,
    String prefix,
    OffsetDateTime createdAt,
    OffsetDateTime rotatedAt,
    OffsetDateTime revokedAt,
    boolean active) {}
