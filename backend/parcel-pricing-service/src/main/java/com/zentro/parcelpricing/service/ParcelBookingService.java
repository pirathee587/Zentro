package com.zentro.parcelpricing.service;

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
import java.util.HexFormat;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * Books customer deliveries: prices the parcel first, then stores the booking with a unique tracking reference.
 * Nothing is stored when pricing fails. Repeated submissions with the same Idempotency-Key are replayed.
 */
@Service
public class ParcelBookingService {
  static final int MAX_TRACKING_REF_ATTEMPTS = 3;
  static final String CURRENCY = "LKR";

  private static final Logger log = LoggerFactory.getLogger(ParcelBookingService.class);

  private final PricingService pricingService;
  private final ParcelBookingRepository repository;
  private final TrackingReferenceGenerator trackingReferenceGenerator;
  private final ObjectMapper canonicalMapper;

  public ParcelBookingService(
      PricingService pricingService,
      ParcelBookingRepository repository,
      TrackingReferenceGenerator trackingReferenceGenerator) {
    this.pricingService = pricingService;
    this.repository = repository;
    this.trackingReferenceGenerator = trackingReferenceGenerator;
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
        ParcelBooking saved = repository.insert(draft.withTrackingRef(trackingReferenceGenerator.next()));
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
