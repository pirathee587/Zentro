package com.zentro.parcelpricing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zentro.parcelpricing.domain.ParcelStatus;
import com.zentro.parcelpricing.domain.ParcelStatusChange;
import com.zentro.parcelpricing.dto.ParcelStatusResponse;
import com.zentro.parcelpricing.error.InvalidTransitionException;
import com.zentro.parcelpricing.error.ParcelNotFoundException;
import com.zentro.parcelpricing.error.StatusFieldException;
import com.zentro.parcelpricing.service.ParcelStatusRepository.LockedParcel;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.QueryTimeoutException;

@ExtendWith(MockitoExtension.class)
class ParcelStatusServiceTest {
  private static final UUID PARCEL_ID = UUID.fromString("3f1c2b7e-8a4d-4c1e-9b2a-5d6e7f8a9b0c");
  private static final String TRACKING_REF = "ZT-ABCDEFGH12";
  private static final String ACTOR = "dispatcher-subject-123";
  private static final Instant NOW = Instant.parse("2026-11-03T10:15:30.123456789Z");
  private static final OffsetDateTime NOW_MICROS = OffsetDateTime.parse("2026-11-03T10:15:30.123456Z");

  @Mock ParcelStatusRepository repository;
  final ObjectMapper objectMapper = new ObjectMapper();
  ParcelStatusService service;

