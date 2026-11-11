package com.zentro.trackingreporting.graphql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zentro.trackingreporting.graphql.config.GraphQlConfig;
import com.zentro.trackingreporting.graphql.endpoint.TrackingGraphQlEndpoint;
import com.zentro.trackingreporting.graphql.exception.GraphQlExceptionResolver;
import com.zentro.trackingreporting.security.DeliveryAuthorizationService;
import com.zentro.trackingreporting.tracking.entity.TrackingParcel;
import com.zentro.trackingreporting.tracking.entity.TrackingStatus;
import com.zentro.trackingreporting.tracking.repository.TrackingEventRepository;
import com.zentro.trackingreporting.tracking.repository.TrackingParcelRepository;
import java.util.List;
import java.util.Optional;
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
class TrackingGraphQlQueryLimitsTest {

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
    parcel.setTrackingRef("ZT-TEST123456");
    parcel.setStatus(TrackingStatus.CREATED);
    parcel.setCustomerSubject("customer-alice");
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  @Test
  @DisplayName("Query with depth within limit (<= 7) succeeds")
  void queryDepth_withinLimit_succeeds() {
    when(parcelRepository.findByTrackingRef("ZT-TEST123456")).thenReturn(Optional.of(parcel));

    String document = """
        query {
          publicTracking(trackingRef: "ZT-TEST123456") {
            trackingRef
            status
            events {
              id
              occurredAt
            }
          }
        }
        """;

    graphQlTester.document(document)
        .execute()
        .path("publicTracking.trackingRef").entity(String.class).isEqualTo("ZT-TEST123456");
  }

  @Test
  @DisplayName("Query exceeding max depth 7 is rejected with depth limit error")
  void queryDepth_exceedingLimit_rejected() {
    // Introspection query nested deeper than 7 levels
    String document = """
        query {
          __schema {
            types {
              fields {
                type {
                  ofType {
                    ofType {
                      ofType {
                        ofType {
                          name
                        }
                      }
                    }
                  }
                }
              }
            }
          }
        }
        """;

    graphQlTester.document(document)
        .execute()
        .errors()
        .satisfy(errors -> {
          assertThat(errors).isNotEmpty();
          assertThat(errors.get(0).getMessage()).containsIgnoringCase("maximum query depth exceeded");
        });
  }

  @Test
  @DisplayName("Query exceeding max complexity 100 is rejected with complexity limit error")
  void queryComplexity_exceedingLimit_rejected() {
    // High complexity query: multiple connection queries or large field trees
    StringBuilder sb = new StringBuilder("query {\n");
    for (int i = 1; i <= 60; i++) {
      sb.append("  q").append(i).append(": publicTracking(trackingRef: \"ZT-TEST123456\") { trackingRef status category }\n");
    }
    sb.append("}");

    graphQlTester.document(sb.toString())
        .execute()
        .errors()
        .satisfy(errors -> {
          assertThat(errors).isNotEmpty();
          assertThat(errors.get(0).getMessage()).containsIgnoringCase("maximum query complexity exceeded");
        });
  }

  @Test
  @DisplayName("Pagination size exceeding max 50 is safely capped at 50")
  void pagination_sizeExceeding50_cappedAt50() {
    when(parcelRepository.findByCustomerSubject(eq("customer-alice"), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of(parcel)));

    SecurityContextHolder.getContext().setAuthentication(
        new TestingAuthenticationToken("customer-alice", "cred", List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")))
    );

    String document = """
        query {
          myDeliveries(page: 1, size: 100) {
            pageInfo {
              page
              size
            }
          }
        }
        """;

    graphQlTester.document(document)
        .execute()
        .path("myDeliveries.pageInfo.size").entity(Integer.class).isEqualTo(50);

    ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
    verify(parcelRepository).findByCustomerSubject(eq("customer-alice"), captor.capture());
    assertThat(captor.getValue().getPageSize()).isEqualTo(50);
  }

  @Test
  @DisplayName("Pagination page < 1 is rejected with BAD_REQUEST error")
  void pagination_invalidPage_rejected() {
    SecurityContextHolder.getContext().setAuthentication(
        new TestingAuthenticationToken("customer-alice", "cred", List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")))
    );

    String document = """
        query {
          myDeliveries(page: 0, size: 10) {
            pageInfo {
              page
            }
          }
        }
        """;

    graphQlTester.document(document)
        .execute()
        .errors()
        .satisfy(errors -> {
          assertThat(errors).isNotEmpty();
          assertThat(errors.get(0).getErrorType().toString()).contains("BAD_REQUEST");
          assertThat(errors.get(0).getMessage()).containsIgnoringCase("page number must be greater than or equal to 1");
        });
  }

  @Test
  @DisplayName("Pagination size < 1 is rejected with BAD_REQUEST error")
  void pagination_invalidSize_rejected() {
    SecurityContextHolder.getContext().setAuthentication(
        new TestingAuthenticationToken("customer-alice", "cred", List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")))
    );

    String document = """
        query {
          myDeliveries(page: 1, size: 0) {
            pageInfo {
              page
            }
          }
        }
        """;

    graphQlTester.document(document)
        .execute()
        .errors()
        .satisfy(errors -> {
          assertThat(errors).isNotEmpty();
          assertThat(errors.get(0).getErrorType().toString()).contains("BAD_REQUEST");
          assertThat(errors.get(0).getMessage()).containsIgnoringCase("page size must be greater than or equal to 1");
        });
  }
}
