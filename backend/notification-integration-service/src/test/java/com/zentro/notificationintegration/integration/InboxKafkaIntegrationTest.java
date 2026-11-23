package com.zentro.notificationintegration.integration;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;
import com.zentro.notificationintegration.inbox.*;
import com.zentro.notificationintegration.webhook.client.IdentityPartnerGrpcClient;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.*;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties={"zentro.inbox.consumer.enabled=true","spring.kafka.producer.properties[delivery.timeout.ms]=90000",
    "spring.kafka.producer.properties[request.timeout.ms]=10000"})
@Import(InboxKafkaIntegrationTest.Topics.class)
class InboxKafkaIntegrationTest extends AbstractNotificationIntegrationTest {
  @MockBean IdentityPartnerGrpcClient identities;
  @MockBean NotificationAssignmentClient assignments;
  @Autowired InboxRepository inbox;
  @TestConfiguration static class Topics {
    @Bean NewTopic inboxParcel() {return TopicBuilder.name("notification.parcel").partitions(1).replicas(1).build();}
    @Bean NewTopic inboxAssignment() {return TopicBuilder.name("notification.assignment").partitions(1).replicas(1).build();}
    @Bean NewTopic inboxProof() {return TopicBuilder.name("notification.proof").partitions(1).replicas(1).build();}
  }
  String body(UUID id,String owner) throws Exception {
    return objectMapper.writeValueAsString(Map.of("eventId",id,"eventType","notification.parcel",
        "aggregateId",UUID.randomUUID(),"occurredAt",OffsetDateTime.now().toString(),
        "payload",Map.of("status","CREATED","customerSubject",owner)));
  }
  @Test void directoryOutageRetriesWithoutLossAndKafkaDuplicatesStayUnique() throws Exception {
    String owner=UUID.randomUUID().toString(); UUID id=UUID.randomUUID();
    when(identities.notificationRecipients(true,null)).thenThrow(new IllegalStateException("synthetic outage"));
    String event=body(id,owner);
    kafka.send("notification.parcel",id.toString(),event).get(30,TimeUnit.SECONDS);
    await().atMost(Duration.ofSeconds(30)).untilAsserted(()->verify(identities,atLeastOnce()).notificationRecipients(true,null));
    assertThat(inbox.summary(owner,"CUSTOMER").total()).isZero();
    doReturn(List.of("dispatcher")).when(identities).notificationRecipients(true,null);
    await().atMost(Duration.ofSeconds(30)).until(()->inbox.summary(owner,"CUSTOMER").total()==1);
    kafka.send("notification.parcel",id.toString(),event).get(30,TimeUnit.SECONDS);
    UUID barrier=UUID.randomUUID(); String other=UUID.randomUUID().toString();
    kafka.send("notification.parcel",barrier.toString(),body(barrier,other)).get(30,TimeUnit.SECONDS);
    await().atMost(Duration.ofSeconds(30)).until(()->inbox.summary(other,"CUSTOMER").total()==1);
    assertThat(inbox.summary(owner,"CUSTOMER").total()).isEqualTo(1);
  }
  @Test void disposableBrokerRestartRecoversPendingPublication() throws Exception {
    doReturn(List.of("dispatcher")).when(identities).notificationRecipients(true,null);
    String owner=UUID.randomUUID().toString(); UUID id=UUID.randomUUID();
    UUID warmup=UUID.randomUUID();
    kafka.send("notification.parcel",warmup.toString(),body(warmup,"warmup")).get(30,TimeUnit.SECONDS);
    var docker=org.testcontainers.DockerClientFactory.instance().client();
    try {
      docker.stopContainerCmd(KAFKA.getContainerId()).withTimeout(1).exec();
      var pending=kafka.send("notification.parcel",id.toString(),body(id,owner));
      assertThat(inbox.summary(owner,"CUSTOMER").total()).isZero();
      docker.startContainerCmd(KAFKA.getContainerId()).exec();
      pending.get(60,TimeUnit.SECONDS);
      await().atMost(Duration.ofSeconds(45)).until(()->inbox.summary(owner,"CUSTOMER").total()==1);
    } finally {
      if(!KAFKA.isRunning()) docker.startContainerCmd(KAFKA.getContainerId()).exec();
    }
  }
}
