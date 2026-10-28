package com.zentro.identitypartner.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class UserProfileServiceTest {

  @Mock private JdbcTemplate jdbc;
  private UserProfileService service;

  private static final UUID USER_A_ID = UUID.randomUUID();
  private static final UUID USER_B_ID = UUID.randomUUID();
  private static final String SUB_A = "subject-a";
  private static final String SUB_B = "subject-b";
  private static final String SUB_DISPATCHER = "subject-dispatcher";

  @BeforeEach
  void setUp() {
    service = new UserProfileService(jdbc);
  }

  private Jwt mockJwt(String subject, String role) {
    Jwt jwt = mock(Jwt.class);
    when(jwt.getSubject()).thenReturn(subject);
    when(jwt.getClaimAsString("email")).thenReturn(subject + "@zentro.test");
    when(jwt.getClaimAsString("name")).thenReturn("User " + subject);
    when(jwt.getClaimAsString("phone_number")).thenReturn("0771234567");
    when(jwt.getClaims()).thenReturn(Map.of("realm_access", Map.of("roles", List.of(role))));
    return jwt;
  }

  @Test
  void getByIdThrowsNotFoundWhenUserDoesNotExist() {
    when(jdbc.queryForObject(anyString(), any(RowMapper.class), eq(USER_A_ID)))
        .thenThrow(new EmptyResultDataAccessException(1));

    assertThatThrownBy(() -> service.getById(USER_A_ID))
        .isInstanceOf(ResponseStatusException.class)
        .hasFieldOrPropertyWithValue("status", HttpStatus.NOT_FOUND);
  }

  @Test
  void getByIdForCallerThrowsAccessDeniedOnCrossUserRead() {
    Jwt jwtCustomer = mockJwt(SUB_A, "CUSTOMER");

    // Mock caller profile retrieval
    UserProfile callerProfile = new UserProfile(USER_A_ID, "a@zentro.com", "User A", "0771112222", true, "ACTIVE", null, List.of("CUSTOMER"));
    when(jdbc.queryForObject(anyString(), any(RowMapper.class), eq(SUB_A)))
        .thenReturn(callerProfile);

    // Caller A tries to read User B's profile
    assertThatThrownBy(() -> service.getByIdForCaller(jwtCustomer, USER_B_ID))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessageContaining("cannot view another user's profile");
  }

  @Test
  void updateByIdForCallerThrowsAccessDeniedOnCrossUserUpdate() {
    Jwt jwtCustomer = mockJwt(SUB_A, "CUSTOMER");

    UserProfile callerProfile = new UserProfile(USER_A_ID, "a@zentro.com", "User A", "0771112222", true, "ACTIVE", null, List.of("CUSTOMER"));
    when(jdbc.queryForObject(anyString(), any(RowMapper.class), eq(SUB_A)))
        .thenReturn(callerProfile);

    UpdateUserProfileRequest request = new UpdateUserProfileRequest("Hacked", "0770000000");

    // Caller A tries to update User B's profile
    assertThatThrownBy(() -> service.updateByIdForCaller(jwtCustomer, USER_B_ID, request))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessageContaining("cannot update another user's profile");
  }

  @Test
  void adminUpdatePreventsSelfLockout() {
    Jwt jwtDispatcher = mockJwt(SUB_DISPATCHER, "DISPATCHER");

    // Dispatcher's own profile has USER_A_ID
    UserProfile dispatcherProfile = new UserProfile(USER_A_ID, "disp@zentro.com", "Dispatcher", "0771112222", true, "ACTIVE", null, List.of("DISPATCHER"));
    when(jdbc.queryForObject(anyString(), any(RowMapper.class), eq(SUB_DISPATCHER)))
        .thenReturn(dispatcherProfile);

    AdminUpdateUserRequest disableSelf = new AdminUpdateUserRequest(null, null, "DISABLED");

    assertThatThrownBy(() -> service.adminUpdate(USER_A_ID, jwtDispatcher, disableSelf))
        .isInstanceOf(ResponseStatusException.class)
        .hasFieldOrPropertyWithValue("status", HttpStatus.BAD_REQUEST)
        .hasMessageContaining("cannot disable or suspend their own account");
  }

  @Test
  void adminUpdateRejectsInvalidAccountStatus() {
    Jwt jwtDispatcher = mockJwt(SUB_DISPATCHER, "DISPATCHER");

    UserProfile dispatcherProfile = new UserProfile(USER_A_ID, "disp@zentro.com", "Dispatcher", "0771112222", true, "ACTIVE", null, List.of("DISPATCHER"));
    when(jdbc.queryForObject(anyString(), any(RowMapper.class), eq(SUB_DISPATCHER)))
        .thenReturn(dispatcherProfile);

    // Target user B exists
    UserProfile targetProfile = new UserProfile(USER_B_ID, "b@zentro.com", "User B", "0772223333", true, "ACTIVE", null, List.of("CUSTOMER"));
    when(jdbc.queryForObject(anyString(), any(RowMapper.class), eq(USER_B_ID)))
        .thenReturn(targetProfile);

    AdminUpdateUserRequest invalidStatus = new AdminUpdateUserRequest(null, null, "INVALID_STATUS");

    assertThatThrownBy(() -> service.adminUpdate(USER_B_ID, jwtDispatcher, invalidStatus))
        .isInstanceOf(ResponseStatusException.class)
        .hasFieldOrPropertyWithValue("status", HttpStatus.BAD_REQUEST)
        .hasMessageContaining("Invalid account status");
  }

  @Test
  void adminUpdateSuccessfullyUpdatesUser() {
    Jwt jwtDispatcher = mockJwt(SUB_DISPATCHER, "DISPATCHER");

    UserProfile dispatcherProfile = new UserProfile(USER_A_ID, "disp@zentro.com", "Dispatcher", "0771112222", true, "ACTIVE", null, List.of("DISPATCHER"));
    when(jdbc.queryForObject(anyString(), any(RowMapper.class), eq(SUB_DISPATCHER)))
        .thenReturn(dispatcherProfile);

    UserProfile targetProfileBefore = new UserProfile(USER_B_ID, "b@zentro.com", "User B", "0772223333", true, "ACTIVE", null, List.of("CUSTOMER"));
    UserProfile targetProfileAfter = new UserProfile(USER_B_ID, "b@zentro.com", "User B Updated", "0779998888", false, "DISABLED", null, List.of("CUSTOMER"));

    when(jdbc.queryForObject(anyString(), any(RowMapper.class), eq(USER_B_ID)))
        .thenReturn(targetProfileBefore)
        .thenReturn(targetProfileAfter);

    AdminUpdateUserRequest updateRequest = new AdminUpdateUserRequest("User B Updated", "0779998888", "DISABLED");

    UserProfile result = service.adminUpdate(USER_B_ID, jwtDispatcher, updateRequest);

    assertThat(result.name()).isEqualTo("User B Updated");
    assertThat(result.accountStatus()).isEqualTo("DISABLED");
    assertThat(result.enabled()).isFalse();

    verify(jdbc).update(anyString(), eq("User B Updated"), eq("0779998888"), eq("DISABLED"), eq(USER_B_ID));
  }
}

