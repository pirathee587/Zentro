package com.zentro.parcelpricing.exception;

import java.util.UUID;

/** No parcel exists with the given id. Mapped to 404 with code PARCEL_NOT_FOUND. */
public class ParcelNotFoundException extends RuntimeException {
  private final UUID parcelId;

  public ParcelNotFoundException(UUID parcelId) {
    super("No parcel exists with id " + parcelId + ".");
    this.parcelId = parcelId;
  }

  public UUID parcelId() {
    return parcelId;
  }
}
