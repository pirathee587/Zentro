package com.zentro.trackingreporting.tracking.service;

import com.zentro.trackingreporting.tracking.entity.TrackingEvent;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class TrackingStreamService {

  private static final Logger log = LoggerFactory.getLogger(TrackingStreamService.class);
  private static final long SSE_TIMEOUT_MS = 300_000L; // 5 minutes

  private final Map<String, List<SseEmitter>> emitters = new ConcurrentHashMap<>();

  public SseEmitter subscribe(String trackingRef) {
    SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
    String key = trackingRef.trim().toUpperCase();

    emitters.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>()).add(emitter);

    emitter.onCompletion(() -> removeEmitter(key, emitter));
    emitter.onTimeout(() -> removeEmitter(key, emitter));
    emitter.onError(ex -> removeEmitter(key, emitter));

    // Send initial connected event
    try {
      emitter.send(SseEmitter.event()
          .name("connected")
          .data(Map.of("trackingRef", key, "status", "CONNECTED")));
    } catch (IOException e) {
      log.debug("Failed to send initial SSE connected event for {}", key, e);
      removeEmitter(key, emitter);
    }

    return emitter;
  }

  public void notifySubscribers(TrackingEvent event) {
    if (event == null || event.getTrackingRef() == null) return;
    String key = event.getTrackingRef().trim().toUpperCase();
    List<SseEmitter> list = emitters.get(key);
    if (list == null || list.isEmpty()) return;

    for (SseEmitter emitter : list) {
      try {
        emitter.send(SseEmitter.event()
            .name("tracking-update")
            .data(Map.of(
                "trackingRef", event.getTrackingRef(),
                "status", event.getStatus().name(),
                "title", event.getTitle(),
                "description", event.getDescription() != null ? event.getDescription() : "",
                "occurredAt", event.getOccurredAt().toString()
            )));
      } catch (IOException e) {
        log.debug("Removing dead SSE emitter for {}", key);
        removeEmitter(key, emitter);
      }
    }
  }

  private void removeEmitter(String trackingRef, SseEmitter emitter) {
    List<SseEmitter> list = emitters.get(trackingRef);
    if (list != null) {
      list.remove(emitter);
      if (list.isEmpty()) {
        emitters.remove(trackingRef);
      }
    }
  }
}

