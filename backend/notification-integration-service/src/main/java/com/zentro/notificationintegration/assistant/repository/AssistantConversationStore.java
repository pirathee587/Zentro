package com.zentro.notificationintegration.assistant.repository;

import com.zentro.notificationintegration.assistant.dto.response.AssistantConversationResponse;
import com.zentro.notificationintegration.assistant.dto.response.AssistantMessageResponse;
import com.zentro.notificationintegration.assistant.dto.response.AssistantHistoryResponse;

import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Uses only S5's existing datasource. Every lookup and update is scoped by JWT subject. */
@Repository
public class AssistantConversationStore {
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;

  public AssistantConversationStore(JdbcTemplate jdbc, org.springframework.transaction.PlatformTransactionManager manager) {
    this.jdbc = jdbc; this.transactions = new TransactionTemplate(manager);
  }

  public AssistantConversationResponse create(String owner) {
    UUID id = UUID.randomUUID(); Instant now = Instant.now();
    jdbc.update("INSERT INTO notification.assistant_conversation(id,owner_subject,created_at) VALUES (?,?,?)",
        id, owner, java.sql.Timestamp.from(now));
    return new AssistantConversationResponse(id, now);
  }

  public List<AssistantConversationResponse> list(String owner) {
    return jdbc.query("SELECT id,created_at FROM notification.assistant_conversation WHERE owner_subject=? ORDER BY created_at DESC LIMIT 50",
        (rs, n) -> new AssistantConversationResponse(rs.getObject(1, UUID.class), rs.getTimestamp(2).toInstant()), owner);
  }

  public AssistantHistoryResponse history(UUID id, String owner) {
    requireOwner(id, owner);
    // Latest 100 messages, returned chronologically. No unbounded history response.
    return new AssistantHistoryResponse(id, jdbc.query("""
        SELECT role,body,created_at FROM (
          SELECT sequence,role,body,created_at FROM notification.assistant_message
          WHERE conversation_id=? ORDER BY sequence DESC LIMIT 100
        ) recent ORDER BY sequence
        """, (rs, n) -> new AssistantMessageResponse(rs.getString(1), rs.getString(2), rs.getTimestamp(3).toInstant()), id));
  }

  public UUID claim(UUID id, String owner) {
    requireOwner(id, owner);
    UUID claim = UUID.randomUUID();
    int updated = jdbc.update("""
        UPDATE notification.assistant_conversation SET active_request=?, busy_until=now()+interval '2 minutes'
        WHERE id=? AND owner_subject=? AND (busy_until IS NULL OR busy_until<now())
        """, claim, id, owner);
    if (updated != 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "A reply is already in progress.");
    return claim;
  }

  public void complete(UUID id, String owner, UUID claim, String question, String answer) {
    transactions.executeWithoutResult(status -> {
      int changed = jdbc.update("""
          UPDATE notification.assistant_conversation SET active_request=NULL,busy_until=NULL
          WHERE id=? AND owner_subject=? AND active_request=?
          """, id, owner, claim);
      if (changed != 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "The reply expired. Please try again.");
      jdbc.update("INSERT INTO notification.assistant_message(conversation_id,role,body) VALUES (?,'user',?),(?,'assistant',?)",
          id, question, id, answer);
    });
  }

  public void release(UUID id, String owner, UUID claim) {
    jdbc.update("UPDATE notification.assistant_conversation SET active_request=NULL,busy_until=NULL WHERE id=? AND owner_subject=? AND active_request=?",
        id, owner, claim);
  }

  private void requireOwner(UUID id, String owner) {
    Integer count = jdbc.queryForObject("SELECT count(*) FROM notification.assistant_conversation WHERE id=? AND owner_subject=?", Integer.class, id, owner);
    if (count == null || count != 1) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Conversation not found.");
  }
}
