package com.zentro.trackingreporting.graphql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.zentro.trackingreporting.graphql.config.GraphQlConfig;
import com.zentro.trackingreporting.graphql.endpoint.TrackingGraphQlEndpoint;
import com.zentro.trackingreporting.graphql.exception.GraphQlExceptionResolver;
import com.zentro.trackingreporting.security.DeliveryAuthorizationService;
import com.zentro.trackingreporting.tracking.entity.TrackingParcel;
import com.zentro.trackingreporting.tracking.entity.TrackingStatus;
import com.zentro.trackingreporting.tracking.repository.TrackingEventRepository;
import com.zentro.trackingreporting.tracking.repository.TrackingParcelRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.graphql.GraphQlTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.graphql.test.tester.GraphQlTester;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

@GraphQlTest(controllers = TrackingGraphQlEndpoint.class)
@Import({GraphQlConfig.class, GraphQlExceptionResolver.class, DeliveryAuthorizationService.class})
class TrackingGraphQlSecurityTest {

  @Autowired
  private GraphQlTester graphQlTester;

  @MockBean
  private TrackingParcelRepository parcelRepository;

  @MockBean
  private TrackingEventRepository eventRepository;

  @MockBean
  private org.springframework.security.oauth2.jwt.JwtDecoder jwtDecoder;

  private TrackingParcel parcel;

