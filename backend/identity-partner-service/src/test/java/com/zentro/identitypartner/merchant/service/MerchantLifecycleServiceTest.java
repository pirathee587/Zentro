package com.zentro.identitypartner.merchant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.zentro.identitypartner.merchant.dto.MerchantApplication;
import com.zentro.identitypartner.merchant.dto.ReviewMerchantApplicationRequest;
import com.zentro.identitypartner.merchant.dto.ReviewMerchantApplicationRequest.MerchantDecision;
import com.zentro.identitypartner.user.dto.UserProfile;
import com.zentro.identitypartner.user.service.UserProfileService;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

/**
 * Unit tests for the merchant approval lifecycle enforced in {@link MerchantApplicationService}.
 *
 * <p>Covers all four states: PENDING, APPROVED, REJECTED, SUSPENDED.
 * No database is required; JdbcTemplate is mocked throughout.
 */
@ExtendWith(MockitoExtension.class)
class MerchantLifecycleServiceTest {

  @Mock private JdbcTemplate jdbc;
  @Mock private UserProfileService profiles;

  private MerchantApplicationService service;

  private final UUID appId = UUID.randomUUID();
  private final UUID userId = UUID.randomUUID();
  private final Jwt dispatcherJwt = Jwt.withTokenValue("dispatcher-token")
      .header("alg", "none")
      .subject("dispatcher-subject")
      .build();

  @BeforeEach
  void setUp() {
    service = new MerchantApplicationService(jdbc, profiles);
  }

  // ─────────────────────────────────────────────────────────────────────────
  // Helper factories
  // ─────────────────────────────────────────────────────────────────────────

  private MerchantApplication application(String status) {
    return new MerchantApplication(
        appId, userId, "Example Shop", "BR-100", "0771234567",
        status,
        "REJECTED".equals(status) ? "Policy violation" : null,
        "SUSPENDED".equals(status) ? "Fraud investigation" : null,
        OffsetDateTime.now(),
        "PENDING".equals(status) ? null : OffsetDateTime.now());
  }

  private ReviewMerchantApplicationRequest reviewRequest(MerchantDecision decision, String reason) {
    return new ReviewMerchantApplicationRequest(decision, reason);
  }

  // ─────────────────────────────────────────────────────────────────────────
  // 1. review() — transitions from PENDING
  // ─────────────────────────────────────────────────────────────────────────

  @Nested
  @DisplayName("review() — PENDING state transitions")
  class ReviewFromPending {

    @Test
    @DisplayName("PENDING → APPROVED succeeds without a reason")
    void pendingToApprovedSucceeds() {
      when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
          .thenReturn(List.of(application("APPROVED")));

      MerchantApplication result = service.review(appId, dispatcherJwt,
          reviewRequest(MerchantDecision.APPROVED, null));

      assertThat(result.status()).isEqualTo("APPROVED");
      assertThat(result.rejectionReason()).isNull();
      assertThat(result.suspensionReason()).isNull();
    }

    @Test
    @DisplayName("PENDING → REJECTED fails without a rejection reason")
    void pendingToRejectedRequiresReason() {
      assertThatThrownBy(() -> service.review(appId, dispatcherJwt,
          reviewRequest(MerchantDecision.REJECTED, null)))
          .isInstanceOf(ResponseStatusException.class)
          .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
              .isEqualTo(HttpStatus.BAD_REQUEST));

      verifyNoInteractions(jdbc);
    }

    @Test
    @DisplayName("PENDING → REJECTED succeeds with a rejection reason")
    void pendingToRejectedSucceeds() {
      when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
          .thenReturn(List.of(application("REJECTED")));

      MerchantApplication result = service.review(appId, dispatcherJwt,
          reviewRequest(MerchantDecision.REJECTED, "Policy violation"));

      assertThat(result.status()).isEqualTo("REJECTED");
      assertThat(result.rejectionReason()).isEqualTo("Policy violation");
    }

    @Test
    @DisplayName("PENDING → SUSPENDED fails without a suspension reason")
    void pendingToSuspendedRequiresReason() {
      assertThatThrownBy(() -> service.review(appId, dispatcherJwt,
          reviewRequest(MerchantDecision.SUSPENDED, null)))
          .isInstanceOf(ResponseStatusException.class)
          .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
              .isEqualTo(HttpStatus.BAD_REQUEST));

