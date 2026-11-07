package com.zentro.identitypartner.merchant.dto;

public record IssuedMerchantApiKey(String secret, MerchantApiKeyMetadata credential) {
  @Override
  public String toString() {
    return "IssuedMerchantApiKey[secret=[REDACTED], credential=" + credential + "]";
  }
}
