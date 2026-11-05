package com.zentro.parcelpricing.parcel.service;

import com.zentro.parcelpricing.pricing.service.PricingService;

import com.zentro.parcelpricing.parcel.repository.MerchantOrderRepository;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zentro.parcelpricing.parcel.entity.DestinationType;
import com.zentro.parcelpricing.parcel.entity.MerchantOrder;
import com.zentro.parcelpricing.parcel.entity.ParcelSize;
import com.zentro.parcelpricing.parcel.entity.ParcelStatus;
import com.zentro.parcelpricing.parcel.dto.CreateMerchantOrderRequest;
import com.zentro.parcelpricing.parcel.dto.CreateParcelBookingRequest;
import com.zentro.parcelpricing.pricing.dto.FeeEstimateRequest;
import com.zentro.parcelpricing.pricing.dto.FeeEstimateResponse;
import com.zentro.parcelpricing.parcel.dto.MerchantOrderResponse;
import com.zentro.parcelpricing.exception.DuplicateExternalReferenceException;
import com.zentro.parcelpricing.exception.PricingUnavailableException;
import com.zentro.parcelpricing.parcel.service.MerchantOrderService.OrderResult;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

class MerchantOrderServiceTest {
  private PricingService pricingService;
  private MerchantOrderRepository repository;
  private TrackingReferenceGenerator trackingGen;
  private MerchantOrderService service;

  @BeforeEach
  void setUp() {
    pricingService = mock(PricingService.class);
    repository = mock(MerchantOrderRepository.class);
    trackingGen = mock(TrackingReferenceGenerator.class);
    service = new MerchantOrderService(pricingService, repository, trackingGen);
  }

  @Test
  @DisplayName("Create new order calculates pricing and saves order with status CREATED")
  void createOrderSucceeds() {
    CreateMerchantOrderRequest request = sampleRequest("ORD-001");
    when(pricingService.estimateFee(any(FeeEstimateRequest.class)))
        .thenReturn(new FeeEstimateResponse(new BigDecimal("850.00"), "LKR 850", "LKR", 2, null));
    when(trackingGen.next()).thenReturn("ZT-1234567890");

    UUID generatedId = UUID.randomUUID();
    when(repository.insert(any(MerchantOrder.class)))
        .thenAnswer(inv -> {
          MerchantOrder draft = inv.getArgument(0);
          return new MerchantOrder(
              generatedId,
              draft.trackingRef(),
              draft.merchantId(),
              draft.externalOrderRef(),
              draft.sender(),
              draft.recipient(),
              draft.weightKg(),
              draft.size(),
              draft.category(),
              draft.description(),
              draft.destinationType(),
              draft.express(),
              draft.notes(),
              draft.fee(),
              draft.currency(),
              draft.estimatedDays(),
              draft.status(),
              draft.requestHash(),
              OffsetDateTime.now(),
              OffsetDateTime.now());
        });

    OrderResult result = service.createOrder("merchant-abc", request);

    assertThat(result.created()).isTrue();
    MerchantOrderResponse response = result.order();
    assertThat(response.id()).isEqualTo(generatedId);
    assertThat(response.trackingRef()).isEqualTo("ZT-1234567890");
    assertThat(response.externalOrderRef()).isEqualTo("ORD-001");
    assertThat(response.merchantId()).isEqualTo("merchant-abc");
    assertThat(response.status()).isEqualTo(ParcelStatus.CREATED);
    assertThat(response.fee()).isEqualByComparingTo("850.00");
    assertThat(response.currency()).isEqualTo("LKR");
  }

  @Test
  @DisplayName("Repeated submission with identical details returns replayed 200 without creating new parcel")
  void replayReturnsExisting() {
    CreateMerchantOrderRequest request = sampleRequest("ORD-001");
    CreateMerchantOrderRequest normalized = MerchantOrderService.normalize(request);

    // Seed existing matching order
    MerchantOrder existing = sampleStoredOrder(UUID.randomUUID(), "merchant-abc", "ORD-001", normalized);
    when(repository.findByMerchantAndExternalRef("merchant-abc", "ORD-001"))
        .thenReturn(Optional.of(existing));

    OrderResult result = service.createOrder("merchant-abc", request);

    assertThat(result.created()).isFalse();
    assertThat(result.order().id()).isEqualTo(existing.id());
    assertThat(result.order().trackingRef()).isEqualTo(existing.trackingRef());
    verify(repository, times(0)).insert(any());
  }

