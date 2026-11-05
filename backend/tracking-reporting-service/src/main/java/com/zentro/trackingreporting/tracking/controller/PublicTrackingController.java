package com.zentro.trackingreporting.tracking.controller;

import com.zentro.trackingreporting.tracking.dto.PublicTrackingResponse;
import com.zentro.trackingreporting.tracking.dto.TrackingEventDto;
import com.zentro.trackingreporting.tracking.service.TrackingService;
import com.zentro.trackingreporting.tracking.service.TrackingStreamService;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/tracking")
@Validated
public class PublicTrackingController {

  private final TrackingService trackingService;
  private final TrackingStreamService streamService;

  public PublicTrackingController(TrackingService trackingService, TrackingStreamService streamService) {
    this.trackingService = trackingService;
    this.streamService = streamService;
  }

  /**
   * Public tracking lookup. Excludes all sensitive customer personal details (names, phones, full addresses, fees).
   */
  @GetMapping(path = "/{trackingRef}", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<PublicTrackingResponse> getTracking(
      @PathVariable("trackingRef")
      @Pattern(regexp = "^[A-Za-z0-9_-]{6,32}$", message = "Invalid tracking reference format")
      String trackingRef) {
    return ResponseEntity.ok(trackingService.getPublicTracking(trackingRef));
  }

  /**
   * Chronological tracking events. Returns JSON array by default, or an SSE stream when Accept: text/event-stream.
   */
  @GetMapping(path = "/{trackingRef}/events")
  public Object getEvents(
      @PathVariable("trackingRef")
      @Pattern(regexp = "^[A-Za-z0-9_-]{6,32}$", message = "Invalid tracking reference format")
      String trackingRef,
      @RequestHeader(name = HttpHeaders.ACCEPT, required = false) String acceptHeader) {

    if (acceptHeader != null && acceptHeader.contains(MediaType.TEXT_EVENT_STREAM_VALUE)) {
      // First verify parcel exists
      trackingService.getPublicTracking(trackingRef);
      return streamService.subscribe(trackingRef);
    }

    List<TrackingEventDto> events = trackingService.getTrackingEvents(trackingRef);
    return ResponseEntity.ok(events);
  }

  /**
   * Explicit SSE stream endpoint.
   */
  @GetMapping(path = "/{trackingRef}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter streamEvents(
      @PathVariable("trackingRef")
      @Pattern(regexp = "^[A-Za-z0-9_-]{6,32}$", message = "Invalid tracking reference format")
      String trackingRef) {
    // Verify parcel exists first
    trackingService.getPublicTracking(trackingRef);
    return streamService.subscribe(trackingRef);
  }
}

