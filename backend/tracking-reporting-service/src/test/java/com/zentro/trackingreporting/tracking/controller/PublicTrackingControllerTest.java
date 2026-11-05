package com.zentro.trackingreporting.tracking.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zentro.trackingreporting.tracking.dto.PublicTrackingResponse;
import com.zentro.trackingreporting.tracking.dto.TrackingEventDto;
import com.zentro.trackingreporting.tracking.dto.TrackingMilestoneDto;
import com.zentro.trackingreporting.exception.GlobalTrackingExceptionHandler;
import com.zentro.trackingreporting.exception.TrackingNotFoundException;
import com.zentro.trackingreporting.tracking.service.TrackingService;
import com.zentro.trackingreporting.tracking.service.TrackingStreamService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@WebMvcTest(controllers = PublicTrackingController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalTrackingExceptionHandler.class)
class PublicTrackingControllerTest {

  @Autowired private MockMvc mockMvc;
  @MockBean private TrackingService trackingService;
  @MockBean private TrackingStreamService streamService;
  @MockBean private JwtDecoder jwtDecoder;

  @Test
  @DisplayName("GET /api/v1/tracking/{trackingRef} returns public tracking view without sensitive data")
  void getPublicTrackingSuccess() throws Exception {
    String ref = "ZT-7K3QX9M2PA";
    PublicTrackingResponse mockResponse = new PublicTrackingResponse(
        ref,
        "In progress",
        "IN_TRANSIT",
        "Small Box",
        "SMALL",
        "Books",
        "Colombo",
        "Kandy",
        3,
        Instant.parse("2026-11-07T10:00:00Z"),
        Instant.parse("2026-11-04T10:00:00Z"),
        null,
        null,
        List.of(new TrackingEventDto(UUID.randomUUID(), ref, "IN_TRANSIT", "In Transit", "Sorting hub", "COURIER", "Colombo", Instant.now(), null)),
        List.of(new TrackingMilestoneDto("1", "In transit", "Sorting hub", Instant.now(), "current", "Colombo", null))
    );

    when(trackingService.getPublicTracking(ref)).thenReturn(mockResponse);

    mockMvc.perform(get("/api/v1/tracking/{trackingRef}", ref)
            .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.trackingRef").value(ref))
        .andExpect(jsonPath("$.status").value("In progress"))
        .andExpect(jsonPath("$.rawStatus").value("IN_TRANSIT"))
        .andExpect(jsonPath("$.originCity").value("Colombo"))
        .andExpect(jsonPath("$.destinationCity").value("Kandy"))
        // Strict verification: verify sensitive customer personal details are NEVER present
        .andExpect(jsonPath("$.sender").doesNotExist())
        .andExpect(jsonPath("$.recipient").doesNotExist())
        .andExpect(jsonPath("$.senderPhone").doesNotExist())
        .andExpect(jsonPath("$.recipientPhone").doesNotExist())
        .andExpect(jsonPath("$.senderAddress").doesNotExist())
        .andExpect(jsonPath("$.recipientAddress").doesNotExist())
        .andExpect(jsonPath("$.fee").doesNotExist());
  }

  @Test
  @DisplayName("GET /api/v1/tracking/{trackingRef} returns 404 ProblemDetail for unknown reference")
  void getPublicTrackingNotFound() throws Exception {
    String ref = "ZT-NONEXIST99";
    when(trackingService.getPublicTracking(ref)).thenThrow(new TrackingNotFoundException(ref));

    mockMvc.perform(get("/api/v1/tracking/{trackingRef}", ref)
            .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("TRACKING_NOT_FOUND"))
        .andExpect(jsonPath("$.trackingRef").value(ref))
        .andExpect(jsonPath("$.title").value("Tracking reference not found"));
  }

  @Test
  @DisplayName("GET /api/v1/tracking/{trackingRef}/events returns chronological tracking events list")
  void getTrackingEventsList() throws Exception {
    String ref = "ZT-7K3QX9M2PA";
    List<TrackingEventDto> events = List.of(
        new TrackingEventDto(UUID.randomUUID(), ref, "CREATED", "Booked", "Registered", "CUSTOMER", "Colombo", Instant.parse("2026-11-04T08:00:00Z"), null),
        new TrackingEventDto(UUID.randomUUID(), ref, "IN_TRANSIT", "In Transit", "Hub", "DISPATCHER", "Colombo Hub", Instant.parse("2026-11-04T12:00:00Z"), null)
    );

    when(trackingService.getTrackingEvents(ref)).thenReturn(events);

    mockMvc.perform(get("/api/v1/tracking/{trackingRef}/events", ref)
            .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isArray())
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[0].status").value("CREATED"))
        .andExpect(jsonPath("$[1].status").value("IN_TRANSIT"));
  }

  @Test
  @DisplayName("GET /api/v1/tracking/{trackingRef}/events supports SSE when Accept: text/event-stream")
  void getTrackingEventsSseStream() throws Exception {
    String ref = "ZT-7K3QX9M2PA";
    SseEmitter emitter = new SseEmitter();
    when(streamService.subscribe(ref)).thenReturn(emitter);

    mockMvc.perform(get("/api/v1/tracking/{trackingRef}/events", ref)
            .accept(MediaType.TEXT_EVENT_STREAM_VALUE))
        .andExpect(request().asyncStarted());
  }
}
