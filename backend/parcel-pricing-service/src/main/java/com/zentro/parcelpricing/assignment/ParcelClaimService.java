package com.zentro.parcelpricing.assignment;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import com.zentro.parcelpricing.parcel.entity.ParcelStatus;

@Service
public class ParcelClaimService {
  public record Candidate(UUID parcelId,String trackingReference,String pickupSummary,String dropoffSummary,Instant createdAt,String serviceArea) {
  }
  public record Snapshot(List<Candidate> parcels,boolean complete) {
  }
  public record Claim(UUID operationId,UUID parcelId,String state,Candidate parcel) {
  }
  public record Request(UUID parcelId,String mode) {
  }
  private final JdbcTemplate jdbc;
  private final AssignmentOutcomeClient outcomes;
  private final Set<String> eligible;
  public ParcelClaimService(JdbcTemplate jdbc,AssignmentOutcomeClient outcomes,
  @Value("${zentro.assignment.eligible-statuses:}") String statuses) {
    this.jdbc=jdbc;
    this.outcomes=outcomes;
    var configured=new HashSet<String>();
    for(String status:statuses.split(",")) if(!status.isBlank()) {
      var parsed=ParcelStatus.valueOf(status.trim());
      if(parsed==ParcelStatus.DELIVERED || parsed==ParcelStatus.FAILED)
      throw new IllegalArgumentException("Terminal parcels cannot be configured as assignment candidates");
      configured.add(parsed.name());
    }
    eligible=Set.copyOf(configured);
  }
  @Transactional(readOnly=true)
  public Snapshot candidates() {
    if(eligible.isEmpty()) throw unavailable();
    var named=new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc);
    var rows=named.query("""
      SELECT id,tracking_ref,sender_city,recipient_city,created_at FROM parcel.parcel p
      WHERE status IN (:states) AND NOT EXISTS
        (SELECT 1 FROM parcel.assignment_claim c WHERE c.parcel_id=p.id AND c.state='HELD')
      ORDER BY created_at,id LIMIT 5001
      """,Map.of("states",eligible),(rs,n)->new Candidate(rs.getObject("id",UUID.class),rs.getString("tracking_ref"),
    rs.getString("sender_city"),rs.getString("recipient_city"),rs.getObject("created_at",OffsetDateTime.class).toInstant(),rs.getString("recipient_city")));
    return new Snapshot(List.copyOf(rows.subList(0,Math.min(rows.size(),5000))),rows.size()<=5000);
  }
  @Transactional(readOnly=true)
  public Candidate candidate(UUID id) {
    if(eligible.isEmpty())throw unavailable();
    if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM parcel.parcel WHERE id=?)",Boolean.class,id)))throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Parcel not found.");
    var named=new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc);
    return named.query("SELECT id,tracking_ref,sender_city,recipient_city,created_at FROM parcel.parcel p WHERE id=:id AND status IN (:states) AND NOT EXISTS (SELECT 1 FROM parcel.assignment_claim c WHERE c.parcel_id=p.id AND state='HELD')",Map.of("id",id,"states",eligible),
    (rs,n)->new Candidate(rs.getObject("id",UUID.class),rs.getString("tracking_ref"),rs.getString("sender_city"),rs.getString("recipient_city"),rs.getObject("created_at",OffsetDateTime.class).toInstant(),rs.getString("recipient_city")))
    .stream().findFirst().orElseThrow(ParcelClaimService::conflict);
  }
  @Transactional(timeout=15)
  public Claim acquire(UUID operation,Request request,String actor,String token) {
    verify(operation,request,actor,token,"PREPARED");
    var parcel=lock(request.parcelId());
    var existing=find(operation);
    if(existing.isPresent()) {
      var claim=existing.get();
      verifyBinding(operation,request,actor);
      if(!"HELD".equals(claim.state())) throw conflict();
      return new Claim(operation,request.parcelId(),"HELD",parcel);
    }
    String status=jdbc.queryForObject("SELECT status FROM parcel.parcel WHERE id=?",String.class,request.parcelId());
    if("ASSIGN".equals(request.mode())) {
      if(eligible.isEmpty())throw unavailable();
      if(!eligible.contains(status))throw conflict();
    }
    else if(!"ASSIGNED".equals(request.mode()) || "DELIVERED".equals(status)||"FAILED".equals(status))throw conflict();
    if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM parcel.assignment_claim WHERE parcel_id=? AND state='HELD')",Boolean.class,request.parcelId())))throw conflict();
    jdbc.update("INSERT INTO parcel.assignment_claim(operation_id,parcel_id,actor_subject,mode,state) VALUES(?,?,?,?,'HELD')",operation,request.parcelId(),actor,request.mode());
    return new Claim(operation,request.parcelId(),"HELD",parcel);
  }
  @Transactional(timeout=15)
  public Claim finish(UUID operation,Request request,String actor,String token,boolean commit) {
    verify(operation,request,actor,token,commit?"COMMITTED":"ABORTED");
    Candidate parcel;
    try {
      parcel=lock(request.parcelId());
    } catch (ResponseStatusException missing) {
      // An absent parcel cannot have acquired a valid claim, but delayed requests still need fencing.
      if (commit || missing.getStatusCode().value()!=404) throw missing;
      parcel=null;
    }
    var existing=find(operation);
    String target=commit?"CONFIRMED":"RELEASED";
    if(existing.isPresent()) {
      verifyBinding(operation,request,actor);
      String state=existing.get().state();
      if(!state.equals("HELD")&&!state.equals(target))throw conflict();
      jdbc.update("UPDATE parcel.assignment_claim SET state=? WHERE operation_id=?",target,operation);
    }
    else {
      if(commit)throw conflict();
      // Durable release tombstone prevents a delayed acquisition from taking effect after rollback.
      jdbc.update("INSERT INTO parcel.assignment_claim(operation_id,parcel_id,actor_subject,mode,state) VALUES(?,?,?,?,'RELEASED')",operation,request.parcelId(),actor,request.mode());
    }
    return new Claim(operation,request.parcelId(),target,parcel);
  }
  @Transactional(readOnly=true)
  public Claim state(UUID operation,String actor) {
    var row=find(operation).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Claim not found."));
    if(!actor.equals(jdbc.queryForObject("SELECT actor_subject FROM parcel.assignment_claim WHERE operation_id=?",String.class,operation)))throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    return row;
  }
  private void verify(UUID operation,Request request,String actor,String token,String expected) {
    if(request==null||request.parcelId()==null||!("ASSIGN".equals(request.mode())||"ASSIGNED".equals(request.mode())))throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
    var outcome=outcomes.outcome(operation,token);
    if(!request.parcelId().equals(outcome.parcelId())||!actor.equals(outcome.actorSubject())||!request.mode().equals(outcome.mode())||!expected.equals(outcome.state()))throw conflict();
  }
  private void verifyBinding(UUID id,Request request,String actor) {
    if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM parcel.assignment_claim WHERE operation_id=? AND parcel_id=? AND actor_subject=? AND mode=?)",Boolean.class,id,request.parcelId(),actor,request.mode())))throw conflict();
  }
  private Candidate lock(UUID id) {
    return jdbc.query("SELECT id,tracking_ref,sender_city,recipient_city,created_at FROM parcel.parcel WHERE id=? FOR UPDATE",
    (rs,n)->new Candidate(rs.getObject("id",UUID.class),rs.getString("tracking_ref"),rs.getString("sender_city"),rs.getString("recipient_city"),rs.getObject("created_at",OffsetDateTime.class).toInstant(),rs.getString("recipient_city")),id)
    .stream().findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Parcel not found."));
  }
  private Optional<Claim> find(UUID id) {
    return jdbc.query("SELECT operation_id,parcel_id,state FROM parcel.assignment_claim WHERE operation_id=?",
    (rs,n)->new Claim(rs.getObject("operation_id",UUID.class),rs.getObject("parcel_id",UUID.class),rs.getString("state"),null),id).stream().findFirst();
  }
  private static ResponseStatusException conflict() {
    return new ResponseStatusException(HttpStatus.CONFLICT,"Assignment claim conflicts with the current lifecycle or operation.");
  }
  private static ResponseStatusException unavailable() {
    return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Assignment eligibility policy has not been configured.");
  }
}
