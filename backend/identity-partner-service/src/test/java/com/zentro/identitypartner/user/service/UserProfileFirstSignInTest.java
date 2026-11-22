package com.zentro.identitypartner.user.service;

import static com.zentro.identitypartner.user.service.UserProfileService.EMAIL_LOCK_SQL;
import static com.zentro.identitypartner.user.service.UserProfileService.EMAIL_OWNED_ELSEWHERE_SQL;
import static com.zentro.identitypartner.user.service.UserProfileService.HAS_PROFILE_SQL;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zentro.identitypartner.user.dto.UserProfile;
import com.zentro.identitypartner.user.exception.AccountIdentityConflictException;
import com.zentro.identitypartner.user.repository.UserAdminAuditRepository;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.oauth2.jwt.Jwt;

/** First sign-in must never create a second business profile for an email owned by another identity. */
@ExtendWith(MockitoExtension.class)
class UserProfileFirstSignInTest {
  private static final List<Map<String, Object>> ROW = List.of(Map.of("?column?", 1));

  @Mock private JdbcTemplate jdbc;
  @Mock private UserAdminAuditRepository audit;
  private UserProfileService service;

  @BeforeEach
  void setUp() {
    service = new UserProfileService(jdbc, audit);
  }

  private Jwt jwt(String subject, String email) {
    Jwt jwt = mock(Jwt.class);
    lenient().when(jwt.getSubject()).thenReturn(subject);
    lenient().when(jwt.getClaimAsString("email")).thenReturn(email);
    lenient().when(jwt.getClaimAsString("name")).thenReturn("Person");
    lenient().when(jwt.getClaims()).thenReturn(Map.of("realm_access", Map.of("roles", List.of("CUSTOMER"))));
    return jwt;
  }

  @SuppressWarnings("unchecked")
  private void profileLoads(String subject) {
    lenient().when(jdbc.queryForObject(anyString(), any(RowMapper.class), eq(subject))).thenReturn(mock(UserProfile.class));
  }

  @Test
  void refusesNewIdentityWhoseEmailBelongsToAnotherProfileAndCreatesNothing() {
    lenient().when(jdbc.queryForList(EMAIL_OWNED_ELSEWHERE_SQL, "person@gmail.com", "new-google-subject")).thenReturn(ROW);

    assertThatThrownBy(() -> service.current(jwt("new-google-subject", "Person@Gmail.com")))
        .isInstanceOf(AccountIdentityConflictException.class)
        .hasFieldOrPropertyWithValue("status", HttpStatus.CONFLICT);
    verify(jdbc, never()).update(contains("INSERT INTO identity.app_user"), any(Object[].class));
  }

  @Test
  void existingIdentityKeepsWorkingEvenWhenItsEmailIsAHistoricalDuplicate() {
    lenient().when(jdbc.queryForList(HAS_PROFILE_SQL, "existing-subject")).thenReturn(ROW);
    profileLoads("existing-subject");

    service.current(jwt("existing-subject", "dup@zentro.test"));

    verify(jdbc, never()).queryForList(eq(EMAIL_OWNED_ELSEWHERE_SQL), any(Object[].class));
    verify(jdbc).update(contains("INSERT INTO identity.app_user"), any(Object[].class));
  }

  @Test
  void newIdentityWithUnusedEmailIsProvisioned() {
    profileLoads("fresh-subject");

    service.current(jwt("fresh-subject", "fresh@gmail.com"));

    verify(jdbc).queryForList(EMAIL_OWNED_ELSEWHERE_SQL, "fresh@gmail.com", "fresh-subject");
    verify(jdbc).update(contains("INSERT INTO identity.app_user"), any(Object[].class));
  }

  @Test
  void serializesFirstSignInPerNormalizedEmail() {
    profileLoads("fresh-subject");

    service.current(jwt("fresh-subject", "  Mixed@GMAIL.com "));

    verify(jdbc).queryForList(EMAIL_LOCK_SQL, "identity.app_user.email:mixed@gmail.com");
  }

  @Test
  void tokenWithoutEmailSkipsTheEmailGuard() {
    profileLoads("no-email-subject");

    service.current(jwt("no-email-subject", null));

    verify(jdbc, never()).queryForList(eq(EMAIL_LOCK_SQL), any(Object[].class));
    verify(jdbc).update(contains("INSERT INTO identity.app_user"), any(Object[].class));
  }
}
