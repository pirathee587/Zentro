package com.zentro.identitypartner.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.zentro.contracts.identity.v1.CourierProfileResponse;
import com.zentro.contracts.identity.v1.GetCourierProfileRequest;
import com.zentro.contracts.identity.v1.GetMerchantStatusRequest;
import com.zentro.contracts.identity.v1.MerchantStatusResponse;
import com.zentro.identitypartner.courier.dto.CourierProfile;
import com.zentro.identitypartner.courier.service.CourierProfileService;
import com.zentro.identitypartner.grpc.service.IdentityPartnerGrpcService;
import com.zentro.identitypartner.merchant.dto.CreateMerchantApplicationRequest;
import com.zentro.identitypartner.merchant.dto.ReviewMerchantApplicationRequest;
import com.zentro.identitypartner.merchant.dto.ReviewMerchantApplicationRequest.MerchantDecision;
import com.zentro.identitypartner.merchant.service.MerchantApplicationService;
import com.zentro.identitypartner.user.dto.AdminUpdateUserRequest;
import com.zentro.identitypartner.user.dto.UpdateUserProfileRequest;
import com.zentro.identitypartner.user.dto.UserProfile;
import com.zentro.identitypartner.user.repository.UserAdminAuditRepository;
import io.grpc.stub.StreamObserver;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
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
class DisabledAccountEnforcementTest {

  @Mock private JdbcTemplate jdbc;
  @Mock private UserAdminAuditRepository audit;

  private UserProfileService userProfileService;
  private MerchantApplicationService merchantApplicationService;
  private CourierProfileService courierProfileService;
  private IdentityPartnerGrpcService grpcService;

  private static final UUID USER_ID = UUID.randomUUID();
  private static final UUID DISPATCHER_ID = UUID.randomUUID();
  private static final String SUB_USER = "user-123";
  private static final String SUB_DISPATCHER = "dispatcher-123";

  @BeforeEach
  void setUp() {
    userProfileService = new UserProfileService(jdbc, audit);
    merchantApplicationService = new MerchantApplicationService(jdbc, userProfileService);
    courierProfileService = new CourierProfileService(jdbc, userProfileService);
    grpcService = new IdentityPartnerGrpcService(courierProfileService, jdbc);
  }

  private Jwt mockJwt(String subject, String role) {
    Jwt jwt = mock(Jwt.class);
    when(jwt.getSubject()).thenReturn(subject);
    when(jwt.getClaimAsString("email")).thenReturn(subject + "@zentro.test");
    when(jwt.getClaimAsString("name")).thenReturn("Name " + subject);
    when(jwt.getClaimAsString("phone_number")).thenReturn("+94770000000");
    when(jwt.getClaims()).thenReturn(Map.of("realm_access", Map.of("roles", List.of(role))));
    return jwt;
  }

  @Test
  @DisplayName("Disabled user cannot update own profile via PATCH /api/v1/users/me")
  void disabledUserCannotUpdateOwnProfile() {
    Jwt jwt = mockJwt(SUB_USER, "CUSTOMER");
    UserProfile disabledProfile = new UserProfile(
        USER_ID, "user@zentro.test", "Disabled User", "+94770000000", false, "DISABLED", null, List.of("CUSTOMER"));
    when(jdbc.queryForObject(anyString(), any(RowMapper.class), eq(SUB_USER)))
        .thenReturn(disabledProfile);

    UpdateUserProfileRequest request = new UpdateUserProfileRequest("New Name", "+94771111111");

    assertThatThrownBy(() -> userProfileService.update(jwt, request))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(ex -> {
          ResponseStatusException rse = (ResponseStatusException) ex;
          assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
          assertThat(rse.getReason()).contains("Account is DISABLED.");
        });
  }

  @Test
  @DisplayName("Suspended user cannot update own profile via PATCH /api/v1/users/me")
  void suspendedUserCannotUpdateOwnProfile() {
    Jwt jwt = mockJwt(SUB_USER, "CUSTOMER");
    UserProfile suspendedProfile = new UserProfile(
        USER_ID, "user@zentro.test", "Suspended User", "+94770000000", false, "SUSPENDED", null, List.of("CUSTOMER"));
    when(jdbc.queryForObject(anyString(), any(RowMapper.class), eq(SUB_USER)))
        .thenReturn(suspendedProfile);

    UpdateUserProfileRequest request = new UpdateUserProfileRequest("New Name", "+94771111111");

    assertThatThrownBy(() -> userProfileService.update(jwt, request))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(ex -> {
          ResponseStatusException rse = (ResponseStatusException) ex;
          assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
          assertThat(rse.getReason()).contains("Account is SUSPENDED.");
        });
  }

  @Test
  @DisplayName("Disabled customer cannot apply for merchant application")
  void disabledCustomerCannotApplyMerchant() {
    Jwt jwt = mockJwt(SUB_USER, "CUSTOMER");
    UserProfile disabledProfile = new UserProfile(
        USER_ID, "user@zentro.test", "Disabled User", "+94770000000", false, "DISABLED", null, List.of("CUSTOMER"));
    when(jdbc.queryForObject(anyString(), any(RowMapper.class), eq(SUB_USER)))
        .thenReturn(disabledProfile);

    CreateMerchantApplicationRequest request = new CreateMerchantApplicationRequest(
        "Shop A", "REG-1234", "+94770000000");

    assertThatThrownBy(() -> merchantApplicationService.createOrReapply(jwt, request))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(ex -> {
          ResponseStatusException rse = (ResponseStatusException) ex;
          assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
          assertThat(rse.getReason()).contains("Account is DISABLED.");
        });
  }

