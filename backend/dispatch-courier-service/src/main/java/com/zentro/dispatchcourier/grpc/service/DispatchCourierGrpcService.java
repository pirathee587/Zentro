package com.zentro.dispatchcourier.grpc.service;

import com.zentro.contracts.dispatch.v1.DispatchCourierServiceGrpc;
import com.zentro.contracts.dispatch.v1.GetParcelAssignmentSummaryRequest;
import com.zentro.contracts.dispatch.v1.ParcelAssignmentSummaryResponse;
import com.zentro.dispatchcourier.dispatch.repository.AssignmentRepository;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class DispatchCourierGrpcService extends DispatchCourierServiceGrpc.DispatchCourierServiceImplBase {
  private final AssignmentRepository assignments;

  public DispatchCourierGrpcService(AssignmentRepository assignments) {
    this.assignments = assignments;
  }

  @Override public void resolveNotificationCourier(GetParcelAssignmentSummaryRequest request,
      StreamObserver<com.zentro.contracts.dispatch.v1.NotificationCourierResponse> observer) {
    try {
      var result=com.zentro.contracts.dispatch.v1.NotificationCourierResponse.newBuilder();
      assignments.find(UUID.fromString(request.getParcelId())).ifPresent(a->result.setCourierProfileId(a.courierId().toString()));
      observer.onNext(result.build()); observer.onCompleted();
    } catch(IllegalArgumentException error) { observer.onError(Status.INVALID_ARGUMENT.asRuntimeException()); }
  }

  @Override
  public void getParcelAssignmentSummary(
      GetParcelAssignmentSummaryRequest request,
      StreamObserver<ParcelAssignmentSummaryResponse> responseObserver) {
    UUID parcelId;
    try {
      parcelId = UUID.fromString(request.getParcelId().trim());
    } catch (IllegalArgumentException ex) {
      responseObserver.onError(Status.INVALID_ARGUMENT.withDescription("Invalid parcel_id UUID").asRuntimeException());
      return;
    }

    var found = assignments.find(parcelId);
    if (found.isEmpty()) {
      responseObserver.onNext(ParcelAssignmentSummaryResponse.newBuilder()
          .setFound(false)
          .setParcelId(parcelId.toString())
          .build());
      responseObserver.onCompleted();
      return;
    }

    var a = found.get();
    boolean accepted = "ACCEPTED".equals(a.assignmentStatus());
    String courierName = accepted
        ? (a.courierName() != null ? a.courierName() : "Assigned Courier")
        : "Awaiting courier confirmation";

    responseObserver.onNext(ParcelAssignmentSummaryResponse.newBuilder()
        .setFound(true)
        .setParcelId(a.parcelId().toString())
        .setAssignmentStatus(nullToEmpty(a.assignmentStatus()))
        .setCourierName(courierName)
        .setCourierPhone(accepted ? nullToEmpty(a.courierPhone()) : "")
        .setCourierVehicle(accepted ? nullToEmpty(a.courierVehicle()) : "")
        .setCourierZone(accepted ? nullToEmpty(a.courierZone()) : "")
        .setAssignedAt(a.assignedAt() != null ? a.assignedAt().toString() : "")
        .setRespondedAt(a.respondedAt() != null ? a.respondedAt().toString() : "")
        .build());
    responseObserver.onCompleted();
  }

  private static String nullToEmpty(String value) {
    return value != null ? value : "";
  }
}
