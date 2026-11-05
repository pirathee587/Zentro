package com.zentro.trackingreporting.messaging.idempotency;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, ProcessedEventId> {

  boolean existsByConsumerNameAndEventId(String consumerName, UUID eventId);
}

