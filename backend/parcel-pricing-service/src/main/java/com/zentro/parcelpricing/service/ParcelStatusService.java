package com.zentro.parcelpricing.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zentro.parcelpricing.domain.ParcelLifecycle;
import com.zentro.parcelpricing.domain.ParcelStatus;
import com.zentro.parcelpricing.domain.ParcelStatusChange;
import com.zentro.parcelpricing.dto.ParcelStatusResponse;
import com.zentro.parcelpricing.error.InvalidTransitionException;
import com.zentro.parcelpricing.error.ParcelNotFoundException;
import com.zentro.parcelpricing.error.StatusFieldException;
import com.zentro.parcelpricing.service.ParcelStatusRepository.LockedParcel;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Moves a parcel through its lifecycle. In one transaction it locks the parcel, checks the transition
 * against {@link ParcelLifecycle}, saves the new status, appends a history row and writes one
 * {@value #EVENT_TYPE} event to the outbox. If any write fails, nothing changes.
 */
@Service
public class ParcelStatusService {
  public static final String EVENT_TYPE = "parcel.status.updated";
  static final int MAX_REASON_LENGTH = 200;
  static final int MAX_NOTE_LENGTH = 500;

  private static final Logger log = LoggerFactory.getLogger(ParcelStatusService.class);

  private final ParcelStatusRepository repository;
  private final ObjectMapper objectMapper;
  private final Clock clock;

  @Autowired
  public ParcelStatusService(ParcelStatusRepository repository, ObjectMapper objectMapper) {
    this(repository, objectMapper, Clock.systemUTC());
  }

  ParcelStatusService(ParcelStatusRepository repository, ObjectMapper objectMapper, Clock clock) {
    this.repository = repository;
    this.objectMapper = objectMapper;
    this.clock = clock;
  }

  /**
   * Applies one status change.
   *
   * @return the result; {@code changed} is false when the parcel already had {@code targetStatus}
   * @throws ParcelNotFoundException    when the parcel does not exist
   * @throws InvalidTransitionException when the parcel is terminal or the transition is not allowed
   * @throws StatusFieldException       when FAILED has no reason, or a text field is too long
   */
  @Transactional
  public ParcelStatusResponse updateStatus(UUID parcelId, ParcelStatus targetStatus, String reason, String note,
      String actorSubject, String actorRole) {
    if (targetStatus == null) {
      throw new StatusFieldException("status", "Status is required.");
    }
    String cleanReason = trimToNull(reason);
    String cleanNote = trimToNull(note);
    if (cleanReason != null && cleanReason.length() > MAX_REASON_LENGTH) {
      throw new StatusFieldException("reason", "Reason must be at most " + MAX_REASON_LENGTH + " characters.");
    }
    if (cleanNote != null && cleanNote.length() > MAX_NOTE_LENGTH) {
      throw new StatusFieldException("note", "Note must be at most " + MAX_NOTE_LENGTH + " characters.");
    }
    if (actorSubject == null || actorSubject.isBlank() || actorRole == null || actorRole.isBlank()) {
      throw new IllegalArgumentException("A status change needs an actor subject and role.");
    }

    LockedParcel parcel = repository.lockForUpdate(parcelId).orElseThrow(() -> new ParcelNotFoundException(parcelId));
    ParcelStatus current = parcel.status();

    if (current == targetStatus) {
      // Repeating the current status is accepted without recording anything.
      return new ParcelStatusResponse(parcel.id(), parcel.trackingRef(), current, current, false, null);
    }
    if (ParcelLifecycle.isTerminal(current)) {
      throw InvalidTransitionException.terminal(current, targetStatus);
    }
    if (!ParcelLifecycle.isAllowed(current, targetStatus)) {
      throw InvalidTransitionException.notAllowed(current, targetStatus);
    }
    if (targetStatus == ParcelStatus.FAILED && cleanReason == null) {
      throw new StatusFieldException("reason", "A reason is required when the status is FAILED.");
    }

    // PostgreSQL stores microseconds, so the returned time matches the stored one exactly.
    OffsetDateTime now = OffsetDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
    ParcelStatusChange change = new ParcelStatusChange(parcel.id(), parcel.trackingRef(), current, targetStatus,
        cleanReason, cleanNote, actorSubject, actorRole.toUpperCase(Locale.ROOT), now);

    repository.updateStatus(parcel.id(), targetStatus, parcel.version(), now);
    repository.insertHistory(change);
    UUID eventId = UUID.randomUUID();
    repository.insertOutboxEvent(eventId, parcel.id(), EVENT_TYPE, eventJson(eventId, change), now);

    log.info("Parcel status changed: parcelId={} {} -> {} by {}", parcel.id(), current, targetStatus,
        change.actorRole());
    return new ParcelStatusResponse(parcel.id(), parcel.trackingRef(), current, targetStatus, true, now);
  }

  /**
   * Event envelope (eventId, eventType, occurredAt, aggregateId, payload). The payload holds only the tracking
   * reference, statuses, actor role, time and failure reason: never names, phone numbers, addresses, notes or
   * the actor subject.
   */
  String eventJson(UUID eventId, ParcelStatusChange change) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("trackingRef", change.trackingRef());
    payload.put("previousStatus", change.previousStatus().name());
    payload.put("status", change.status().name());
    payload.put("actorRole", change.actorRole());
    payload.put("occurredAt", change.occurredAt().toString());
    if (change.status() == ParcelStatus.FAILED) {
      payload.put("failureReason", change.reason());
    }

    Map<String, Object> envelope = new LinkedHashMap<>();
    envelope.put("eventId", eventId.toString());
    envelope.put("eventType", EVENT_TYPE);
    envelope.put("occurredAt", change.occurredAt().toString());
    envelope.put("aggregateId", change.parcelId().toString());
    envelope.put("payload", payload);
    try {
      return objectMapper.writeValueAsString(envelope);
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException("Could not serialise the status event", ex);
    }
  }

  private static String trimToNull(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }
}
