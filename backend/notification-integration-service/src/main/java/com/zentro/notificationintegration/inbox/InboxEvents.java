package com.zentro.notificationintegration.inbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zentro.notificationintegration.webhook.client.IdentityPartnerGrpcClient;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Dedicated privacy-minimal events; existing status v1, mock SMS and signed webhooks remain unchanged. */
@Service
public class InboxEvents {
  private final InboxRepository inbox;
  private final IdentityPartnerGrpcClient identities;
  private final ObjectMapper mapper;
  private final NotificationAssignmentClient assignments;
  public InboxEvents(InboxRepository inbox, IdentityPartnerGrpcClient identities, ObjectMapper mapper, NotificationAssignmentClient assignments) {
    this.inbox=inbox; this.identities=identities; this.mapper=mapper; this.assignments=assignments;
  }
  @Transactional
  public void accept(String topic, String body) throws Exception {
    JsonNode e=mapper.readTree(body), p=e.path("payload");
    String type=e.path("eventType").asText();
    if (!topic.equals(type) || !java.util.Set.of("notification.parcel","notification.assignment","notification.proof").contains(type))
      throw new IllegalArgumentException("Unexpected notification event");
    UUID event=UUID.fromString(e.path("eventId").asText()), parcel=UUID.fromString(e.path("aggregateId").asText());
    OffsetDateTime occurred=OffsetDateTime.parse(e.path("occurredAt").asText());
    if (type.equals("notification.parcel")) {
      String status=p.path("status").asText();
      if (!java.util.Set.of("CREATED","PICKED_UP","IN_TRANSIT","OUT_FOR_DELIVERY","DELIVERED","FAILED").contains(status))
        throw new IllegalArgumentException("Unknown parcel status");
      String title=switch(status) {
        case "CREATED" -> "Booking confirmed"; case "PICKED_UP" -> "Parcel picked up";
        case "IN_TRANSIT" -> "Parcel in transit"; case "OUT_FOR_DELIVERY" -> "Parcel out for delivery";
        case "DELIVERED" -> "Parcel delivered"; default -> "Delivery failed";
      };
      String message=status.equals("FAILED") ? "Delivery could not be completed. Contact support for assistance." : title + ".";
      // Merchant orders are not customer bookings; never infer an owner from a tracking reference.
      String merchant=p.path("merchantSubject").asText("");
      if (!merchant.isBlank()) add(event,merchant,"MERCHANT",status,title,message,parcel,occurred);
      else if (!status.equals("IN_TRANSIT")) add(event,p.path("customerSubject").asText(""),"CUSTOMER",status,title,message,parcel,occurred);
      if (java.util.Set.of("CREATED","FAILED","DELIVERED").contains(status)) dispatch(event,status,
          status.equals("CREATED") ? "New parcel awaiting assignment" : title,message,parcel,occurred);
      if (java.util.Set.of("FAILED","DELIVERED").contains(status)) {
        String courier=assignments.courier(parcel);
        if(!courier.isBlank()) for(String subject:identities.notificationRecipients(false,courier))
          add(event,subject,"COURIER",status,title,message,parcel,occurred);
      }
    } else if (type.equals("notification.assignment")) {
      String action=p.path("action").asText();
      if (!java.util.Set.of("ASSIGNED","REASSIGNED","CANCELLED","ACCEPTED","REJECTED").contains(action))
        throw new IllegalArgumentException("Unknown assignment action");
      String title="Assignment " + action.toLowerCase(java.util.Locale.ROOT);
      dispatch(event,"ASSIGNMENT_"+action,title,title+".",parcel,occurred);
      java.util.Set<String> subjects=new java.util.HashSet<>();
      for (String field : java.util.List.of("oldCourierId","newCourierId")) {
        String id=p.path(field).asText("");
        if (!id.isBlank()) subjects.addAll(identities.notificationRecipients(false,id));
      }
      for (String subject:subjects) add(event,subject,"COURIER","ASSIGNMENT_"+action,title,title+". Review your current deliveries.",parcel,occurred);
    } else {
      String decision=p.path("decision").asText();
      String title=switch(decision) {
        case "PENDING_REVIEW" -> p.path("revision").asInt()>1 ? "Proof resubmitted" : "Proof submitted";
        case "APPROVED", "COMPLETION_PENDING" -> "Proof approved";
        case "REJECTED" -> "Proof rejected";
        case "COMPLETED" -> "Delivery completion confirmed";
        case "COMPLETION_FAILED" -> "Delivery completion failed";
        case "BLOCKED" -> "Delivery completion blocked";
        default -> throw new IllegalArgumentException("Unknown proof decision");
      };
      String reason=p.path("reason").asText("").replaceAll("[\\p{Cntrl}]"," ").strip();
      String message=title+"."+(decision.equals("REJECTED") && !reason.isBlank() ? " "+reason.substring(0,Math.min(500,reason.length())) : "");
      add(event,p.path("courierSubject").asText(""),"COURIER","PROOF_"+decision,title,message,parcel,occurred);
      dispatch(event,"PROOF_"+decision,title,message,parcel,occurred);
    }
  }
  private void dispatch(UUID event,String type,String title,String message,UUID parcel,OffsetDateTime at) {
    for(String subject:identities.notificationRecipients(true,null)) add(event,subject,"DISPATCHER",type,title,message,parcel,at);
  }
  private void add(UUID event,String subject,String portal,String type,String title,String message,UUID parcel,OffsetDateTime at) {
    String target=switch(portal) {
      case "CUSTOMER" -> "/customer/history";
      case "MERCHANT" -> "/merchant/dashboard/orders";
      case "COURIER" -> "/courier/deliveries/"+parcel;
      default -> type.startsWith("PROOF_") ? "/dispatcher/proof-reviews" : "/dispatcher/parcels";
    };
    inbox.insert(event,subject,portal,type,title,message,parcel,target,at);
  }
}
