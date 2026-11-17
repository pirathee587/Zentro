package com.zentro.parcelpricing.parcel.service;

import com.zentro.parcelpricing.parcel.dto.CustomerParcelView;
import com.zentro.parcelpricing.parcel.entity.ParcelStatus;
import com.zentro.parcelpricing.parcel.repository.ParcelBookingRepository;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional(readOnly = true)
public class CustomerParcelReadService {
  private static final List<String> ACTIVE = List.of("CREATED", "PICKED_UP", "IN_TRANSIT", "OUT_FOR_DELIVERY");
  private final ParcelBookingRepository repository;
  private final Clock clock;
  public record Page(List<CustomerParcelView> content, long totalElements, int page, int size, boolean hasNext, List<Map<String,Object>> totalFees) { }
  @Autowired public CustomerParcelReadService(ParcelBookingRepository repository) { this(repository, Clock.systemUTC()); }
  public CustomerParcelReadService(ParcelBookingRepository repository, Clock clock) { this.repository=repository; this.clock=clock; }
  public Page page(String subject, int page, int size, String status, String search) {
    if (page<1 || size<1 || size>50 || (search!=null && search.length()>100)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid page or search");
    List<String> states = statuses(status);
    long count=repository.countCustomer(subject,states,search);
    int offset;
    try { offset=Math.multiplyExact(page-1,size); } catch (ArithmeticException ex) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid page"); }
    return new Page(repository.findCustomerPage(subject,states,search,offset,size).stream().map(CustomerParcelView::from).toList(),count,page,size,(long)page*size<count,repository.customerTotalFees(subject));
  }
  public CustomerParcelView reference(String subject, String reference) {
    if (reference==null || !reference.matches("[A-Za-z0-9_-]{6,64}")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid parcel reference");
    return repository.findCustomerReference(subject,reference).map(CustomerParcelView::from)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,"Parcel not found"));
  }
  public CustomerParcelView live(String subject) {
    var active=repository.findCustomerPage(subject,ACTIVE,null,0,1);
    if (active.isEmpty()) active=repository.findCustomerPage(subject,List.of(),null,0,1);
    return active.isEmpty()?null:CustomerParcelView.from(active.getFirst());
  }
  public Map<String,Object> summary(String subject) {
    var counts=new HashMap<String,Long>();
    for (var row:repository.customerStatusCounts(subject)) counts.put((String)row.get("status"),((Number)row.get("total")).longValue());
    var zone=ZoneId.of("Asia/Colombo"); var month=YearMonth.now(clock.withZone(zone));
    var fees=repository.customerFees(subject,month.atDay(1).atStartOfDay(zone).toInstant(),month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant());
    long active=ACTIVE.stream().mapToLong(state->counts.getOrDefault(state,0L)).sum(), awaiting=counts.getOrDefault("CREATED",0L);
    var result=new LinkedHashMap<String,Object>();
    result.put("activeCount",active); result.put("awaitingPickupCount",awaiting); result.put("inTransitCount",active-awaiting);
    result.put("deliveredCount",counts.getOrDefault("DELIVERED",0L)); result.put("failedCount",counts.getOrDefault("FAILED",0L));
    result.put("totalParcels",counts.values().stream().mapToLong(Long::longValue).sum());
    result.put("month",month.toString()); result.put("timeZone",zone.getId());
    result.put("totalFees",fees.stream().map(row->Map.of("currency",row.get("currency"),"amount",row.get("total"))).toList());
    result.put("monthlyFees",fees.stream().map(row->Map.of("currency",row.get("currency"),"amount",row.get("monthly"))).toList());
    return result;
  }
  private List<String> statuses(String raw) {
    if (raw==null || raw.isBlank() || raw.equalsIgnoreCase("ALL")) return List.of();
    String value=raw.toUpperCase(Locale.ROOT).replace(' ','_');
    if (value.equals("ACTIVE")) return ACTIVE;
    if (value.equals("IN_PROGRESS")) return List.of("PICKED_UP","IN_TRANSIT","OUT_FOR_DELIVERY");
    try { return List.of(ParcelStatus.valueOf(value).name()); }
    catch (IllegalArgumentException ex) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid status"); }
  }
}