  @Test
  @DisplayName("Same externalOrderRef with different body throws DuplicateExternalReferenceException")
  void duplicateExternalRefWithDifferentBodyThrowsConflict() {
    CreateMerchantOrderRequest originalRequest = sampleRequest("ORD-001");
    CreateMerchantOrderRequest normalized = MerchantOrderService.normalize(originalRequest);

    MerchantOrder existing = sampleStoredOrder(UUID.randomUUID(), "merchant-abc", "ORD-001", normalized);
    when(repository.findByMerchantAndExternalRef("merchant-abc", "ORD-001"))
        .thenReturn(Optional.of(existing));

    // Submit request with different recipient address
    CreateMerchantOrderRequest modifiedRequest = new CreateMerchantOrderRequest(
        "ORD-001",
        originalRequest.sender(),
        new CreateParcelBookingRequest.Contact("Nimal Perera", "+94771234567", "99 Different Street, Kandy", "Kandy"),
        originalRequest.parcel(),
        originalRequest.destinationType(),
        originalRequest.isExpress(),
        originalRequest.notes());

    assertThatThrownBy(() -> service.createOrder("merchant-abc", modifiedRequest))
        .isInstanceOf(DuplicateExternalReferenceException.class)
        .hasMessageContaining("different order details");
  }

  @Test
  @DisplayName("Two different merchants can use the same externalOrderRef without collision")
  void differentMerchantsCanUseSameExternalRef() {
    CreateMerchantOrderRequest request = sampleRequest("ORD-SHARED");
    when(pricingService.estimateFee(any())).thenReturn(new FeeEstimateResponse(new BigDecimal("500.00"), "LKR 500", "LKR", 1, null));
    when(trackingGen.next()).thenReturn("ZT-REF1", "ZT-REF2");
    when(repository.findByMerchantAndExternalRef(any(), eq("ORD-SHARED"))).thenReturn(Optional.empty());
    when(repository.insert(any())).thenAnswer(inv -> inv.getArgument(0));

    OrderResult result1 = service.createOrder("merchant-1", request);
    OrderResult result2 = service.createOrder("merchant-2", request);

    assertThat(result1.order().merchantId()).isEqualTo("merchant-1");
    assertThat(result2.order().merchantId()).isEqualTo("merchant-2");
    assertThat(result1.order().externalOrderRef()).isEqualTo("ORD-SHARED");
    assertThat(result2.order().externalOrderRef()).isEqualTo("ORD-SHARED");
  }

  @Test
  @DisplayName("Tracking reference collisions retry up to limit")
  void trackingCollisionRetries() {
    CreateMerchantOrderRequest request = sampleRequest("ORD-001");
    when(pricingService.estimateFee(any())).thenReturn(new FeeEstimateResponse(new BigDecimal("500.00"), "LKR 500", "LKR", 1, null));
    when(trackingGen.next()).thenReturn("ZT-COLLIDE", "ZT-UNIQUE");

    DuplicateKeyException collision = new DuplicateKeyException("Pretend duplicate " + MerchantOrderRepository.TRACKING_REF_CONSTRAINT);
    when(repository.insert(any()))
        .thenThrow(collision)
        .thenAnswer(inv -> inv.getArgument(0));

    OrderResult result = service.createOrder("merchant-abc", request);

    assertThat(result.created()).isTrue();
    verify(trackingGen, times(2)).next();
  }

  @Test
  @DisplayName("Pricing failure aborts order and throws PricingUnavailableException")
  void pricingFailureAborts() {
    CreateMerchantOrderRequest request = sampleRequest("ORD-001");
    when(pricingService.estimateFee(any())).thenThrow(new RuntimeException("Pricing service down"));

    assertThatThrownBy(() -> service.createOrder("merchant-abc", request))
        .isInstanceOf(PricingUnavailableException.class);

    verify(repository, times(0)).insert(any());
  }

