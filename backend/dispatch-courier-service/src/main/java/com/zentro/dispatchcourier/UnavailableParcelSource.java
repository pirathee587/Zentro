package com.zentro.dispatchcourier;

import java.util.UUID;
import java.util.function.Function;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Production integration stays unavailable until S2 contract/policy confirmation. */
@Component
public class UnavailableParcelSource implements ParcelSource {
  @Override public Snapshot eligibleParcels(String token) { throw unavailable(); }
  @Override public <T> T withEligibleParcel(UUID id, String token, Function<Parcel, T> action) {
    throw unavailable();
  }
  private DispatchException unavailable() {
    return new DispatchException(HttpStatus.SERVICE_UNAVAILABLE, "DISPATCH_SOURCE_UNAVAILABLE",
        "The parcel source is unavailable. Queue eligibility and assignment cannot be verified. Try again later.");
  }
}
