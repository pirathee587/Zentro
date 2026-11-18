package com.zentro.trackingreporting.graphql.dashboard;

import com.zentro.trackingreporting.graphql.dto.PageInfoDto;
import com.zentro.trackingreporting.tracking.dto.PartyDto;
import com.zentro.trackingreporting.tracking.entity.TrackingStatus;
import java.util.List;
import java.util.UUID;

public record CourierTodayDashboardDto(String businessDate, String timeZone, Counts counts,
    Delivery currentDelivery, List<Delivery> deliveries, PageInfoDto pageInfo) {
  public record Counts(int assigned, int delivered, int failed, int remaining) {}
  public record Delivery(UUID parcelId, String assignedAt, String assignmentState, boolean projectionAvailable,
      String trackingRef, TrackingStatus status, PartyDto recipient, Double weightKg, String size,
      String failureReason, UUID proofId, String completionState) {}
}
