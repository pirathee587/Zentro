package com.zentro.parcelpricing.parcel.service;

import com.zentro.parcelpricing.pricing.service.PricingService;

import com.zentro.parcelpricing.parcel.repository.MerchantOrderRepository;
import com.zentro.parcelpricing.parcel.repository.ParcelStatusRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.zentro.parcelpricing.parcel.entity.MerchantOrder;
import com.zentro.parcelpricing.parcel.entity.ParcelStatus;
import com.zentro.parcelpricing.parcel.dto.CreateMerchantOrderRequest;
import com.zentro.parcelpricing.parcel.dto.CreateParcelBookingRequest;
import com.zentro.parcelpricing.pricing.dto.FeeEstimateRequest;
import com.zentro.parcelpricing.pricing.dto.FeeEstimateResponse;
import com.zentro.parcelpricing.parcel.dto.MerchantOrderResponse;
import com.zentro.parcelpricing.exception.DuplicateExternalReferenceException;
import com.zentro.parcelpricing.exception.PricingUnavailableException;
import java.math.RoundingMode;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
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
 * Handles merchant order operations: normalizes inputs, prices parcels, safely handles duplicate
 * external references, and scopes all operations to the authenticated merchant. A new order and its
 * parcel.created outbox event (source MERCHANT_ORDER, with the merchant id) are written in one transaction;
 * replays write no event.
 */
@Service
public class MerchantOrderService {
  static final int MAX_TRACKING_REF_ATTEMPTS = 3;
  static final String CURRENCY = "LKR";
  static final String CREATED_EVENT_TYPE = "parcel.created";
  static final String SOURCE_MERCHANT_ORDER = "MERCHANT_ORDER";

  private static final Logger log = LoggerFactory.getLogger(MerchantOrderService.class);

  private final PricingService pricingService;
  private final MerchantOrderRepository repository;
  private final TrackingReferenceGenerator trackingReferenceGenerator;
  private final ParcelStatusRepository eventRepository;
  private final ObjectMapper eventMapper;
  private final TransactionOperations transactions;
  private final ObjectMapper canonicalMapper;

  @Autowired
  public MerchantOrderService(
      PricingService pricingService,
      MerchantOrderRepository repository,
      TrackingReferenceGenerator trackingReferenceGenerator,
      ParcelStatusRepository eventRepository,
      ObjectMapper eventMapper,
      PlatformTransactionManager transactionManager) {
    this(pricingService, repository, trackingReferenceGenerator, eventRepository, eventMapper,
        new TransactionTemplate(transactionManager));
  }

