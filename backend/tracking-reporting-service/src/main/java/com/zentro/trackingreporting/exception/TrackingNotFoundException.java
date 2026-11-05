package com.zentro.trackingreporting.exception;

public class TrackingNotFoundException extends RuntimeException {
  private final String trackingRef;

  public TrackingNotFoundException(String trackingRef) {
    super("No tracking record found for reference: " + trackingRef);
    this.trackingRef = trackingRef;
  }

  public String getTrackingRef() {
    return trackingRef;
  }
}

