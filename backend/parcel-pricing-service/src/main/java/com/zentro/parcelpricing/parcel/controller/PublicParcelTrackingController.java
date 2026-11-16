package com.zentro.parcelpricing.parcel.controller;

import com.zentro.parcelpricing.parcel.repository.ParcelBookingRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/parcels/tracking")
public class PublicParcelTrackingController {
  private final ParcelBookingRepository bookings;
  public PublicParcelTrackingController(ParcelBookingRepository bookings) { this.bookings=bookings; }
  @GetMapping("/{reference}") public Map<String,Object> tracking(@PathVariable("reference") String reference) {
    if(!reference.matches("[A-Za-z0-9_-]{6,32}")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid tracking reference");
    var p=bookings.findPublicReference(reference).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Parcel not found"));
    var result=new LinkedHashMap<String,Object>(); result.put("trackingRef",p.trackingRef()); result.put("status",p.status());
    result.put("category",p.category()); result.put("size",p.size()); result.put("description",p.description());
    result.put("originCity",p.sender().city()); result.put("destinationCity",p.recipient().city());
    result.put("createdAt",p.createdAt()); result.put("estimatedDays",p.estimatedDays()); return result;
  }
}
