package com.zentro.parcelpricing.assignment;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import com.zentro.parcelpricing.parcel.entity.ParcelStatus;
import com.zentro.parcelpricing.parcel.repository.ParcelStatusRepository;
@Testcontainers
class ParcelClaimPostgresTest {
 @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:16-alpine");
 static JdbcTemplate jdbc;static TransactionTemplate tx;
 AssignmentOutcomeClient outcomes;ParcelClaimService service;UUID parcel;
 @BeforeAll static void database() throws Exception {
   var ds=new DriverManagerDataSource(DB.getJdbcUrl(),DB.getUsername(),DB.getPassword());jdbc=new JdbcTemplate(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
   jdbc.execute("CREATE SCHEMA parcel");
   jdbc.execute("CREATE TABLE parcel.parcel(id UUID PRIMARY KEY,tracking_ref text,sender_city text,recipient_city text,created_at timestamptz DEFAULT now(),updated_at timestamptz DEFAULT now(),status text,version bigint DEFAULT 0,merchant_id text)");
   jdbc.execute(java.nio.file.Files.readString(java.nio.file.Path.of("src/main/resources/db/migration/V10__assignment_coordination.sql")));
 }
 @BeforeEach void setup(){jdbc.execute("TRUNCATE parcel.assignment_claim,parcel.parcel");outcomes=mock(AssignmentOutcomeClient.class);service=new ParcelClaimService(jdbc,outcomes,"PICKED_UP");parcel=UUID.randomUUID();jdbc.update("INSERT INTO parcel.parcel(id,tracking_ref,sender_city,recipient_city,status) VALUES(?,'SYNTHETIC','Area A','Area B','PICKED_UP')",parcel);}
 UUID operation(String mode,String state){UUID id=UUID.randomUUID();outcome(id,mode,state);return id;}
 void outcome(UUID id,String mode,String state){when(outcomes.outcome(id,"synthetic-token")).thenReturn(new AssignmentOutcomeClient.Outcome(id,parcel,"dispatcher",mode,state));}
 ParcelClaimService.Claim acquire(UUID id,String mode){return tx.execute(s->service.acquire(id,new ParcelClaimService.Request(parcel,mode),"dispatcher","synthetic-token"));}
 ParcelClaimService.Claim finish(UUID id,String mode,boolean commit){return tx.execute(s->service.finish(id,new ParcelClaimService.Request(parcel,mode),"dispatcher","synthetic-token",commit));}
 @Test void eligibleAcquisitionIsIdempotentAndMinimizesData(){var id=operation("ASSIGN","PREPARED");var first=acquire(id,"ASSIGN");var again=acquire(id,"ASSIGN");assertThat(again).isEqualTo(first);assertThat(first.parcel().parcelId()).isEqualTo(parcel);assertThat(jdbc.queryForObject("SELECT count(*) FROM parcel.assignment_claim",Integer.class)).isEqualTo(1);}
 @Test void unspecifiedPolicyFailsClosed(){service=new ParcelClaimService(jdbc,outcomes,"");assertThatThrownBy(service::candidates).isInstanceOf(ResponseStatusException.class);var id=operation("ASSIGN","PREPARED");assertThatThrownBy(()->acquire(id,"ASSIGN")).isInstanceOf(ResponseStatusException.class);}
 @Test void explicitlyEligibleCreatedCustomerOrderIsReturnedAsDispatcherCandidate(){
   jdbc.update("UPDATE parcel.parcel SET status='CREATED' WHERE id=?",parcel);
   service=new ParcelClaimService(jdbc,outcomes,"CREATED");
   var snapshot=service.candidates();
   assertThat(snapshot.complete()).isTrue();
   assertThat(snapshot.parcels()).extracting(ParcelClaimService.Candidate::parcelId).containsExactly(parcel);
   assertThat(snapshot.parcels().getFirst().trackingReference()).isEqualTo("SYNTHETIC");
 }
 @Test void createdAndTerminalParcelsAreNotImplicitCandidates(){for(String state:List.of("CREATED","DELIVERED","FAILED")){jdbc.update("UPDATE parcel.parcel SET status=? WHERE id=?",state,parcel);var id=operation("ASSIGN","PREPARED");assertThatThrownBy(()->acquire(id,"ASSIGN")).isInstanceOf(ResponseStatusException.class);}assertThat(service.candidates().parcels()).isEmpty();}
 @Test void missingParcelRejected(){jdbc.update("DELETE FROM parcel.parcel WHERE id=?",parcel);var id=operation("ASSIGN","PREPARED");assertThatThrownBy(()->acquire(id,"ASSIGN")).isInstanceOfSatisfying(ResponseStatusException.class,e->assertThat(e.getStatusCode().value()).isEqualTo(404));}
 @Test void concurrentClaimsAllowOnlyOneOwner() throws Exception {var a=operation("ASSIGN","PREPARED");var b=operation("ASSIGN","PREPARED");var start=new CountDownLatch(1);try(var pool=Executors.newFixedThreadPool(2)){var results=new ArrayList<Future<Boolean>>();for(var id:List.of(a,b))results.add(pool.submit(()->{start.await();try{acquire(id,"ASSIGN");return true;}catch(ResponseStatusException conflict){return false;}}));start.countDown();int wins=0;for(var f:results)if(f.get(10,TimeUnit.SECONDS))wins++;assertThat(wins).isEqualTo(1);}}
 @Test void heldClaimBlocksStatusMutationAndQueueUntilConfirmation(){var id=operation("ASSIGN","PREPARED");acquire(id,"ASSIGN");assertThat(service.candidates().parcels()).isEmpty();var repo=new ParcelStatusRepository(jdbc);assertThatThrownBy(()->tx.execute(s->{repo.lockForUpdate(parcel);return repo.updateStatus(parcel,ParcelStatus.IN_TRANSIT,0,java.time.OffsetDateTime.now());})).isInstanceOf(org.springframework.dao.TransientDataAccessResourceException.class);outcome(id,"ASSIGN","COMMITTED");finish(id,"ASSIGN",true);tx.execute(s->{repo.lockForUpdate(parcel);return repo.updateStatus(parcel,ParcelStatus.IN_TRANSIT,0,java.time.OffsetDateTime.now());});assertThat(jdbc.queryForObject("SELECT status FROM parcel.parcel WHERE id=?",String.class,parcel)).isEqualTo("IN_TRANSIT");}
 @Test void statusCommitBeforeClaimCausesFreshEligibilityRejection() throws Exception {var id=operation("ASSIGN","PREPARED");var locked=new CountDownLatch(1);var proceed=new CountDownLatch(1);try(var pool=Executors.newFixedThreadPool(2)){var status=pool.submit(()->tx.execute(s->{jdbc.queryForObject("SELECT status FROM parcel.parcel WHERE id=? FOR UPDATE",String.class,parcel);locked.countDown();try{proceed.await(5,TimeUnit.SECONDS);}catch(InterruptedException e){throw new RuntimeException(e);}jdbc.update("UPDATE parcel.parcel SET status='IN_TRANSIT' WHERE id=?",parcel);return true;}));assertThat(locked.await(5,TimeUnit.SECONDS)).isTrue();var claim=pool.submit(()->{try{acquire(id,"ASSIGN");return true;}catch(ResponseStatusException failure){return false;}});proceed.countDown();assertThat(status.get(10,TimeUnit.SECONDS)).isTrue();assertThat(claim.get(10,TimeUnit.SECONDS)).isFalse();}}
 @Test void releaseRequiresAbortedOutcomeAndCreatesDurableTombstone(){var id=operation("ASSIGN","PREPARED");assertThatThrownBy(()->finish(id,"ASSIGN",false)).isInstanceOf(ResponseStatusException.class);outcome(id,"ASSIGN","ABORTED");assertThat(finish(id,"ASSIGN",false).state()).isEqualTo("RELEASED");assertThat(finish(id,"ASSIGN",false).state()).isEqualTo("RELEASED");outcome(id,"ASSIGN","PREPARED");assertThatThrownBy(()->acquire(id,"ASSIGN")).isInstanceOf(ResponseStatusException.class);}
 @Test void confirmationRequiresCommittedOutcomeAndDoesNotExpire(){var id=operation("ASSIGN","PREPARED");acquire(id,"ASSIGN");jdbc.update("UPDATE parcel.assignment_claim SET created_at=now()-interval '30 days'");assertThatThrownBy(()->finish(id,"ASSIGN",true)).isInstanceOf(ResponseStatusException.class);assertThat(service.candidates().parcels()).isEmpty();outcome(id,"ASSIGN","COMMITTED");assertThat(finish(id,"ASSIGN",true).state()).isEqualTo("CONFIRMED");assertThat(finish(id,"ASSIGN",true).state()).isEqualTo("CONFIRMED");}
 @Test void assignedOperationsAllowNonterminalLifecycleButRejectTerminal(){jdbc.update("UPDATE parcel.parcel SET status='OUT_FOR_DELIVERY' WHERE id=?",parcel);var id=operation("ASSIGNED","PREPARED");acquire(id,"ASSIGNED");outcome(id,"ASSIGNED","ABORTED");finish(id,"ASSIGNED",false);jdbc.update("UPDATE parcel.parcel SET status='DELIVERED' WHERE id=?",parcel);var terminal=operation("ASSIGNED","PREPARED");assertThatThrownBy(()->acquire(terminal,"ASSIGNED")).isInstanceOf(ResponseStatusException.class);}
 @Test void uncertainOutcomeNeverReleasesHeldClaim(){var id=operation("ASSIGN","PREPARED");acquire(id,"ASSIGN");when(outcomes.outcome(id,"synthetic-token")).thenThrow(new ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE));assertThatThrownBy(()->finish(id,"ASSIGN",false)).isInstanceOf(ResponseStatusException.class);assertThat(service.state(id,"dispatcher").state()).isEqualTo("HELD");}
 @Test void wrongOwnerOrParcelCannotMutateClaim(){var id=operation("ASSIGN","PREPARED");when(outcomes.outcome(id,"synthetic-token")).thenReturn(new AssignmentOutcomeClient.Outcome(id,UUID.randomUUID(),"other","ASSIGN","PREPARED"));assertThatThrownBy(()->acquire(id,"ASSIGN")).isInstanceOf(ResponseStatusException.class);}
 @Test void abortedMissingParcelReconcilesWithoutCreatingParcel(){jdbc.update("DELETE FROM parcel.parcel WHERE id=?",parcel);var id=operation("ASSIGN","ABORTED");assertThat(finish(id,"ASSIGN",false).state()).isEqualTo("RELEASED");assertThat(jdbc.queryForObject("SELECT count(*) FROM parcel.parcel",Integer.class)).isZero();assertThat(service.state(id,"dispatcher").state()).isEqualTo("RELEASED");}

}