  @Test
  @DisplayName("Normalizer cleans whitespaces, trims fields, and strips phone separators")
  void normalizerCleansFields() {
    CreateMerchantOrderRequest request = new CreateMerchantOrderRequest(
        "  ORD-001  ",
        new CreateParcelBookingRequest.Contact("  Apex Electronics  ", "+94 11-234 5678", "  77 Galle Road  ", "  Colombo  "),
        new CreateParcelBookingRequest.Contact("  Nimal Perera  ", "077-123-4567", "  15 Peradeniya Road  ", "   "),
        new CreateParcelBookingRequest.ParcelDetails(new BigDecimal("2.500"), "  Electronics  ", ParcelSize.SMALL, "  Fragile  "),
        DestinationType.SAME_CITY,
        true,
        "  Handle with care  "
    );

    CreateMerchantOrderRequest normalized = MerchantOrderService.normalize(request);

    assertThat(normalized.externalOrderRef()).isEqualTo("ORD-001");
    assertThat(normalized.sender().name()).isEqualTo("Apex Electronics");
    assertThat(normalized.sender().phone()).isEqualTo("+94112345678");
    assertThat(normalized.sender().address()).isEqualTo("77 Galle Road");
    assertThat(normalized.sender().city()).isEqualTo("Colombo");
    assertThat(normalized.recipient().phone()).isEqualTo("0771234567");
    assertThat(normalized.recipient().city()).isNull();
    assertThat(normalized.parcel().weightKg()).isEqualByComparingTo("2.50");
    assertThat(normalized.parcel().category()).isEqualTo("Electronics");
    assertThat(normalized.parcel().description()).isEqualTo("Fragile");
    assertThat(normalized.notes()).isEqualTo("Handle with care");
  }

  @Test
  @DisplayName("findById scopes strictly to merchantId")
  void findByIdScopedToMerchant() {
    UUID id = UUID.randomUUID();
    MerchantOrder order = sampleStoredOrder(id, "merchant-abc", "ORD-001", MerchantOrderService.normalize(sampleRequest("ORD-001")));
    when(repository.findByIdAndMerchant(id, "merchant-abc")).thenReturn(Optional.of(order));
    when(repository.findByIdAndMerchant(id, "merchant-other")).thenReturn(Optional.empty());

    assertThat(service.findById(id, "merchant-abc")).isPresent();
    assertThat(service.findById(id, "merchant-other")).isEmpty();
  }

  private static CreateMerchantOrderRequest sampleRequest(String externalRef) {
    return new CreateMerchantOrderRequest(
        externalRef,
        new CreateParcelBookingRequest.Contact("Apex Electronics", "0112345678", "77 Galle Road, Colombo 03", "Colombo"),
        new CreateParcelBookingRequest.Contact("Nimal Perera", "+94 77 123 4567", "15 Peradeniya Road, Kandy", "Kandy"),
        new CreateParcelBookingRequest.ParcelDetails(new BigDecimal("2.0"), "Electronics", ParcelSize.MEDIUM, "Headphones"),
        DestinationType.OTHER_DISTRICT,
        false,
        "Call before delivery");
  }

  private MerchantOrder sampleStoredOrder(UUID id, String merchantId, String externalRef, CreateMerchantOrderRequest normalized) {
    // Hash using the service's canonical mapper
    OrderResult temp = null;
    try {
      java.lang.reflect.Method hashMethod = MerchantOrderService.class.getDeclaredMethod("hash", CreateMerchantOrderRequest.class);
      hashMethod.setAccessible(true);
      String requestHash = (String) hashMethod.invoke(service, normalized);

      return new MerchantOrder(
          id,
          "ZT-EXISTING01",
          merchantId,
          externalRef,
          new MerchantOrder.Contact(normalized.sender().name(), normalized.sender().phone(), normalized.sender().address(), normalized.sender().city()),
          new MerchantOrder.Contact(normalized.recipient().name(), normalized.recipient().phone(), normalized.recipient().address(), normalized.recipient().city()),
          normalized.parcel().weightKg(),
          normalized.parcel().size(),
          normalized.parcel().category(),
          normalized.parcel().description(),
          normalized.destinationType(),
          Boolean.TRUE.equals(normalized.isExpress()),
          normalized.notes(),
          new BigDecimal("850.00"),
          "LKR",
          2,
          ParcelStatus.CREATED,
          requestHash,
          OffsetDateTime.now(),
          OffsetDateTime.now());
    } catch (Exception ex) {
      throw new RuntimeException(ex);
    }
  }
}
