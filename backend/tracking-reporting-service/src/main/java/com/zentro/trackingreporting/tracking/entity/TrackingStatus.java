package com.zentro.trackingreporting.tracking.entity;

public enum TrackingStatus {
  CREATED("Created", "Parcel Booked"),
  PICKED_UP("In progress", "Picked Up"),
  IN_TRANSIT("In progress", "In Transit"),
  OUT_FOR_DELIVERY("In progress", "Out for Delivery"),
  DELIVERED("Delivered", "Delivered"),
  FAILED("Failed", "Delivery Failed");

  private final String portalStatus;
  private final String defaultTitle;

  TrackingStatus(String portalStatus, String defaultTitle) {
    this.portalStatus = portalStatus;
    this.defaultTitle = defaultTitle;
  }

  public String portalStatus() {
    return portalStatus;
  }

  public String defaultTitle() {
    return defaultTitle;
  }

  public static TrackingStatus fromString(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    String normalized = value.trim().toUpperCase().replace("-", "_").replace(" ", "_");
    try {
      return TrackingStatus.valueOf(normalized);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }
}

