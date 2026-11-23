package com.zentro.notificationintegration.inbox;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zentro.notificationintegration.webhook.client.IdentityPartnerGrpcClient;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers(disabledWithoutDocker=true)
class InboxIntegrationTest {
  @Container static final PostgreSQLContainer<?> db=new PostgreSQLContainer<>("postgres:16-alpine");
  InboxRepository inbox; InboxEvents events; IdentityPartnerGrpcClient identities; NotificationAssignmentClient assignments;
  JdbcTemplate jdbc; TransactionTemplate transactions;
  final UUID parcel=UUID.randomUUID();
  @BeforeEach void setup() {
    var ds=new DriverManagerDataSource(db.getJdbcUrl(),db.getUsername(),db.getPassword());
    Flyway.configure().dataSource(ds).load().migrate();
    jdbc=new JdbcTemplate(ds); jdbc.update("DELETE FROM notification.inbox");
    transactions=new TransactionTemplate(new DataSourceTransactionManager(ds));
    inbox=new InboxRepository(jdbc); identities=mock(IdentityPartnerGrpcClient.class); assignments=mock(NotificationAssignmentClient.class);
    doReturn(List.of("dispatcher")).when(identities).notificationRecipients(true,null);
    when(assignments.courier(any())).thenReturn("");
    events=new InboxEvents(inbox,identities,new ObjectMapper(),assignments);
  }
  String event(String type,UUID id,Map<String,Object> payload) throws Exception {
    return new ObjectMapper().writeValueAsString(Map.of("eventId",id,"eventType",type,"aggregateId",parcel,
        "occurredAt",OffsetDateTime.now().toString(),"payload",payload));
  }
  void consume(String type,UUID id,Map<String,Object> payload) throws Exception {
    String body=event(type,id,payload);
    transactions.executeWithoutResult(tx->{try {events.accept(type,body);}catch(Exception e){throw new RuntimeException(e);}});
  }
  @Test void allPortalsAuthoritativeDeliveryAndRetryDeduplication() throws Exception {
    UUID proof=UUID.randomUUID();
    consume("notification.proof",proof,Map.of("decision","PENDING_REVIEW","courierSubject","courier","revision",1));
    assertThat(inbox.list("dispatcher","DISPATCHER",0)).extracting(InboxRepository.Item::title).containsExactly("Proof submitted");
    assertThat(inbox.list("customer","CUSTOMER",0)).isEmpty();
    consume("notification.proof",UUID.randomUUID(),Map.of("decision","COMPLETION_PENDING","courierSubject","courier"));
    assertThat(inbox.list("customer","CUSTOMER",0)).isEmpty();
    UUID delivered=UUID.randomUUID();
    consume("notification.parcel",delivered,Map.of("status","DELIVERED","customerSubject","customer"));
    consume("notification.parcel",delivered,Map.of("status","DELIVERED","customerSubject","customer"));
    assertThat(inbox.list("customer","CUSTOMER",0)).extracting(InboxRepository.Item::type).containsExactly("DELIVERED");
    assertThat(inbox.list("merchant","MERCHANT",0)).isEmpty();
    consume("notification.parcel",UUID.randomUUID(),Map.of("status","IN_TRANSIT","merchantSubject","merchant"));
    assertThat(inbox.list("merchant","MERCHANT",0)).extracting(InboxRepository.Item::type).containsExactly("IN_TRANSIT");
    when(identities.notificationRecipients(false,"profile")).thenReturn(List.of("courier"));
    consume("notification.assignment",UUID.randomUUID(),Map.of("action","ASSIGNED","newCourierId","profile"));
    consume("notification.proof",UUID.randomUUID(),Map.of("decision","REJECTED","courierSubject","courier","reason","Photo is blurred"));
    assertThat(inbox.list("courier","COURIER",0)).extracting(InboxRepository.Item::type).contains("ASSIGNMENT_ASSIGNED","PROOF_REJECTED");
    assertThat(inbox.list("courier","COURIER",0)).extracting(InboxRepository.Item::message).contains("Proof rejected. Photo is blurred");
    assertThat(inbox.list("other-merchant","MERCHANT",0)).isEmpty();
  }
  @Test void recipientFailureRollsBackAndRecoveryPersistsExactlyOnce() throws Exception {
    UUID id=UUID.randomUUID();
    var p=Map.<String,Object>of("status","CREATED","customerSubject","customer");
    when(identities.notificationRecipients(true,null)).thenThrow(new IllegalStateException("temporary directory outage"));
    assertThatThrownBy(()->consume("notification.parcel",id,p)).isInstanceOf(RuntimeException.class);
    assertThat(inbox.list("customer","CUSTOMER",0)).isEmpty();
    doReturn(List.of("dispatcher")).when(identities).notificationRecipients(true,null);
    consume("notification.parcel",id,p); consume("notification.parcel",id,p);
    assertThat(inbox.summary("customer","CUSTOMER").unread()).isEqualTo(1);
  }
  @Test void ownerAndRoleIsolationReadAllPaginationAndRestart() throws Exception {
    for(int i=0;i<23;i++) consume("notification.parcel",UUID.randomUUID(),Map.of("status","PICKED_UP","customerSubject","customer"));
    UUID id=inbox.list("customer","CUSTOMER",0).getFirst().id();
    assertThat(inbox.read("stranger","CUSTOMER",id)).isFalse();
    assertThat(inbox.read("customer","MERCHANT",id)).isFalse();
    assertThat(inbox.list("customer","CUSTOMER",0)).hasSize(20);
    assertThat(inbox.list("customer","CUSTOMER",1)).hasSize(3);
    assertThat(inbox.read("customer","CUSTOMER",id)).isTrue();
    assertThat(inbox.summary("customer","CUSTOMER").unread()).isEqualTo(22);
    inbox.readAll("customer","CUSTOMER");
    assertThat(new InboxRepository(jdbc).summary("customer","CUSTOMER").unread()).isZero();
    var jwt=Jwt.withTokenValue("test").header("alg","none").subject("customer").expiresAt(Instant.now().plusSeconds(60)).build();
    var auth=new JwtAuthenticationToken(jwt,List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")));
    assertThat(InboxController.owner(auth,"CUSTOMER").subject()).isEqualTo("customer");
    assertThatThrownBy(()->InboxController.owner(auth,"DISPATCHER")).isInstanceOf(ResponseStatusException.class);
    InboxStream streams=new InboxStream(inbox);
    try {
      var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new InboxController(inbox,streams)).build();
      var connection=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/notifications/events")
          .param("portal","CUSTOMER").principal(auth)).andReturn();
      streams.poll();
      assertThat(connection.getResponse().getContentAsString()).contains("event:inbox","\"unread\":0");
      consume("notification.parcel",UUID.randomUUID(),Map.of("status","PICKED_UP","customerSubject","customer"));
      streams.poll();
      assertThat(connection.getResponse().getContentAsString()).contains("\"unread\":1");
      inbox.readAll("customer","CUSTOMER"); streams.poll();
      mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/notifications/"+id+"/read")
          .param("portal","MERCHANT").principal(auth)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
      var foreign=Jwt.withTokenValue("synthetic").header("alg","none").subject("stranger").expiresAt(Instant.now().plusSeconds(60)).build();
      mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/notifications/"+id+"/read")
          .param("portal","CUSTOMER").principal(new JwtAuthenticationToken(foreign,List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")))))
          .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound());
      var reconnect=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/notifications/events")
          .param("portal","CUSTOMER").principal(auth)).andReturn();
      streams.poll();
      assertThat(reconnect.getResponse().getContentAsString()).contains("\"unread\":0");
    } finally { streams.stop(); }
  }
  @Test void sourceMigrationsCaptureOnlyCommittedParcelAssignmentAndProofTransitions() throws Exception {
    var ds=new DriverManagerDataSource(db.getJdbcUrl(),db.getUsername(),db.getPassword());
    for(String service:List.of("parcel-pricing-service","dispatch-courier-service")) {
      String history=service.startsWith("parcel") ? "parcel_migrations" : "dispatch_migrations";
      Flyway.configure().dataSource(ds).table(history).locations("filesystem:../"+service+"/src/main/resources/db/migration")
          .baselineOnMigrate(true).baselineVersion("0").load().migrate();
    }
    UUID courier=UUID.randomUUID(), proof=UUID.randomUUID(), revision=UUID.randomUUID(), review=UUID.randomUUID();
    jdbc.update("""
        INSERT INTO parcel.parcel(id,tracking_ref,customer_subject,sender_name,sender_phone,sender_address,
        recipient_name,recipient_phone,recipient_address,weight_kg,category,fee,currency,estimated_days)
        VALUES(?,?,'customer','Synthetic','000','Synthetic','Synthetic','000','Synthetic',1,'OTHER',1,'LKR',1)
        """,parcel,"ZT-"+UUID.randomUUID().toString().replace("-","").substring(0,10));
    assertThat(jdbc.queryForObject("SELECT count(*) FROM parcel.outbox_event WHERE aggregate_id=? AND event_type='notification.parcel'",Integer.class,parcel)).isEqualTo(1);
    transactions.executeWithoutResult(tx->{jdbc.update("UPDATE parcel.parcel SET status='DELIVERED' WHERE id=?",parcel);tx.setRollbackOnly();});
    assertThat(jdbc.queryForObject("SELECT count(*) FROM parcel.outbox_event WHERE aggregate_id=?",Integer.class,parcel)).isEqualTo(1);
    jdbc.update("UPDATE parcel.parcel SET status='DELIVERED' WHERE id=?",parcel);
    var delivery=jdbc.queryForObject("SELECT payload::text FROM parcel.outbox_event WHERE aggregate_id=? AND payload->'payload'->>'status'='DELIVERED'",String.class,parcel);
    events.accept("notification.parcel",delivery);
    assertThat(inbox.list("customer","CUSTOMER",0)).extracting(InboxRepository.Item::type).containsExactly("DELIVERED");
    jdbc.update("INSERT INTO dispatch.delivery_assignment(parcel_id,courier_profile_id,courier_user_id,assigned_by_subject) VALUES(?,?,?,'dispatcher')",parcel,courier,UUID.randomUUID());
    jdbc.update("INSERT INTO dispatch.assignment_audit(audit_id,parcel_id,action,new_courier_profile_id,actor_subject,occurred_at) VALUES(?,?,'ASSIGNED',?,'dispatcher',now())",UUID.randomUUID(),parcel,courier);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM dispatch.outbox_event WHERE aggregate_id=? AND event_type='notification.assignment'",Integer.class,parcel)).isEqualTo(1);
    jdbc.update("INSERT INTO dispatch.proof_of_delivery(id,parcel_id,recipient_name,proof_reference,submitted_by_subject) VALUES(?,?,'Synthetic','synthetic','courier')",proof,parcel);
    jdbc.update("INSERT INTO dispatch.proof_revision(id,proof_id,revision_no,submitted_by_subject) VALUES(?,?,1,'courier')",revision,proof);
    jdbc.update("INSERT INTO dispatch.proof_review(id,revision_id,decision) VALUES(?,?,'PENDING_REVIEW')",review,revision);
    var submitted=jdbc.queryForObject("SELECT payload::text FROM dispatch.outbox_event WHERE aggregate_id=? AND event_type='notification.proof'",String.class,parcel);
    events.accept("notification.proof",submitted);
    assertThat(inbox.list("dispatcher","DISPATCHER",0)).extracting(InboxRepository.Item::type).contains("PROOF_PENDING_REVIEW");
    jdbc.update("UPDATE dispatch.proof_review SET decision='REJECTED',rejection_reason='Photo is blurred',reviewed_by_subject='dispatcher',reviewed_at=now() WHERE id=?",review);
    var rejected=jdbc.queryForObject("SELECT payload::text FROM dispatch.outbox_event WHERE aggregate_id=? AND payload->'payload'->>'decision'='REJECTED'",String.class,parcel);
    events.accept("notification.proof",rejected);
    assertThat(inbox.list("courier","COURIER",0)).extracting(InboxRepository.Item::message).contains("Proof rejected. Photo is blurred");
  }
  @Test void webhookRetryAndFailureNotifyOnlyOwningMerchantWithoutOperationalSecrets() {
    UUID subscription=UUID.randomUUID(), delivery=UUID.randomUUID();
    jdbc.update("INSERT INTO notification.webhook_subscription(id,merchant_id,url,secret_ciphertext) VALUES(?,'merchant','https://example.invalid/private-endpoint','synthetic-ciphertext')",subscription);
    jdbc.update("""
        INSERT INTO notification.webhook_delivery(id,subscription_id,event_id,event_type,parcel_id,payload,payload_sha256,status)
        VALUES(?,?,?,'parcel.status.updated',?,'{}',?,'PENDING')
        """,delivery,subscription,UUID.randomUUID(),parcel,"0".repeat(64));
    jdbc.update("UPDATE notification.webhook_delivery SET status='RETRY',attempts=1,last_error='synthetic private response' WHERE id=?",delivery);
    jdbc.update("UPDATE notification.webhook_delivery SET status='RETRY',attempts=2 WHERE id=?",delivery);
    jdbc.update("UPDATE notification.webhook_delivery SET status='FAILED',attempts=3 WHERE id=?",delivery);
    assertThat(inbox.list("merchant","MERCHANT",0)).hasSize(2).allSatisfy(item->{
      assertThat(item.message()).doesNotContain("private-endpoint","ciphertext","private response");
      assertThat(item.target()).isEqualTo("/merchant/dashboard/webhooks");
    });
    assertThat(inbox.list("other-merchant","MERCHANT",0)).isEmpty();
    assertThat(inbox.list("merchant","CUSTOMER",0)).isEmpty();
  }
}
