package com.zentro.parcelpricing.service;

import com.zentro.parcelpricing.repository.MerchantOrderRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.zentro.parcelpricing.domain.MerchantOrder;
import com.zentro.parcelpricing.domain.ParcelStatus;
import com.zentro.parcelpricing.dto.CreateMerchantOrderRequest;
import com.zentro.parcelpricing.dto.CreateParcelBookingRequest;
import com.zentro.parcelpricing.dto.FeeEstimateRequest;
import com.zentro.parcelpricing.dto.FeeEstimateResponse;
import com.zentro.parcelpricing.dto.MerchantOrderResponse;
import com.zentro.parcelpricing.error.DuplicateExternalReferenceException;
import com.zentro.parcelpricing.error.PricingUnavailableException;
import java.math.RoundingMode;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * Handles merchant order operations: normalizes inputs, prices parcels, safely handles duplicate
 * external references, and scopes all operations to the authenticated merchant.
 */
@Service
public class MerchantOrderService {
  static final int MAX_TRACKING_REF_ATTEMPTS = 3;
  static final String CURRENCY = "LKR";

  private static final Logger log = LoggerFactory.getLogger(MerchantOrderService.class);

  private final PricingService pricingService;
  private final MerchantOrderRepository repository;
  private final TrackingReferenceGenerator trackingReferenceGenerator;
  private final ObjectMapper canonicalMapper;

  public MerchantOrderService(
      PricingService pricingService,
      MerchantOrderRepository repository,
      TrackingReferenceGenerator trackingReferenceGenerator) {
    this.pricingService = pricingService;
    this.repository = repository;
    this.trackingReferenceGenerator = trackingReferenceGenerator;
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
        MerchantOrder saved = repository.insert(draft.withTrackingRef(trackingReferenceGenerator.next()));
        log.info("Merchant order created: id={} merchantId={} externalRef={} trackingRef={}",
            saved.id(), merchantId, saved.externalOrderRef(), saved.trackingRef());
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
