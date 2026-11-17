package com.zentro.trackingreporting.graphql.dashboard;

import java.util.*;
import java.time.OffsetDateTime;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import com.zentro.trackingreporting.tracking.entity.TrackingParcel;
import com.zentro.trackingreporting.tracking.entity.TrackingStatus;

/** Reads actual event projections for parcels not yet hydrated with contact metadata. Never persists placeholders. */
@Repository
public class CourierEventProjectionRepository {
  private final NamedParameterJdbcTemplate jdbc;
  public CourierEventProjectionRepository(JdbcTemplate jdbc) { this.jdbc=new NamedParameterJdbcTemplate(jdbc); }
  @Transactional(readOnly=true)
  public List<TrackingParcel> find(Collection<UUID> ids) {
    if(ids.isEmpty())return List.of();
    return jdbc.query("""
        SELECT DISTINCT ON (p.parcel_id) p.parcel_id,p.current_status,p.terminal_at,
               e.tracking_ref,e.failure_reason
        FROM tracking.delivery_projection p JOIN tracking.tracking_event e
          ON e.parcel_id=p.parcel_id AND e.status=p.current_status AND e.event_id IS NOT NULL
        WHERE p.parcel_id IN (:ids)
        ORDER BY p.parcel_id,e.occurred_at DESC,e.event_id DESC
        """,Map.of("ids",ids),(rs,n)->{
          var value=new TrackingParcel();
          value.setWeightKg(null); // Event projection has no authoritative package weight.
          value.setParcelId(rs.getObject("parcel_id",UUID.class));
          value.setTrackingRef(rs.getString("tracking_ref"));
          value.setStatus(TrackingStatus.valueOf(rs.getString("current_status")));
          value.setFailureReason(rs.getString("failure_reason"));
          var completed=rs.getObject("terminal_at",OffsetDateTime.class);
          if(value.getStatus()==TrackingStatus.DELIVERED&&completed!=null)value.setDeliveredAt(completed.toInstant());
          return value;
        });
  }
}
