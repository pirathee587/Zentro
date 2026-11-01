package com.zentro.dispatchcourier;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/**
 * S3-owned port, not an S2 API or an eligibility policy. An approved adapter must
 * supply eligible parcels and enforce the agreed lifecycle/claim protocol.
 * No parcel status (including CREATED) is interpreted by S3 as eligibility.
 */
public interface ParcelSource {
  record Parcel(UUID parcelId, String trackingReference, String pickupSummary,
                String dropoffSummary, Instant createdAt) {}
  /** Complete means a fully reconciled source, not one page of candidates. */
  record Snapshot(List<Parcel> parcels, boolean complete) {}

  Snapshot eligibleParcels(String bearerToken);

  /**
   * Invoke the callback only for an existing, currently eligible parcel. The
   * adapter must preserve the agreed eligibility guarantee through the callback
   * (sole lifecycle ownership or an approved conditional-claim/recovery protocol).
   * A mere remote lookup followed by local locking is NOT an implementation.
   * Missing -> 404, stale/ineligible -> 409, unverified/unavailable -> 503.
   */
  <T> T withEligibleParcel(UUID parcelId, String bearerToken, Function<Parcel, T> action);
}
