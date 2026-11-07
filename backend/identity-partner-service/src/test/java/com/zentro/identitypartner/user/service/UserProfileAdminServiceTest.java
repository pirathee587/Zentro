package com.zentro.identitypartner.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.zentro.identitypartner.user.audit.UserAdminAuditRecord;
import com.zentro.identitypartner.user.audit.UserAdminAuditRecord.Action;
import com.zentro.identitypartner.user.dto.AdminUpdateUserRequest;
import com.zentro.identitypartner.user.dto.UpdateUserProfileRequest;
import com.zentro.identitypartner.user.dto.UserProfile;
import com.zentro.identitypartner.user.exception.InvalidUserInputException;
import com.zentro.identitypartner.user.exception.UserNotFoundException;
import com.zentro.identitypartner.user.repository.UserAdminAuditRepository;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.invocation.Invocation;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;

/** Dispatcher user-management rules in {@link UserProfileService} (ZD-042), with a mocked data layer. */
@ExtendWith(MockitoExtension.class)
class UserProfileAdminServiceTest {
  private static final UUID DISPATCHER_ID = UUID.randomUUID();
  private static final UUID TARGET_ID = UUID.randomUUID();
  private static final String DISPATCHER_SUB = "dispatcher-subject";

  @Mock private JdbcTemplate jdbc;
  @Mock private UserAdminAuditRepository audit;
  private UserProfileService service;

  @BeforeEach
  void setUp() {
    service = new UserProfileService(jdbc, audit);
  }

  private static Jwt jwt(String subject, String... roles) {
    Jwt jwt = mock(Jwt.class);
    lenient().when(jwt.getSubject()).thenReturn(subject);
    lenient().when(jwt.getClaims()).thenReturn(Map.of("realm_access", Map.of("roles", List.of(roles))));
    return jwt;
  }

  private static UserProfile profile(UUID id, String name, String phone, String status) {
    return new UserProfile(id, id + "@zentro.test", name, phone, "ACTIVE".equals(status), status, null, List.of());
  }

  private void actor(String status) {
    when(jdbc.queryForObject(anyString(), any(RowMapper.class), eq(DISPATCHER_SUB)))
        .thenReturn(profile(DISPATCHER_ID, "Dispatcher", "0771112222", status));
  }

  private void target(UserProfile before, UserProfile after) {
    when(jdbc.queryForObject(anyString(), any(RowMapper.class), eq(TARGET_ID))).thenReturn(before, after);
  }

  /** Parameters of every "UPDATE identity.app_user" call (the profile upsert in current() is an INSERT). */
  private List<List<Object>> userUpdates() {
    return mockingDetails(jdbc).getInvocations().stream()
        .filter(i -> i.getMethod().getName().equals("update"))
        .filter(i -> String.valueOf(i.getArguments()[0]).contains("UPDATE identity.app_user"))
        .map(UserProfileAdminServiceTest::params)
        .toList();
  }

  private static List<Object> params(Invocation invocation) {
    Object[] args = invocation.getArguments();
    return Arrays.asList(args).subList(1, args.length);
  }

  private UserAdminAuditRecord audited() {
    ArgumentCaptor<UserAdminAuditRecord> record = ArgumentCaptor.forClass(UserAdminAuditRecord.class);
    verify(audit).insert(record.capture());
    return record.getValue();
  }

  @Test
  @DisplayName("Only DISPATCHER may call adminUpdate, even when the controller is bypassed")
  void serviceLevelRoleCheck() {
    for (String role : List.of("CUSTOMER", "MERCHANT", "COURIER")) {
      Jwt caller = jwt("someone", role);
      assertThatThrownBy(() -> service.adminUpdate(TARGET_ID, caller, new AdminUpdateUserRequest("X", null, null)))
          .isInstanceOf(AccessDeniedException.class);
    }
    verifyNoInteractions(jdbc, audit);
  }

