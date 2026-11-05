package com.zentro.identitypartner.merchant.dto;

public record IssuedMerchantApiKey(String secret, MerchantApiKeyMetadata credential) {}
