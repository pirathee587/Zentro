package com.zentro.trackingreporting.messaging.idempotency;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, ProcessedEventId> {

  @org.springframework.data.jpa.repository.Modifying
  @org.springframework.data.jpa.repository.Query(value = "INSERT INTO tracking.processed_event(consumer_name, event_id) VALUES (:consumer, :event) ON CONFLICT DO NOTHING", nativeQuery = true)
  int claim(@org.springframework.data.repository.query.Param("consumer") String consumer,
      @org.springframework.data.repository.query.Param("event") UUID event);

  boolean existsByConsumerNameAndEventId(String consumerName, UUID eventId);
}