  @Test
  @DisplayName("Disabled courier cannot provision courier profile")
  void disabledCourierCannotProvisionProfile() {
    Jwt jwt = mockJwt(SUB_USER, "COURIER");
    UserProfile disabledProfile = new UserProfile(
        USER_ID, "courier@zentro.test", "Disabled Courier", "+94770000000", false, "DISABLED", null, List.of("COURIER"));
    when(jdbc.queryForObject(anyString(), any(RowMapper.class), eq(SUB_USER)))
        .thenReturn(disabledProfile);

    assertThatThrownBy(() -> courierProfileService.provisionCourier(jwt))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(ex -> {
          ResponseStatusException rse = (ResponseStatusException) ex;
          assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
          assertThat(rse.getReason()).contains("Account is DISABLED.");
        });
  }

  @Test
  @DisplayName("Disabled dispatcher cannot perform admin user updates")
  void disabledDispatcherCannotPerformAdminUpdates() {
    Jwt jwt = mockJwt(SUB_DISPATCHER, "DISPATCHER");
    UserProfile disabledDispatcher = new UserProfile(
        DISPATCHER_ID, "disp@zentro.test", "Disabled Disp", "+94770000000", false, "DISABLED", null, List.of("DISPATCHER"));
    when(jdbc.queryForObject(anyString(), any(RowMapper.class), eq(SUB_DISPATCHER)))
        .thenReturn(disabledDispatcher);

    AdminUpdateUserRequest request = new AdminUpdateUserRequest("Target Name", "+94779999999", "ACTIVE");

    assertThatThrownBy(() -> userProfileService.adminUpdate(USER_ID, jwt, request))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(ex -> {
          ResponseStatusException rse = (ResponseStatusException) ex;
          assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
          assertThat(rse.getReason()).contains("Account is DISABLED.");
        });
  }

  @Test
  @DisplayName("Disabled dispatcher cannot review merchant applications")
  void disabledDispatcherCannotReviewMerchants() {
    Jwt jwt = mockJwt(SUB_DISPATCHER, "DISPATCHER");
    UserProfile disabledDispatcher = new UserProfile(
        DISPATCHER_ID, "disp@zentro.test", "Disabled Disp", "+94770000000", false, "DISABLED", null, List.of("DISPATCHER"));
    when(jdbc.queryForObject(anyString(), any(RowMapper.class), eq(SUB_DISPATCHER)))
        .thenReturn(disabledDispatcher);

    ReviewMerchantApplicationRequest request = new ReviewMerchantApplicationRequest(MerchantDecision.APPROVED, null);

    assertThatThrownBy(() -> merchantApplicationService.review(UUID.randomUUID(), jwt, request))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(ex -> {
          ResponseStatusException rse = (ResponseStatusException) ex;
          assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
          assertThat(rse.getReason()).contains("Account is DISABLED.");
        });
  }

  @Test
  @DisplayName("gRPC GetMerchantStatus returns SUSPENDED when underlying user account is DISABLED/SUSPENDED")
  void grpcGetMerchantStatusReturnsSuspendedWhenUserAccountNotActive() {
    when(jdbc.queryForObject(anyString(), eq(String.class), eq("merchant-disabled-user")))
        .thenReturn("SUSPENDED");

    AtomicReference<MerchantStatusResponse> responseRef = new AtomicReference<>();
    grpcService.getMerchantStatus(
        GetMerchantStatusRequest.newBuilder().setMerchantId("merchant-disabled-user").build(),
        new StreamObserver<>() {
          @Override public void onNext(MerchantStatusResponse val) { responseRef.set(val); }
          @Override public void onError(Throwable t) {}
          @Override public void onCompleted() {}
        });

    assertThat(responseRef.get()).isNotNull();
    assertThat(responseRef.get().getStatus()).isEqualTo("SUSPENDED");
  }

  @Test
  @DisplayName("gRPC GetCourierProfile returns active=false when courier user account is not ACTIVE")
  void grpcGetCourierProfileReturnsInactiveWhenAccountNotActive() {
    UUID courierUserId = UUID.randomUUID();
    UUID profileId = UUID.randomUUID();
    CourierProfile disabledCourier = new CourierProfile(
        profileId, courierUserId, "c@zentro.test", "Courier", "+94770000000",
        false, "DISABLED", "ZONE-A", "BIKE", null, null);

    when(jdbc.queryForObject(anyString(), any(RowMapper.class), eq(courierUserId), eq(courierUserId)))
        .thenReturn(disabledCourier);

    AtomicReference<CourierProfileResponse> responseRef = new AtomicReference<>();
    grpcService.getCourierProfile(
        GetCourierProfileRequest.newBuilder().setCourierId(courierUserId.toString()).build(),
        new StreamObserver<>() {
          @Override public void onNext(CourierProfileResponse val) { responseRef.set(val); }
          @Override public void onError(Throwable t) {}
          @Override public void onCompleted() {}
        });

    assertThat(responseRef.get()).isNotNull();
    assertThat(responseRef.get().getActive()).isFalse();
  }
}
