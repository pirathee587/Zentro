package com.zentro.trackingreporting.tracking.entity;

public enum TrackingStatus {
  CREATED("Created", "Parcel Booked", "Delivery order created and registered in system"),
  PICKED_UP("In progress", "Picked Up", "Courier collected parcel from sender"),
  IN_TRANSIT("In progress", "In Transit", "Processed through sorting facility"),
  OUT_FOR_DELIVERY("In progress", "Out for Delivery", "Parcel is with courier for final delivery"),
  DELIVERED("Delivered", "Delivered", "Parcel successfully delivered to recipient"),
  FAILED("Failed", "Delivery Failed", "Delivery could not be completed");

  private final String portalStatus;
  private final String defaultTitle;
  private final String defaultDescription;

  TrackingStatus(String portalStatus, String defaultTitle, String defaultDescription) {
    this.portalStatus = portalStatus;
    this.defaultTitle = defaultTitle;
    this.defaultDescription = defaultDescription;
  }

  public String portalStatus() {
    return portalStatus;
  }

  public String defaultTitle() {
    return defaultTitle;
  }

  public String defaultDescription() {
    return defaultDescription;
  }

  public static TrackingStatus fromString(String value) {
    if (value == null || value.isBlank()) {
      return CREATED;
    }
    String normalized = value.trim().toUpperCase().replace("-", "_").replace(" ", "_");
    try {
      return TrackingStatus.valueOf(normalized);
    } catch (IllegalArgumentException e) {
      if (normalized.contains("DELIVER")) return DELIVERED;
      if (normalized.contains("FAIL") || normalized.contains("CANCEL")) return FAILED;
      if (normalized.contains("TRANSIT") || normalized.contains("PICK")) return IN_TRANSIT;
      return CREATED;
    }
  }
}

