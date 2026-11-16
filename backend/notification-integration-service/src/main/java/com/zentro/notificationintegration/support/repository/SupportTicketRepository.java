package com.zentro.notificationintegration.support.repository;
import com.zentro.notificationintegration.support.dto.*;
import java.util.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
@Repository
public class SupportTicketRepository {
  private final JdbcTemplate jdbc;
  public SupportTicketRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
  public SupportTicketResponse insert(String owner,SupportTicketRequest request) {
    UUID id=UUID.randomUUID();
    return jdbc.queryForObject("""
      INSERT INTO notification.customer_support_ticket
        (id,owner_subject,subject,category,tracking_ref,message)
      VALUES (?,?,?,?,?,?) RETURNING *
      """,(rs,n)->map(rs),id,owner,request.subject().trim(),request.category(),request.trackingRef(),request.message().trim());
  }
  public Optional<SupportTicketResponse> find(UUID id,String owner) {
    return jdbc.query("SELECT * FROM notification.customer_support_ticket WHERE id=? AND owner_subject=?",
        (rs,n)->map(rs),id,owner).stream().findFirst();
  }
  private SupportTicketResponse map(java.sql.ResultSet rs) throws java.sql.SQLException {
    var date=rs.getObject("created_at",OffsetDateTime.class);
    UUID id=rs.getObject("id",UUID.class);
    return new SupportTicketResponse(id,"SUP-"+id,rs.getString("subject"),rs.getString("category"),
        rs.getString("tracking_ref"),rs.getString("message"),rs.getString("status"),date,
        date.atZoneSameInstant(ZoneId.of("Asia/Colombo")).format(DateTimeFormatter.ofPattern("dd MMM yyyy",Locale.ENGLISH)));
  }
}
