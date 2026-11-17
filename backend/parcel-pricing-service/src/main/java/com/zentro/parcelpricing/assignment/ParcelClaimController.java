package com.zentro.parcelpricing.assignment;

import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/assignment-claims")
@PreAuthorize("hasRole('DISPATCHER')")
public class ParcelClaimController {
  private final ParcelClaimService claims;
  public ParcelClaimController(ParcelClaimService claims) {
    this.claims=claims;
  }
  @GetMapping("/candidates")
  @PreAuthorize("hasRole('DISPATCHER') and @assignmentPermissions.has(authentication, 'DISPATCH_VIEW')")
  public ResponseEntity<ParcelClaimService.Snapshot> candidates() {
    return response(claims.candidates());
  }
  @GetMapping("/candidates/{parcel}")
  @PreAuthorize("hasRole('DISPATCHER') and @assignmentPermissions.has(authentication, 'DISPATCH_VIEW')")
  public ResponseEntity<ParcelClaimService.Candidate> candidate(@PathVariable("parcel") UUID parcel) {
    return response(claims.candidate(parcel));
  }
  @PostMapping("/{operation}/acquire")
  public ResponseEntity<ParcelClaimService.Claim> acquire(@PathVariable("operation") UUID operation,@RequestBody ParcelClaimService.Request request,@AuthenticationPrincipal Jwt jwt) {
    authorize(request,jwt);
    return response(claims.acquire(operation,request,jwt.getSubject(),jwt.getTokenValue()));
  }
  @PostMapping("/{operation}/confirm")
  public ResponseEntity<ParcelClaimService.Claim> confirm(@PathVariable("operation") UUID operation,@RequestBody ParcelClaimService.Request request,@AuthenticationPrincipal Jwt jwt) {
    authorize(request,jwt);
    return response(claims.finish(operation,request,jwt.getSubject(),jwt.getTokenValue(),true));
  }
  @PostMapping("/{operation}/release")
  public ResponseEntity<ParcelClaimService.Claim> release(@PathVariable("operation") UUID operation,@RequestBody ParcelClaimService.Request request,@AuthenticationPrincipal Jwt jwt) {
    authorize(request,jwt);
    return response(claims.finish(operation,request,jwt.getSubject(),jwt.getTokenValue(),false));
  }
  @GetMapping("/{operation}")
  public ResponseEntity<ParcelClaimService.Claim> state(@PathVariable("operation") UUID operation,@AuthenticationPrincipal Jwt jwt) {
    return response(claims.state(operation,jwt.getSubject()));
  }
  private static void authorize(ParcelClaimService.Request request,Jwt jwt) {
    if(jwt.getSubject()==null||jwt.getSubject().isBlank()||jwt.getSubject().length()>255)throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED);
    if(request==null)throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST);
    if("ASSIGN".equals(request.mode())&&!AssignmentPermissions.permits(jwt,"DISPATCH_ASSIGN"))throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN);
  }
  private static <T> ResponseEntity<T> response(T value) {
    return ResponseEntity.ok().header("Cache-Control","no-store").body(value);
  }
}
