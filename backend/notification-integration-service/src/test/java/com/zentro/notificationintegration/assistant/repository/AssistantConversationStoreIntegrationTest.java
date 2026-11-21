package com.zentro.notificationintegration.assistant.repository;

import com.zentro.notificationintegration.assistant.dto.response.AssistantMessageResponse;

import static org.assertj.core.api.Assertions.*;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers(disabledWithoutDocker=true)
class AssistantConversationStoreIntegrationTest {
  @Container static final PostgreSQLContainer<?> db = new PostgreSQLContainer<>("postgres:16-alpine");
  @Test void migrationPersistenceIsolationAndConcurrentTurnClaims() {
    var ds = new DriverManagerDataSource(db.getJdbcUrl(),db.getUsername(),db.getPassword());
    Flyway.configure().dataSource(ds).load().migrate();
    var store = new AssistantConversationStore(new JdbcTemplate(ds), new DataSourceTransactionManager(ds));
    UUID id = store.create("alice").id(), claim = store.claim(id,"alice");
    assertThatThrownBy(() -> store.claim(id,"alice")).isInstanceOf(ResponseStatusException.class).hasMessageContaining("409");
    assertThatThrownBy(() -> store.history(id,"bob")).isInstanceOf(ResponseStatusException.class).hasMessageContaining("404");
    assertThatThrownBy(() -> store.claim(id,"bob")).isInstanceOf(ResponseStatusException.class).hasMessageContaining("404");
    store.complete(id,"alice",claim,"hello","Hello Alice");
    var restartedStore = new AssistantConversationStore(new JdbcTemplate(ds),new DataSourceTransactionManager(ds));
    assertThat(restartedStore.history(id,"alice").messages()).extracting(AssistantMessageResponse::text).containsExactly("hello","Hello Alice");
    assertThat(restartedStore.list("bob")).isEmpty();
    assertThat(restartedStore.claim(id,"alice")).isNotNull();
  }
}
