package com.zentro.parcelpricing.domain;

/** Lifecycle states stored in parcel.parcel.status. A new booking always starts as CREATED. */
public enum ParcelStatus {
  CREATED,
  PICKED_UP,
  IN_TRANSIT,
  OUT_FOR_DELIVERY,
  DELIVERED,
  FAILED
}
