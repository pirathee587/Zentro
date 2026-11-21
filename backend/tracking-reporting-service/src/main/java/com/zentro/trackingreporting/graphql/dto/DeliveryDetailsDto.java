package com.zentro.trackingreporting.graphql.dto;

import com.zentro.trackingreporting.tracking.dto.PartyDto;
import com.zentro.trackingreporting.tracking.dto.TrackingEventDto;
import com.zentro.trackingreporting.tracking.entity.TrackingStatus;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.Collections;
import java.time.temporal.ChronoUnit;
import com.zentro.trackingreporting.tracking.entity.TrackingParcel;

public record DeliveryDetailsDto(
    UUID id,
    String trackingRef,
    TrackingStatus status,
    String rawStatus,
    String category,
    String size,
    String description,
    String destinationType,
    boolean isExpress,
    PartyDto sender,
    PartyDto recipient,
    BigDecimal fee,
    String currency,
    String formattedFee,
    int estimatedDays,
    String createdAt,
    String deliveredAt,
    String failureReason,
    List<TrackingEventDto> events,
    BigDecimal weightKg,
    String estimatedDeliveryDate,
    AssignedCourierDto courier
) {
  public record AssignedCourierDto(
      String name,
      String phone,
      String vehicle,
      String zone,
      String assignmentStatus,
      String assignedAt,
      String respondedAt
  ) {}

  public DeliveryDetailsDto(
      UUID id, String trackingRef, TrackingStatus status, String rawStatus, String category,
      String size, String description, String destinationType, boolean isExpress,
      PartyDto sender, PartyDto recipient, BigDecimal fee, String currency, String formattedFee,
      int estimatedDays, String createdAt, String deliveredAt, String failureReason,
      List<TrackingEventDto> events, BigDecimal weightKg, String estimatedDeliveryDate) {
    this(id, trackingRef, status, rawStatus, category, size, description, destinationType, isExpress,
        sender, recipient, fee, currency, formattedFee, estimatedDays, createdAt, deliveredAt,
        failureReason, events, weightKg, estimatedDeliveryDate, null);
  }

  public DeliveryDetailsDto withCourier(AssignedCourierDto assignedCourier) {
    return new DeliveryDetailsDto(id, trackingRef, status, rawStatus, category, size, description,
        destinationType, isExpress, sender, recipient, fee, currency, formattedFee, estimatedDays,
        createdAt, deliveredAt, failureReason, events, weightKg, estimatedDeliveryDate, assignedCourier);
  }
  public static DeliveryDetailsDto from(TrackingParcel parcel) {
    PartyDto sender = new PartyDto(
        parcel.getSenderName(),
        parcel.getSenderPhone(),
        parcel.getSenderAddress(),
        parcel.getSenderCity()
    );
    PartyDto recipient = new PartyDto(
        parcel.getRecipientName(),
        parcel.getRecipientPhone(),
        parcel.getRecipientAddress(),
        parcel.getRecipientCity()
    );

    String formattedFee = null;
    if (parcel.getFee() != null && parcel.getCurrency() != null) {
      formattedFee = parcel.getCurrency() + " " + parcel.getFee().toPlainString();
    }

    return new DeliveryDetailsDto(
        parcel.getId(),
        parcel.getTrackingRef(),
        parcel.getStatus(),
        parcel.getStatus() != null ? parcel.getStatus().name() : "",
        parcel.getCategory(),
        parcel.getSize(),
        parcel.getDescription(),
        parcel.getDestinationType(),
        parcel.isExpress(),
        sender,
        recipient,
        parcel.getFee(),
        parcel.getCurrency(),
        formattedFee,
        parcel.getEstimatedDays(),
        parcel.getCreatedAt() != null ? parcel.getCreatedAt().toString() : null,
        parcel.getDeliveredAt() != null ? parcel.getDeliveredAt().toString() : null,
        parcel.getFailureReason(),
        Collections.emptyList(),
        parcel.getWeightKg(),
        parcel.getCreatedAt() != null && parcel.getEstimatedDays() > 0
            ? parcel.getCreatedAt().plus(parcel.getEstimatedDays(), ChronoUnit.DAYS).toString() : null
    );
  }
}
