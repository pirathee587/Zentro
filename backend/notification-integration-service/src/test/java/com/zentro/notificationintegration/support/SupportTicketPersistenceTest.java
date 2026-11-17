package com.zentro.notificationintegration.support;
import static org.assertj.core.api.Assertions.*;
import com.zentro.notificationintegration.support.dto.*;
import com.zentro.notificationintegration.support.repository.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
@Testcontainers(disabledWithoutDocker=true)
class SupportTicketPersistenceTest {
 @Container static final PostgreSQLContainer<?> db=new PostgreSQLContainer<>("postgres:16-alpine");
 @Test void savesReadsAfterRestartAndIsolatesCustomer() {
  var ds=new DriverManagerDataSource(db.getJdbcUrl(),db.getUsername(),db.getPassword());Flyway.configure().dataSource(ds).load().migrate();
  var store=new SupportTicketRepository(new JdbcTemplate(ds));
  var ticket=store.insert("alice",new SupportTicketRequest("Delivery question","delivery",null,"Please check my booking"));
  assertThat(ticket.ticketNumber()).isEqualTo("SUP-"+ticket.id());assertThat(ticket.status()).isEqualTo("Open");
  var restarted=new SupportTicketRepository(new JdbcTemplate(ds));
  assertThat(restarted.find(ticket.id(),"alice")).contains(ticket);assertThat(restarted.find(ticket.id(),"bob")).isEmpty();
 }
}
