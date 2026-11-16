package com.zentro.trackingreporting.graphql.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.zentro.trackingreporting.graphql.dto.CustomerDashboardSummary;
import com.zentro.trackingreporting.graphql.dto.PublicTrackingParcelDto;
import com.zentro.trackingreporting.tracking.entity.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;

/** S4 read aggregation calls S2's authorized APIs through the gateway, never S2's database. */
@Component
public class CustomerBookingClient {
  private final RestClient client;
  @Autowired public CustomerBookingClient(RestClient.Builder builder,
      @Value("${zentro.dashboard.gateway-url:http://localhost:8080}") String gateway) {
    var factory=new SimpleClientHttpRequestFactory(); factory.setConnectTimeout(1000); factory.setReadTimeout(4000);
    client=builder.clone().baseUrl(gateway).requestFactory(factory).build();
  }
  public CustomerBookingClient(RestClient client) { this.client=client; }
  public CustomerDashboardSummary summary(Jwt jwt) {
    return client.get().uri("/api/v1/parcels/mine/summary").headers(h->h.setBearerAuth(jwt.getTokenValue()))
        .retrieve().body(CustomerDashboardSummary.class);
  }
  public TrackingParcel reference(Jwt jwt,String reference) {
    JsonNode row=client.get().uri("/api/v1/parcels/mine/{reference}",reference)
        .headers(h->h.setBearerAuth(jwt.getTokenValue())).retrieve().body(JsonNode.class);
    return row==null?null:parcel(row,jwt.getSubject());
  }
  public TrackingParcel live(Jwt jwt) {
    JsonNode row=client.get().uri("/api/v1/parcels/mine/live").headers(h->h.setBearerAuth(jwt.getTokenValue())).retrieve().body(JsonNode.class);
    return row==null || row.isNull()?null:parcel(row,jwt.getSubject());
  }
  public Page<TrackingParcel> page(Jwt jwt, Pageable pageable, TrackingStatus status, String search) {
    JsonNode result=client.get().uri(b->{
      b.path("/api/v1/parcels/mine").queryParam("page",pageable.getPageNumber()+1).queryParam("size",pageable.getPageSize());
      if(status!=null)b.queryParam("status",status.name()); if(search!=null)b.queryParam("q",search); return b.build();
    }).headers(h->h.setBearerAuth(jwt.getTokenValue())).retrieve().body(JsonNode.class);
    if(result==null)throw new IllegalStateException("Booking data unavailable");
    var rows=new ArrayList<TrackingParcel>();
    for(var row:result.path("content")) rows.add(parcel(row,jwt.getSubject()));
    return new PageImpl<>(rows,pageable,result.path("totalElements").asLong());
  }
  public PublicTrackingParcelDto publicTracking(String ref) {
    JsonNode p;
    try { p=client.get().uri("/api/v1/parcels/tracking/{ref}",ref).retrieve().body(JsonNode.class); }
    catch(HttpClientErrorException.NotFound ex) { return null; }
    if(p==null) return null;
    String created=p.path("createdAt").asText(); int days=p.path("estimatedDays").asInt();
    return new PublicTrackingParcelDto(p.path("trackingRef").asText(),TrackingStatus.valueOf(p.path("status").asText()),
        p.path("status").asText(),p.path("category").asText(),text(p,"size"),text(p,"description"),text(p,"originCity"),text(p,"destinationCity"),
        days,days>0?OffsetDateTime.parse(created).plusDays(days).toInstant().toString():null,created,null,null,List.of());
  }
  private String text(JsonNode row,String key) { return row.path(key).isNull()||row.path(key).isMissingNode()?null:row.path(key).asText(); }
  private TrackingParcel parcel(JsonNode row,String owner) {
    var p=new TrackingParcel(); p.setId(UUID.fromString(row.path("id").asText())); p.setParcelId(p.getId());
    p.setCustomerSubject(owner); p.setTrackingRef(row.path("trackingRef").asText());
    p.setStatus(TrackingStatus.valueOf(row.path("status").asText()));
    p.setFee(row.path("fee").decimalValue()); p.setCurrency(row.path("currency").asText());
    p.setCreatedAt(OffsetDateTime.parse(row.path("createdAt").asText()).toInstant()); p.setEstimatedDays(row.path("estimatedDays").asInt());
    p.setDestinationType(text(row,"destinationType")); p.setExpress(row.path("isExpress").asBoolean());
    p.setSenderName(text(row.path("sender"),"name")); p.setSenderPhone(text(row.path("sender"),"phone"));
    p.setSenderAddress(text(row.path("sender"),"address")); p.setSenderCity(text(row.path("sender"),"city"));
    p.setRecipientName(text(row.path("recipient"),"name")); p.setRecipientPhone(text(row.path("recipient"),"phone"));
    p.setRecipientAddress(text(row.path("recipient"),"address")); p.setRecipientCity(text(row.path("recipient"),"city"));
    p.setWeightKg(row.path("parcel").path("weightKg").decimalValue()); p.setCategory(text(row.path("parcel"),"category"));
    p.setSize(text(row.path("parcel"),"size")); p.setDescription(text(row.path("parcel"),"description")); return p;
  }
}
