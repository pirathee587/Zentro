package com.zentro.dispatchcourier.dispatch.service;

import com.zentro.contracts.parcel.v1.ParcelResponse;
import com.zentro.dispatchcourier.courier.service.ParcelPricingGrpcClient;
import com.zentro.dispatchcourier.exception.DispatchException;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Reads the authoritative parcel state from S2 over its SERVICE-authenticated gRPC API. */
@Component
@Primary
public class GrpcParcelSource implements ParcelSource {
  private static final Logger log = LoggerFactory.getLogger(GrpcParcelSource.class);
  private final ParcelPricingGrpcClient parcels;

  @Autowired
  public GrpcParcelSource(ParcelPricingGrpcClient parcels) { this.parcels = parcels; }

  @Override
  public Snapshot eligibleParcels(String bearerToken) {
    // S2 currently exposes an ID lookup, not a paged dispatch queue contract.
    return new Snapshot(List.of(), false);
  }

  @Override
  public <T> T withEligibleParcel(UUID parcelId, String bearerToken, Function<Parcel, T> action) {
    ParcelResponse response;
    try {
      response = parcels.getParcel(parcelId);
    } catch (StatusRuntimeException error) {
      if (error.getStatus().getCode() == Status.Code.NOT_FOUND) {
        throw new DispatchException(HttpStatus.NOT_FOUND, "PARCEL_NOT_FOUND", "Parcel was not found.");
      }
      log.warn("S2 parcel lookup failed: grpcStatus={}", error.getStatus().getCode());
      throw unavailable();
    }
    if (!parcelId.toString().equals(response.getParcelId())) throw unavailable();
    if (!"CREATED".equals(response.getStatus())) {
      throw new DispatchException(HttpStatus.CONFLICT, "PARCEL_NOT_ELIGIBLE",
          "Only parcels in CREATED status can be assigned.");
    }
    return action.apply(new Parcel(parcelId, response.getTrackingNumber(), null, null, Instant.EPOCH));
  }

  private static DispatchException unavailable() {
    return new DispatchException(HttpStatus.SERVICE_UNAVAILABLE, "DISPATCH_SOURCE_UNAVAILABLE",
        "The parcel source is unavailable. Queue eligibility and assignment cannot be verified. Try again later.");
  }
}
