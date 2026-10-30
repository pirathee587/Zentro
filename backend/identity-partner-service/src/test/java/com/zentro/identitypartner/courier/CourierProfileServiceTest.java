package com.zentro.identitypartner.courier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zentro.identitypartner.user.UserProfile;
import com.zentro.identitypartner.user.UserProfileService;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

class CourierProfileServiceTest {

  private JdbcTemplate jdbc;
  private UserProfileService profiles;
  private CourierProfileService service;

  private static final UUID USER_ID = UUID.randomUUID();
  private static final UUID PROFILE_ID = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    jdbc = mock(JdbcTemplate.class);
    profiles = mock(UserProfileService.class);
    service = new CourierProfileService(jdbc, profiles);
  }

  @Test
  void getByJwtCreatesAndReturnsProfileForCourierRole() {
    Jwt jwt = mock(Jwt.class);
    when(jwt.getClaims()).thenReturn(Map.of("realm_access", Map.of("roles", List.of("COURIER"))));

    UserProfile user = new UserProfile(USER_ID, "courier@zentro.lk", "Kamal P", "+94771234567", true, "ACTIVE", null, List.of("COURIER"));
    when(profiles.current(jwt)).thenReturn(user);
    when(profiles.getById(USER_ID)).thenReturn(user);

    OffsetDateTime now = OffsetDateTime.now();
    CourierProfile expected = new CourierProfile(PROFILE_ID, USER_ID, "courier@zentro.lk", "Kamal P", "+94771234567", true, "ACTIVE", "Colombo", "MOTORCYCLE", now, now);
    when(jdbc.queryForObject(anyString(), any(RowMapper.class), eq(USER_ID), eq(USER_ID))).thenReturn(expected);

    CourierProfile result = service.getByJwt(jwt);

    assertThat(result).isNotNull();
    assertThat(result.userId()).isEqualTo(USER_ID);
    assertThat(result.active()).isTrue();
    verify(jdbc).update(anyString(), eq(USER_ID));
  }

  @Test
  void getByJwtRejectsNonCourier() {
    Jwt jwt = mock(Jwt.class);
    when(jwt.getClaims()).thenReturn(Map.of("realm_access", Map.of("roles", List.of("CUSTOMER"))));

    assertThatThrownBy(() -> service.getByJwt(jwt))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
  }

  @Test
  void getByCourierIdThrows404WhenNotFound() {
    when(jdbc.queryForObject(anyString(), any(RowMapper.class), eq(PROFILE_ID), eq(PROFILE_ID)))
        .thenThrow(new EmptyResultDataAccessException(1));

    assertThatThrownBy(() -> service.getByCourierId(PROFILE_ID))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
  }

  @Test
  void getByCourierIdThrowsBadRequestOnNull() {
    assertThatThrownBy(() -> service.getByCourierId(null))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
  }

  @Test
  void isCourierActiveReturnsFalseWhenInactiveOrDisabled() {
    OffsetDateTime now = OffsetDateTime.now();
    CourierProfile disabledProfile = new CourierProfile(PROFILE_ID, USER_ID, "courier@zentro.lk", "Kamal", "+9477", true, "DISABLED", null, null, now, now);
    when(jdbc.queryForObject(anyString(), any(RowMapper.class), eq(PROFILE_ID), eq(PROFILE_ID))).thenReturn(disabledProfile);

    assertThat(service.isCourierActive(PROFILE_ID)).isFalse();
  }

  @Test
  void isCourierActiveReturnsFalseWhenNotFound() {
    when(jdbc.queryForObject(anyString(), any(RowMapper.class), eq(PROFILE_ID), eq(PROFILE_ID)))
        .thenThrow(new EmptyResultDataAccessException(1));

    assertThat(service.isCourierActive(PROFILE_ID)).isFalse();
  }

  @Test
  void isCourierActiveReturnsTrueWhenActiveAndAccountActive() {
    OffsetDateTime now = OffsetDateTime.now();
    CourierProfile activeProfile = new CourierProfile(PROFILE_ID, USER_ID, "courier@zentro.lk", "Kamal", "+9477", true, "ACTIVE", "Zone 1", "VAN", now, now);
    when(jdbc.queryForObject(anyString(), any(RowMapper.class), eq(PROFILE_ID), eq(PROFILE_ID))).thenReturn(activeProfile);

    assertThat(service.isCourierActive(PROFILE_ID)).isTrue();
  }

  @Test
  void getCourierProfileForGrpcReturnsSafeContract() {
    OffsetDateTime now = OffsetDateTime.now();
    CourierProfile activeProfile = new CourierProfile(PROFILE_ID, USER_ID, "courier@zentro.lk", "Kamal", "+9477", true, "ACTIVE", "Zone 1", "VAN", now, now);
    when(jdbc.queryForObject(anyString(), any(RowMapper.class), eq(PROFILE_ID), eq(PROFILE_ID))).thenReturn(activeProfile);

    CourierGrpcResponse grpcResponse = service.getCourierProfileForGrpc(PROFILE_ID.toString());

    assertThat(grpcResponse.courierId()).isEqualTo(PROFILE_ID.toString());
    assertThat(grpcResponse.active()).isTrue();
  }

  @Test
  void getCourierProfileForGrpcHandlesInvalidOrMissingCourierGracefully() {
    CourierGrpcResponse invalid = service.getCourierProfileForGrpc("not-a-uuid");
    assertThat(invalid.active()).isFalse();

    CourierGrpcResponse empty = service.getCourierProfileForGrpc(null);
    assertThat(empty.active()).isFalse();
  }

  @Test
  void updateCourierProfileThrows404WhenNoCourierProfileExists() {
    when(jdbc.queryForObject(anyString(), any(RowMapper.class), eq(PROFILE_ID), eq(PROFILE_ID)))
        .thenThrow(new EmptyResultDataAccessException(1));

    UpdateCourierProfileRequest request = new UpdateCourierProfileRequest(false, "Colombo", "VAN", "New Name", "+94771234567");

    assertThatThrownBy(() -> service.updateCourierProfile(PROFILE_ID, request))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
  }

  @Test
  void updateCourierProfileSuccessfullyUpdatesExistingCourier() {
    OffsetDateTime now = OffsetDateTime.now();
    CourierProfile existing = new CourierProfile(PROFILE_ID, USER_ID, "courier@zentro.lk", "Kamal", "+9477", true, "ACTIVE", null, null, now, now);
    when(jdbc.queryForObject(anyString(), any(RowMapper.class), eq(PROFILE_ID), eq(PROFILE_ID))).thenReturn(existing);

    UpdateCourierProfileRequest request = new UpdateCourierProfileRequest(false, "Kandy", "VAN", "Kamal Updated", "+94779876543");
    CourierProfile updated = service.updateCourierProfile(PROFILE_ID, request);

    assertThat(updated).isNotNull();
    verify(jdbc).update(anyString(), eq(false), eq("Kandy"), eq("VAN"), eq(USER_ID));
    verify(jdbc).update(anyString(), eq("Kamal Updated"), eq("+94779876543"), eq(USER_ID));
  }

  @Test
  void provisionCourierSuccessfullyProvisionsForCourierRole() {
    Jwt jwt = mock(Jwt.class);
    when(jwt.getClaims()).thenReturn(Map.of("realm_access", Map.of("roles", List.of("COURIER"))));

    UserProfile user = new UserProfile(USER_ID, "courier@zentro.lk", "Kamal P", "+94771234567", true, "ACTIVE", null, List.of("COURIER"));
    when(profiles.current(jwt)).thenReturn(user);
    when(profiles.getById(USER_ID)).thenReturn(user);

    OffsetDateTime now = OffsetDateTime.now();
    CourierProfile expected = new CourierProfile(PROFILE_ID, USER_ID, "courier@zentro.lk", "Kamal P", "+94771234567", true, "ACTIVE", null, null, now, now);
    when(jdbc.queryForObject(anyString(), any(RowMapper.class), eq(USER_ID), eq(USER_ID))).thenReturn(expected);

    CourierProfile result = service.provisionCourier(jwt);

    assertThat(result).isNotNull();
    assertThat(result.userId()).isEqualTo(USER_ID);
    verify(jdbc).update(anyString(), eq(USER_ID));
  }

  @Test
  void provisionCourierRejectsCustomerRole() {
    Jwt jwt = mock(Jwt.class);
    when(jwt.getClaims()).thenReturn(Map.of("realm_access", Map.of("roles", List.of("CUSTOMER"))));

    assertThatThrownBy(() -> service.provisionCourier(jwt))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
  }

  @Test
  void provisionCourierRejectsMerchantRole() {
    Jwt jwt = mock(Jwt.class);
    when(jwt.getClaims()).thenReturn(Map.of("realm_access", Map.of("roles", List.of("MERCHANT"))));

    assertThatThrownBy(() -> service.provisionCourier(jwt))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
  }

  @Test
  void listCouriersBuildsQueryWithCorrectFilters() {
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());

    List<CourierProfile> results = service.listCouriers("ACTIVE", true, "Zone-1", "Kamal");
    assertThat(results).isNotNull();

    verify(jdbc).query(anyString(), any(RowMapper.class), any(Object[].class));
  }
}