  @BeforeEach
  void setUp() {
    service = new ParcelStatusService(repository, objectMapper, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private void parcelIs(ParcelStatus status, long version) {
    when(repository.lockForUpdate(PARCEL_ID)).thenReturn(Optional.of(new LockedParcel(PARCEL_ID, TRACKING_REF, status, version)));
  }

  private void verifyNothingWritten() {
    verify(repository, never()).updateStatus(any(), any(), anyLong(), any());
    verify(repository, never()).insertHistory(any());
    verify(repository, never()).insertOutboxEvent(any(), any(), anyString(), anyString(), any());
  }

  @Test
  @DisplayName("Allowed change updates the status once, writes one history row and one outbox event")
  void allowedChangeWritesStatusHistoryAndEvent() throws Exception {
    parcelIs(ParcelStatus.CREATED, 4);

    ParcelStatusResponse response = service.updateStatus(PARCEL_ID, ParcelStatus.PICKED_UP, null, "Gate 2",
        ACTOR, "dispatcher");

    assertThat(response).isEqualTo(new ParcelStatusResponse(PARCEL_ID, TRACKING_REF, ParcelStatus.CREATED,
        ParcelStatus.PICKED_UP, true, NOW_MICROS));
    verify(repository).updateStatus(PARCEL_ID, ParcelStatus.PICKED_UP, 4, NOW_MICROS);

    ArgumentCaptor<ParcelStatusChange> history = ArgumentCaptor.forClass(ParcelStatusChange.class);
    verify(repository).insertHistory(history.capture());
    assertThat(history.getValue()).isEqualTo(new ParcelStatusChange(PARCEL_ID, TRACKING_REF, ParcelStatus.CREATED,
        ParcelStatus.PICKED_UP, null, "Gate 2", ACTOR, "DISPATCHER", NOW_MICROS));

    ArgumentCaptor<UUID> eventId = ArgumentCaptor.forClass(UUID.class);
    ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
    verify(repository).insertOutboxEvent(eventId.capture(), eq(PARCEL_ID), eq("parcel.status.updated"),
        json.capture(), eq(NOW_MICROS));

    JsonNode event = objectMapper.readTree(json.getValue());
    assertThat(event.get("eventId").asText()).isEqualTo(eventId.getValue().toString());
    assertThat(event.get("eventType").asText()).isEqualTo("parcel.status.updated");
    assertThat(event.get("aggregateId").asText()).isEqualTo(PARCEL_ID.toString());
    assertThat(event.get("occurredAt").asText()).isEqualTo("2026-11-03T10:15:30.123456Z");
    assertThat(event.get("payload").get("trackingRef").asText()).isEqualTo(TRACKING_REF);
    assertThat(event.get("payload").get("previousStatus").asText()).isEqualTo("CREATED");
    assertThat(event.get("payload").get("status").asText()).isEqualTo("PICKED_UP");
    assertThat(event.get("payload").get("actorRole").asText()).isEqualTo("DISPATCHER");
    assertThat(event.get("payload").has("failureReason")).isFalse();
  }

  @Test
  @DisplayName("Event follows the envelope contract and holds no personal data, note or actor subject")
  void eventHasOnlyApprovedFields() throws Exception {
    parcelIs(ParcelStatus.PICKED_UP, 0);

    service.updateStatus(PARCEL_ID, ParcelStatus.FAILED, "Recipient not available",
        "Call 0771234567 at 12 Galle Road", ACTOR, "SERVICE");

    ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
    verify(repository).insertOutboxEvent(any(), any(), anyString(), json.capture(), any());
    JsonNode event = objectMapper.readTree(json.getValue());
    assertThat(fieldNames(event)).containsExactly("eventId", "eventType", "occurredAt", "aggregateId", "payload");
    assertThat(fieldNames(event.get("payload")))
        .containsExactly("trackingRef", "previousStatus", "status", "actorRole", "occurredAt", "failureReason");
    assertThat(event.get("payload").get("failureReason").asText()).isEqualTo("Recipient not available");
    assertThat(json.getValue()).doesNotContain(ACTOR, "0771234567", "Galle Road", "Call");
  }

  @Test
  @DisplayName("Repeating the current status returns changed=false and writes nothing")
  void repeatWritesNothing() {
    parcelIs(ParcelStatus.PICKED_UP, 1);

    ParcelStatusResponse response = service.updateStatus(PARCEL_ID, ParcelStatus.PICKED_UP, null, null, ACTOR,
        "DISPATCHER");

    assertThat(response.changed()).isFalse();
    assertThat(response.previousStatus()).isEqualTo(ParcelStatus.PICKED_UP);
    assertThat(response.status()).isEqualTo(ParcelStatus.PICKED_UP);
    assertThat(response.changedAt()).isNull();
    verifyNothingWritten();
  }

  @Test
  @DisplayName("Repeating a terminal status is still an idempotent success")
  void repeatOfTerminalStatusIsAccepted() {
    parcelIs(ParcelStatus.DELIVERED, 5);

    assertThat(service.updateStatus(PARCEL_ID, ParcelStatus.DELIVERED, null, null, ACTOR, "SERVICE").changed())
        .isFalse();
    verifyNothingWritten();
  }

  @Test
  @DisplayName("Transition outside the lifecycle is rejected with INVALID_TRANSITION and the allowed next statuses")
  void invalidTransitionIsRejected() {
    parcelIs(ParcelStatus.PICKED_UP, 1);

    assertThatThrownBy(() -> service.updateStatus(PARCEL_ID, ParcelStatus.DELIVERED, null, null, ACTOR, "DISPATCHER"))
        .isInstanceOfSatisfying(InvalidTransitionException.class, ex -> {
          assertThat(ex.code()).isEqualTo(InvalidTransitionException.Code.INVALID_TRANSITION);
          assertThat(ex.currentStatus()).isEqualTo(ParcelStatus.PICKED_UP);
          assertThat(ex.requestedStatus()).isEqualTo(ParcelStatus.DELIVERED);
          assertThat(ex.allowedNext()).containsExactlyInAnyOrder(ParcelStatus.IN_TRANSIT, ParcelStatus.FAILED);
        });
    verifyNothingWritten();
  }

  @Test
  @DisplayName("Going back to an earlier status is rejected")
  void backwardsTransitionIsRejected() {
    parcelIs(ParcelStatus.IN_TRANSIT, 2);

    assertThatThrownBy(() -> service.updateStatus(PARCEL_ID, ParcelStatus.CREATED, null, null, ACTOR, "DISPATCHER"))
        .isInstanceOf(InvalidTransitionException.class);
    verifyNothingWritten();
  }

  @Test
  @DisplayName("A terminal parcel rejects any change with PARCEL_TERMINAL")
  void terminalParcelIsRejected() {
    parcelIs(ParcelStatus.DELIVERED, 5);

    assertThatThrownBy(() -> service.updateStatus(PARCEL_ID, ParcelStatus.FAILED, "Lost", null, ACTOR, "DISPATCHER"))
        .isInstanceOfSatisfying(InvalidTransitionException.class, ex -> {
          assertThat(ex.code()).isEqualTo(InvalidTransitionException.Code.PARCEL_TERMINAL);
          assertThat(ex.allowedNext()).isEmpty();
        });
    verifyNothingWritten();
  }

  @Test
  @DisplayName("FAILED without a reason (missing or blank) is rejected with a reason field error")
  void failedNeedsReason() {
    parcelIs(ParcelStatus.IN_TRANSIT, 2);

    for (String reason : new String[] {null, "", "   "}) {
      assertThatThrownBy(() -> service.updateStatus(PARCEL_ID, ParcelStatus.FAILED, reason, null, ACTOR, "DISPATCHER"))
          .isInstanceOfSatisfying(StatusFieldException.class,
              ex -> assertThat(ex.fieldErrors()).containsKey("reason"));
    }
    verifyNothingWritten();
  }

  @Test
  @DisplayName("Reason and note are trimmed and limited to 200 and 500 characters")
  void textLimits() {
    assertThatThrownBy(() -> service.updateStatus(PARCEL_ID, ParcelStatus.FAILED, "r".repeat(201), null, ACTOR, "DISPATCHER"))
        .isInstanceOfSatisfying(StatusFieldException.class, ex -> assertThat(ex.fieldErrors()).containsKey("reason"));
    assertThatThrownBy(() -> service.updateStatus(PARCEL_ID, ParcelStatus.PICKED_UP, null, "n".repeat(501), ACTOR, "DISPATCHER"))
        .isInstanceOfSatisfying(StatusFieldException.class, ex -> assertThat(ex.fieldErrors()).containsKey("note"));
    verify(repository, never()).lockForUpdate(any());

    parcelIs(ParcelStatus.PICKED_UP, 1);
    service.updateStatus(PARCEL_ID, ParcelStatus.FAILED, "  Damaged  ", "   ", ACTOR, "DISPATCHER");
    ArgumentCaptor<ParcelStatusChange> history = ArgumentCaptor.forClass(ParcelStatusChange.class);
    verify(repository).insertHistory(history.capture());
    assertThat(history.getValue().reason()).isEqualTo("Damaged");
    assertThat(history.getValue().note()).isNull();
  }

  @Test
  @DisplayName("Missing parcel is reported as not found")
  void missingParcel() {
    when(repository.lockForUpdate(PARCEL_ID)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.updateStatus(PARCEL_ID, ParcelStatus.PICKED_UP, null, null, ACTOR, "DISPATCHER"))
        .isInstanceOf(ParcelNotFoundException.class);
    verifyNothingWritten();
  }

  @Test
  @DisplayName("Missing target status is a status field error")
  void missingStatus() {
    assertThatThrownBy(() -> service.updateStatus(PARCEL_ID, null, null, null, ACTOR, "DISPATCHER"))
        .isInstanceOfSatisfying(StatusFieldException.class, ex -> assertThat(ex.fieldErrors()).containsKey("status"));
  }

  @Test
  @DisplayName("A failing outbox write is not swallowed, so the transaction rolls back the status and history")
  void outboxFailurePropagates() {
    parcelIs(ParcelStatus.CREATED, 0);
    doThrow(new QueryTimeoutException("outbox down"))
        .when(repository).insertOutboxEvent(any(), any(), anyString(), anyString(), any());

    assertThatThrownBy(() -> service.updateStatus(PARCEL_ID, ParcelStatus.PICKED_UP, null, null, ACTOR, "DISPATCHER"))
        .isInstanceOf(QueryTimeoutException.class);
  }

  @Test
  @DisplayName("updateStatus runs in a transaction")
  void updateStatusIsTransactional() throws Exception {
    assertThat(ParcelStatusService.class.getMethod("updateStatus", UUID.class, ParcelStatus.class, String.class,
        String.class, String.class, String.class)
        .isAnnotationPresent(org.springframework.transaction.annotation.Transactional.class)).isTrue();
  }

  @Test
  @DisplayName("Logging the change never shows the note or the actor subject")
  void changeToStringHasNoPersonalData() {
    ParcelStatusChange change = new ParcelStatusChange(PARCEL_ID, TRACKING_REF, ParcelStatus.CREATED,
        ParcelStatus.PICKED_UP, null, "Call 0771234567", ACTOR, "DISPATCHER", NOW_MICROS);
    assertThat(change.toString()).doesNotContain("0771234567", ACTOR);
  }

  private static List<String> fieldNames(JsonNode node) {
    List<String> names = new java.util.ArrayList<>();
    node.fieldNames().forEachRemaining(names::add);
    return names;
  }
}
