package com.zentro.parcelpricing.parcel.controller;

import com.zentro.parcelpricing.parcel.service.CustomerParcelReadService;
import com.zentro.parcelpricing.parcel.dto.CustomerParcelView;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/parcels/mine")
@PreAuthorize("hasRole('CUSTOMER')")
public class CustomerParcelReadController {
  private final CustomerParcelReadService service;
  public CustomerParcelReadController(CustomerParcelReadService service) { this.service=service; }
  @GetMapping public CustomerParcelReadService.Page page(@AuthenticationPrincipal Jwt jwt,
      @RequestParam(name="page",defaultValue="1") int page, @RequestParam(name="size",defaultValue="10") int size,
      @RequestParam(name="status",required=false) String status, @RequestParam(name="q",required=false) String query) {
    return service.page(subject(jwt),page,size,status,query);
  }
  @GetMapping("/summary") public Map<String,Object> summary(@AuthenticationPrincipal Jwt jwt) { return service.summary(subject(jwt)); }
  @GetMapping("/live") public CustomerParcelView live(@AuthenticationPrincipal Jwt jwt) { return service.live(subject(jwt)); }
  @GetMapping("/{reference}") public CustomerParcelView reference(@AuthenticationPrincipal Jwt jwt,@PathVariable("reference") String reference) { return service.reference(subject(jwt),reference); }
  private String subject(Jwt jwt) {
    if(jwt==null || jwt.getSubject()==null || jwt.getSubject().isBlank()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    return jwt.getSubject();
  }
}