  @Test
  @DisplayName("An update with no name, phone or status is rejected before any database work")
  void emptyUpdate() {
    Jwt dispatcher = jwt(DISPATCHER_SUB, "DISPATCHER");

    assertThatThrownBy(() -> service.adminUpdate(TARGET_ID, dispatcher, new AdminUpdateUserRequest(null, null, null, "why")))
        .isInstanceOfSatisfying(InvalidUserInputException.class, e -> assertThat(e.code()).isEqualTo("EMPTY_UPDATE"));
    verifyNoInteractions(jdbc, audit);
  }

  @Test
  @DisplayName("Disabling a user writes one STATUS_CHANGED audit row with field names, statuses and reason only")
  void statusChangeIsAudited() {
    actor("ACTIVE");
    target(profile(TARGET_ID, "Kamal Silva", "0772223333", "ACTIVE"),
        profile(TARGET_ID, "Kamal S", "0772223333", "DISABLED"));

    UserProfile result = service.adminUpdate(TARGET_ID, jwt(DISPATCHER_SUB, "DISPATCHER"),
        new AdminUpdateUserRequest(" Kamal S ", "0772223333", "disabled", " Repeated fraud reports "));

    assertThat(result.accountStatus()).isEqualTo("DISABLED");
    assertThat(userUpdates()).containsExactly(List.of("Kamal S", "0772223333", "DISABLED", TARGET_ID));
    UserAdminAuditRecord record = audited();
    assertThat(record.action()).isEqualTo(Action.STATUS_CHANGED);
    assertThat(record.changedFields()).containsExactly("name", "accountStatus");
    assertThat(record.oldStatus()).isEqualTo("ACTIVE");
    assertThat(record.newStatus()).isEqualTo("DISABLED");
    assertThat(record.reason()).isEqualTo("Repeated fraud reports");
    assertThat(record.actorUserId()).isEqualTo(DISPATCHER_ID);
    assertThat(record.actorSubject()).isEqualTo(DISPATCHER_SUB);
    assertThat(record.targetUserId()).isEqualTo(TARGET_ID);
    assertThat(record.toString()).doesNotContain("Kamal", "0772223333", "fraud", DISPATCHER_SUB);
  }

  @Test
  @DisplayName("A phone-only change is PROFILE_UPDATED and the phone is stored without spaces or dashes")
  void profileChangeIsAudited() {
    actor("ACTIVE");
    target(profile(TARGET_ID, "Kamal", "0772223333", "ACTIVE"), profile(TARGET_ID, "Kamal", "+94771234567", "ACTIVE"));

    service.adminUpdate(TARGET_ID, jwt(DISPATCHER_SUB, "DISPATCHER"),
        new AdminUpdateUserRequest("Kamal", "+94 77-123-4567", "ACTIVE"));

    assertThat(userUpdates()).containsExactly(List.of("Kamal", "+94771234567", "ACTIVE", TARGET_ID));
    UserAdminAuditRecord record = audited();
    assertThat(record.action()).isEqualTo(Action.PROFILE_UPDATED);
    assertThat(record.changedFields()).containsExactly("phone");
    assertThat(record.oldStatus()).isEqualTo("ACTIVE");
    assertThat(record.newStatus()).isEqualTo("ACTIVE");
  }

  @Test
  @DisplayName("Sending the stored values again changes nothing, writes nothing and returns the user")
  void noOpUpdate() {
    actor("ACTIVE");
    UserProfile stored = profile(TARGET_ID, "Kamal", "0772223333", "DISABLED");
    when(jdbc.queryForObject(anyString(), any(RowMapper.class), eq(TARGET_ID))).thenReturn(stored);

    UserProfile result = service.adminUpdate(TARGET_ID, jwt(DISPATCHER_SUB, "DISPATCHER"),
        new AdminUpdateUserRequest("Kamal", "0772223333", "disabled", "again"));

    assertThat(result).isSameAs(stored);
    assertThat(userUpdates()).isEmpty();
    verify(audit, never()).insert(any());
  }

