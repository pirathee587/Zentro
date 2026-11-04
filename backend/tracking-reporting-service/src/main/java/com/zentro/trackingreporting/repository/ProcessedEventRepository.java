package com.zentro.trackingreporting.repository;

import com.zentro.trackingreporting.domain.ProcessedEvent;
import com.zentro.trackingreporting.domain.ProcessedEventId;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, ProcessedEventId> {

  boolean existsByConsumerNameAndEventId(String consumerName, UUID eventId);
}

