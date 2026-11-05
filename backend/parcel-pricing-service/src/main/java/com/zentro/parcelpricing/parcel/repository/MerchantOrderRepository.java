package com.zentro.parcelpricing.parcel.repository;

import com.zentro.parcelpricing.parcel.entity.DestinationType;
import com.zentro.parcelpricing.parcel.entity.MerchantOrder;
import com.zentro.parcelpricing.parcel.entity.ParcelSize;
import com.zentro.parcelpricing.parcel.entity.ParcelStatus;
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

/**
 * JdbcTemplate access to parcel.parcel for merchant orders.
 */
@Repository
public class MerchantOrderRepository {
  public static final String TRACKING_REF_CONSTRAINT = "uq_parcel_tracking_ref";
  static final String MERCHANT_EXTERNAL_REF_CONSTRAINT = "uq_parcel_merchant_external_ref";

  private static final String COLUMNS = """
      id, tracking_ref, merchant_id, external_order_ref,
      sender_name, sender_phone, sender_address, sender_city,
      recipient_name, recipient_phone, recipient_address, recipient_city,
      weight_kg, size, category, description, destination_type, is_express, notes,
      fee, currency, estimated_days, status, request_hash, created_at, updated_at
      """;

  private static final RowMapper<MerchantOrder> ROW_MAPPER = MerchantOrderRepository::mapRow;

  private final JdbcTemplate jdbc;

  public MerchantOrderRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Transactional
  public MerchantOrder insert(MerchantOrder order) {
    return jdbc.queryForObject("""
        INSERT INTO parcel.parcel (
            tracking_ref, customer_subject, merchant_id, external_order_ref,
            sender_name, sender_phone, sender_address, sender_city,
            recipient_name, recipient_phone, recipient_address, recipient_city,
            weight_kg, size, category, description, destination_type, is_express, notes,
            fee, currency, estimated_days, status, request_hash
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        RETURNING """ + " " + COLUMNS,
        ROW_MAPPER,
        order.trackingRef(),
        order.merchantId(), // customer_subject is NOT NULL, populated with merchant ID
        order.merchantId(),
        order.externalOrderRef(),
        order.sender().name(),
        order.sender().phone(),
        order.sender().address(),
        order.sender().city(),
        order.recipient().name(),
        order.recipient().phone(),
        order.recipient().address(),
        order.recipient().city(),
        order.weightKg(),
        order.size() == null ? null : order.size().name(),
        order.category(),
        order.description(),
        order.destinationType() == null ? null : order.destinationType().name(),
        order.express(),
        order.notes(),
        order.fee(),
        order.currency(),
        order.estimatedDays(),
        order.status().name(),
        order.requestHash());
  }

  public Optional<MerchantOrder> findByMerchantAndExternalRef(String merchantId, String externalOrderRef) {
    List<MerchantOrder> rows = jdbc.query(
        "SELECT " + COLUMNS + " FROM parcel.parcel WHERE merchant_id = ? AND external_order_ref = ?",
        ROW_MAPPER, merchantId, externalOrderRef);
    return rows.stream().findFirst();
  }

  public Optional<MerchantOrder> findByIdAndMerchant(UUID id, String merchantId) {
    List<MerchantOrder> rows = jdbc.query(
        "SELECT " + COLUMNS + " FROM parcel.parcel WHERE id = ? AND merchant_id = ?",
        ROW_MAPPER, id, merchantId);
    return rows.stream().findFirst();
  }

  public List<MerchantOrder> listByMerchant(String merchantId, int limit) {
    return jdbc.query(
        "SELECT " + COLUMNS + " FROM parcel.parcel WHERE merchant_id = ? ORDER BY created_at DESC LIMIT ?",
        ROW_MAPPER, merchantId, limit);
  }

  public static boolean isTrackingRefCollision(DuplicateKeyException ex) {
    return violates(ex, TRACKING_REF_CONSTRAINT);
  }

  public static boolean isMerchantExternalRefDuplicate(DuplicateKeyException ex) {
    return violates(ex, MERCHANT_EXTERNAL_REF_CONSTRAINT);
  }

  private static boolean violates(Throwable ex, String constraint) {
    Throwable current = ex;
    while (current != null) {
      String message = current.getMessage();
      if (message != null && message.contains(constraint)) {
        return true;
      }
      current = current.getCause();
    }
    return false;
  }

  private static MerchantOrder mapRow(ResultSet rs, int rowNum) throws SQLException {
    String sizeValue = rs.getString("size");
    String destinationTypeValue = rs.getString("destination_type");
    return new MerchantOrder(
        rs.getObject("id", UUID.class),
        rs.getString("tracking_ref"),
        rs.getString("merchant_id"),
        rs.getString("external_order_ref"),
        new MerchantOrder.Contact(
            rs.getString("sender_name"),
            rs.getString("sender_phone"),
            rs.getString("sender_address"),
            rs.getString("sender_city")),
        new MerchantOrder.Contact(
            rs.getString("recipient_name"),
            rs.getString("recipient_phone"),
            rs.getString("recipient_address"),
            rs.getString("recipient_city")),
        rs.getBigDecimal("weight_kg"),
        sizeValue == null ? null : ParcelSize.valueOf(sizeValue),
        rs.getString("category"),
        rs.getString("description"),
        destinationTypeValue == null ? null : DestinationType.valueOf(destinationTypeValue),
        rs.getBoolean("is_express"),
        rs.getString("notes"),
        rs.getBigDecimal("fee"),
        rs.getString("currency"),
        rs.getInt("estimated_days"),
        ParcelStatus.valueOf(rs.getString("status")),
        rs.getString("request_hash"),
        rs.getObject("created_at", OffsetDateTime.class),
        rs.getObject("updated_at", OffsetDateTime.class));
  }
}