  @BeforeEach
  void setUp() {
    SecurityContextHolder.clearContext();

    parcel = new TrackingParcel();
    parcel.setId(UUID.randomUUID());
    parcel.setParcelId(UUID.randomUUID());
    parcel.setTrackingRef("ZT-TEST123456");
    parcel.setCustomerSubject("customer-alice");
    parcel.setMerchantSubject("merchant-acme");
    parcel.setCourierSubject("courier-bob");
    parcel.setSenderName("Alice Sender");
    parcel.setSenderPhone("+94770000001");
    parcel.setSenderAddress("123 Main Street");
    parcel.setSenderCity("Colombo");
    parcel.setRecipientName("Bob Recipient");
    parcel.setRecipientPhone("+94770000002");
    parcel.setRecipientAddress("456 Market Road");
    parcel.setRecipientCity("Kandy");
    parcel.setWeightKg(new BigDecimal("2.50"));
    parcel.setSize("MEDIUM");
    parcel.setCategory("ELECTRONICS");
    parcel.setDescription("Laptop charger");
    parcel.setDestinationType("HOME");
    parcel.setExpress(true);
    parcel.setFee(new BigDecimal("750.00"));
    parcel.setCurrency("LKR");
    parcel.setEstimatedDays(2);
    parcel.setStatus(TrackingStatus.IN_TRANSIT);
    parcel.setCreatedAt(Instant.parse("2026-11-10T10:00:00Z"));
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  @Test
  @DisplayName("Public tracking succeeds unauthenticated and does NOT expose any PII or fee data")
  void publicTracking_unauthenticated_succeeds_noPii() {
    when(parcelRepository.findByTrackingRef("ZT-TEST123456")).thenReturn(Optional.of(parcel));

    String document = """
        query {
          publicTracking(trackingRef: "ZT-TEST123456") {
            trackingRef
            status
            rawStatus
            category
            size
            description
            originCity
            destinationCity
            estimatedDays
            createdAt
          }
        }
        """;

    graphQlTester.document(document)
        .execute()
        .path("publicTracking.trackingRef").entity(String.class).isEqualTo("ZT-TEST123456")
        .path("publicTracking.status").entity(String.class).isEqualTo("IN_TRANSIT")
        .path("publicTracking.originCity").entity(String.class).isEqualTo("Colombo")
        .path("publicTracking.destinationCity").entity(String.class).isEqualTo("Kandy");
  }

  @Test
  @DisplayName("Authenticated query 'delivery' fails when unauthenticated")
  void delivery_unauthenticated_fails() {
    when(parcelRepository.findByTrackingRef("ZT-TEST123456")).thenReturn(Optional.of(parcel));

    String document = """
        query {
          delivery(trackingRef: "ZT-TEST123456") {
            id
            trackingRef
          }
        }
        """;

    graphQlTester.document(document)
        .execute()
        .errors()
        .satisfy(errors -> {
          assertThat(errors).isNotEmpty();
          assertThat(errors.get(0).getErrorType().toString()).contains("UNAUTHORIZED");
        });
  }

  @Test
  @DisplayName("Customer can access their own delivery with full details including PII and fees")
  void delivery_customerOwnParcel_succeeds() {
    when(parcelRepository.findByTrackingRef("ZT-TEST123456")).thenReturn(Optional.of(parcel));

    SecurityContextHolder.getContext().setAuthentication(
        new TestingAuthenticationToken("customer-alice", "cred", List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")))
    );

    String document = """
        query {
          delivery(trackingRef: "ZT-TEST123456") {
            trackingRef
            status
            sender {
              name
              phone
              address
              city
            }
            recipient {
              name
              phone
              address
              city
            }
            fee
            currency
          }
        }
        """;

    graphQlTester.document(document)
        .execute()
        .path("delivery.trackingRef").entity(String.class).isEqualTo("ZT-TEST123456")
        .path("delivery.sender.name").entity(String.class).isEqualTo("Alice Sender")
        .path("delivery.recipient.name").entity(String.class).isEqualTo("Bob Recipient")
        .path("delivery.fee").entity(Double.class).isEqualTo(750.0)
        .path("delivery.currency").entity(String.class).isEqualTo("LKR");
  }

  @Test
  @DisplayName("Customer cannot access another customer's delivery (forbidden)")
  void delivery_customerOtherParcel_forbidden() {
    when(parcelRepository.findByTrackingRef("ZT-TEST123456")).thenReturn(Optional.of(parcel));

    SecurityContextHolder.getContext().setAuthentication(
        new TestingAuthenticationToken("customer-eve", "cred", List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")))
    );

    String document = """
        query {
          delivery(trackingRef: "ZT-TEST123456") {
            trackingRef
          }
        }
        """;

    graphQlTester.document(document)
        .execute()
        .errors()
        .satisfy(errors -> {
          assertThat(errors).isNotEmpty();
          assertThat(errors.get(0).getErrorType().toString()).contains("FORBIDDEN");
        });
  }

  @Test
  @DisplayName("Merchant can access their own delivery")
  void delivery_merchantOwnParcel_succeeds() {
    when(parcelRepository.findByTrackingRef("ZT-TEST123456")).thenReturn(Optional.of(parcel));

    SecurityContextHolder.getContext().setAuthentication(
        new TestingAuthenticationToken("merchant-acme", "cred", List.of(new SimpleGrantedAuthority("ROLE_MERCHANT")))
    );

    String document = """
        query {
          delivery(trackingRef: "ZT-TEST123456") {
            trackingRef
          }
        }
        """;

    graphQlTester.document(document)
        .execute()
        .path("delivery.trackingRef").entity(String.class).isEqualTo("ZT-TEST123456");
  }

  @Test
  @DisplayName("Merchant cannot access other merchant delivery (forbidden)")
  void delivery_merchantOtherParcel_forbidden() {
    when(parcelRepository.findByTrackingRef("ZT-TEST123456")).thenReturn(Optional.of(parcel));

    SecurityContextHolder.getContext().setAuthentication(
        new TestingAuthenticationToken("merchant-other", "cred", List.of(new SimpleGrantedAuthority("ROLE_MERCHANT")))
    );

    String document = """
        query {
          delivery(trackingRef: "ZT-TEST123456") {
            trackingRef
          }
        }
        """;

    graphQlTester.document(document)
        .execute()
        .errors()
        .satisfy(errors -> {
          assertThat(errors).isNotEmpty();
          assertThat(errors.get(0).getErrorType().toString()).contains("FORBIDDEN");
        });
  }

  @Test
  @DisplayName("Courier can access assigned delivery")
  void delivery_courierAssignedParcel_succeeds() {
    when(parcelRepository.findByTrackingRef("ZT-TEST123456")).thenReturn(Optional.of(parcel));

    SecurityContextHolder.getContext().setAuthentication(
        new TestingAuthenticationToken("courier-bob", "cred", List.of(new SimpleGrantedAuthority("ROLE_COURIER")))
    );

    String document = """
        query {
          delivery(trackingRef: "ZT-TEST123456") {
            trackingRef
          }
        }
        """;

    graphQlTester.document(document)
        .execute()
        .path("delivery.trackingRef").entity(String.class).isEqualTo("ZT-TEST123456");
  }

  @Test
  @DisplayName("Courier cannot access unassigned delivery (forbidden)")
  void delivery_courierUnassignedParcel_forbidden() {
    when(parcelRepository.findByTrackingRef("ZT-TEST123456")).thenReturn(Optional.of(parcel));

    SecurityContextHolder.getContext().setAuthentication(
        new TestingAuthenticationToken("courier-other", "cred", List.of(new SimpleGrantedAuthority("ROLE_COURIER")))
    );

    String document = """
        query {
          delivery(trackingRef: "ZT-TEST123456") {
            trackingRef
          }
        }
        """;

    graphQlTester.document(document)
        .execute()
        .errors()
        .satisfy(errors -> {
          assertThat(errors).isNotEmpty();
          assertThat(errors.get(0).getErrorType().toString()).contains("FORBIDDEN");
        });
  }

  @Test
  @DisplayName("Dispatcher can access any delivery")
  void delivery_dispatcher_succeeds() {
    when(parcelRepository.findByTrackingRef("ZT-TEST123456")).thenReturn(Optional.of(parcel));

    SecurityContextHolder.getContext().setAuthentication(
        new TestingAuthenticationToken("dispatcher-dan", "cred", List.of(new SimpleGrantedAuthority("ROLE_DISPATCHER")))
    );

    String document = """
        query {
          delivery(trackingRef: "ZT-TEST123456") {
            trackingRef
          }
        }
        """;

    graphQlTester.document(document)
        .execute()
        .path("delivery.trackingRef").entity(String.class).isEqualTo("ZT-TEST123456");
  }

  @Test
  @DisplayName("Dispatcher can access platform-wide deliveries query")
  void deliveries_dispatcher_succeeds() {
    when(parcelRepository.findAll(any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of(parcel)));

    SecurityContextHolder.getContext().setAuthentication(
        new TestingAuthenticationToken("dispatcher-dan", "cred", List.of(new SimpleGrantedAuthority("ROLE_DISPATCHER")))
    );

    String document = """
        query {
          deliveries(page: 1, size: 10) {
            items {
              trackingRef
            }
            pageInfo {
              page
              size
              totalElements
              totalPages
            }
          }
        }
        """;

    graphQlTester.document(document)
        .execute()
        .path("deliveries.items[0].trackingRef").entity(String.class).isEqualTo("ZT-TEST123456")
        .path("deliveries.pageInfo.page").entity(Integer.class).isEqualTo(1)
        .path("deliveries.pageInfo.totalElements").entity(Integer.class).isEqualTo(1);
  }

  @Test
  @DisplayName("Customer cannot access platform-wide deliveries query (forbidden)")
  void deliveries_customer_forbidden() {
    SecurityContextHolder.getContext().setAuthentication(
        new TestingAuthenticationToken("customer-alice", "cred", List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")))
    );

    String document = """
        query {
          deliveries(page: 1, size: 10) {
            items {
              trackingRef
            }
          }
        }
        """;

    graphQlTester.document(document)
        .execute()
        .errors()
        .satisfy(errors -> {
          assertThat(errors).isNotEmpty();
          assertThat(errors.get(0).getErrorType().toString()).contains("FORBIDDEN");
        });
  }

  @Test
  @DisplayName("myDeliveries returns customer parcels only")
  void myDeliveries_customer_succeeds() {
    when(parcelRepository.findByCustomerSubject(eq("customer-alice"), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of(parcel)));

    SecurityContextHolder.getContext().setAuthentication(
        new TestingAuthenticationToken("customer-alice", "cred", List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")))
    );

    String document = """
        query {
          myDeliveries(page: 1, size: 10) {
            items {
              trackingRef
            }
            pageInfo {
              totalElements
            }
          }
        }
        """;

    graphQlTester.document(document)
        .execute()
        .path("myDeliveries.items[0].trackingRef").entity(String.class).isEqualTo("ZT-TEST123456")
        .path("myDeliveries.pageInfo.totalElements").entity(Integer.class).isEqualTo(1);
  }
}
