package com.zentro.trackingreporting.graphql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zentro.trackingreporting.graphql.config.GraphQlConfig;
import com.zentro.trackingreporting.graphql.endpoint.TrackingGraphQlEndpoint;
import com.zentro.trackingreporting.graphql.exception.GraphQlExceptionResolver;
import com.zentro.trackingreporting.security.DeliveryAuthorizationService;
import com.zentro.trackingreporting.tracking.entity.TrackingEvent;
import com.zentro.trackingreporting.tracking.entity.TrackingParcel;
import com.zentro.trackingreporting.tracking.entity.TrackingStatus;
import com.zentro.trackingreporting.tracking.repository.TrackingEventRepository;
import com.zentro.trackingreporting.tracking.repository.TrackingParcelRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
class TrackingGraphQlBatchingTest {

  @Autowired
  private GraphQlTester graphQlTester;

  @MockBean
  private TrackingParcelRepository parcelRepository;

  @MockBean
  private TrackingEventRepository eventRepository;

  @MockBean
  private org.springframework.security.oauth2.jwt.JwtDecoder jwtDecoder;

  @BeforeEach
  void setUp() {
    SecurityContextHolder.clearContext();
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  @Test
  @DisplayName("Batch mapping resolves events for multiple parcels in a single database query (no N+1)")
  void batchMapping_resolvesEventsInSingleQuery() {
    TrackingParcel p1 = createParcel("ZT-AAA111", "customer-alice");
    TrackingParcel p2 = createParcel("ZT-BBB222", "customer-alice");
    TrackingParcel p3 = createParcel("ZT-CCC333", "customer-alice");

    when(parcelRepository.findByCustomerSubject(eq("customer-alice"), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of(p1, p2, p3)));

    TrackingEvent e1 = createEvent("ZT-AAA111", TrackingStatus.CREATED, "Parcel created");
    TrackingEvent e2 = createEvent("ZT-BBB222", TrackingStatus.PICKED_UP, "Parcel picked up");
    TrackingEvent e3 = createEvent("ZT-CCC333", TrackingStatus.IN_TRANSIT, "In transit to hub");

    when(eventRepository.findByTrackingRefInOrderByOccurredAtAsc(anyList()))
        .thenReturn(List.of(e1, e2, e3));

    SecurityContextHolder.getContext().setAuthentication(
        new TestingAuthenticationToken("customer-alice", "cred", List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")))
    );

    String document = """
        query {
          myDeliveries(page: 1, size: 10) {
            items {
              trackingRef
              events {
                status
                title
              }
            }
          }
        }
        """;

    graphQlTester.document(document)
        .execute()
        .path("myDeliveries.items[0].trackingRef").entity(String.class).isEqualTo("ZT-AAA111")
        .path("myDeliveries.items[0].events[0].title").entity(String.class).isEqualTo("Parcel created")
        .path("myDeliveries.items[1].trackingRef").entity(String.class).isEqualTo("ZT-BBB222")
        .path("myDeliveries.items[1].events[0].title").entity(String.class).isEqualTo("Parcel picked up")
        .path("myDeliveries.items[2].trackingRef").entity(String.class).isEqualTo("ZT-CCC333")
        .path("myDeliveries.items[2].events[0].title").entity(String.class).isEqualTo("In transit to hub");

    // Verify findByTrackingRefInOrderByOccurredAtAsc was invoked exactly ONCE for all 3 parcels
    ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
    verify(eventRepository, times(1)).findByTrackingRefInOrderByOccurredAtAsc(captor.capture());
    assertThat(captor.getValue()).containsExactlyInAnyOrder("ZT-AAA111", "ZT-BBB222", "ZT-CCC333");
  }

  private TrackingParcel createParcel(String ref, String customerSubject) {
    TrackingParcel p = new TrackingParcel();
    p.setId(UUID.randomUUID());
    p.setTrackingRef(ref);
    p.setCustomerSubject(customerSubject);
    p.setStatus(TrackingStatus.IN_TRANSIT);
    p.setSenderName("Sender");
    p.setSenderPhone("+94770000001");
    p.setSenderAddress("Address");
    p.setRecipientName("Recipient");
    p.setRecipientPhone("+94770000002");
    p.setRecipientAddress("Address");
    return p;
  }

  private TrackingEvent createEvent(String ref, TrackingStatus status, String title) {
    TrackingEvent event = new TrackingEvent();
    event.setEventId(UUID.randomUUID());
    event.setTrackingRef(ref);
    event.setStatus(status);
    event.setTitle(title);
    event.setOccurredAt(Instant.now());
    return event;
  }
}
