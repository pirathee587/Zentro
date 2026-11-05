package com.zentro.parcelpricing.parcel.dto;

import com.zentro.parcelpricing.parcel.entity.ParcelStatus;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Result of a status update.
 *
 * @param changed   false when the parcel already had the requested status (nothing was recorded)
 * @param changedAt time of the recorded change; null when {@code changed} is false
 */
public record ParcelStatusResponse(
    UUID parcelId,
    String trackingRef,
    ParcelStatus previousStatus,
    ParcelStatus status,
    boolean changed,
    OffsetDateTime changedAt
) {}
