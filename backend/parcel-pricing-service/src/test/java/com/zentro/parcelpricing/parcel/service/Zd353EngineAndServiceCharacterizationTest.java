package com.zentro.parcelpricing.parcel.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zentro.parcelpricing.parcel.dto.CreateParcelBookingRequest;
import com.zentro.parcelpricing.parcel.entity.DestinationType;
import com.zentro.parcelpricing.parcel.entity.ParcelBooking;
import com.zentro.parcelpricing.parcel.entity.ParcelSize;
import com.zentro.parcelpricing.parcel.entity.ParcelStatus;
import com.zentro.parcelpricing.parcel.repository.ParcelBookingRepository;
import com.zentro.parcelpricing.parcel.repository.ParcelStatusRepository;
import com.zentro.parcelpricing.parcel.service.ParcelBookingService.BookingResult;
import com.zentro.parcelpricing.pricing.dto.FeeEstimateRequest;
import com.zentro.parcelpricing.pricing.dto.FeeEstimateResponse;
import com.zentro.parcelpricing.pricing.rules.DeliveryZone;
import com.zentro.parcelpricing.pricing.rules.PricingCalculationEngine;
import com.zentro.parcelpricing.pricing.rules.PricingRule;
import com.zentro.parcelpricing.pricing.service.PricingService;
import com.zentro.parcelpricing.pricing.service.ZoneService;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Characterization test suite for ZD-353 (#353) findings:
 * - G10 (#301): Destination classification caller control and forward parity.
 * - G11 (#306): Dimension quote vs booking consistency (volumetric weight).
 * - G12 (#305): Same address booking acceptance.
 * - G13 (#194): Standard fallback behavior under database outage.
 */
@ExtendWith(MockitoExtension.class)
class Zd353EngineAndServiceCharacterizationTest {
  private static final String CUSTOMER = "cust-zd353";
  private final PricingCalculationEngine engine = new PricingCalculationEngine();
  private final PricingRule defaultRule = PricingRule.defaultStandard();

  @Mock PricingService pricingService;
  @Mock ParcelBookingRepository repository;
  @Mock TrackingReferenceGenerator trackingReferenceGenerator;
  @Mock ParcelStatusRepository eventRepository;
  @Mock JdbcTemplate jdbc;

  private final ObjectMapper objectMapper = new ObjectMapper();
  private ParcelBookingService bookingService;

  @BeforeEach
  void setUp() {
    bookingService = new ParcelBookingService(
        pricingService,
        repository,
        trackingReferenceGenerator,
        eventRepository,
        objectMapper,
        TransactionOperations.withoutTransaction());
  }

  @Test
  @DisplayName("G10: Caller-supplied destinationType controls fee on same route (402 LKR delta)")
  void g10_callerSuppliedDestinationTypeChangesFeeBy402Lkr() {
    // Route: Colombo -> Jaffna, 1.0 kg, non-express
    // With SAME_CITY: base fee 550.00, weight fee 0.00, distance surcharge 0.00 -> total 550.00 LKR
    FeeEstimateRequest sameCityReq = new FeeEstimateRequest(
        "Colombo", "Jaffna", DestinationType.SAME_CITY, ParcelSize.SMALL,
        new BigDecimal("1.00"), "Documents", false, null, null, null, null, null, null, "LKR");
    FeeEstimateResponse sameCityRes = engine.calculate(sameCityReq, defaultRule);

    // With OTHER_DISTRICT: base fee 550.00, weight fee 0.00, distance surcharge 402.00 -> total 952.00 LKR
    FeeEstimateRequest otherDistrictReq = new FeeEstimateRequest(
        "Colombo", "Jaffna", DestinationType.OTHER_DISTRICT, ParcelSize.SMALL,
        new BigDecimal("1.00"), "Documents", false, null, null, null, null, null, null, "LKR");
    FeeEstimateResponse otherDistrictRes = engine.calculate(otherDistrictReq, defaultRule);

    assertThat(sameCityRes.fee()).isEqualByComparingTo("550.00");
    assertThat(otherDistrictRes.fee()).isEqualByComparingTo("952.00");
    BigDecimal delta = otherDistrictRes.fee().subtract(sameCityRes.fee());
    assertThat(delta).isEqualByComparingTo("402.00");
  }

  @Test
  @DisplayName("G10: ParcelBookingService forwards caller destinationType directly without server-side derivation")
  void g10_bookingServiceForwardsCallerDestinationTypeDirectly() {
    FeeEstimateResponse stubPrice = new FeeEstimateResponse(
        new BigDecimal("952.00"), "952.00 LKR", "LKR", 3,
        new FeeEstimateResponse.Breakdown(new BigDecimal("550.00"), BigDecimal.ZERO, new BigDecimal("402.00"), null));
    when(pricingService.estimateFee(any())).thenReturn(stubPrice);
    when(trackingReferenceGenerator.next()).thenReturn("ZT-CHARACT001");
    when(repository.insert(any())).thenAnswer(inv -> saved(inv.getArgument(0)));

    // Caller submits destinationType OTHER_DISTRICT even though pickup and delivery cities are both Colombo
    CreateParcelBookingRequest req = new CreateParcelBookingRequest(
        new CreateParcelBookingRequest.Contact("Sender", "0771234567", "12 Galle Rd, Colombo 03", "Colombo"),
        new CreateParcelBookingRequest.Contact("Recipient", "0771234568", "14 Galle Rd, Colombo 03", "Colombo"),
        new CreateParcelBookingRequest.ParcelDetails(new BigDecimal("1.00"), "Box", ParcelSize.SMALL, null),
        DestinationType.OTHER_DISTRICT,
        false,
        null);

    bookingService.book(CUSTOMER, req, null);

    ArgumentCaptor<FeeEstimateRequest> priceCaptor = ArgumentCaptor.forClass(FeeEstimateRequest.class);
    verify(pricingService).estimateFee(priceCaptor.capture());
    // DestinationType is forwarded directly as OTHER_DISTRICT, not corrected to SAME_CITY
    assertThat(priceCaptor.getValue().destinationType()).isEqualTo(DestinationType.OTHER_DISTRICT);
    assertThat(priceCaptor.getValue().pickupCity()).isEqualTo("Colombo");
    assertThat(priceCaptor.getValue().deliveryCity()).isEqualTo("Colombo");
  }

  @Test
  @DisplayName("G11: Estimate quote charges volumetric weight (12 kg = 1870 LKR) whereas booking has no dimensions and charges actual weight (1 kg = 550 LKR)")
  void g11_dimensionQuoteVersusBookingDiscrepancy() {
    // Dimensions: 50 x 40 x 30 cm, actual weight 1.0 kg -> volumetric weight = 12.0 kg
    // Under default rule: base fee 550.00 (includes 1.0 kg), extra weight 11.0 kg * 120.00/kg = 1320.00 LKR
    // Total quote = 1870.00 LKR
    FeeEstimateRequest dimensionalEstimate = new FeeEstimateRequest(
        "Colombo", "Colombo", DestinationType.SAME_CITY, null,
        new BigDecimal("1.00"), "Merchandise", false, null,
        new BigDecimal("50.00"), new BigDecimal("40.00"), new BigDecimal("30.00"),
        null, null, "LKR");
    FeeEstimateResponse quote = engine.calculate(dimensionalEstimate, defaultRule);
    assertThat(quote.fee()).isEqualByComparingTo("1870.00");
    assertThat(quote.breakdown().weightFee()).isEqualByComparingTo("1320.00");

    // In booking, CreateParcelBookingRequest has no length/width/height fields
    // Booking only supplies parcel.weightKg = 1.0 kg -> price calculated is 550.00 LKR
    FeeEstimateRequest bookingPricedRequest = new FeeEstimateRequest(
        "Colombo", "Colombo", DestinationType.SAME_CITY, ParcelSize.SMALL,
        new BigDecimal("1.00"), "Merchandise", false, null,
        null, null, null,
        null, null, "LKR");
    FeeEstimateResponse bookingPrice = engine.calculate(bookingPricedRequest, defaultRule);
    assertThat(bookingPrice.fee()).isEqualByComparingTo("550.00");
    assertThat(bookingPrice.breakdown().weightFee()).isEqualByComparingTo("0.00");

    // Discrepancy is 1320.00 LKR
    BigDecimal discrepancy = quote.fee().subtract(bookingPrice.fee());
    assertThat(discrepancy).isEqualByComparingTo("1320.00");
  }

  @Test
  @DisplayName("G12: Booking permits identical sender and recipient address without warning or rejection")
  void g12_bookingPermitsIdenticalSenderAndRecipientAddress() {
    FeeEstimateResponse stubPrice = new FeeEstimateResponse(
        new BigDecimal("550.00"), "550.00 LKR", "LKR", 2,
        new FeeEstimateResponse.Breakdown(new BigDecimal("550.00"), BigDecimal.ZERO, null, null));
    when(pricingService.estimateFee(any())).thenReturn(stubPrice);
    when(trackingReferenceGenerator.next()).thenReturn("ZT-CHARACT002");
    when(repository.insert(any())).thenAnswer(inv -> saved(inv.getArgument(0)));

    String identicalAddress = "12 Galle Road, Colombo 03";
    CreateParcelBookingRequest req = new CreateParcelBookingRequest(
        new CreateParcelBookingRequest.Contact("Alice", "0771234567", identicalAddress, "Colombo"),
        new CreateParcelBookingRequest.Contact("Bob", "0771234568", identicalAddress, "Colombo"),
        new CreateParcelBookingRequest.ParcelDetails(new BigDecimal("1.00"), "Box", ParcelSize.SMALL, null),
        DestinationType.SAME_CITY,
        false,
        null);

    BookingResult result = bookingService.book(CUSTOMER, req, null);
    assertThat(result.created()).isTrue();
    assertThat(result.booking().sender().address()).isEqualTo(identicalAddress);
    assertThat(result.booking().recipient().address()).isEqualTo(identicalAddress);
    assertThat(result.booking().status()).isEqualTo(ParcelStatus.CREATED);
  }

  @Test
  @DisplayName("G13: PricingService and ZoneService fall back to approved defaults on database failure")
  @SuppressWarnings("unchecked")
  void g13_servicesFallbackToApprovedDefaultsOnDatabaseFailure() {
    PricingService realPricingService = new PricingService(jdbc, engine);
    when(jdbc.query(any(String.class), any(RowMapper.class)))
        .thenThrow(new DataRetrievalFailureException("Simulated database outage"));

    PricingRule activeRule = realPricingService.getActiveRule();
    assertThat(activeRule.ruleName()).isEqualTo("STANDARD_DEFAULT");
    assertThat(activeRule.baseFee()).isEqualByComparingTo("550.00");

    ZoneService realZoneService = new ZoneService(jdbc);
    List<DeliveryZone> zones = realZoneService.getActiveZones();
    assertThat(zones).isNotEmpty();
    assertThat(zones).extracting(DeliveryZone::code).contains("ZONE_COLOMBO_METRO", "ZONE_WESTERN");
  }

  private static ParcelBooking saved(ParcelBooking draft) {
    OffsetDateTime now = OffsetDateTime.now();
    return new ParcelBooking(
        UUID.randomUUID(), draft.trackingRef(), draft.customerSubject(), draft.sender(),
        draft.recipient(), draft.weightKg(), draft.size(), draft.category(), draft.description(),
        draft.destinationType(), draft.express(), draft.notes(), draft.fee(), draft.currency(),
        draft.estimatedDays(), draft.status(), draft.idempotencyKey(), draft.requestHash(),
        draft.fingerprintVersion(), now, now);
  }
}
