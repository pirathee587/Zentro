package com.zentro.trackingreporting.graphql.service;

import com.zentro.contracts.parcel.v1.CurrencyAmountProto;
import com.zentro.contracts.parcel.v1.CustomerDashboardSummaryResponse;
import com.zentro.contracts.parcel.v1.CustomerParcelPageResponse;
import com.zentro.contracts.parcel.v1.CustomerParcelViewResponse;
import com.zentro.contracts.parcel.v1.OptionalCustomerParcelViewResponse;
import com.zentro.contracts.parcel.v1.ParcelHistoryResponse;
import com.zentro.contracts.parcel.v1.PublicTrackingResponse;
import com.zentro.contracts.parcel.v1.StatusHistoryEntryProto;
import com.zentro.trackingreporting.graphql.dto.CustomerDashboardSummary;
import com.zentro.trackingreporting.graphql.dto.DeliveryDetailsDto;
import com.zentro.trackingreporting.graphql.dto.PublicTrackingParcelDto;
import com.zentro.trackingreporting.tracking.dto.TrackingEventDto;
import com.zentro.trackingreporting.tracking.entity.TrackingParcel;
import com.zentro.trackingreporting.tracking.entity.TrackingStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Customer dashboard read-only client backed by S2 ({@link ParcelPricingGrpcClient}) and
 * S3 ({@link DispatchCourierGrpcClient}) over gRPC.
 */
@Component
public class CustomerBookingClient {
  private final ParcelPricingGrpcClient parcelGrpc;
  private final DispatchCourierGrpcClient dispatchGrpc;

  public CustomerBookingClient(ParcelPricingGrpcClient parcelGrpc, DispatchCourierGrpcClient dispatchGrpc) {
    this.parcelGrpc = parcelGrpc;
    this.dispatchGrpc = dispatchGrpc;
  }

  public CustomerDashboardSummary summary(Jwt jwt) {
    CustomerDashboardSummaryResponse resp = parcelGrpc.getSummary(jwt.getSubject());
    return new CustomerDashboardSummary(
        resp.getActiveCount(),
        resp.getDeliveredCount(),
        resp.getAwaitingPickupCount(),
        resp.getFailedCount(),
        resp.getInTransitCount(),
        resp.getTotalParcels(),
        resp.getMonth(),
        resp.getTimeZone(),
        toCurrencyAmounts(resp.getTotalFeesList()),
        toCurrencyAmounts(resp.getMonthlyFeesList()));
  }

  public TrackingParcel reference(Jwt jwt, String reference) {
    return referenceForCustomer(jwt.getSubject(), reference);
  }

  /** Authoritative ownership lookup for long-lived streams, using S4's SERVICE token. */
  public TrackingParcel referenceForCustomer(String subject, String reference) {
    TrackingParcel parcel = toParcel(parcelGrpc.getParcelByReference(subject, reference));
    // S2 checks this subject before returning the parcel. Keep identity out of Kafka.
    parcel.setCustomerSubject(subject);
    return parcel;
  }

  public TrackingParcel live(Jwt jwt) {
    OptionalCustomerParcelViewResponse resp = parcelGrpc.getLiveParcel(jwt.getSubject());
    return resp.getFound() ? toParcel(resp.getParcel()) : null;
  }

  public Page<TrackingParcel> page(Jwt jwt, Pageable pageable, TrackingStatus status, String search) {
    CustomerParcelPageResponse resp = parcelGrpc.listParcels(
        jwt.getSubject(),
        pageable.getPageNumber() + 1,
        pageable.getPageSize(),
        status != null ? status.name() : null,
        search);
    List<TrackingParcel> content = resp.getContentList().stream()
        .map(CustomerBookingClient::toParcel)
        .toList();
    return new PageImpl<>(content, pageable, resp.getTotalElements());
  }

  public PublicTrackingParcelDto publicTracking(String ref) {
    PublicTrackingResponse resp = parcelGrpc.getPublicTracking(ref);
    TrackingStatus mappedStatus = TrackingStatus.fromString(resp.getStatus());
    List<TrackingEventDto> events = resp.getEventsList().stream()
        .map(entry -> toEventDto(resp.getTrackingRef(), entry)).toList();
    String deliveredAt = events.stream().filter(e -> "DELIVERED".equals(e.status()))
        .map(e -> e.occurredAt().toString()).findFirst().orElse(null);
    String failureReason = events.stream().filter(e -> "FAILED".equals(e.status()))
        .map(TrackingEventDto::failureReason).filter(java.util.Objects::nonNull).findFirst().orElse(null);
    return new PublicTrackingParcelDto(
        resp.getTrackingRef(),
        mappedStatus,
        resp.getStatus(),
        emptyToNull(resp.getCategory()),
        emptyToNull(resp.getSize()),
        emptyToNull(resp.getDescription()),
        emptyToNull(resp.getOriginCity()),
        emptyToNull(resp.getDestinationCity()),
        resp.getEstimatedDays(),
        null,
        emptyToNull(resp.getCreatedAt()),
        deliveredAt,
        failureReason,
        events);
  }

