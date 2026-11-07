package com.zentro.identitypartner.merchant.dto;

import java.util.UUID;

/**
 * Result of validating a raw merchant API key secret.
 *
 * @param keyId Unique identifier of the API key record
 * @param appUserId Owner application user identifier
 * @param prefix Display prefix (e.g. "zn_live_a1b2c3d4")
 * @param active Whether the key is unrevoked, user is ACTIVE, and merchant application is APPROVED
 */
public record ValidatedMerchantApiKey(
    UUID keyId,
    UUID appUserId,
    String prefix,
    boolean active) {}
