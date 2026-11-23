package com.zentro.dispatchcourier.pod;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Durable compensation ledger for the non-atomic Storage/Postgres boundary. */
@Repository public class ProofStageRepository {
  public record Stage(UUID id, UUID parcelId, String courierSubject, UUID key, String path, String type, long size, String sha256, String state, Instant expiresAt, UUID proofId) {}
  private final JdbcTemplate jdbc; public ProofStageRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
  public Optional<Stage> byKey(UUID parcel, String subject, UUID key) { return jdbc.query("SELECT id,parcel_id,courier_subject,idempotency_key,object_path,content_type,byte_size,content_sha256,state,expires_at,proof_id FROM dispatch.proof_upload_stage WHERE parcel_id=? AND courier_subject=? AND idempotency_key=?",(r,n)->new Stage(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getString(3),r.getObject(4,UUID.class),r.getString(5),r.getString(6),r.getLong(7),r.getString(8),r.getString(9),r.getObject(10,java.time.OffsetDateTime.class).toInstant(),r.getObject(11,UUID.class)),parcel,subject,key).stream().findFirst(); }
  @Transactional public Stage record(UUID parcel,String subject,UUID key,String path,String type,long size,String hash) {
    var existing=byKey(parcel,subject,key); if(existing.isPresent()) return existing.get();
    UUID id=UUID.randomUUID(); jdbc.update("INSERT INTO dispatch.proof_upload_stage(id,parcel_id,courier_subject,idempotency_key,object_path,content_type,byte_size,content_sha256,state,expires_at) VALUES(?,?,?,?,?,?,?,?, 'UPLOADED', now()+interval '24 hours')",id,parcel,subject,key,path,type,size,hash);
    return byKey(parcel,subject,key).orElseThrow();
  }
  @Transactional public Stage begin(UUID parcel,String subject,UUID key,String path,String type,long size,String hash) {
    var existing=byKey(parcel,subject,key); if(existing.isPresent()) return existing.get(); UUID id=UUID.randomUUID();
    jdbc.update("INSERT INTO dispatch.proof_upload_stage(id,parcel_id,courier_subject,idempotency_key,object_path,content_type,byte_size,content_sha256,state,expires_at) VALUES(?,?,?,?,?,?,?,?, 'UPLOADING', now()+interval '24 hours')",id,parcel,subject,key,path,type,size,hash);
    return byKey(parcel,subject,key).orElseThrow();
  }
  @Transactional public boolean uploaded(UUID id) { return jdbc.update("UPDATE dispatch.proof_upload_stage SET state='UPLOADED' WHERE id=? AND state='UPLOADING'",id)==1; }
  @Transactional public boolean finalize(UUID id) { return jdbc.update("UPDATE dispatch.proof_upload_stage SET state='FINALIZED', finalized_at=now() WHERE id=? AND state='UPLOADED'",id)==1; }
  @Transactional public boolean finalize(UUID id, UUID proofId) { return jdbc.update("UPDATE dispatch.proof_upload_stage SET state='FINALIZED', proof_id=?, finalized_at=now() WHERE id=? AND state='UPLOADED'",proofId,id)==1; }
  /** Stages older than the configured retention are returned to a storage cleanup caller; no delete occurs here. */
  public java.util.List<Stage> expired() { return jdbc.query("SELECT id,parcel_id,courier_subject,idempotency_key,object_path,content_type,byte_size,content_sha256,state,expires_at,proof_id FROM dispatch.proof_upload_stage WHERE state='UPLOADED' AND expires_at < now()",(r,n)->new Stage(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getString(3),r.getObject(4,UUID.class),r.getString(5),r.getString(6),r.getLong(7),r.getString(8),r.getString(9),r.getObject(10,java.time.OffsetDateTime.class).toInstant(),r.getObject(11,UUID.class))); }
  @Transactional public boolean abandon(UUID id) { return jdbc.update("UPDATE dispatch.proof_upload_stage SET state='ABANDONED' WHERE id=? AND state='UPLOADED'",id)==1; }
}
