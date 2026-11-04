package com.zentro.trackingreporting.repository;

import com.zentro.trackingreporting.domain.TrackingEvent;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface TrackingEventRepository extends JpaRepository<TrackingEvent, UUID> {

  List<TrackingEvent> findByTrackingRefOrderByOccurredAtAsc(String trackingRef);

  List<TrackingEvent> findByParcelIdOrderByOccurredAtAsc(UUID parcelId);

  boolean existsByEventId(UUID eventId);
}

