package com.zentro.trackingreporting.graphql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.zentro.trackingreporting.graphql.config.GraphQlConfig;
import com.zentro.trackingreporting.graphql.endpoint.TrackingGraphQlEndpoint;
import com.zentro.trackingreporting.graphql.exception.GraphQlExceptionResolver;
import com.zentro.trackingreporting.security.DeliveryAuthorizationService;
import com.zentro.trackingreporting.tracking.entity.TrackingParcel;
import com.zentro.trackingreporting.tracking.entity.TrackingStatus;
import com.zentro.trackingreporting.tracking.repository.TrackingEventRepository;
import com.zentro.trackingreporting.tracking.repository.TrackingParcelRepository;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.graphql.GraphQlTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.graphql.test.tester.GraphQlTester;

@GraphQlTest(controllers = TrackingGraphQlEndpoint.class)
@Import({GraphQlConfig.class, GraphQlExceptionResolver.class, DeliveryAuthorizationService.class})
class TrackingGraphQlReadOnlyTest {

  @Autowired
  private GraphQlTester graphQlTester;

  @MockBean
  private TrackingParcelRepository parcelRepository;

  @MockBean
  private TrackingEventRepository eventRepository;

  @MockBean
  private org.springframework.security.oauth2.jwt.JwtDecoder jwtDecoder;

  @Test
  @DisplayName("GraphQL schema strictly supports Query operations")
  void queryOperations_succeed() {
    TrackingParcel parcel = new TrackingParcel();
    parcel.setTrackingRef("ZT-TEST123456");
    parcel.setStatus(TrackingStatus.CREATED);
    parcel.setCategory("Standard");
    when(parcelRepository.findByTrackingRef("ZT-TEST123456")).thenReturn(Optional.of(parcel));

    String document = """
        query {
          publicTracking(trackingRef: "ZT-TEST123456") {
            trackingRef
            status
          }
        }
        """;

    graphQlTester.document(document)
        .execute()
        .path("publicTracking.trackingRef").entity(String.class).isEqualTo("ZT-TEST123456")
        .path("publicTracking.status").entity(String.class).isEqualTo("CREATED");
  }

  @Test
  @DisplayName("GraphQL schema strictly rejects Mutation operations because no mutations exist in schema")
  void mutationOperations_failAtSchemaLevel() {
    String document = """
        mutation UpdateParcelStatus {
          updateStatus(trackingRef: "ZT-TEST123456", status: "DELIVERED") {
            trackingRef
          }
        }
        """;

    graphQlTester.document(document)
        .execute()
        .errors()
        .satisfy(errors -> {
          assertThat(errors).isNotEmpty();
          String errorMessage = errors.get(0).getMessage();
          // Schema does not define Mutation type
          assertThat(errorMessage).containsIgnoringCase("mutation");
        });
  }
}
