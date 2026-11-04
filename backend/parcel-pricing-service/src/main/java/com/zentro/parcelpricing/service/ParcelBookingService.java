package com.zentro.parcelpricing.service;

import com.zentro.parcelpricing.repository.ParcelStatusRepository;
import com.zentro.parcelpricing.repository.ParcelBookingRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.zentro.parcelpricing.domain.ParcelBooking;
import com.zentro.parcelpricing.domain.ParcelStatus;
import com.zentro.parcelpricing.dto.CreateParcelBookingRequest;
import com.zentro.parcelpricing.dto.FeeEstimateRequest;
import com.zentro.parcelpricing.dto.FeeEstimateResponse;
import com.zentro.parcelpricing.dto.ParcelBookingResponse;
import com.zentro.parcelpricing.error.IdempotencyConflictException;
import com.zentro.parcelpricing.error.PricingUnavailableException;
import java.math.RoundingMode;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Books customer deliveries: prices the parcel first, then stores the booking with a unique tracking reference
 * and a parcel.created outbox event in the same transaction. Nothing is stored when pricing fails.
 * Repeated submissions with the same Idempotency-Key are replayed without a new event.
 */
@Service
public class ParcelBookingService {
  static final int MAX_TRACKING_REF_ATTEMPTS = 3;
  static final String CURRENCY = "LKR";

  private static final Logger log = LoggerFactory.getLogger(ParcelBookingService.class);

  public static final String CREATED_EVENT_TYPE = "parcel.created";
  static final String SOURCE_CUSTOMER_BOOKING = "CUSTOMER_BOOKING";

  private final PricingService pricingService;
  private final ParcelBookingRepository repository;
  private final TrackingReferenceGenerator trackingReferenceGenerator;
  private final ParcelStatusRepository eventRepository;
  private final ObjectMapper eventMapper;
  private final TransactionOperations transactions;
  private final ObjectMapper canonicalMapper;

  @Autowired
  public ParcelBookingService(
      PricingService pricingService,
      ParcelBookingRepository repository,
      TrackingReferenceGenerator trackingReferenceGenerator,
      ParcelStatusRepository eventRepository,
      ObjectMapper eventMapper,
      PlatformTransactionManager transactionManager) {
    this(pricingService, repository, trackingReferenceGenerator, eventRepository, eventMapper,
        new TransactionTemplate(transactionManager));
  }

  ParcelBookingService(
      PricingService pricingService,
      ParcelBookingRepository repository,
      TrackingReferenceGenerator trackingReferenceGenerator,
      ParcelStatusRepository eventRepository,
      ObjectMapper eventMapper,
      TransactionOperations transactions) {
    this.pricingService = pricingService;
    this.repository = repository;
    this.trackingReferenceGenerator = trackingReferenceGenerator;
    this.eventRepository = eventRepository;
    this.eventMapper = eventMapper;
    this.transactions = transactions;
    this.canonicalMapper = JsonMapper.builder()
        .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
        .build();
  }

  /** Result of a booking call: the booking and whether it was created now (201) or replayed (200). */
  public record BookingResult(ParcelBookingResponse booking, boolean created) {}

  public BookingResult book(String customerSubject, CreateParcelBookingRequest rawRequest, String idempotencyKey) {
    CreateParcelBookingRequest request = normalize(rawRequest);
    String requestHash = idempotencyKey == null ? null : hash(request);

    if (idempotencyKey != null) {
      Optional<ParcelBooking> existing = repository.findByCustomerAndIdempotencyKey(customerSubject, idempotencyKey);
      if (existing.isPresent()) {
        return replay(existing.get(), requestHash);
      }
    }

    FeeEstimateResponse price = price(request);
    ParcelBooking draft = toBooking(customerSubject, request, price, idempotencyKey, requestHash);

    for (int attempt = 1; ; attempt++) {
      try {
        ParcelBooking candidate = draft.withTrackingRef(trackingReferenceGenerator.next());
        // The booking and its parcel.created event are written in one transaction: both or neither.
        ParcelBooking saved = transactions.execute(status -> {
          ParcelBooking inserted = repository.insert(candidate);
          writeCreatedEvent(inserted);
          return inserted;
        });
        log.info("Parcel booked: id={} trackingRef={}", saved.id(), saved.trackingRef());
        return new BookingResult(ParcelBookingResponse.from(saved), true);
      } catch (DuplicateKeyException ex) {
        if (idempotencyKey != null && ParcelBookingRepository.isIdempotencyKeyDuplicate(ex)) {
          // A concurrent request with the same key won the race; return its booking instead.
          return repository.findByCustomerAndIdempotencyKey(customerSubject, idempotencyKey)
              .map(winner -> replay(winner, requestHash))
              .orElseThrow(() -> ex);
        }
        if (!ParcelBookingRepository.isTrackingRefCollision(ex) || attempt >= MAX_TRACKING_REF_ATTEMPTS) {
          throw ex;
        }
        log.warn("Tracking reference collision on attempt {}; retrying", attempt);
      }
    }
  }

