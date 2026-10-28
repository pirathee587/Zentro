package com.zentro.identitypartner.merchant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zentro.identitypartner.user.UserProfile;
import com.zentro.identitypartner.user.UserProfileService;
import java.time.OffsetDateTime;
import java.util.List;
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
  }

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
}
