package com.zentro.parcelpricing.service;

import com.zentro.parcelpricing.domain.DestinationType;
import com.zentro.parcelpricing.domain.ParcelBooking;
import com.zentro.parcelpricing.domain.ParcelSize;
import com.zentro.parcelpricing.domain.ParcelStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** JdbcTemplate access to parcel.parcel, the booking table owned by this service. */
@Repository
public class ParcelBookingRepository {
  static final String TRACKING_REF_CONSTRAINT = "uq_parcel_tracking_ref";
  static final String IDEMPOTENCY_CONSTRAINT = "uq_parcel_customer_idempotency";

  private static final String COLUMNS = """
      id, tracking_ref, customer_subject,
      sender_name, sender_phone, sender_address, sender_city,
      recipient_name, recipient_phone, recipient_address, recipient_city,
      weight_kg, size, category, description, destination_type, is_express, notes,
      fee, currency, estimated_days, status, idempotency_key, request_hash, created_at, updated_at
      """;

  private static final RowMapper<ParcelBooking> ROW_MAPPER = ParcelBookingRepository::mapRow;

  private final JdbcTemplate jdbc;

  public ParcelBookingRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Inserts a new booking in its own transaction and returns the stored row.
   *
   * @throws DuplicateKeyException when the tracking reference or the (customer, idempotency key) pair already exists
   */
  @Transactional
  public ParcelBooking insert(ParcelBooking booking) {
    return jdbc.queryForObject("""
        INSERT INTO parcel.parcel (
            tracking_ref, customer_subject,
            sender_name, sender_phone, sender_address, sender_city,
            recipient_name, recipient_phone, recipient_address, recipient_city,
            weight_kg, size, category, description, destination_type, is_express, notes,
            fee, currency, estimated_days, status, idempotency_key, request_hash
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        RETURNING """ + " " + COLUMNS,
        ROW_MAPPER,
        booking.trackingRef(),
        booking.customerSubject(),
        booking.sender().name(),
        booking.sender().phone(),
        booking.sender().address(),
        booking.sender().city(),
        booking.recipient().name(),
        booking.recipient().phone(),
        booking.recipient().address(),
        booking.recipient().city(),
        booking.weightKg(),
        booking.size() == null ? null : booking.size().name(),
        booking.category(),
        booking.description(),
        booking.destinationType() == null ? null : booking.destinationType().name(),
        booking.express(),
        booking.notes(),
        booking.fee(),
        booking.currency(),
        booking.estimatedDays(),
        booking.status().name(),
        booking.idempotencyKey(),
        booking.requestHash());
  }

  public Optional<ParcelBooking> findByCustomerAndIdempotencyKey(String customerSubject, String idempotencyKey) {
    List<ParcelBooking> rows = jdbc.query(
        "SELECT " + COLUMNS + " FROM parcel.parcel WHERE customer_subject = ? AND idempotency_key = ?",
        ROW_MAPPER, customerSubject, idempotencyKey);
    return rows.stream().findFirst();
  }

  /** True when the duplicate key came from the tracking reference constraint (a random collision). */
  public static boolean isTrackingRefCollision(DuplicateKeyException ex) {
    return violates(ex, TRACKING_REF_CONSTRAINT);
  }

  /** True when the duplicate key came from the per-customer idempotency key constraint. */
  public static boolean isIdempotencyKeyDuplicate(DuplicateKeyException ex) {
    return violates(ex, IDEMPOTENCY_CONSTRAINT);
  }

  private static boolean violates(Throwable ex, String constraint) {
    for (Throwable t = ex; t != null; t = t.getCause()) {
      if (t.getMessage() != null && t.getMessage().contains(constraint)) {
        return true;
      }
      if (t.getCause() == t) {
        break;
      }
    }
    return false;
  }

  private static ParcelBooking mapRow(ResultSet rs, int rowNum) throws SQLException {
    String size = rs.getString("size");
    String destinationType = rs.getString("destination_type");
    return new ParcelBooking(
        rs.getObject("id", UUID.class),
        rs.getString("tracking_ref"),
        rs.getString("customer_subject"),
        new ParcelBooking.Contact(
            rs.getString("sender_name"),
            rs.getString("sender_phone"),
            rs.getString("sender_address"),
            rs.getString("sender_city")),
        new ParcelBooking.Contact(
            rs.getString("recipient_name"),
            rs.getString("recipient_phone"),
            rs.getString("recipient_address"),
            rs.getString("recipient_city")),
        rs.getBigDecimal("weight_kg"),
        size == null ? null : ParcelSize.valueOf(size),
        rs.getString("category"),
        rs.getString("description"),
        destinationType == null ? null : DestinationType.valueOf(destinationType),
        rs.getBoolean("is_express"),
        rs.getString("notes"),
        rs.getBigDecimal("fee"),
        rs.getString("currency"),
        rs.getInt("estimated_days"),
        ParcelStatus.valueOf(rs.getString("status")),
        rs.getString("idempotency_key"),
        rs.getString("request_hash"),
        rs.getObject("created_at", OffsetDateTime.class),
        rs.getObject("updated_at", OffsetDateTime.class));
  }
}
