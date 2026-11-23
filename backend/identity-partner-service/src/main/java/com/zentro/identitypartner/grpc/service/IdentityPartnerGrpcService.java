package com.zentro.identitypartner.grpc.service;

import com.zentro.contracts.identity.v1.CourierDetails;
import com.zentro.contracts.identity.v1.CourierProfileResponse;
import com.zentro.contracts.identity.v1.GetCourierByIdRequest;
import com.zentro.contracts.identity.v1.GetCourierByUserSubjectRequest;
import com.zentro.contracts.identity.v1.GetCourierProfileRequest;
import com.zentro.contracts.identity.v1.GetMerchantStatusRequest;
import com.zentro.contracts.identity.v1.IdentityPartnerServiceGrpc;
import com.zentro.contracts.identity.v1.ListCouriersRequest;
import com.zentro.contracts.identity.v1.ListCouriersResponse;
import com.zentro.contracts.identity.v1.MerchantStatusResponse;
import com.zentro.contracts.identity.v1.MerchantVerificationResponse;
import com.zentro.contracts.identity.v1.VerifyMerchantAccountRequest;
import com.zentro.identitypartner.courier.dto.CourierGrpcResponse;
import com.zentro.identitypartner.courier.dto.CourierProfile;
import com.zentro.identitypartner.courier.service.CourierProfileService;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

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
  private com.zentro.identitypartner.user.service.KeycloakAdminService directory;
  @org.springframework.beans.factory.annotation.Autowired
  public void setDirectory(com.zentro.identitypartner.user.service.KeycloakAdminService directory) { this.directory = directory; }

  @Override
  public void resolveNotificationRecipients(com.zentro.contracts.identity.v1.NotificationRecipientsRequest request,
      StreamObserver<com.zentro.contracts.identity.v1.NotificationRecipientsResponse> observer) {
    try {
      if (request.getDispatchers() == !request.getCourierProfileId().isBlank()) {
        observer.onError(Status.INVALID_ARGUMENT.asRuntimeException()); return;
      }
      List<String> subjects = request.getDispatchers() ? directory.notificationDispatchers()
          : jdbc.queryForList("""
              SELECT u.keycloak_subject FROM identity.courier_profile c
              JOIN identity.app_user u ON u.id=c.app_user_id
              WHERE c.id=? AND u.account_status='ACTIVE'
              """, String.class, UUID.fromString(request.getCourierProfileId()));
      observer.onNext(com.zentro.contracts.identity.v1.NotificationRecipientsResponse.newBuilder().addAllSubjects(subjects).build());
      observer.onCompleted();
    } catch (IllegalArgumentException error) { observer.onError(Status.INVALID_ARGUMENT.asRuntimeException()); }
    catch (Exception error) { observer.onError(Status.UNAVAILABLE.withDescription("Recipient directory unavailable").asRuntimeException()); }
  }

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

  @Override
  public void listCouriers(
      ListCouriersRequest request, StreamObserver<ListCouriersResponse> responseObserver) {
    Boolean activeOnly = request.getFilterActive() ? request.getActiveOnly() : null;
    List<CourierProfile> couriers = courierProfileService.listCouriers(
        blankToNull(request.getStatus()),
        activeOnly,
        blankToNull(request.getZone()),
        blankToNull(request.getSearch()));
    ListCouriersResponse.Builder response = ListCouriersResponse.newBuilder();
    for (CourierProfile profile : couriers) {
      response.addCouriers(toCourierDetails(profile));
    }
    responseObserver.onNext(response.build());
    responseObserver.onCompleted();
  }

  @Override
  public void getCourierById(
      GetCourierByIdRequest request, StreamObserver<CourierDetails> responseObserver) {
    UUID courierId;
    try {
      courierId = UUID.fromString(request.getCourierId().trim());
    } catch (IllegalArgumentException e) {
      responseObserver.onError(Status.INVALID_ARGUMENT
          .withDescription("Invalid courier_id UUID")
          .asRuntimeException());
      return;
    }
    try {
      CourierProfile profile = courierProfileService.getByCourierId(courierId);
      responseObserver.onNext(toCourierDetails(profile));
      responseObserver.onCompleted();
    } catch (ResponseStatusException e) {
      responseObserver.onError(mapStatusException(e).asRuntimeException());
    }
  }

  @Override
  public void getCourierByUserSubject(
      GetCourierByUserSubjectRequest request, StreamObserver<CourierDetails> responseObserver) {
    String subject = request.getKeycloakSubject() != null ? request.getKeycloakSubject().trim() : "";
    if (subject.isEmpty()) {
      responseObserver.onError(Status.INVALID_ARGUMENT
          .withDescription("keycloak_subject must not be blank")
          .asRuntimeException());
      return;
    }
    try {
      UUID userId = jdbc.queryForObject(
          "SELECT id FROM identity.app_user WHERE keycloak_subject = ?",
          UUID.class,
          subject);
      if (userId == null) {
        responseObserver.onError(Status.NOT_FOUND
            .withDescription("Courier user not found")
            .asRuntimeException());
        return;
      }
      CourierProfile profile = courierProfileService.getOrCreateProfile(userId);
      responseObserver.onNext(toCourierDetails(profile));
      responseObserver.onCompleted();
    } catch (EmptyResultDataAccessException e) {
      responseObserver.onError(Status.NOT_FOUND
          .withDescription("Courier user not found")
          .asRuntimeException());
    } catch (ResponseStatusException e) {
      responseObserver.onError(mapStatusException(e).asRuntimeException());
    }
  }

  @Override
  public void verifyMerchantAccount(
      VerifyMerchantAccountRequest request,
      StreamObserver<MerchantVerificationResponse> responseObserver) {
    String subject = request.getKeycloakSubject() != null ? request.getKeycloakSubject().trim() : "";
    if (subject.isEmpty()) {
      responseObserver.onError(Status.INVALID_ARGUMENT
          .withDescription("keycloak_subject must not be blank")
          .asRuntimeException());
      return;
    }
    try {
      MerchantVerificationResponse response = jdbc.queryForObject("""
          SELECT u.id::text AS user_id,
                 u.account_status,
                 COALESCE(m.status, '') AS merchant_status
            FROM identity.app_user u
            LEFT JOIN identity.merchant_application m ON m.app_user_id = u.id
           WHERE u.keycloak_subject = ?
          """,
          (rs, rowNum) -> {
            String accountStatus = rs.getString("account_status");
            String merchantStatus = rs.getString("merchant_status");
            boolean approved = "ACTIVE".equalsIgnoreCase(accountStatus)
                && "APPROVED".equalsIgnoreCase(merchantStatus);
            return MerchantVerificationResponse.newBuilder()
                .setKeycloakSubject(subject)
                .setUserId(nullToEmpty(rs.getString("user_id")))
                .setAccountStatus(nullToEmpty(accountStatus))
                .setMerchantStatus(nullToEmpty(merchantStatus))
                .setApproved(approved)
                .build();
          },
          subject);
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (EmptyResultDataAccessException e) {
      responseObserver.onError(Status.NOT_FOUND
          .withDescription("Merchant account not found")
          .asRuntimeException());
    }
  }

  private static CourierDetails toCourierDetails(CourierProfile profile) {
    return CourierDetails.newBuilder()
        .setId(profile.id() != null ? profile.id().toString() : "")
        .setUserId(profile.userId() != null ? profile.userId().toString() : "")
        .setEmail(nullToEmpty(profile.email()))
        .setName(nullToEmpty(profile.name()))
        .setPhone(nullToEmpty(profile.phone()))
        .setActive(profile.active())
        .setAccountStatus(nullToEmpty(profile.accountStatus()))
        .setAssignedZone(nullToEmpty(profile.assignedZone()))
        .setVehicleType(nullToEmpty(profile.vehicleType()))
        .build();
  }

  private static Status mapStatusException(ResponseStatusException e) {
    if (e.getStatusCode().value() == HttpStatus.NOT_FOUND.value()) {
      return Status.NOT_FOUND.withDescription(e.getReason());
    }
    if (e.getStatusCode().is4xxClientError()) {
      return Status.INVALID_ARGUMENT.withDescription(e.getReason());
    }
    return Status.INTERNAL.withDescription(e.getReason());
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private static String nullToEmpty(String value) {
    return value != null ? value : "";
  }
}
