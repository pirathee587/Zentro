package com.zentro.dispatchcourier.dispatch.coordination;

import java.util.*;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Repository
public class AssignmentOperationStore {
  public record Operation(UUID operationId,UUID parcelId,String actorSubject,String mode,String state,boolean settled) {
  }
  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;
  public AssignmentOperationStore(JdbcTemplate jdbc,PlatformTransactionManager manager) {
    this.jdbc=jdbc;
    tx=new TransactionTemplate(manager);
    tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    tx.setTimeout(30);
  }
  public Operation prepare(UUID parcel,String actor,String mode) {
    return tx.execute(status-> {
      lockParcel(parcel);
      if(unsettled(parcel).isPresent())throw conflict();
      UUID id=UUID.randomUUID();
      jdbc.update("INSERT INTO dispatch.assignment_operation(operation_id,parcel_id,actor_subject,mode,state) VALUES(?,?,?,?,'PREPARED')",id,parcel,actor,mode);
      return get(id);
    });
  }
  public Optional<Operation> unsettled(UUID parcel) {
    return query("SELECT * FROM dispatch.assignment_operation WHERE parcel_id=? AND NOT settled",parcel).stream().findFirst();
  }
  public List<Operation> pending(String actor) {
    return jdbc.query("SELECT * FROM dispatch.assignment_operation WHERE actor_subject=? AND NOT settled ORDER BY created_at LIMIT 100",
    (rs,n)->new Operation(rs.getObject("operation_id",UUID.class),rs.getObject("parcel_id",UUID.class),rs.getString("actor_subject"),rs.getString("mode"),rs.getString("state"),rs.getBoolean("settled")),actor);
  }
  public Operation get(UUID id) {
    return query("SELECT * FROM dispatch.assignment_operation WHERE operation_id=?",id).stream().findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Operation not found."));
  }
  public <T> T commit(UUID id,Supplier<T> work) {
    return tx.execute(status-> {
      var operation=locked(id);
      if(!"PREPARED".equals(operation.state()))throw conflict();
      T result=work.get();
      jdbc.update("UPDATE dispatch.assignment_operation SET state='COMMITTED' WHERE operation_id=?",id);
      return result;
    });
  }
  /** Taking the same row lock fences every callback before deciding rollback. No elapsed-time expiry. */
  public Operation abortIfPrepared(UUID id) {
    return tx.execute(status-> {
      var operation=locked(id);
      if("PREPARED".equals(operation.state()))jdbc.update("UPDATE dispatch.assignment_operation SET state='ABORTED' WHERE operation_id=?",id);
      return get(id);
    });
  }
  public void settled(UUID id) {
    tx.executeWithoutResult(status-> {
      var op=locked(id);
      if("PREPARED".equals(op.state()))throw conflict();
      jdbc.update("UPDATE dispatch.assignment_operation SET settled=true WHERE operation_id=?",id);
    });
  }
  private Operation locked(UUID id) {
    return query("SELECT * FROM dispatch.assignment_operation WHERE operation_id=? FOR UPDATE",id).stream().findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
  }
  private void lockParcel(UUID id) {
    jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(CAST(? AS text),0))",ps->ps.setString(1,id.toString()),rs->null);
  }
  private List<Operation> query(String sql,UUID id) {
    return jdbc.query(sql,(rs,n)->new Operation(rs.getObject("operation_id",UUID.class),rs.getObject("parcel_id",UUID.class),rs.getString("actor_subject"),rs.getString("mode"),rs.getString("state"),rs.getBoolean("settled")),id);
  }
  private static ResponseStatusException conflict() {
    return new ResponseStatusException(HttpStatus.CONFLICT,"An assignment operation requires reconciliation or has already finished.");
  }
}
