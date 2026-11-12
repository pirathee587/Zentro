package com.zentro.identitypartner.grpc.service;

import com.zentro.contracts.identity.v1.CourierProfileResponse;
import com.zentro.contracts.identity.v1.GetCourierProfileRequest;
import com.zentro.contracts.identity.v1.GetMerchantStatusRequest;
import com.zentro.contracts.identity.v1.IdentityPartnerServiceGrpc;
import com.zentro.contracts.identity.v1.MerchantStatusResponse;
import com.zentro.identitypartner.courier.dto.CourierGrpcResponse;
import com.zentro.identitypartner.courier.service.CourierProfileService;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * gRPC server implementation for the IdentityPartnerService contract.
 *
 * <p>{@code getMerchantStatus} queries the actual {@code merchant_application} table so that
 * callers (e.g. S3 dispatch) can check whether a merchant is APPROVED before acting on their
 * parcels. It previously returned a hardcoded {@code "ACTIVE"}, which prevented lifecycle
 * enforcement from propagating across service boundaries.
 */
@Service
public class IdentityPartnerGrpcService extends IdentityPartnerServiceGrpc.IdentityPartnerServiceImplBase {
  private static final Logger log = LoggerFactory.getLogger(IdentityPartnerGrpcService.class);

  /** Returned when no application row is found for the given merchant id (Keycloak subject). */
  private static final String STATUS_NOT_FOUND = "NOT_FOUND";

  private final CourierProfileService courierProfileService;
  private final JdbcTemplate jdbc;

  public IdentityPartnerGrpcService(CourierProfileService courierProfileService, JdbcTemplate jdbc) {
    this.courierProfileService = courierProfileService;
    this.jdbc = jdbc;
  }

  @Override
  public void getCourierProfile(
      GetCourierProfileRequest request,
      StreamObserver<CourierProfileResponse> responseObserver) {
    String courierId = request.getCourierId();
    log.debug("Handling gRPC GetCourierProfile");

    CourierGrpcResponse response = courierProfileService.getCourierProfileForGrpc(courierId);

    CourierProfileResponse grpcResponse = CourierProfileResponse.newBuilder()
        .setCourierId(response.courierId())
        .setActive(response.active())
        .build();

    responseObserver.onNext(grpcResponse);
    responseObserver.onCompleted();
  }

  /**
   * Returns the merchant's application approval status ({@code PENDING}, {@code APPROVED},
   * {@code REJECTED}, {@code SUSPENDED}) for the given Keycloak subject (merchant id).
   *
   * <p>The {@code merchant_id} field in the request is the Keycloak subject of the merchant's
   * user account; it corresponds to {@code identity.app_user.keycloak_subject} joined through
   * {@code identity.merchant_application.app_user_id}.
   *
   * <p>Returns {@code NOT_FOUND} if no application exists for the merchant.
   */
  @Override
  public void getMerchantStatus(
      GetMerchantStatusRequest request,
      StreamObserver<MerchantStatusResponse> responseObserver) {
    String merchantId = request.getMerchantId();
    log.debug("Handling gRPC GetMerchantStatus");

    String status = queryMerchantStatus(merchantId);

    MerchantStatusResponse grpcResponse = MerchantStatusResponse.newBuilder()
        .setMerchantId(merchantId != null ? merchantId : "")
        .setStatus(status)
        .build();

    responseObserver.onNext(grpcResponse);
    responseObserver.onCompleted();
  }

  /**
   * Looks up the merchant application status from the database.
   *
   * @param merchantSubject Keycloak subject (may be null or blank)
   * @return the status string, or {@link #STATUS_NOT_FOUND} if no row exists
   */
  private String queryMerchantStatus(String merchantSubject) {
    if (merchantSubject == null || merchantSubject.isBlank()) {
      log.warn("getMerchantStatus called with blank merchant id; returning {}", STATUS_NOT_FOUND);
      return STATUS_NOT_FOUND;
    }
    try {
      String status = jdbc.queryForObject("""
          SELECT CASE
                   WHEN u.account_status IN ('DISABLED', 'SUSPENDED') THEN 'SUSPENDED'
                   ELSE m.status
                 END
            FROM identity.merchant_application m
            JOIN identity.app_user u ON u.id = m.app_user_id
           WHERE u.keycloak_subject = ?
          """, String.class, merchantSubject);
      return status != null ? status : STATUS_NOT_FOUND;
    } catch (EmptyResultDataAccessException e) {
      log.debug("Merchant application not found");
      return STATUS_NOT_FOUND;
    }
  }
}
