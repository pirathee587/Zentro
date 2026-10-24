package com.zentro.identitypartner.merchant;

import java.time.OffsetDateTime;
import java.util.UUID;

public record MerchantApiKeyMetadata(
    UUID id,
    String prefix,
    OffsetDateTime createdAt,
    OffsetDateTime rotatedAt,
    OffsetDateTime revokedAt,
    boolean active) {}
