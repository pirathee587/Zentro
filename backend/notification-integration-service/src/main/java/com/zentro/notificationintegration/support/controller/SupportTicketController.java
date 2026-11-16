package com.zentro.notificationintegration.support.controller;
import com.zentro.notificationintegration.support.dto.*;
import com.zentro.notificationintegration.support.service.SupportTicketService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
@RestController
@RequestMapping("/api/v1/support/tickets")
@PreAuthorize("hasRole('CUSTOMER')")
public class SupportTicketController {
  private final SupportTicketService service;
  public SupportTicketController(SupportTicketService service) { this.service=service; }
  @PostMapping @ResponseStatus(HttpStatus.CREATED)
  public SupportTicketResponse create(@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody SupportTicketRequest request) {
    return service.create(subject(jwt),request);
  }
  @GetMapping("/{id}") public SupportTicketResponse find(@AuthenticationPrincipal Jwt jwt,@PathVariable("id") UUID id) {
    return service.find(subject(jwt),id);
  }
  private String subject(Jwt jwt) {
    if(jwt==null||jwt.getSubject()==null||jwt.getSubject().isBlank()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    return jwt.getSubject();
  }
}
