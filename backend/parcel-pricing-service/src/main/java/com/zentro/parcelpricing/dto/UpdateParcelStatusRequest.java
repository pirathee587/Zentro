package com.zentro.parcelpricing.dto;

import com.zentro.parcelpricing.domain.ParcelStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Body of PATCH /api/v1/parcels/{parcelId}/status.
 * The actor is always taken from the access token, never from this body.
 * A reason is also required when {@code status} is FAILED; the service checks that rule.
 */
public record UpdateParcelStatusRequest(
    @NotNull(message = "Status is required.")
    ParcelStatus status,

    @Size(max = 200, message = "Reason must be at most 200 characters.")
    String reason,

    @Size(max = 500, message = "Note must be at most 500 characters.")
    String note
) {}