  @Test
  @DisplayName("An unchanged older phone format is not re-validated, so the user can still be disabled")
  void unchangedLegacyPhoneIsKept() {
    actor("ACTIVE");
    target(profile(TARGET_ID, "Legacy", "+1 555 0001", "ACTIVE"), profile(TARGET_ID, "Legacy", "+1 555 0001", "DISABLED"));

    service.adminUpdate(TARGET_ID, jwt(DISPATCHER_SUB, "DISPATCHER"),
        new AdminUpdateUserRequest("Legacy", "+1 555 0001", "DISABLED"));

    assertThat(userUpdates()).containsExactly(List.of("Legacy", "+1 555 0001", "DISABLED", TARGET_ID));
    assertThat(audited().changedFields()).containsExactly("accountStatus");
  }

  @Test
  @DisplayName("A changed phone in another format is rejected and nothing is written")
  void changedInvalidPhone() {
    actor("ACTIVE");
    when(jdbc.queryForObject(anyString(), any(RowMapper.class), eq(TARGET_ID)))
        .thenReturn(profile(TARGET_ID, "Kamal", "0772223333", "ACTIVE"));

    assertThatThrownBy(() -> service.adminUpdate(TARGET_ID, jwt(DISPATCHER_SUB, "DISPATCHER"),
        new AdminUpdateUserRequest(null, "+1 555 0001", null)))
        .isInstanceOfSatisfying(InvalidUserInputException.class, e -> assertThat(e.field()).isEqualTo("phone"));
    assertThat(userUpdates()).isEmpty();
    verify(audit, never()).insert(any());
  }

  @Test
  @DisplayName("A changed name with a control character is rejected")
  void changedInvalidName() {
    actor("ACTIVE");
    when(jdbc.queryForObject(anyString(), any(RowMapper.class), eq(TARGET_ID)))
        .thenReturn(profile(TARGET_ID, "Kamal", "0772223333", "ACTIVE"));

    assertThatThrownBy(() -> service.adminUpdate(TARGET_ID, jwt(DISPATCHER_SUB, "DISPATCHER"),
        new AdminUpdateUserRequest("Kamal\u0000", null, null)))
        .isInstanceOfSatisfying(InvalidUserInputException.class, e -> assertThat(e.field()).isEqualTo("name"));
    assertThat(userUpdates()).isEmpty();
  }

  @Test
  @DisplayName("A dispatcher cannot re-activate their own suspended account either")
  void selfReactivationRejected() {
    actor("SUSPENDED");

    assertThatThrownBy(() -> service.adminUpdate(DISPATCHER_ID, jwt(DISPATCHER_SUB, "DISPATCHER"),
        new AdminUpdateUserRequest(null, null, "ACTIVE")))
        .isInstanceOfSatisfying(InvalidUserInputException.class, e -> {
          assertThat(e.code()).isEqualTo("SELF_STATUS_CHANGE");
          assertThat(e.getMessage()).isEqualTo("Dispatchers cannot change their own account status.");
        });
    assertThat(userUpdates()).isEmpty();
    verifyNoInteractions(audit);
  }

  @Test
  @DisplayName("A dispatcher sending their own current status (as the Users page does) may still edit their name")
  void selfSameStatusAllowed() {
    actor("ACTIVE");
    when(jdbc.queryForObject(anyString(), any(RowMapper.class), eq(DISPATCHER_ID)))
        .thenReturn(profile(DISPATCHER_ID, "Dispatcher", "0771112222", "ACTIVE"),
            profile(DISPATCHER_ID, "Dispatcher One", "0771112222", "ACTIVE"));

    service.adminUpdate(DISPATCHER_ID, jwt(DISPATCHER_SUB, "DISPATCHER"),
        new AdminUpdateUserRequest("Dispatcher One", "0771112222", "ACTIVE"));

    assertThat(audited().changedFields()).containsExactly("name");
  }

