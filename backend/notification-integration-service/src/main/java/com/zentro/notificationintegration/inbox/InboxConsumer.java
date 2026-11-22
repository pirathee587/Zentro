package com.zentro.notificationintegration.inbox;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="zentro.inbox.consumer.enabled",havingValue="true")
public class InboxConsumer {
  private final InboxEvents events;
  public InboxConsumer(InboxEvents events) { this.events=events; }
  // Container retries on exceptions; offsets are acknowledged only after the whole recipient transaction commits.
  @KafkaListener(topics={"notification.parcel","notification.assignment","notification.proof"},
      containerFactory="inboxListenerContainerFactory",
      groupId="${zentro.inbox.consumer.group:in-app-inbox-v1}")
  public void receive(ConsumerRecord<String,String> record,Acknowledgment ack) throws Exception {
    events.accept(record.topic(),record.value());
    ack.acknowledge();
  }
}
