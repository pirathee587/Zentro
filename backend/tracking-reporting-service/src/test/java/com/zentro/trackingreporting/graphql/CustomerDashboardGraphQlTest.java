package com.zentro.trackingreporting.graphql;

import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.zentro.trackingreporting.graphql.config.GraphQlConfig;
import com.zentro.trackingreporting.graphql.endpoint.*;
import com.zentro.trackingreporting.graphql.exception.GraphQlExceptionResolver;
import com.zentro.trackingreporting.graphql.dto.CustomerDashboardSummary;
import com.zentro.trackingreporting.graphql.service.*;
import com.zentro.trackingreporting.security.DeliveryAuthorizationService;
import com.zentro.trackingreporting.tracking.repository.*;
import com.zentro.trackingreporting.tracking.entity.TrackingParcel;
import java.util.List;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.graphql.GraphQlTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.graphql.test.tester.GraphQlTester;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

@GraphQlTest(controllers = {CustomerDashboardGraphQlEndpoint.class, TrackingGraphQlEndpoint.class})
@Import({GraphQlConfig.class, GraphQlExceptionResolver.class, DeliveryAuthorizationService.class})
class CustomerDashboardGraphQlTest {
  @MockBean com.zentro.trackingreporting.graphql.service.CustomerBookingClient bookings;
  @Autowired GraphQlTester tester;
  @MockBean CustomerDashboardService dashboard;
  @MockBean DashboardPricingClient pricing;
  @MockBean TrackingParcelRepository parcels;
  @MockBean TrackingEventRepository events;
  @MockBean org.springframework.security.oauth2.jwt.JwtDecoder decoder;
  private org.springframework.security.core.Authentication authentication(String... roles) {
    var jwt=org.springframework.security.oauth2.jwt.Jwt.withTokenValue("synthetic-customer-token").header("alg","RS256").subject("alice").build();
    return new org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken(jwt,
      java.util.Arrays.stream(roles).map(org.springframework.security.core.authority.SimpleGrantedAuthority::new).toList());
  }
  @AfterEach void clear() { SecurityContextHolder.clearContext(); }
  @Test void anonymousAndNonCustomersCannotQuerySummary() {
    for (String role : List.of("", "ROLE_COURIER", "ROLE_MERCHANT")) {
      if (role.isEmpty()) SecurityContextHolder.clearContext();
      else SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("bob", "", role));
      tester.document("{ customerDashboardSummary { activeCount } }").execute().errors().satisfy(errors ->
          org.assertj.core.api.Assertions.assertThat(errors).isNotEmpty());
    }
    verifyNoInteractions(dashboard);
  }
  @Test void summaryUsesAuthenticatedSubjectAndLiveFieldsRemainAccurate() {
    SecurityContextHolder.getContext().setAuthentication(authentication("ROLE_CUSTOMER"));
    when(dashboard.summary(org.mockito.ArgumentMatchers.any(org.springframework.security.oauth2.jwt.Jwt.class))).thenReturn(new CustomerDashboardSummary(1,2,1,0,0,3,"2026-11","Asia/Colombo",List.of(),List.of()));
    tester.document("{ customerDashboardSummary { activeCount deliveredCount totalParcels month } }").execute()
        .path("customerDashboardSummary.activeCount").entity(Integer.class).isEqualTo(1);
    verify(dashboard).summary(argThat(jwt -> "alice".equals(jwt.getSubject())));
    var p = new TrackingParcel(); p.setTrackingRef("ZT-ALICE01"); p.setSenderName("Alice"); p.setSenderPhone("000"); p.setSenderAddress("Owned");
    p.setRecipientName("Recipient"); p.setRecipientPhone("000"); p.setRecipientAddress("Owned");
    p.setWeightKg(new java.math.BigDecimal("2.5"));
    when(dashboard.liveParcel(org.mockito.ArgumentMatchers.any(org.springframework.security.oauth2.jwt.Jwt.class))).thenReturn(p);
    tester.document("{ customerLiveDelivery { trackingRef weightKg estimatedDeliveryDate } }").execute()
        .path("customerLiveDelivery.weightKg").entity(Double.class).isEqualTo(2.5);
    verify(dashboard).liveParcel(argThat(jwt -> "alice".equals(jwt.getSubject())));
    verify(dashboard, never()).summary(argThat(jwt -> "bob".equals(jwt.getSubject())));
  }
  @Test void customerParcelLookupUsesTheAuthenticatedJwtAndS2OwnerRead() {
    var jwt=org.springframework.security.oauth2.jwt.Jwt.withTokenValue("synthetic-customer-token").header("alg","RS256").subject("alice").build();
    SecurityContextHolder.getContext().setAuthentication(new org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken(
        jwt,List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_CUSTOMER"))));
    var parcel=new TrackingParcel();parcel.setTrackingRef("ZT-ALICE01");parcel.setStatus(com.zentro.trackingreporting.tracking.entity.TrackingStatus.CREATED);
    when(dashboard.parcel(jwt,"ZT-ALICE01")).thenReturn(parcel);
    tester.document("{ customerDelivery(trackingRef: \"ZT-ALICE01\") { trackingRef status } }").execute()
       .path("customerDelivery.trackingRef").entity(String.class).isEqualTo("ZT-ALICE01");
    verify(dashboard).parcel(jwt,"ZT-ALICE01");
  }

  @Test void summaryDoesNotAcceptCustomerIdentifier() {
    tester.document("{ customerDashboardSummary(customerId: \"bob\") { activeCount } }").execute()
        .errors().satisfy(errors -> org.assertj.core.api.Assertions.assertThat(errors).isNotEmpty());
    verifyNoInteractions(dashboard);
  }
  @Test void dashboardHistoryRemainsCustomerScopedForMixedDispatcherRoles() {
    SecurityContextHolder.getContext().setAuthentication(authentication("ROLE_CUSTOMER", "ROLE_DISPATCHER"));
    when(bookings.page(any(), any(), any(), any())).thenReturn(new org.springframework.data.domain.PageImpl<>(List.of()));
    tester.document("{ myDeliveries(customerOnly: true) { pageInfo { totalElements } } }").execute()
        .path("myDeliveries.pageInfo.totalElements").entity(Integer.class).isEqualTo(0);
    verify(bookings).page(argThat(jwt -> "alice".equals(jwt.getSubject())), any(), any(), any());
    verify(parcels, never()).findAll(any(org.springframework.data.domain.Pageable.class));
  }

  @Test void quotationResolverPassesOnlyValidatedCallerJwtToExistingPricingAdapter() {
    var jwt = org.springframework.security.oauth2.jwt.Jwt.withTokenValue("synthetic-customer-token")
        .header("alg", "RS256").subject("alice").build();
    SecurityContextHolder.getContext().setAuthentication(new org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken(
        jwt, List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_CUSTOMER"))));
    when(pricing.estimate(jwt, null, null, 2.0, "OTHER_DISTRICT", null)).thenReturn(
        new com.zentro.trackingreporting.graphql.dto.DashboardFeeEstimate(new java.math.BigDecimal("925.50"), "LKR 925.50", "LKR", 3,
            new com.zentro.trackingreporting.graphql.dto.DashboardFeeEstimate.Breakdown(new java.math.BigDecimal("500"), new java.math.BigDecimal("425.50"), null, null)));
    tester.document("{ dashboardFeeEstimate(weightKg: 2, destinationType: \"OTHER_DISTRICT\") { fee currency } }").execute()
        .path("dashboardFeeEstimate.fee").entity(Double.class).isEqualTo(925.5);
    verify(pricing).estimate(jwt, null, null, 2.0, "OTHER_DISTRICT", null);
  }

}
