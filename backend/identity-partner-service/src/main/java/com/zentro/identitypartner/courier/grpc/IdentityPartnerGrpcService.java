package com.zentro.identitypartner.courier.grpc;

import com.zentro.contracts.identity.v1.CourierProfileResponse;
import com.zentro.contracts.identity.v1.GetCourierProfileRequest;
import com.zentro.contracts.identity.v1.GetMerchantStatusRequest;
import com.zentro.contracts.identity.v1.IdentityPartnerServiceGrpc;
import com.zentro.contracts.identity.v1.MerchantStatusResponse;
import com.zentro.identitypartner.courier.CourierGrpcResponse;
import com.zentro.identitypartner.courier.CourierProfileService;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class IdentityPartnerGrpcService extends IdentityPartnerServiceGrpc.IdentityPartnerServiceImplBase {
  private static final Logger log = LoggerFactory.getLogger(IdentityPartnerGrpcService.class);

  private final CourierProfileService courierProfileService;

  public IdentityPartnerGrpcService(CourierProfileService courierProfileService) {
    this.courierProfileService = courierProfileService;
  }

  @Override
  public void getCourierProfile(
      GetCourierProfileRequest request,
      StreamObserver<CourierProfileResponse> responseObserver) {
    String courierId = request.getCourierId();
    log.debug("Handling gRPC GetCourierProfile for courierId: {}", courierId);

    CourierGrpcResponse response = courierProfileService.getCourierProfileForGrpc(courierId);

    CourierProfileResponse grpcResponse = CourierProfileResponse.newBuilder()
        .setCourierId(response.courierId())
        .setActive(response.active())
        .build();

    responseObserver.onNext(grpcResponse);
    responseObserver.onCompleted();
  }

  @Override
  public void getMerchantStatus(
      GetMerchantStatusRequest request,
      StreamObserver<MerchantStatusResponse> responseObserver) {
    String merchantId = request.getMerchantId();
    log.debug("Handling gRPC GetMerchantStatus for merchantId: {}", merchantId);

    MerchantStatusResponse grpcResponse = MerchantStatusResponse.newBuilder()
        .setMerchantId(merchantId != null ? merchantId : "")
        .setStatus("ACTIVE")
        .build();

    responseObserver.onNext(grpcResponse);
    responseObserver.onCompleted();
  }
}
