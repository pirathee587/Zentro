package com.zentro.identitypartner.courier.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.sql.ResultSet;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.annotation.Transactional;

class CourierIdentityReadServiceTest {
  private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
  private final ResultSet row = mock(ResultSet.class);
  private final CourierIdentityReadService service = new CourierIdentityReadService(jdbc);
  private final UUID userId = UUID.randomUUID();
  private final UUID profileId = UUID.randomUUID();

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setup() throws Exception {
    when(row.getObject("user_id", UUID.class)).thenReturn(userId);
    when(row.getObject("profile_id", UUID.class)).thenReturn(profileId);
    when(row.getObject("is_active", Boolean.class)).thenReturn(true);
    when(row.getString("account_status")).thenReturn("ACTIVE");
    when(jdbc.queryForObject(anyString(), any(RowMapper.class), eq("courier-subject")))
        .thenAnswer(invocation -> ((RowMapper<?>) invocation.getArgument(1)).mapRow(row, 0));
  }

  @Test void resolvesDistinctCanonicalIdsUsingOneParameterizedSelect() {
    assertThat(service.resolve("courier-subject"))
        .isEqualTo(new CourierIdentityReadService.Identity(userId, profileId));
    var sql = ArgumentCaptor.forClass(String.class);
    verify(jdbc).queryForObject(sql.capture(), any(RowMapper.class), eq("courier-subject"));
    assertThat(sql.getValue()).startsWith("SELECT ").contains("LEFT JOIN", "WHERE u.keycloak_subject = ?")
        .doesNotContain("INSERT", "UPDATE", "DELETE", "FOR UPDATE");
    verifyNoMoreInteractions(jdbc);
  }

  @Test void missingUserDoesNotProvision() {
    when(jdbc.queryForObject(anyString(), any(RowMapper.class), eq("courier-subject")))
        .thenThrow(new EmptyResultDataAccessException(1));
    expectStatus(HttpStatus.NOT_FOUND);
  }

  @Test void missingProfileDoesNotProvision() throws Exception {
    when(row.getObject("profile_id", UUID.class)).thenReturn(null);
    expectStatus(HttpStatus.NOT_FOUND);
  }

  @Test void inactiveProfileIsDenied() throws Exception {
    when(row.getObject("is_active", Boolean.class)).thenReturn(false);
    expectStatus(HttpStatus.FORBIDDEN);
  }

  @ParameterizedTest @NullAndEmptySource @ValueSource(strings = {"DISABLED", "SUSPENDED", "UNKNOWN"})
  void ineligibleAccountIsDenied(String accountStatus) throws Exception {
    when(row.getString("account_status")).thenReturn(accountStatus);
    expectStatus(HttpStatus.FORBIDDEN);
  }

  @ParameterizedTest @NullAndEmptySource @ValueSource(strings = {" "})
  void missingVerifiedSubjectNeverQueriesStorage(String subject) {
    assertThatThrownBy(() -> service.resolve(subject)).isInstanceOfSatisfying(ResponseStatusException.class,
        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
    verifyNoInteractions(jdbc);
  }

  @Test void transactionIsReadOnly() throws Exception {
    assertThat(CourierIdentityReadService.class.getMethod("resolve", String.class)
        .getAnnotation(Transactional.class).readOnly()).isTrue();
  }

  private void expectStatus(HttpStatus expected) {
    assertThatThrownBy(() -> service.resolve("courier-subject")).isInstanceOfSatisfying(ResponseStatusException.class,
        error -> assertThat(error.getStatusCode()).isEqualTo(expected));
    verify(jdbc).queryForObject(anyString(), any(RowMapper.class), eq("courier-subject"));
    verifyNoMoreInteractions(jdbc);
  }
}
