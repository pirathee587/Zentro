package com.zentro.parcelpricing.error;

import com.zentro.parcelpricing.domain.ParcelLifecycle;
import com.zentro.parcelpricing.domain.ParcelStatus;
import java.util.Set;

/** A requested status change that the lifecycle does not allow. Mapped to 409. */
public class InvalidTransitionException extends RuntimeException {

  public enum Code {
    /** The parcel is DELIVERED or FAILED and accepts no further changes. */
    PARCEL_TERMINAL,
    /** The requested status is not one of the allowed next statuses. */
    INVALID_TRANSITION
  }

  private final Code code;
  private final ParcelStatus currentStatus;
  private final ParcelStatus requestedStatus;
  private final Set<ParcelStatus> allowedNext;

  private InvalidTransitionException(Code code, ParcelStatus currentStatus, ParcelStatus requestedStatus,
      String message) {
    super(message);
    this.code = code;
    this.currentStatus = currentStatus;
    this.requestedStatus = requestedStatus;
    this.allowedNext = ParcelLifecycle.allowedNext(currentStatus);
  }

  public static InvalidTransitionException terminal(ParcelStatus current, ParcelStatus requested) {
    return new InvalidTransitionException(Code.PARCEL_TERMINAL, current, requested,
        "The parcel is " + current + " and its status can no longer change.");
  }

  public static InvalidTransitionException notAllowed(ParcelStatus current, ParcelStatus requested) {
    return new InvalidTransitionException(Code.INVALID_TRANSITION, current, requested,
        "A parcel cannot move from " + current + " to " + requested + ".");
  }

  public Code code() {
    return code;
  }

  public ParcelStatus currentStatus() {
    return currentStatus;
  }

  public ParcelStatus requestedStatus() {
    return requestedStatus;
  }

  public Set<ParcelStatus> allowedNext() {
    return allowedNext;
  }
}
