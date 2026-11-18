package com.zentro.dispatchcourier.dashboard;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.sql.ResultSet;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.annotation.Transactional;

class CourierAssignmentSnapshotRepositoryTest {
  @Test @SuppressWarnings("unchecked") void oneSelectScopesBothCanonicalIdsAndMapsProofWithoutWrites() throws Exception {
    var jdbc = mock(JdbcTemplate.class); var row = mock(ResultSet.class);
    UUID user = UUID.randomUUID(), profile = UUID.randomUUID(), parcel = UUID.randomUUID(), proof = UUID.randomUUID();
    var time = OffsetDateTime.parse("2026-11-15T12:00:00Z");
    when(row.getObject("parcel_id", UUID.class)).thenReturn(parcel);
    when(row.getObject("assigned_at", OffsetDateTime.class)).thenReturn(time);
    when(row.getObject("proof_id", UUID.class)).thenReturn(proof);
    when(row.getString("completion_state")).thenReturn("PENDING");
    when(jdbc.query(anyString(), any(RowMapper.class), eq(user), eq(profile)))
        .thenAnswer(i -> List.of(((RowMapper<?>) i.getArgument(1)).mapRow(row, 0)));
    var result = new CourierAssignmentSnapshotRepository(jdbc).snapshot(user, profile);
    assertThat(result).containsExactly(new CourierAssignmentSnapshotRepository.Assignment(
        parcel, time.toInstant(), "ASSIGNED", proof, "PENDING", null));
    var sql = ArgumentCaptor.forClass(String.class);
    verify(jdbc).query(sql.capture(), any(RowMapper.class), eq(user), eq(profile));
    assertThat(sql.getValue()).startsWith("SELECT").contains("a.courier_user_id = ? AND a.courier_profile_id = ?")
        .doesNotContain("INSERT", "UPDATE", "DELETE", "assignment_audit");
    verifyNoMoreInteractions(jdbc);
    assertThat(CourierAssignmentSnapshotRepository.class.getMethod("snapshot", UUID.class, UUID.class)
        .getAnnotation(Transactional.class).readOnly()).isTrue();
  }
  @Test void absentCancelledOrReassignedRowsProduceEmptySnapshot() {
    var jdbc = mock(JdbcTemplate.class); UUID user = UUID.randomUUID(), profile = UUID.randomUUID();
    when(jdbc.query(anyString(), any(RowMapper.class), eq(user), eq(profile))).thenReturn(List.of());
    assertThat(new CourierAssignmentSnapshotRepository(jdbc).snapshot(user, profile)).isEmpty();
    verify(jdbc).query(anyString(), any(RowMapper.class), eq(user), eq(profile)); verifyNoMoreInteractions(jdbc);
  }
}