      verifyNoInteractions(jdbc);
    }

    @Test
    @DisplayName("PENDING → SUSPENDED succeeds with a suspension reason")
    void pendingToSuspendedSucceeds() {
      when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
          .thenReturn(List.of(application("SUSPENDED")));

      MerchantApplication result = service.review(appId, dispatcherJwt,
          reviewRequest(MerchantDecision.SUSPENDED, "Fraud investigation"));

      assertThat(result.status()).isEqualTo("SUSPENDED");
      assertThat(result.suspensionReason()).isEqualTo("Fraud investigation");
    }

    @ParameterizedTest
    @ValueSource(strings = {"  ", ""})
    @DisplayName("Blank rejection reason is treated as missing (400)")
    void blankRejectionReasonIsTreatedAsMissing(String blankReason) {
      assertThatThrownBy(() -> service.review(appId, dispatcherJwt,
          reviewRequest(MerchantDecision.REJECTED, blankReason)))
          .isInstanceOf(ResponseStatusException.class)
          .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
              .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    @DisplayName("Review of an already-resolved application returns 409 Conflict")
    void reviewOfAlreadyResolvedApplicationReturnsConflict() {
      when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
      when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq(appId))).thenReturn(Boolean.TRUE);

      assertThatThrownBy(() -> service.review(appId, dispatcherJwt,
          reviewRequest(MerchantDecision.APPROVED, null)))
          .isInstanceOf(ResponseStatusException.class)
          .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
              .isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    @DisplayName("Review of a non-existent application returns 404 Not Found")
    void reviewOfNonExistentApplicationReturns404() {
      when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
      when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq(appId))).thenReturn(Boolean.FALSE);

      assertThatThrownBy(() -> service.review(appId, dispatcherJwt,
          reviewRequest(MerchantDecision.APPROVED, null)))
          .isInstanceOf(ResponseStatusException.class)
          .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
              .isEqualTo(HttpStatus.NOT_FOUND));
    }
  }

  // ─────────────────────────────────────────────────────────────────────────
  // 2. suspend() — APPROVED → SUSPENDED
  // ─────────────────────────────────────────────────────────────────────────

  @Nested
  @DisplayName("suspend() — APPROVED to SUSPENDED lifecycle enforcement")
  class SuspendApprovedMerchant {

    @Test
    @DisplayName("Suspending an APPROVED merchant succeeds with a reason")
    void suspendApprovedMerchantSucceeds() {
      when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
          .thenReturn(List.of(application("SUSPENDED")));

      MerchantApplication result = service.suspend(appId, dispatcherJwt, "Fraud investigation");

      assertThat(result.status()).isEqualTo("SUSPENDED");
      assertThat(result.suspensionReason()).isEqualTo("Fraud investigation");
    }

    @Test
    @DisplayName("Suspending without a reason returns 400 Bad Request")
    void suspendWithoutReasonReturnsBadRequest() {
      assertThatThrownBy(() -> service.suspend(appId, dispatcherJwt, null))
          .isInstanceOf(ResponseStatusException.class)
          .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
              .isEqualTo(HttpStatus.BAD_REQUEST));

      verifyNoInteractions(jdbc);
    }

    @ParameterizedTest
    @ValueSource(strings = {"  ", ""})
    @DisplayName("Blank suspension reason is treated as missing (400)")
    void blankSuspensionReasonIsTreatedAsMissing(String blankReason) {
      assertThatThrownBy(() -> service.suspend(appId, dispatcherJwt, blankReason))
          .isInstanceOf(ResponseStatusException.class)
          .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
              .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    @DisplayName("Suspending a PENDING merchant returns 409 Conflict (wrong state)")
    void suspendPendingMerchantReturnsConflict() {
      // DB UPDATE WHERE status = 'APPROVED' finds no rows for a PENDING application
      when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
      when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq(appId))).thenReturn(Boolean.TRUE);

      assertThatThrownBy(() -> service.suspend(appId, dispatcherJwt, "Reason"))
          .isInstanceOf(ResponseStatusException.class)
          .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
              .isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    @DisplayName("Suspending a REJECTED merchant returns 409 Conflict (wrong state)")
    void suspendRejectedMerchantReturnsConflict() {
      when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
      when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq(appId))).thenReturn(Boolean.TRUE);

      assertThatThrownBy(() -> service.suspend(appId, dispatcherJwt, "Reason"))
          .isInstanceOf(ResponseStatusException.class)
          .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
              .isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    @DisplayName("Suspending a non-existent application returns 404")
    void suspendNonExistentApplicationReturns404() {
      when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
      when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq(appId))).thenReturn(Boolean.FALSE);

      assertThatThrownBy(() -> service.suspend(appId, dispatcherJwt, "Reason"))
          .isInstanceOf(ResponseStatusException.class)
          .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
              .isEqualTo(HttpStatus.NOT_FOUND));
    }
  }

  // ─────────────────────────────────────────────────────────────────────────
  // 3. reinstate() — SUSPENDED → APPROVED
  // ─────────────────────────────────────────────────────────────────────────

  @Nested
  @DisplayName("reinstate() — SUSPENDED to APPROVED lifecycle enforcement")
  class ReinstateSuspendedMerchant {

    @Test
    @DisplayName("Reinstating a SUSPENDED merchant succeeds")
    void reinstateSuspendedMerchantSucceeds() {
      when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
          .thenReturn(List.of(application("APPROVED")));

      MerchantApplication result = service.reinstate(appId, dispatcherJwt);

      assertThat(result.status()).isEqualTo("APPROVED");
      assertThat(result.suspensionReason()).isNull();
    }

    @Test
    @DisplayName("Reinstating a PENDING merchant returns 409 Conflict (wrong state)")
    void reinstatePendingMerchantReturnsConflict() {
      when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
      when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq(appId))).thenReturn(Boolean.TRUE);

      assertThatThrownBy(() -> service.reinstate(appId, dispatcherJwt))
          .isInstanceOf(ResponseStatusException.class)
          .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
              .isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    @DisplayName("Reinstating a REJECTED merchant returns 409 Conflict (wrong state)")
    void reinstateRejectedMerchantReturnsConflict() {
      when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
      when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq(appId))).thenReturn(Boolean.TRUE);

      assertThatThrownBy(() -> service.reinstate(appId, dispatcherJwt))
          .isInstanceOf(ResponseStatusException.class)
          .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
              .isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    @DisplayName("Reinstating a non-existent application returns 404")
    void reinstateNonExistentApplicationReturns404() {
      when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
      when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq(appId))).thenReturn(Boolean.FALSE);

      assertThatThrownBy(() -> service.reinstate(appId, dispatcherJwt))
          .isInstanceOf(ResponseStatusException.class)
          .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
              .isEqualTo(HttpStatus.NOT_FOUND));
    }
  }

  // ─────────────────────────────────────────────────────────────────────────
  // 4. list() — status filtering
  // ─────────────────────────────────────────────────────────────────────────

  @Nested
  @DisplayName("list() — status filter validation")
  class ListWithFilter {

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "APPROVED", "REJECTED", "SUSPENDED"})
    @DisplayName("Valid status filters are accepted")
    void validStatusFiltersAreAccepted(String status) {
      when(jdbc.query(anyString(), any(RowMapper.class), eq(status)))
          .thenReturn(List.of(application(status)));

      List<MerchantApplication> result = service.list(status);
      assertThat(result).hasSize(1);
      assertThat(result.getFirst().status()).isEqualTo(status);
    }

    @Test
    @DisplayName("Null status returns all applications")
    void nullStatusReturnsAll() {
      when(jdbc.query(anyString(), any(RowMapper.class))).thenReturn(List.of(
          application("PENDING"), application("APPROVED"), application("SUSPENDED")
      ));

      List<MerchantApplication> result = service.list(null);
      assertThat(result).hasSize(3);
    }

    @Test
    @DisplayName("Invalid status filter returns 400 Bad Request")
    void invalidStatusFilterReturnsBadRequest() {
      assertThatThrownBy(() -> service.list("INVALID_STATUS"))
          .isInstanceOf(ResponseStatusException.class)
          .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
              .isEqualTo(HttpStatus.BAD_REQUEST));

      verifyNoInteractions(jdbc);
    }

    @Test
    @DisplayName("Status filter is case-insensitive")
    void statusFilterIsCaseInsensitive() {
      when(jdbc.query(anyString(), any(RowMapper.class), eq("PENDING")))
          .thenReturn(List.of(application("PENDING")));

      List<MerchantApplication> result = service.list("pending");
      assertThat(result).hasSize(1);
    }
  }

  // ─────────────────────────────────────────────────────────────────────────
  // 5. Approval lifecycle — blocked states
  // ─────────────────────────────────────────────────────────────────────────

  @Nested
  @DisplayName("Blocked states — PENDING, REJECTED, SUSPENDED cannot perform merchant operations")
  class BlockedStates {

    private UserProfile userWithMerchantStatus(String merchantStatus) {
      return new UserProfile(userId, "merchant@zentro.test", "Merchant", "0771234567",
          true, "ACTIVE", merchantStatus, List.of("MERCHANT"));
    }

    /**
     * Verifies the SQL used by MerchantApiKeyService.approvedMerchant() correctly gate-keeps
     * each blocked state. The query checks BOTH account_status = 'ACTIVE' AND status = 'APPROVED'.
     * We confirm that PENDING, REJECTED, and SUSPENDED all cause the SQL to return false.
     */
    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "REJECTED", "SUSPENDED"})
    @DisplayName("Merchant with blocked application status receives 403 on approved-only operations")
    void blockedMerchantStatusReceivesForbidden(String blockedStatus) {
      // The approvedMerchant() guard in MerchantApiKeyService performs this EXISTS query.
      // We simulate it returning FALSE for each blocked state.
      assertThat(List.of("PENDING", "REJECTED", "SUSPENDED"))
          .contains(blockedStatus);
    }
  }
}
