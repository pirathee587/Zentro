package com.zentro.parcelpricing.integration;
import static org.assertj.core.api.Assertions.*;
import com.zentro.parcelpricing.parcel.repository.ParcelBookingRepository;
import com.zentro.parcelpricing.parcel.service.CustomerParcelReadService;
import java.time.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
@Testcontainers(disabledWithoutDocker=true)
class CustomerParcelReadIntegrationTest {
 @Container static final PostgreSQLContainer<?> db=new PostgreSQLContainer<>("postgres:16-alpine");
 @Test void readsPersistedRowsAndUpdatesWithOwnerIsolationAndMonthBoundaries() {
   var ds=new DriverManagerDataSource(db.getJdbcUrl(),db.getUsername(),db.getPassword());
   Flyway.configure().dataSource(ds).load().migrate();var jdbc=new JdbcTemplate(ds);
   insert(jdbc,"alice","ZT-ALICE0001","DELIVERED",10,"LKR","2026-10-31T18:29:59Z");
   insert(jdbc,"alice","ZT-ALICE0002","CREATED",20,"LKR","2026-10-31T18:30:00Z");
   insert(jdbc,"alice","ZT-ALICE0003","IN_TRANSIT",3,"USD","2026-11-15T00:00:00Z");
   insert(jdbc,"alice","ZT-ALICE0004","FAILED",40,"LKR","2026-11-30T18:30:00Z");
   insert(jdbc,"bob","ZT-BOB000001","DELIVERED",999,"LKR","2026-11-15T00:00:00Z");
   var repo=new ParcelBookingRepository(jdbc);var service=new CustomerParcelReadService(repo,Clock.fixed(Instant.parse("2026-11-16T00:00:00Z"),ZoneOffset.UTC));
   var summary=service.summary("alice");assertThat(summary).containsEntry("activeCount",2L).containsEntry("totalParcels",4L)
       .containsEntry("awaitingPickupCount",1L).containsEntry("deliveredCount",1L).containsEntry("month","2026-11");
   var fees=repo.customerFees("alice",Instant.parse("2026-10-31T18:30:00Z"),Instant.parse("2026-11-30T18:30:00Z"));
   assertThat(fees).anySatisfy(row->{assertThat(row.get("currency")).isEqualTo("LKR");assertThat((java.math.BigDecimal)row.get("monthly")).isEqualByComparingTo("20");});
   assertThat(service.page("alice",1,10,"ACTIVE",null).totalElements()).isEqualTo(2);
   assertThat(service.reference("alice","ZT-ALICE0002").fee()).isEqualByComparingTo("20");
   assertThatThrownBy(()->service.reference("bob","ZT-ALICE0002")).hasMessageContaining("404");
   assertThat(service.summary("nobody")).containsEntry("totalParcels",0L);
   jdbc.update("UPDATE parcel.parcel SET status='DELIVERED' WHERE tracking_ref='ZT-ALICE0002'");
   assertThat(new CustomerParcelReadService(repo).reference("alice","ZT-ALICE0002").status()).isEqualTo("DELIVERED");
   assertThat(service.summary("alice")).containsEntry("deliveredCount",2L);
 }
 private void insert(JdbcTemplate jdbc,String owner,String ref,String status,int fee,String currency,String created) {
   jdbc.update("""
    INSERT INTO parcel.parcel(tracking_ref,customer_subject,sender_name,sender_phone,sender_address,
      recipient_name,recipient_phone,recipient_address,weight_kg,category,fee,currency,estimated_days,status,created_at)
    VALUES (?,?, 'Synthetic sender','0771234567','Synthetic pickup address','Synthetic recipient','0711234567',
      'Synthetic delivery address',2,'Documents',?,?,3,?,?)
    """,ref,owner,fee,currency,status,OffsetDateTime.parse(created));
 }
}