  public List<TrackingEventDto> customerHistory(Jwt jwt, String ref) {
    ParcelHistoryResponse resp = parcelGrpc.getHistory(jwt.getSubject(), ref);
    return resp.getEntriesList().stream()
        .map(entry -> toEventDto(ref, entry))
        .toList();
  }

  public DeliveryDetailsDto.AssignedCourierDto assignedCourier(Jwt jwt, UUID parcelId) {
    return dispatchGrpc.getAssignmentSummary(parcelId)
        .map(a -> new DeliveryDetailsDto.AssignedCourierDto(
            emptyToNull(a.getCourierName()),
            emptyToNull(a.getCourierPhone()),
            emptyToNull(a.getCourierVehicle()),
            emptyToNull(a.getCourierZone()),
            emptyToNull(a.getAssignmentStatus()),
            emptyToNull(a.getAssignedAt()),
            emptyToNull(a.getRespondedAt())))
        .orElse(null);
  }

  private static TrackingEventDto toEventDto(String ref, StatusHistoryEntryProto entry) {
    String status = entry.getStatus();
    String title = switch (status) {
      case "CREATED" -> "Parcel booked";
      case "PICKED_UP" -> "Picked up by courier";
      case "IN_TRANSIT" -> "In transit";
      case "OUT_FOR_DELIVERY" -> "Out for delivery";
      case "DELIVERED" -> "Delivered";
      case "FAILED" -> "Delivery failed";
      case "CANCELLED" -> "Cancelled";
      default -> status;
    };
    String desc = !entry.getNote().isBlank() ? entry.getNote() : entry.getReason();
    Instant occurredAt = OffsetDateTime.parse(entry.getChangedAt()).toInstant();
    return new TrackingEventDto(
        entry.getId().isBlank() ? UUID.nameUUIDFromBytes((ref + ":" + status + ":" + entry.getChangedAt()).getBytes(java.nio.charset.StandardCharsets.UTF_8)) : UUID.fromString(entry.getId()),
        ref,
        status,
        title,
        desc,
        emptyToNull(entry.getActorRole()),
        null,
        occurredAt,
        "FAILED".equals(status) ? emptyToNull(entry.getReason()) : null);
  }

  private static TrackingParcel toParcel(CustomerParcelViewResponse v) {
    TrackingParcel p = new TrackingParcel();
    if (!v.getId().isBlank()) {
      UUID id = UUID.fromString(v.getId());
      p.setId(id);
      p.setParcelId(id);
    }
    p.setTrackingRef(v.getTrackingRef());
    p.setStatus(TrackingStatus.fromString(v.getStatus()));
    if (!v.getFee().isBlank()) {
      p.setFee(new BigDecimal(v.getFee()));
    }
    p.setCurrency(emptyToNull(v.getCurrency()));
    p.setEstimatedDays(v.getEstimatedDays());
    p.setDestinationType(emptyToNull(v.getDestinationType()));
    p.setExpress(v.getIsExpress());
    if (!v.getCreatedAt().isBlank()) {
      p.setCreatedAt(OffsetDateTime.parse(v.getCreatedAt()).toInstant());
    }
    if (v.hasSender()) {
      p.setSenderName(emptyToNull(v.getSender().getName()));
      p.setSenderPhone(emptyToNull(v.getSender().getPhone()));
      p.setSenderAddress(emptyToNull(v.getSender().getAddress()));
      p.setSenderCity(emptyToNull(v.getSender().getCity()));
    }
    if (v.hasRecipient()) {
      p.setRecipientName(emptyToNull(v.getRecipient().getName()));
      p.setRecipientPhone(emptyToNull(v.getRecipient().getPhone()));
      p.setRecipientAddress(emptyToNull(v.getRecipient().getAddress()));
      p.setRecipientCity(emptyToNull(v.getRecipient().getCity()));
    }
    if (v.hasParcel()) {
      if (!v.getParcel().getWeightKg().isBlank()) {
        p.setWeightKg(new BigDecimal(v.getParcel().getWeightKg()));
      }
      p.setCategory(emptyToNull(v.getParcel().getCategory()));
      p.setSize(emptyToNull(v.getParcel().getSize()));
      p.setDescription(emptyToNull(v.getParcel().getDescription()));
    }
    return p;
  }

  private static List<CustomerDashboardSummary.CurrencyAmount> toCurrencyAmounts(List<CurrencyAmountProto> protos) {
    return protos.stream()
        .map(c -> new CustomerDashboardSummary.CurrencyAmount(
            c.getCurrency(),
            c.getAmount().isBlank() ? BigDecimal.ZERO : new BigDecimal(c.getAmount())))
        .toList();
  }

  private static String emptyToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }
}
