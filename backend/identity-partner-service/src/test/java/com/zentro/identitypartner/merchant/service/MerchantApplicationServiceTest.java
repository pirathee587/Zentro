package com.zentro.identitypartner.merchant.service;

import com.zentro.identitypartner.merchant.dto.MerchantApplication;


import com.zentro.identitypartner.merchant.dto.CreateMerchantApplicationRequest;
import com.zentro.identitypartner.merchant.dto.ReviewMerchantApplicationRequest;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;

import com.zentro.identitypartner.user.dto.UserProfile;
import com.zentro.identitypartner.user.service.UserProfileService;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class MerchantApplicationServiceTest {
  @Mock private JdbcTemplate jdbc;
  @Mock private UserProfileService profiles;

  private MerchantApplicationService service;
  private final UUID userId = UUID.randomUUID();
  private final Jwt jwt = Jwt.withTokenValue("token").header("alg", "none").subject("subject").build();
  private final UserProfile user = new UserProfile(
      userId, "customer@zentro.test", "Customer", "0771234567",
      true, "ACTIVE", null, List.of("CUSTOMER"));

  @BeforeEach
  void setUp() {
    service = new MerchantApplicationService(jdbc, profiles);
  }

  @Test
  void duplicateBusinessRegistrationNumberReturnsConflictWithoutDatabaseDetails() {
    when(profiles.current(jwt)).thenReturn(user);
    doThrow(new DuplicateKeyException("duplicate key value contains internal database details"))
        .when(jdbc).query(anyString(), any(RowMapper.class), any(Object[].class));

    assertThatThrownBy(() -> service.createOrReapply(
        jwt, new CreateMerchantApplicationRequest("Example Shop", "br-100", "0771234567")))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(error -> {
          ResponseStatusException response = (ResponseStatusException) error;
          org.assertj.core.api.Assertions.assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
          org.assertj.core.api.Assertions.assertThat(response.getReason()).doesNotContain("internal database");
        });
  }

  @Test
  void rejectionRequiresAReasonBeforeWritingToDatabase() {
    ReviewMerchantApplicationRequest request =
        new ReviewMerchantApplicationRequest(ReviewMerchantApplicationRequest.MerchantDecision.REJECTED, "  ");

    assertThatThrownBy(() -> service.review(UUID.randomUUID(), jwt, request))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(error -> {
          ResponseStatusException response = (ResponseStatusException) error;
          org.assertj.core.api.Assertions.assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        });

    verifyNoInteractions(jdbc);
  }

  @Test
  void approvedReviewReturnsPersistedApprovedApplication() {
    UUID applicationId = UUID.randomUUID();
    MerchantApplication approved = new MerchantApplication(
        applicationId, userId, "Example Shop", "BR-100", "0771234567", "APPROVED", null,
        OffsetDateTime.now(), OffsetDateTime.now());
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of(approved));

    MerchantApplication result = service.review(applicationId, jwt,
        new ReviewMerchantApplicationRequest(ReviewMerchantApplicationRequest.MerchantDecision.APPROVED, "ignored"));

    org.assertj.core.api.Assertions.assertThat(result.status()).isEqualTo("APPROVED");
    org.assertj.core.api.Assertions.assertThat(result.rejectionReason()).isNull();
  }

  @Test
  void reviewOfAlreadyResolvedApplicationReturnsConflict() {
    UUID applicationId = UUID.randomUUID();
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
    when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq(applicationId))).thenReturn(Boolean.TRUE);

    assertThatThrownBy(() -> service.review(applicationId, jwt,
        new ReviewMerchantApplicationRequest(ReviewMerchantApplicationRequest.MerchantDecision.APPROVED, null)))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(error -> {
          ResponseStatusException response = (ResponseStatusException) error;
          org.assertj.core.api.Assertions.assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        });
  }
}
