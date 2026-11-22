package com.zentro.parcelpricing.grpc.service;

import com.zentro.contracts.parcel.v1.CompleteParcelDeliveryRequest;
import com.zentro.contracts.parcel.v1.CurrencyAmountProto;
import com.zentro.contracts.parcel.v1.CustomerDashboardSummaryResponse;
import com.zentro.contracts.parcel.v1.CustomerParcelIdRequest;
import com.zentro.contracts.parcel.v1.CustomerParcelPageResponse;
import com.zentro.contracts.parcel.v1.CustomerParcelReferenceRequest;
import com.zentro.contracts.parcel.v1.CustomerParcelViewResponse;
import com.zentro.contracts.parcel.v1.CustomerSubjectRequest;
import com.zentro.contracts.parcel.v1.EstimateDeliveryFeeRequest;
import com.zentro.contracts.parcel.v1.EstimateDeliveryFeeResponse;
import com.zentro.contracts.parcel.v1.FeeBreakdownProto;
import com.zentro.contracts.parcel.v1.GetParcelRequest;
import com.zentro.contracts.parcel.v1.ListCustomerParcelsRequest;
import com.zentro.contracts.parcel.v1.OptionalCustomerParcelViewResponse;
import com.zentro.contracts.parcel.v1.ParcelContactProto;
import com.zentro.contracts.parcel.v1.ParcelDetailsProto;
import com.zentro.contracts.parcel.v1.ParcelHistoryResponse;
import com.zentro.contracts.parcel.v1.ParcelPricingServiceGrpc;
import com.zentro.contracts.parcel.v1.ParcelResponse;
import com.zentro.contracts.parcel.v1.ParcelStatusUpdateResponse;
import com.zentro.contracts.parcel.v1.PublicTrackingRequest;
import com.zentro.contracts.parcel.v1.PublicTrackingResponse;
import com.zentro.contracts.parcel.v1.StatusHistoryEntryProto;
import com.zentro.contracts.parcel.v1.UpdateCourierParcelStatusRequest;
import com.zentro.parcelpricing.exception.InvalidTransitionException;
import com.zentro.parcelpricing.exception.ParcelNotFoundException;
import com.zentro.parcelpricing.exception.StatusFieldException;
import com.zentro.parcelpricing.grpc.security.PodGrpcSecurityInterceptor;
import com.zentro.parcelpricing.parcel.dto.CustomerParcelView;
import com.zentro.parcelpricing.parcel.dto.ParcelBookingResponse;
import com.zentro.parcelpricing.parcel.dto.ParcelStatusResponse;
import com.zentro.parcelpricing.parcel.entity.DestinationType;
import com.zentro.parcelpricing.parcel.entity.ParcelBooking;
import com.zentro.parcelpricing.parcel.entity.ParcelStatus;
import com.zentro.parcelpricing.parcel.repository.ParcelBookingRepository;
import com.zentro.parcelpricing.parcel.repository.ParcelStatusRepository;
import com.zentro.parcelpricing.parcel.service.CustomerParcelReadService;
import com.zentro.parcelpricing.parcel.service.ParcelStatusService;
import com.zentro.parcelpricing.pricing.dto.FeeEstimateRequest;
import com.zentro.parcelpricing.pricing.dto.FeeEstimateResponse;
import com.zentro.parcelpricing.pricing.service.PricingService;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ParcelPricingGrpcService extends ParcelPricingServiceGrpc.ParcelPricingServiceImplBase {
  private final ParcelBookingRepository bookingRepository;
  private final ParcelStatusService statusService;
  private final CustomerParcelReadService customerReadService;
  private final PricingService pricingService;
  private final ParcelStatusRepository statusHistory;

  public ParcelPricingGrpcService(ParcelBookingRepository bookingRepository, ParcelStatusService statusService) {
    this(bookingRepository, statusService, null, null);
  }

  public ParcelPricingGrpcService(
      ParcelBookingRepository bookingRepository,
      ParcelStatusService statusService,
      CustomerParcelReadService customerReadService,
      PricingService pricingService) {
    this(bookingRepository, statusService, customerReadService, pricingService, null);
  }

  @Autowired
  public ParcelPricingGrpcService(ParcelBookingRepository bookingRepository, ParcelStatusService statusService,
      CustomerParcelReadService customerReadService, PricingService pricingService, ParcelStatusRepository statusHistory) {
    this.statusHistory = statusHistory;
    this.bookingRepository = bookingRepository;
    this.statusService = statusService;
    this.customerReadService = customerReadService;
    this.pricingService = pricingService;
  }

  @Override
  public void getParcel(GetParcelRequest request, StreamObserver<ParcelResponse> responseObserver) {
    UUID parcelId;
    try {
      parcelId = UUID.fromString(request.getParcelId());
    } catch (IllegalArgumentException ex) {
      responseObserver.onError(Status.INVALID_ARGUMENT.withDescription("Invalid parcelId UUID").asRuntimeException());
      return;
    }

    bookingRepository.findById(parcelId).ifPresentOrElse(
        parcel -> {
          responseObserver.onNext(ParcelResponse.newBuilder()
              .setParcelId(parcel.id().toString())
              .setTrackingNumber(parcel.trackingRef())
              .setStatus(parcel.status().name())
              .build());
          responseObserver.onCompleted();
        },
        () -> responseObserver.onError(Status.NOT_FOUND.withDescription("Parcel not found").asRuntimeException())
    );
  }

  @Override
  public void completeParcelDelivery(CompleteParcelDeliveryRequest request,
      StreamObserver<ParcelStatusUpdateResponse> responseObserver) {
    try {
      UUID parcelId = UUID.fromString(request.getParcelId());
      String actor = PodGrpcSecurityInterceptor.CALLER_IDENTITY_CONTEXT_KEY.get();
      String callerSubject = actor != null && !actor.isBlank() ? actor : "service-account";
      ParcelStatusResponse res = statusService.updateStatus(
          parcelId, ParcelStatus.DELIVERED, null, null, callerSubject, "SERVICE");
      responseObserver.onNext(toProto(res));
      responseObserver.onCompleted();
    } catch (ParcelNotFoundException ex) {
      responseObserver.onError(Status.NOT_FOUND.withDescription(ex.getMessage()).asRuntimeException());
    } catch (InvalidTransitionException ex) {
      responseObserver.onError(Status.FAILED_PRECONDITION.withDescription(ex.getMessage()).asRuntimeException());
    } catch (StatusFieldException | IllegalArgumentException ex) {
      responseObserver.onError(Status.INVALID_ARGUMENT.withDescription(ex.getMessage()).asRuntimeException());
    }
  }

  @Override
  public void updateCourierParcelStatus(UpdateCourierParcelStatusRequest request,
      StreamObserver<ParcelStatusUpdateResponse> responseObserver) {
    try {
      UUID parcelId = UUID.fromString(request.getParcelId());
      ParcelStatus target = ParcelStatus.valueOf(request.getStatus().trim().toUpperCase());
      ParcelStatusResponse res = statusService.updateCourierVerifiedStatus(
          parcelId, target, request.getReason(), request.getNote(), request.getCourierSubject());
      responseObserver.onNext(toProto(res));
      responseObserver.onCompleted();
    } catch (ParcelNotFoundException ex) {
      responseObserver.onError(Status.NOT_FOUND.withDescription(ex.getMessage()).asRuntimeException());
    } catch (InvalidTransitionException ex) {
      responseObserver.onError(Status.FAILED_PRECONDITION.withDescription(ex.getMessage()).asRuntimeException());
    } catch (StatusFieldException | IllegalArgumentException ex) {
      responseObserver.onError(Status.INVALID_ARGUMENT.withDescription(ex.getMessage()).asRuntimeException());
    }
  }

  private static ParcelStatusUpdateResponse toProto(ParcelStatusResponse res) {
    return ParcelStatusUpdateResponse.newBuilder()
        .setParcelId(res.parcelId().toString())
        .setTrackingRef(res.trackingRef() == null ? "" : res.trackingRef())
        .setPreviousStatus(res.previousStatus() == null ? "" : res.previousStatus().name())
        .setStatus(res.status().name())
        .setChanged(res.changed())
        .build();
  }

  @Override
  public void getCustomerDashboardSummary(
      CustomerSubjectRequest request, StreamObserver<CustomerDashboardSummaryResponse> responseObserver) {
    if (customerReadService == null) {
      responseObserver.onError(Status.UNIMPLEMENTED.withDescription("Customer read service unavailable").asRuntimeException());
      return;
    }
    String subject = nullToEmpty(request.getKeycloakSubject()).trim();
    if (subject.isEmpty()) {
      responseObserver.onError(Status.INVALID_ARGUMENT.withDescription("keycloak_subject is required").asRuntimeException());
      return;
    }
    Map<String, Object> summary = customerReadService.summary(subject);
    CustomerDashboardSummaryResponse.Builder builder = CustomerDashboardSummaryResponse.newBuilder()
        .setActiveCount(asLong(summary.get("activeCount")))
        .setDeliveredCount(asLong(summary.get("deliveredCount")))
        .setAwaitingPickupCount(asLong(summary.get("awaitingPickupCount")))
        .setFailedCount(asLong(summary.get("failedCount")))
        .setInTransitCount(asLong(summary.get("inTransitCount")))
        .setTotalParcels(asLong(summary.get("totalParcels")))
        .setMonth(nullToEmpty(summary.get("month")))
        .setTimeZone(nullToEmpty(summary.get("timeZone")));
    addCurrencyAmounts(summary.get("totalFees"), builder::addTotalFees);
    addCurrencyAmounts(summary.get("monthlyFees"), builder::addMonthlyFees);
    responseObserver.onNext(builder.build());
    responseObserver.onCompleted();
  }

  @Override
  public void listCustomerParcels(
      ListCustomerParcelsRequest request, StreamObserver<CustomerParcelPageResponse> responseObserver) {
    if (customerReadService == null) {
      responseObserver.onError(Status.UNIMPLEMENTED.withDescription("Customer read service unavailable").asRuntimeException());
      return;
    }
    String subject = nullToEmpty(request.getKeycloakSubject()).trim();
    if (subject.isEmpty()) {
      responseObserver.onError(Status.INVALID_ARGUMENT.withDescription("keycloak_subject is required").asRuntimeException());
      return;
    }
    try {
      CustomerParcelReadService.Page page = customerReadService.page(
          subject,
          request.getPage() <= 0 ? 1 : request.getPage(),
          request.getSize() <= 0 ? 10 : request.getSize(),
          blankToNull(request.getStatus()),
          blankToNull(request.getQuery()));
      CustomerParcelPageResponse.Builder builder = CustomerParcelPageResponse.newBuilder()
          .setTotalElements(page.totalElements())
          .setPage(page.page())
          .setSize(page.size())
          .setHasNext(page.hasNext());
      for (CustomerParcelView view : page.content()) {
        builder.addContent(toParcelViewProto(view));
      }
      addCurrencyAmounts(page.totalFees(), builder::addTotalFees);
      responseObserver.onNext(builder.build());
      responseObserver.onCompleted();
    } catch (ResponseStatusException ex) {
      responseObserver.onError(mapResponseException(ex).asRuntimeException());
    }
  }

  @Override
  public void getCustomerParcel(
      CustomerParcelReferenceRequest request, StreamObserver<CustomerParcelViewResponse> responseObserver) {
    if (customerReadService == null) {
      responseObserver.onError(Status.UNIMPLEMENTED.withDescription("Customer read service unavailable").asRuntimeException());
      return;
    }
    String subject = nullToEmpty(request.getKeycloakSubject()).trim();
    if (subject.isEmpty()) {
      responseObserver.onError(Status.INVALID_ARGUMENT.withDescription("keycloak_subject is required").asRuntimeException());
      return;
    }
    try {
      CustomerParcelView view = customerReadService.reference(subject, request.getReference());
      responseObserver.onNext(toParcelViewProto(view));
      responseObserver.onCompleted();
    } catch (ResponseStatusException ex) {
      responseObserver.onError(mapResponseException(ex).asRuntimeException());
    }
  }

  @Override
  public void getCustomerParcelById(
      CustomerParcelIdRequest request, StreamObserver<CustomerParcelViewResponse> responseObserver) {
    String subject = nullToEmpty(request.getKeycloakSubject()).trim();
    if (subject.isEmpty()) {
      responseObserver.onError(Status.INVALID_ARGUMENT.withDescription("keycloak_subject is required").asRuntimeException());
      return;
    }
    UUID parcelId;
    try {
      parcelId = UUID.fromString(nullToEmpty(request.getParcelId()).trim());
    } catch (IllegalArgumentException ex) {
      responseObserver.onError(Status.INVALID_ARGUMENT.withDescription("Invalid parcel_id UUID").asRuntimeException());
      return;
    }
    ParcelBooking booking = bookingRepository.findById(parcelId).orElse(null);
    if (booking == null || !subject.equals(booking.customerSubject())) {
      responseObserver.onError(Status.NOT_FOUND.withDescription("Parcel not found").asRuntimeException());
      return;
    }
    responseObserver.onNext(toParcelViewProto(CustomerParcelView.from(booking)));
    responseObserver.onCompleted();
  }

  @Override
  public void getCustomerLiveParcel(
      CustomerSubjectRequest request, StreamObserver<OptionalCustomerParcelViewResponse> responseObserver) {
    if (customerReadService == null) {
      responseObserver.onError(Status.UNIMPLEMENTED.withDescription("Customer read service unavailable").asRuntimeException());
      return;
    }
    String subject = nullToEmpty(request.getKeycloakSubject()).trim();
    if (subject.isEmpty()) {
      responseObserver.onError(Status.INVALID_ARGUMENT.withDescription("keycloak_subject is required").asRuntimeException());
      return;
    }
    CustomerParcelView view = customerReadService.live(subject);
    OptionalCustomerParcelViewResponse.Builder builder = OptionalCustomerParcelViewResponse.newBuilder()
        .setFound(view != null);
    if (view != null) {
      builder.setParcel(toParcelViewProto(view));
    }
    responseObserver.onNext(builder.build());
    responseObserver.onCompleted();
  }

  @Override
  public void getCustomerParcelHistory(
      CustomerParcelReferenceRequest request, StreamObserver<ParcelHistoryResponse> responseObserver) {
    if (customerReadService == null) {
      responseObserver.onError(Status.UNIMPLEMENTED.withDescription("Customer read service unavailable").asRuntimeException());
      return;
    }
    String subject = nullToEmpty(request.getKeycloakSubject()).trim();
    if (subject.isEmpty()) {
      responseObserver.onError(Status.INVALID_ARGUMENT.withDescription("keycloak_subject is required").asRuntimeException());
      return;
    }
    try {
      List<ParcelStatusRepository.StatusHistoryRow> rows = customerReadService.history(subject, request.getReference());
      ParcelHistoryResponse.Builder builder = ParcelHistoryResponse.newBuilder();
      for (ParcelStatusRepository.StatusHistoryRow row : rows) {
        builder.addEntries(StatusHistoryEntryProto.newBuilder()
            .setId(row.id().toString())
            .setStatus(row.toStatus() != null ? row.toStatus() : "")
            .setReason(nullToEmpty(row.reason()))
            .setNote(nullToEmpty(row.note()))
            .setActorRole(nullToEmpty(row.actorRole()))
            .setChangedAt(row.occurredAt() != null ? row.occurredAt().toString() : "")
            .build());
      }
      responseObserver.onNext(builder.build());
      responseObserver.onCompleted();
    } catch (ResponseStatusException ex) {
      responseObserver.onError(mapResponseException(ex).asRuntimeException());
    }
  }

  @Override
  @org.springframework.transaction.annotation.Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
  public void getPublicTracking(
      PublicTrackingRequest request, StreamObserver<PublicTrackingResponse> responseObserver) {
    String reference = nullToEmpty(request.getReference()).trim();
    if (!reference.matches("[A-Za-z0-9_-]{6,32}")) {
      responseObserver.onError(Status.INVALID_ARGUMENT.withDescription("Invalid tracking reference").asRuntimeException());
      return;
    }
    ParcelBooking p = bookingRepository.findPublicReference(reference).orElse(null);
    if (p == null) {
      responseObserver.onError(Status.NOT_FOUND.withDescription("Parcel not found").asRuntimeException());
      return;
    }
    var builder = PublicTrackingResponse.newBuilder()
        .setTrackingRef(nullToEmpty(p.trackingRef()))
        .setStatus(p.status() != null ? p.status().name() : "")
        .setCategory(nullToEmpty(p.category()))
        .setSize(nullToEmpty(p.size()))
        .setDescription(nullToEmpty(p.description()))
        .setOriginCity(p.sender() != null ? nullToEmpty(p.sender().city()) : "")
        .setDestinationCity(p.recipient() != null ? nullToEmpty(p.recipient().city()) : "")
        .setCreatedAt(p.createdAt() != null ? p.createdAt().toString() : "")
        .setEstimatedDays(p.estimatedDays());
    if (statusHistory != null) {
      for (var row : statusHistory.findHistoryByParcelId(p.id())) {
        builder.addEvents(StatusHistoryEntryProto.newBuilder()
            .setId(row.id().toString())
            .setStatus(row.toStatus())
            .setActorRole(nullToEmpty(row.actorRole()))
            .setChangedAt(row.occurredAt().toString())
            // Internal notes are never part of public tracking.
            .setReason("FAILED".equals(row.toStatus()) ? nullToEmpty(row.reason()) : ""));
      }
    }
    responseObserver.onNext(builder.build());
    responseObserver.onCompleted();
  }

  @Override
  public void estimateDeliveryFee(
      EstimateDeliveryFeeRequest request, StreamObserver<EstimateDeliveryFeeResponse> responseObserver) {
    if (pricingService == null) {
      responseObserver.onError(Status.UNIMPLEMENTED.withDescription("Pricing service unavailable").asRuntimeException());
      return;
    }
    try {
      BigDecimal weight = new BigDecimal(request.getWeightKg().trim());
      DestinationType destinationType = DestinationType.valueOf(request.getDestinationType().trim());
      FeeEstimateRequest dto = new FeeEstimateRequest(
          blankToNull(request.getPickupCity()),
          blankToNull(request.getDeliveryCity()),
          destinationType,
          null,
          weight,
          blankToNull(request.getParcelCategory()),
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null);
      FeeEstimateResponse res = pricingService.estimateFee(dto);
      FeeBreakdownProto.Builder breakdown = FeeBreakdownProto.newBuilder();
      if (res.breakdown() != null) {
        breakdown
            .setBaseFee(decimalString(res.breakdown().baseFee()))
            .setWeightFee(decimalString(res.breakdown().weightFee()))
            .setDistanceSurcharge(decimalString(res.breakdown().distanceSurcharge()))
            .setExpressFee(decimalString(res.breakdown().expressFee()));
      }
      responseObserver.onNext(EstimateDeliveryFeeResponse.newBuilder()
          .setFee(decimalString(res.fee()))
          .setFormattedFee(nullToEmpty(res.formattedFee()))
          .setCurrency(nullToEmpty(res.currency()))
          .setEstimatedDays(res.estimatedDays())
          .setBreakdown(breakdown.build())
          .build());
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      responseObserver.onError(Status.INVALID_ARGUMENT.withDescription(ex.getMessage()).asRuntimeException());
    } catch (RuntimeException ex) {
      responseObserver.onError(Status.UNAVAILABLE.withDescription("Pricing service unavailable").asRuntimeException());
    }
  }

  private static CustomerParcelViewResponse toParcelViewProto(CustomerParcelView view) {
    CustomerParcelViewResponse.Builder builder = CustomerParcelViewResponse.newBuilder()
        .setId(view.id() != null ? view.id().toString() : "")
        .setTrackingRef(nullToEmpty(view.trackingRef()))
        .setStatus(nullToEmpty(view.status()))
        .setFee(decimalString(view.fee()))
        .setCurrency(nullToEmpty(view.currency()))
        .setEstimatedDays(view.estimatedDays())
        .setCreatedAt(view.createdAt() != null ? view.createdAt().toString() : "")
        .setDestinationType(nullToEmpty(view.destinationType()))
        .setIsExpress(view.isExpress());
    if (view.sender() != null) {
      builder.setSender(toContactProto(view.sender()));
    }
    if (view.recipient() != null) {
      builder.setRecipient(toContactProto(view.recipient()));
    }
    if (view.parcel() != null) {
      builder.setParcel(ParcelDetailsProto.newBuilder()
          .setWeightKg(decimalString(view.parcel().weightKg()))
          .setCategory(nullToEmpty(view.parcel().category()))
          .setSize(nullToEmpty(view.parcel().size()))
          .setDescription(nullToEmpty(view.parcel().description()))
          .build());
    }
    return builder.build();
  }

  private static ParcelContactProto toContactProto(ParcelBookingResponse.Contact contact) {
    return ParcelContactProto.newBuilder()
        .setName(nullToEmpty(contact.name()))
        .setPhone(nullToEmpty(contact.phone()))
        .setAddress(nullToEmpty(contact.address()))
        .setCity(nullToEmpty(contact.city()))
        .build();
  }

  private static void addCurrencyAmounts(Object raw, java.util.function.Consumer<CurrencyAmountProto> consumer) {
    if (!(raw instanceof List<?> list)) return;
    for (Object item : list) {
      if (item instanceof Map<?, ?> map) {
        consumer.accept(CurrencyAmountProto.newBuilder()
            .setCurrency(nullToEmpty(map.get("currency")))
            .setAmount(nullToEmpty(map.get("amount")))
            .build());
      }
    }
  }

  private static Status mapResponseException(ResponseStatusException ex) {
    int code = ex.getStatusCode().value();
    if (code == 404) return Status.NOT_FOUND.withDescription(ex.getReason());
    if (code == 403) return Status.PERMISSION_DENIED.withDescription(ex.getReason());
    if (code >= 400 && code < 500) return Status.INVALID_ARGUMENT.withDescription(ex.getReason());
    return Status.INTERNAL.withDescription(ex.getReason());
  }

  private static long asLong(Object value) {
    return value instanceof Number n ? n.longValue() : 0L;
  }

  private static String decimalString(BigDecimal value) {
    return value != null ? value.toPlainString() : "";
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private static String nullToEmpty(Object value) {
    return value != null ? value.toString() : "";
  }
}