  @Test
  @DisplayName("An unknown target user is 404 without the id in the message")
  void unknownTarget() {
    actor("ACTIVE");
    when(jdbc.queryForObject(anyString(), any(RowMapper.class), eq(TARGET_ID)))
        .thenThrow(new EmptyResultDataAccessException(1));

    assertThatThrownBy(() -> service.adminUpdate(TARGET_ID, jwt(DISPATCHER_SUB, "DISPATCHER"),
        new AdminUpdateUserRequest("Kamal", null, null)))
        .isInstanceOf(UserNotFoundException.class)
        .hasMessageNotContaining(TARGET_ID.toString());
    verifyNoInteractions(audit);
  }

  @Test
  @DisplayName("A too long reason is rejected")
  void longReason() {
    actor("ACTIVE");
    when(jdbc.queryForObject(anyString(), any(RowMapper.class), eq(TARGET_ID)))
        .thenReturn(profile(TARGET_ID, "Kamal", "0772223333", "ACTIVE"));

    assertThatThrownBy(() -> service.adminUpdate(TARGET_ID, jwt(DISPATCHER_SUB, "DISPATCHER"),
        new AdminUpdateUserRequest(null, null, "DISABLED", "r".repeat(501))))
        .isInstanceOfSatisfying(InvalidUserInputException.class, e -> assertThat(e.field()).isEqualTo("reason"));
    verifyNoInteractions(audit);
  }

  @Test
  @DisplayName("A dispatcher changing another user's profile via /api/v1/users/{id} is validated and audited (D5)")
  void crossUserProfilePathIsAudited() {
    actor("ACTIVE");
    target(profile(TARGET_ID, "Kamal", "0772223333", "ACTIVE"), profile(TARGET_ID, "Kamal P", "0772223333", "ACTIVE"));

    service.updateByIdForCaller(jwt(DISPATCHER_SUB, "DISPATCHER"), TARGET_ID, new UpdateUserProfileRequest("Kamal P", null));

    assertThat(userUpdates()).containsExactly(List.of("Kamal P", "0772223333", "ACTIVE", TARGET_ID));
    UserAdminAuditRecord record = audited();
    assertThat(record.action()).isEqualTo(Action.PROFILE_UPDATED);
    assertThat(record.changedFields()).containsExactly("name");
    assertThat(record.reason()).isNull();
  }

  @Test
  @DisplayName("The cross-user profile path rejects an invalid changed phone")
  void crossUserProfilePathValidates() {
    actor("ACTIVE");
    when(jdbc.queryForObject(anyString(), any(RowMapper.class), eq(TARGET_ID)))
        .thenReturn(profile(TARGET_ID, "Kamal", "0772223333", "ACTIVE"));

    assertThatThrownBy(() -> service.updateByIdForCaller(jwt(DISPATCHER_SUB, "DISPATCHER"), TARGET_ID,
        new UpdateUserProfileRequest(null, "12345")))
        .isInstanceOf(InvalidUserInputException.class);
    verifyNoInteractions(audit);
  }

  @Test
  @DisplayName("The list rejects an unknown status and escapes LIKE wildcards in search")
  void listFilters() {
    assertThatThrownBy(() -> service.listUsers("ARCHIVED", null)).isInstanceOf(InvalidUserInputException.class);
    assertThatThrownBy(() -> service.listUsers(null, "x".repeat(101))).isInstanceOf(InvalidUserInputException.class);
    verifyNoInteractions(jdbc);

    service.listUsers("suspended", "50%_Off");

    Invocation query = mockingDetails(jdbc).getInvocations().iterator().next();
    assertThat(String.valueOf(query.getArguments()[0])).contains("ESCAPE");
    assertThat(params(query)).contains("SUSPENDED", "%50\\%\\_off%");
  }
}
