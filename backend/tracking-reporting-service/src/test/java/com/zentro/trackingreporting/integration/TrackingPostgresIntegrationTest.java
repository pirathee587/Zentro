package com.zentro.trackingreporting.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zentro.trackingreporting.domain.TrackingParcel;
import com.zentro.trackingreporting.domain.TrackingStatus;
import com.zentro.trackingreporting.service.TrackingService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class TrackingPostgresIntegrationTest {

  @Container
  static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
    registry.add("zentro.security.issuer-uri", () -> "http://localhost:8180/realms/zentro");
    registry.add("zentro.security.audience", () -> "zentro-api");
  }

  @Autowired private MockMvc mockMvc;
  @Autowired private TrackingService trackingService;
  @MockBean private JwtDecoder jwtDecoder;

  @Test
  @DisplayName("Full tracking lifecycle: creates parcel, records out-of-order events, queries in chronological order")
  void trackingLifecycleChronologicalAndPrivacy() throws Exception {
    String trackingRef = "ZT-INTEG12345";
    String customer = "customer-alice";

    TrackingParcel parcel = new TrackingParcel();
    parcel.setTrackingRef(trackingRef);
    parcel.setParcelId(UUID.randomUUID());
    parcel.setCustomerSubject(customer);
    parcel.setSenderName("Alice Sender");
    parcel.setSenderPhone("0771234567");
    parcel.setSenderAddress("10 Secret Lane");
    parcel.setSenderCity("Colombo");
    parcel.setRecipientName("Bob Receiver");
    parcel.setRecipientPhone("0779998888");
    parcel.setRecipientAddress("20 Hidden Road");
    parcel.setRecipientCity("Galle");
    parcel.setFee(new BigDecimal("1200.00"));
    parcel.setStatus(TrackingStatus.CREATED);
    parcel.setCreatedAt(Instant.parse("2026-11-04T08:00:00Z"));

    trackingService.saveParcel(parcel);

    // Insert subsequent events out of chronological order to verify database ordering
    Instant t3 = Instant.parse("2026-11-04T14:00:00Z");
    Instant t2 = Instant.parse("2026-11-04T10:30:00Z");

    trackingService.recordEvent(
        trackingRef, parcel.getParcelId(), TrackingStatus.IN_TRANSIT, "PICKED_UP",
        "DISPATCHER", "In Transit", "Highway transfer", null, "Expressway", t3, UUID.randomUUID()
    );

    trackingService.recordEvent(
        trackingRef, parcel.getParcelId(), TrackingStatus.PICKED_UP, "CREATED",
        "COURIER", "Picked Up", "Courier collected parcel", null, "Colombo", t2, UUID.randomUUID()
    );

    // 1. Verify Public Tracking excludes sensitive data and orders events
    mockMvc.perform(get("/api/v1/tracking/{trackingRef}", trackingRef)
            .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.trackingRef").value(trackingRef))
        .andExpect(jsonPath("$.originCity").value("Colombo"))
        .andExpect(jsonPath("$.destinationCity").value("Galle"))
        .andExpect(jsonPath("$.sender").doesNotExist())
        .andExpect(jsonPath("$.recipient").doesNotExist())
        .andExpect(jsonPath("$.senderPhone").doesNotExist())
        .andExpect(jsonPath("$.recipientPhone").doesNotExist())
        .andExpect(jsonPath("$.fee").doesNotExist())
        // Verify chronological order in events
        .andExpect(jsonPath("$.events[0].status").value("CREATED"))
        .andExpect(jsonPath("$.events[1].status").value("PICKED_UP"))
        .andExpect(jsonPath("$.events[2].status").value("IN_TRANSIT"));

    // 2. Verify Customer Deliveries endpoint returns full authorized data strictly for the owner
    mockMvc.perform(get("/api/v1/tracking/mine")
            .with(jwt().jwt(j -> j.subject(customer)).authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER")))
            .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.content[0].trackingRef").value(trackingRef))
        .andExpect(jsonPath("$.content[0].sender.name").value("Alice Sender"))
        .andExpect(jsonPath("$.content[0].recipient.name").value("Bob Receiver"))
        .andExpect(jsonPath("$.content[0].fee").value(1200.00));

    // 3. Verify Customer Ownership Isolation: Another customer cannot see Alice's deliveries
    mockMvc.perform(get("/api/v1/tracking/mine")
            .with(jwt().jwt(j -> j.subject("customer-charlie-other")).authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER")))
            .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(0))
        .andExpect(jsonPath("$.content").isEmpty());
  }
}