  /**
   * Writes the parcel.created outbox event for a newly inserted booking. The payload holds only the tracking
   * reference, status, source and time: never names, phone numbers, addresses or the customer id.
   */
  private void writeCreatedEvent(ParcelBooking booking) {
    UUID eventId = UUID.randomUUID();
    OffsetDateTime occurredAt = booking.createdAt().withOffsetSameInstant(ZoneOffset.UTC);
    String timestamp = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(occurredAt);

    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("trackingRef", booking.trackingRef());
    payload.put("status", ParcelStatus.CREATED.name());
    payload.put("source", SOURCE_CUSTOMER_BOOKING);
    payload.put("occurredAt", timestamp);

    Map<String, Object> envelope = new LinkedHashMap<>();
    envelope.put("eventId", eventId.toString());
    envelope.put("eventType", CREATED_EVENT_TYPE);
    envelope.put("occurredAt", timestamp);
    envelope.put("aggregateId", booking.id().toString());
    envelope.put("payload", payload);
    try {
      eventRepository.insertOutboxEvent(eventId, booking.id(), CREATED_EVENT_TYPE,
          eventMapper.writeValueAsString(envelope), occurredAt);
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException("Could not serialise the parcel.created event", ex);
    }
  }

  private BookingResult replay(ParcelBooking existing, String requestHash) {
    if (!existing.requestHash().equals(requestHash)) {
      throw new IdempotencyConflictException();
    }
    return new BookingResult(ParcelBookingResponse.from(existing), false);
  }

  private FeeEstimateResponse price(CreateParcelBookingRequest request) {
    FeeEstimateRequest estimate = new FeeEstimateRequest(
        request.sender().city(),
        request.recipient().city(),
        request.destinationType(),
        request.parcel().size(),
        request.parcel().weightKg(),
        request.parcel().category(),
        request.isExpress(),
        null, null, null, null, null, null,
        CURRENCY);
    FeeEstimateResponse response;
    try {
      response = pricingService.estimateFee(estimate);
    } catch (IllegalArgumentException ex) {
      throw ex;
    } catch (RuntimeException ex) {
      throw new PricingUnavailableException(ex);
    }
    if (response == null || response.fee() == null || response.currency() == null) {
      throw new PricingUnavailableException(new IllegalStateException("Pricing returned no fee"));
    }
    return response;
  }

  private static ParcelBooking toBooking(String customerSubject, CreateParcelBookingRequest request,
      FeeEstimateResponse price, String idempotencyKey, String requestHash) {
    return new ParcelBooking(
        null,
        null,
        customerSubject,
        toContact(request.sender()),
        toContact(request.recipient()),
        request.parcel().weightKg(),
        request.parcel().size(),
        request.parcel().category(),
        request.parcel().description(),
        request.destinationType(),
        request.isExpress(),
        request.notes(),
        price.fee().setScale(2, RoundingMode.HALF_UP),
        price.currency(),
        price.estimatedDays(),
        ParcelStatus.CREATED,
        idempotencyKey,
        requestHash,
        null,
        null);
  }

  private static ParcelBooking.Contact toContact(CreateParcelBookingRequest.Contact contact) {
    return new ParcelBooking.Contact(contact.name(), contact.phone(), contact.address(), contact.city());
  }

  /** Trims text, drops empty optional values, strips phone separators and fixes the weight scale. */
  static CreateParcelBookingRequest normalize(CreateParcelBookingRequest request) {
    CreateParcelBookingRequest.ParcelDetails parcel = request.parcel();
    return new CreateParcelBookingRequest(
        normalize(request.sender()),
        normalize(request.recipient()),
        new CreateParcelBookingRequest.ParcelDetails(
            parcel.weightKg().setScale(2, RoundingMode.HALF_UP),
            parcel.category().trim(),
            parcel.size(),
            emptyToNull(parcel.description())),
        request.destinationType(),
        Boolean.TRUE.equals(request.isExpress()),
        emptyToNull(request.notes()));
  }

  private static CreateParcelBookingRequest.Contact normalize(CreateParcelBookingRequest.Contact contact) {
    return new CreateParcelBookingRequest.Contact(
        contact.name().trim(),
        contact.phone().replaceAll("[\\s-]", ""),
        contact.address().trim(),
        emptyToNull(contact.city()));
  }

  private static String emptyToNull(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private String hash(CreateParcelBookingRequest request) {
    try {
      byte[] json = canonicalMapper.writeValueAsBytes(request);
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(json);
      return HexFormat.of().formatHex(digest);
    } catch (JsonProcessingException | NoSuchAlgorithmException ex) {
      throw new IllegalStateException("Could not hash booking request", ex);
    }
  }
}
