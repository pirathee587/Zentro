package com.zentro.dispatchcourier.dispatch.coordination;

import com.zentro.dispatchcourier.dispatch.service.ParcelSource;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
@Primary
public class CoordinatedParcelSource implements ParcelSource {
  private final AssignmentOperationStore operations;
  private final ParcelClaimClient claims;
  private final JwtDecoder decoder;
  public CoordinatedParcelSource(AssignmentOperationStore operations,ParcelClaimClient claims,JwtDecoder decoder) {
    this.operations=operations;
    this.claims=claims;
    this.decoder=decoder;
  }
  @Override public void reconcileParcel(UUID parcel,String token) {
    var pending=operations.unsettled(parcel);
    if(pending.isPresent())reconcile(pending.get().operationId(),token);
  }
  @Override public <T>T inspectEligibleParcel(UUID parcel,String token,Function<Parcel,T> read) {
    return read.apply(claims.candidate(parcel,token));
  }
  @Override public Snapshot eligibleParcels(String token) {
    return claims.candidates(token);
  }
  public <T>T withEligibleParcel(UUID parcel,String token,Function<Parcel,T> work) {
    return coordinate(parcel,token,"ASSIGN",work);
  }
  public <T>T withAssignedParcel(UUID parcel,String token,Function<Parcel,T> work) {
    return coordinate(parcel,token,"ASSIGNED",work);
  }
  private <T>T coordinate(UUID parcel,String token,String mode,Function<Parcel,T> work) {
    String actor=actor(token);
    var pending=operations.unsettled(parcel);
    if(pending.isPresent())reconcile(pending.get().operationId(),token);
    var op=operations.prepare(parcel,actor,mode);
    T result;
    try {
      result=operations.commit(op.operationId(),()-> {
        var held=claims.acquire(op,token);
        return work.apply(held.parcel());
      });
    }
    catch(RuntimeException failure) {
      // A commit exception may mean committed, rolled back, or unknown. Resolve from durable storage.
      // If storage cannot answer, do not release anything.
      try {
        reconcile(op.operationId(),token);
      }
      catch(RuntimeException unresolved) {
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Assignment outcome requires reconciliation.");
      }
      throw failure;
    }
    // A failed confirmation never rolls back the committed assignment, nor releases its claim.
    claims.finish(operations.get(op.operationId()),token);
    operations.settled(op.operationId());
    return result;
  }
  public AssignmentOperationStore.Operation reconcile(UUID id,String token) {
    var op=operations.get(id);
    if(!actor(token).equals(op.actorSubject()))throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    if(op.settled())return op;
    op=operations.abortIfPrepared(id);
    claims.finish(op,token);
    operations.settled(id);
    return operations.get(id);
  }
  private String actor(String token) {
    var jwt=decoder.decode(token);
    String subject=jwt.getSubject();
    if(subject==null||subject.isBlank()||subject.length()>255)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    return subject;
  }
}
