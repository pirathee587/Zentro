package com.zentro.identitypartner.merchant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zentro.identitypartner.merchant.dto.IssuedMerchantApiKey;
import com.zentro.identitypartner.merchant.dto.MerchantApiKeyMetadata;
import com.zentro.identitypartner.merchant.dto.ValidatedMerchantApiKey;
import com.zentro.identitypartner.user.dto.UserProfile;
import com.zentro.identitypartner.user.service.UserProfileService;
import java.time.OffsetDateTime;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class MerchantApiKeyServiceTest {

  @Mock private JdbcTemplate jdbc;
  @Mock private UserProfileService profiles;

  private MerchantApiKeyService service;

  private final UUID userId = UUID.randomUUID();
  private final UUID applicationId = UUID.randomUUID();
  private final Jwt jwt = Jwt.withTokenValue("mock-jwt")
      .header("alg", "none")
      .subject("user-subject-123")
      .build();

  private final UserProfile activeMerchantUser = new UserProfile(
      userId, "merchant@zentro.com", "Merchant User", "0771234567",
      true, "ACTIVE", "APPROVED", List.of("MERCHANT")
  );

  @BeforeEach
  void setUp() {
    service = new MerchantApiKeyService(jdbc, profiles);
  }

  // =========================================================================
  // Current Key Retrieval
  // =========================================================================

  @Test
  @DisplayName("Approved, active merchant can view their current API key metadata")
  void approvedActiveMerchantCanViewKey() {
    when(profiles.current(jwt)).thenReturn(activeMerchantUser);
    when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq(userId))).thenReturn(Boolean.TRUE);

    MerchantApiKeyMetadata expected = new MerchantApiKeyMetadata(
        UUID.randomUUID(), "zn_live_12345678", OffsetDateTime.now(), OffsetDateTime.now(), null, true
    );
    when(jdbc.query(anyString(), any(RowMapper.class), eq(userId))).thenReturn(List.of(expected));

    MerchantApiKeyMetadata result = service.current(jwt);

    assertThat(result).isNotNull();
    assertThat(result.prefix()).isEqualTo("zn_live_12345678");
    assertThat(result.active()).isTrue();
    verify(jdbc).query(anyString(), any(RowMapper.class), eq(userId));
  }

  @Test
  @DisplayName("Current returns null when merchant has not created an API key yet")
  void currentReturnsNullWhenNoKeyExists() {
    when(profiles.current(jwt)).thenReturn(activeMerchantUser);
    when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq(userId))).thenReturn(Boolean.TRUE);
    when(jdbc.query(anyString(), any(RowMapper.class), eq(userId))).thenReturn(List.of());

    MerchantApiKeyMetadata result = service.current(jwt);

    assertThat(result).isNull();
  }

  @Test
  @DisplayName("Unapproved merchant receives 403 Forbidden when requesting API key")
  void unapprovedMerchantReceivesForbidden() {
    when(profiles.current(jwt)).thenReturn(activeMerchantUser);
    when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq(userId))).thenReturn(Boolean.FALSE);

    assertThatThrownBy(() -> service.current(jwt))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(ex -> {
          ResponseStatusException rse = (ResponseStatusException) ex;
          assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
          assertThat(rse.getReason()).contains("API keys are available to approved, active merchants only");
        });
  }

  // =========================================================================
  // Key Issuance & Rotation
  // =========================================================================

  @Test
  @DisplayName("Approved merchant can issue or rotate an API key")
  void approvedMerchantCanIssueOrRotateKey() {
    when(profiles.current(jwt)).thenReturn(activeMerchantUser);
    when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq(userId))).thenReturn(Boolean.TRUE);

    MerchantApiKeyMetadata meta = new MerchantApiKeyMetadata(
        UUID.randomUUID(), "zn_live_new12345", OffsetDateTime.now(), OffsetDateTime.now(), null, true
    );
    when(jdbc.queryForObject(anyString(), any(RowMapper.class), eq(userId), anyString(), anyString()))
        .thenReturn(meta);

    IssuedMerchantApiKey issued = service.issueOrRotate(jwt);

    assertThat(issued).isNotNull();
    assertThat(issued.secret()).startsWith("zn_live_");
    assertThat(issued.credential().prefix()).isEqualTo("zn_live_new12345");
    assertThat(issued.toString()).doesNotContain(issued.secret());
    assertThat(issued.toString()).contains("[REDACTED]");
    assertThat(issued.secret()).matches("zn_live_[A-Za-z0-9_-]{43}");
  }

  @Test
  @DisplayName("Each creation stores only the SHA-256 hash of a distinct 256-bit key")
  void creationsUseDistinctKeysAndStoreOnlySha256Hash() throws Exception {
    when(profiles.current(jwt)).thenReturn(activeMerchantUser);
    when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq(userId))).thenReturn(Boolean.TRUE);
    MerchantApiKeyMetadata meta = new MerchantApiKeyMetadata(
        UUID.randomUUID(), "zn_live_new12345", OffsetDateTime.now(), OffsetDateTime.now(), null, true);
    when(jdbc.queryForObject(anyString(), any(RowMapper.class), eq(userId), anyString(), anyString()))
        .thenReturn(meta);

    IssuedMerchantApiKey first = service.issueOrRotate(jwt);
    IssuedMerchantApiKey second = service.issueOrRotate(jwt);
    org.mockito.ArgumentCaptor<String> prefix = org.mockito.ArgumentCaptor.forClass(String.class);
    org.mockito.ArgumentCaptor<String> hash = org.mockito.ArgumentCaptor.forClass(String.class);
    verify(jdbc, org.mockito.Mockito.times(2)).queryForObject(
        anyString(), any(RowMapper.class), eq(userId), prefix.capture(), hash.capture());

    String expectedHash = HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(first.secret().getBytes(StandardCharsets.UTF_8)));
    assertThat(first.secret()).isNotEqualTo(second.secret());
    assertThat(first.secret()).hasSize(51);
    assertThat(prefix.getAllValues()).contains(first.secret().substring(0, 16), second.secret().substring(0, 16));
    assertThat(hash.getAllValues()).contains(expectedHash);
    assertThat(hash.getAllValues()).doesNotContain(first.secret(), second.secret());
  }

  @Test
  @DisplayName("Unapproved merchant cannot issue or rotate an API key (403 Forbidden)")
  void unapprovedMerchantCannotIssueApiKey() {
    when(profiles.current(jwt)).thenReturn(activeMerchantUser);
    when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq(userId))).thenReturn(Boolean.FALSE);

    assertThatThrownBy(() -> service.issueOrRotate(jwt))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(ex -> {
          ResponseStatusException rse = (ResponseStatusException) ex;
          assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        });
  }

  // =========================================================================
  // Key Revocation
  // =========================================================================

  @Test
  @DisplayName("Approved merchant can revoke their API key")
  void approvedMerchantCanRevokeKey() {
    when(profiles.current(jwt)).thenReturn(activeMerchantUser);
    when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq(userId))).thenReturn(Boolean.TRUE);

    MerchantApiKeyMetadata revoked = new MerchantApiKeyMetadata(
        UUID.randomUUID(), "zn_live_rev12345", OffsetDateTime.now(), OffsetDateTime.now(), OffsetDateTime.now(), false
    );
    when(jdbc.query(anyString(), any(RowMapper.class), eq(userId))).thenReturn(List.of(revoked));

    MerchantApiKeyMetadata result = service.revoke(jwt);

    assertThat(result).isNotNull();
    assertThat(result.active()).isFalse();
    assertThat(result.revokedAt()).isNotNull();
  }

  @Test
  @DisplayName("Revoking non-existent API key throws 404 NOT_FOUND")
  void revokingNonExistentKeyThrowsNotFound() {
    when(profiles.current(jwt)).thenReturn(activeMerchantUser);
    when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq(userId))).thenReturn(Boolean.TRUE);
    when(jdbc.query(anyString(), any(RowMapper.class), eq(userId))).thenReturn(List.of());

    assertThatThrownBy(() -> service.revoke(jwt))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
  }

  @Test
  @DisplayName("Unapproved merchant cannot revoke an API key (403 Forbidden)")
  void unapprovedMerchantCannotRevokeApiKey() {
    when(profiles.current(jwt)).thenReturn(activeMerchantUser);
    when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq(userId))).thenReturn(Boolean.FALSE);

    assertThatThrownBy(() -> service.revoke(jwt))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(ex -> {
          ResponseStatusException rse = (ResponseStatusException) ex;
          assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        });
  }

  // =========================================================================
  // Admin Operations (by application ID)
  // =========================================================================

  @Test
  @DisplayName("Admin can view merchant API key metadata by application ID")
  void adminCanViewKeyByApplicationId() {
    when(jdbc.query(anyString(), any(RowMapper.class), eq(applicationId))).thenReturn(List.of(userId));

    MerchantApiKeyMetadata meta = new MerchantApiKeyMetadata(
        UUID.randomUUID(), "zn_live_adm12345", OffsetDateTime.now(), OffsetDateTime.now(), null, true
    );
    when(jdbc.query(anyString(), any(RowMapper.class), eq(userId))).thenReturn(List.of(meta));

    MerchantApiKeyMetadata result = service.getByApplicationId(applicationId);

    assertThat(result).isNotNull();
    assertThat(result.prefix()).isEqualTo("zn_live_adm12345");
    assertThat(result.active()).isTrue();
  }

  @Test
  @DisplayName("Admin view throws 404 when merchant application is not found")
  void adminViewMissingApplicationThrowsNotFound() {
    when(jdbc.query(anyString(), any(RowMapper.class), eq(applicationId))).thenReturn(List.of());

    assertThatThrownBy(() -> service.getByApplicationId(applicationId))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
  }

  @Test
  @DisplayName("Admin view throws 404 when merchant has no API key")
  void adminViewMissingKeyThrowsNotFound() {
    when(jdbc.query(anyString(), any(RowMapper.class), eq(applicationId))).thenReturn(List.of(userId));
    when(jdbc.query(anyString(), any(RowMapper.class), eq(userId))).thenReturn(List.of());

    assertThatThrownBy(() -> service.getByApplicationId(applicationId))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
  }

  @Test
  @DisplayName("Admin can revoke merchant API key by application ID")
  void adminCanRevokeKeyByApplicationId() {
    when(jdbc.query(anyString(), any(RowMapper.class), eq(applicationId))).thenReturn(List.of(userId));

    MerchantApiKeyMetadata revoked = new MerchantApiKeyMetadata(
        UUID.randomUUID(), "zn_live_adm12345", OffsetDateTime.now(), OffsetDateTime.now(), OffsetDateTime.now(), false
    );
    when(jdbc.query(anyString(), any(RowMapper.class), eq(userId))).thenReturn(List.of(revoked));

    MerchantApiKeyMetadata result = service.revokeByApplicationId(applicationId);

    assertThat(result).isNotNull();
    assertThat(result.active()).isFalse();
    assertThat(result.revokedAt()).isNotNull();
  }

  @Test
  @DisplayName("Admin revoke throws 404 when merchant application is not found")
  void adminRevokeMissingApplicationThrowsNotFound() {
    when(jdbc.query(anyString(), any(RowMapper.class), eq(applicationId))).thenReturn(List.of());

    assertThatThrownBy(() -> service.revokeByApplicationId(applicationId))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
  }

  // =========================================================================
  // Key Validation (validateKey)
  // =========================================================================

  @Test
  @DisplayName("validateKey returns active ValidatedMerchantApiKey for valid secret")
  void validateKeyReturnsActiveForValidSecret() {
    String secret = "zn_live_abcdefghijklmnopqrstuvwxyz0123456789";
    UUID keyId = UUID.randomUUID();
    ValidatedMerchantApiKey expected = new ValidatedMerchantApiKey(keyId, userId, "zn_live_abcdefgh", true);

    when(jdbc.query(anyString(), any(RowMapper.class), anyString())).thenReturn(List.of(expected));

    Optional<ValidatedMerchantApiKey> result = service.validateKey(secret);

    assertThat(result).isPresent();
    assertThat(result.get().active()).isTrue();
    assertThat(result.get().prefix()).isEqualTo("zn_live_abcdefgh");
    assertThat(result.get().appUserId()).isEqualTo(userId);
  }

  @Test
  @DisplayName("validateKey returns inactive ValidatedMerchantApiKey for revoked key")
  void validateKeyReturnsInactiveForRevokedKey() {
    String secret = "zn_live_abcdefghijklmnopqrstuvwxyz0123456789";
    UUID keyId = UUID.randomUUID();
    ValidatedMerchantApiKey expected = new ValidatedMerchantApiKey(keyId, userId, "zn_live_abcdefgh", false);

    when(jdbc.query(anyString(), any(RowMapper.class), anyString())).thenReturn(List.of(expected));

    Optional<ValidatedMerchantApiKey> result = service.validateKey(secret);

    assertThat(result).isPresent();
    assertThat(result.get().active()).isFalse();
  }

  @Test
  @DisplayName("validateKey returns empty for null, malformed, or short secrets")
  void validateKeyReturnsEmptyForInvalidSecrets() {
    assertThat(service.validateKey(null)).isEmpty();
    assertThat(service.validateKey("")).isEmpty();
    assertThat(service.validateKey("invalid_prefix_secret")).isEmpty();
    assertThat(service.validateKey("zn_live_short")).isEmpty();
  }

  @Test
  @DisplayName("validateKey returns empty when hash not found in database")
  void validateKeyReturnsEmptyWhenHashNotFound() {
    String secret = "zn_live_nonexistentsecretkey123456789012345";
    when(jdbc.query(anyString(), any(RowMapper.class), anyString())).thenReturn(List.of());

    assertThat(service.validateKey(secret)).isEmpty();
  }
}