  MerchantOrderService(
      PricingService pricingService,
      MerchantOrderRepository repository,
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

  public record OrderResult(MerchantOrderResponse order, boolean created) {}

  public OrderResult createOrder(String merchantId, CreateMerchantOrderRequest rawRequest) {
    CreateMerchantOrderRequest request = normalize(rawRequest);
    String requestHash = hash(request);

    Optional<MerchantOrder> existing = repository.findByMerchantAndExternalRef(merchantId, request.externalOrderRef());
    if (existing.isPresent()) {
      return replay(existing.get(), requestHash);
    }

    FeeEstimateResponse price = price(request.toBookingRequest());
    MerchantOrder draft = toOrder(merchantId, request, price, requestHash);

    for (int attempt = 1; ; attempt++) {
      try {
        MerchantOrder candidate = draft.withTrackingRef(trackingReferenceGenerator.next());
        // The order and its parcel.created event are written in one transaction: both or neither.
        MerchantOrder saved = transactions.execute(status -> {
          MerchantOrder inserted = repository.insert(candidate);
          writeCreatedEvent(inserted);
          return inserted;
        });
        log.info("Merchant order created: id={} trackingRef={}", saved.id(), saved.trackingRef());
        return new OrderResult(MerchantOrderResponse.from(saved), true);
      } catch (DuplicateKeyException ex) {
        if (MerchantOrderRepository.isMerchantExternalRefDuplicate(ex)) {
          return repository.findByMerchantAndExternalRef(merchantId, request.externalOrderRef())
              .map(winner -> replay(winner, requestHash))
              .orElseThrow(() -> ex);
        }
        if (!MerchantOrderRepository.isTrackingRefCollision(ex) || attempt >= MAX_TRACKING_REF_ATTEMPTS) {
          throw ex;
        }
        log.warn("Tracking reference collision on attempt {}; retrying", attempt);
      }
    }
  }

  public Optional<MerchantOrderResponse> findById(UUID id, String merchantId) {
    return repository.findByIdAndMerchant(id, merchantId).map(MerchantOrderResponse::from);
  }

  public List<MerchantOrderResponse> listByMerchant(String merchantId, int limit) {
    return repository.listByMerchant(merchantId, limit).stream()
        .map(MerchantOrderResponse::from)
        .toList();
  }

  /**
   * Writes the parcel.created outbox event for a newly inserted merchant order. The payload holds only the
   * tracking reference, status, source, time and merchant account id: never names, phone numbers or addresses.
   */
  private void writeCreatedEvent(MerchantOrder order) {
    UUID eventId = UUID.randomUUID();
    OffsetDateTime occurredAt = order.createdAt().withOffsetSameInstant(ZoneOffset.UTC);
    String timestamp = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(occurredAt);

    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("trackingRef", order.trackingRef());
    payload.put("status", ParcelStatus.CREATED.name());
    payload.put("source", SOURCE_MERCHANT_ORDER);
    payload.put("occurredAt", timestamp);
    payload.put("merchantId", order.merchantId());

    Map<String, Object> envelope = new LinkedHashMap<>();
    envelope.put("eventId", eventId.toString());
    envelope.put("eventType", CREATED_EVENT_TYPE);
    envelope.put("occurredAt", timestamp);
    envelope.put("aggregateId", order.id().toString());
    envelope.put("payload", payload);
    try {
      eventRepository.insertOutboxEvent(eventId, order.id(), CREATED_EVENT_TYPE,
          eventMapper.writeValueAsString(envelope), occurredAt);
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException("Could not serialise the parcel.created event", ex);
    }
  }

  private OrderResult replay(MerchantOrder existing, String requestHash) {
    if (!existing.requestHash().equals(requestHash)) {
      throw new DuplicateExternalReferenceException();
    }
    return new OrderResult(MerchantOrderResponse.from(existing), false);
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

  private static MerchantOrder toOrder(String merchantId, CreateMerchantOrderRequest request,
      FeeEstimateResponse price, String requestHash) {
    return new MerchantOrder(
        null,
        null,
        merchantId,
        request.externalOrderRef(),
        toContact(request.sender()),
        toContact(request.recipient()),
        request.parcel().weightKg(),
        request.parcel().size(),
        request.parcel().category(),
        request.parcel().description(),
        request.destinationType(),
        Boolean.TRUE.equals(request.isExpress()),
        request.notes(),
        price.fee().setScale(2, RoundingMode.HALF_UP),
        price.currency(),
        price.estimatedDays(),
        ParcelStatus.CREATED,
        requestHash,
        null,
        null);
  }

  private static MerchantOrder.Contact toContact(CreateParcelBookingRequest.Contact contact) {
    return new MerchantOrder.Contact(contact.name(), contact.phone(), contact.address(), contact.city());
  }

  static CreateMerchantOrderRequest normalize(CreateMerchantOrderRequest request) {
    CreateParcelBookingRequest.ParcelDetails parcel = request.parcel();
    return new CreateMerchantOrderRequest(
        request.externalOrderRef().trim(),
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

  private String hash(CreateMerchantOrderRequest request) {
    try {
      byte[] json = canonicalMapper.writeValueAsBytes(request);
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(json);
      return HexFormat.of().formatHex(digest);
    } catch (JsonProcessingException | NoSuchAlgorithmException ex) {
      throw new IllegalStateException("Could not hash merchant order request", ex);
    }
  }
}
