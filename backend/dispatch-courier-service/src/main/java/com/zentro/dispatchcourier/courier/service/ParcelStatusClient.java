package com.zentro.dispatchcourier.courier.service;

import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** S3's internal parcel operations are exclusively gRPC calls to S2. */
@Component
public class ParcelStatusClient {
  private final ParcelPricingGrpcClient grpcClient;

  public ParcelStatusClient(ParcelPricingGrpcClient grpcClient) {
    this.grpcClient = grpcClient;
  }

  public void complete(UUID parcelId) {
    grpcClient.completeDelivery(parcelId);
  }

  public Map<?, ?> updateCourierStatus(UUID parcelId, String targetStatus, String reason, String note,
      String courierSubject) {
    return grpcClient.updateCourierStatus(parcelId, targetStatus, reason, note, courierSubject);
  }
}
