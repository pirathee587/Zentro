package com.zentro.dispatchcourier.dispatch.coordination;

import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/internal/dispatch/assignment-operations")
@PreAuthorize("hasRole('DISPATCHER')")
public class AssignmentOperationController {
  private final AssignmentOperationStore operations;
  private final org.springframework.beans.factory.ObjectProvider<CoordinatedParcelSource> source;
  public AssignmentOperationController(AssignmentOperationStore operations,org.springframework.beans.factory.ObjectProvider<CoordinatedParcelSource> source) {
    this.operations=operations;
    this.source=source;
  }
  @GetMapping
  public ResponseEntity<java.util.List<AssignmentOperationStore.Operation>> pending(@AuthenticationPrincipal Jwt jwt) {
    return response(operations.pending(jwt.getSubject()));
  }
  @GetMapping("/{id}")
  public ResponseEntity<AssignmentOperationStore.Operation> state(@PathVariable("id") UUID id,@AuthenticationPrincipal Jwt jwt) {
    var op=operations.get(id);
    authorize(op,jwt);
    if(!jwt.getSubject().equals(op.actorSubject()))throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    return response(op);
  }
  @PostMapping("/{id}/reconcile")
  public ResponseEntity<AssignmentOperationStore.Operation> reconcile(@PathVariable("id") UUID id,@AuthenticationPrincipal Jwt jwt) {
    authorize(operations.get(id),jwt);
    return response(source.getObject().reconcile(id,jwt.getTokenValue()));
  }
  private static void authorize(AssignmentOperationStore.Operation op,Jwt jwt) {
    if("ASSIGN".equals(op.mode())) {
      Object claim=jwt.getClaim("permissions");
      if(!(claim instanceof java.util.List<?> values)||values.stream().anyMatch(v->!(v instanceof String text)||text.isBlank()||!text.equals(text.strip()))||!values.contains("DISPATCH_ASSIGN"))throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }
  }
  private static <T>ResponseEntity<T> response(T value) {
    return ResponseEntity.ok().header("Cache-Control","no-store").body(value);
  }
}
