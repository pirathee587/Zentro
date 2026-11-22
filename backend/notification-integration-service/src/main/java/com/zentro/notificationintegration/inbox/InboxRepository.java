package com.zentro.notificationintegration.inbox;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class InboxRepository {
  public record Item(UUID id, String type, String title, String message, UUID parcelId, String target,
                     OffsetDateTime occurredAt, OffsetDateTime readAt) {}
  public record Summary(long unread, long total, String version) {}
  private final JdbcTemplate jdbc;
  public InboxRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

  /** Read-only readiness probe; application startup never creates the prepared schema. */
  public boolean available() {
    return Boolean.TRUE.equals(jdbc.queryForObject("SELECT to_regclass('notification.inbox') IS NOT NULL",Boolean.class));
  }

  public void insert(UUID event, String subject, String portal, String type, String title, String message,
      UUID parcel, String target, OffsetDateTime occurred) {
    if (subject == null || subject.isBlank()) return;
    jdbc.update("""
        INSERT INTO notification.inbox(event_id,recipient_subject,portal,type,title,message,parcel_id,target,occurred_at)
        VALUES(?,?,?,?,?,?,?,?,?) ON CONFLICT(event_id,recipient_subject,portal) DO NOTHING
        """, event,subject,portal,type,title,message,parcel,target,occurred);
  }
  public List<Item> list(String subject, String portal, int page) {
    return jdbc.query("""
        SELECT id,type,title,message,parcel_id,target,occurred_at,read_at FROM notification.inbox
        WHERE recipient_subject=? AND portal=? ORDER BY occurred_at DESC,id DESC LIMIT 20 OFFSET ?
        """, (r,n) -> new Item(r.getObject("id",UUID.class),r.getString("type"),r.getString("title"),
        r.getString("message"),r.getObject("parcel_id",UUID.class),r.getString("target"),
        r.getObject("occurred_at",OffsetDateTime.class),r.getObject("read_at",OffsetDateTime.class)),subject,portal,page*20);
  }
  public Summary summary(String subject, String portal) {
    return jdbc.queryForObject("""
        SELECT count(*) FILTER (WHERE read_at IS NULL) AS unread,count(*) AS total,
          coalesce(max(created_at)::text,'') || ':' || coalesce(max(read_at)::text,'') AS version
        FROM notification.inbox WHERE recipient_subject=? AND portal=?
        """,(r,n)->new Summary(r.getLong("unread"),r.getLong("total"),r.getString("version")),subject,portal);
  }
  public boolean read(String subject, String portal, UUID id) {
    return jdbc.update("""
        UPDATE notification.inbox SET read_at=coalesce(read_at,now())
        WHERE id=? AND recipient_subject=? AND portal=?
        """,id,subject,portal)>0;
  }
  public void readAll(String subject, String portal) {
    jdbc.update("UPDATE notification.inbox SET read_at=now() WHERE recipient_subject=? AND portal=? AND read_at IS NULL",subject,portal);
  }
}
