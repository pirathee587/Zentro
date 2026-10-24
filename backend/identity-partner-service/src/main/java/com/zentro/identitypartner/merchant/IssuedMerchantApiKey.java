package com.zentro.identitypartner.merchant;

public record IssuedMerchantApiKey(String secret, MerchantApiKeyMetadata credential) {}
