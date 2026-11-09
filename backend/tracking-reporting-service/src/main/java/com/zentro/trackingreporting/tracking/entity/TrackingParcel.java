package com.zentro.trackingreporting.tracking.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "tracking_parcel", schema = "tracking")
public class TrackingParcel {

  @Id
  @GeneratedValue(strategy = GenerationType.AUTO)
  private UUID id;

  @Column(name = "parcel_id")
  private UUID parcelId;

  @Column(name = "tracking_ref", nullable = false, unique = true, length = 32)
  private String trackingRef;

  @Column(name = "customer_subject", nullable = false)
  private String customerSubject;

  @Column(name = "merchant_subject")
  private String merchantSubject;

  @Column(name = "courier_subject")
  private String courierSubject;

  @Column(name = "sender_name", nullable = false, length = 100)
  private String senderName;

  @Column(name = "sender_phone", nullable = false, length = 20)
  private String senderPhone;

  @Column(name = "sender_address", nullable = false, length = 255)
  private String senderAddress;

  @Column(name = "sender_city", length = 100)
  private String senderCity;

  @Column(name = "recipient_name", nullable = false, length = 100)
  private String recipientName;

  @Column(name = "recipient_phone", nullable = false, length = 20)
  private String recipientPhone;

  @Column(name = "recipient_address", nullable = false, length = 255)
  private String recipientAddress;

  @Column(name = "recipient_city", length = 100)
  private String recipientCity;

  @Column(name = "weight_kg", nullable = false, precision = 6, scale = 2)
  private BigDecimal weightKg;

  @Column(name = "size", length = 16)
  private String size;

  @Column(name = "category", nullable = false, length = 40)
  private String category;

  @Column(name = "description", length = 255)
  private String description;

  @Column(name = "destination_type", length = 32)
  private String destinationType;

  @Column(name = "is_express", nullable = false)
  private boolean isExpress;

  @Column(name = "fee", nullable = false, precision = 12, scale = 2)
  private BigDecimal fee;

  @Column(name = "currency", nullable = false, length = 3)
  private String currency;

  @Column(name = "estimated_days", nullable = false)
  private int estimatedDays;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 32)
  private TrackingStatus status;

  @Column(name = "failure_reason", length = 200)
  private String failureReason;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "delivered_at")
  private Instant deliveredAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  public TrackingParcel() {
    this.createdAt = Instant.now();
    this.updatedAt = Instant.now();
    this.currency = "LKR";
    this.weightKg = BigDecimal.ONE;
    this.fee = BigDecimal.ZERO;
    this.category = "Standard";
    this.status = TrackingStatus.CREATED;
    this.estimatedDays = 3;
  }

  // Getters and setters
  public UUID getId() { return id; }
  public void setId(UUID id) { this.id = id; }

  public UUID getParcelId() { return parcelId; }
  public void setParcelId(UUID parcelId) { this.parcelId = parcelId; }

  public String getTrackingRef() { return trackingRef; }
  public void setTrackingRef(String trackingRef) { this.trackingRef = trackingRef; }

  public String getCustomerSubject() { return customerSubject; }
  public void setCustomerSubject(String customerSubject) { this.customerSubject = customerSubject; }

  public String getMerchantSubject() { return merchantSubject; }
  public void setMerchantSubject(String merchantSubject) { this.merchantSubject = merchantSubject; }

  public String getCourierSubject() { return courierSubject; }
  public void setCourierSubject(String courierSubject) { this.courierSubject = courierSubject; }

  public String getSenderName() { return senderName; }
  public void setSenderName(String senderName) { this.senderName = senderName; }

  public String getSenderPhone() { return senderPhone; }
  public void setSenderPhone(String senderPhone) { this.senderPhone = senderPhone; }

  public String getSenderAddress() { return senderAddress; }
  public void setSenderAddress(String senderAddress) { this.senderAddress = senderAddress; }

  public String getSenderCity() { return senderCity; }
  public void setSenderCity(String senderCity) { this.senderCity = senderCity; }

  public String getRecipientName() { return recipientName; }
  public void setRecipientName(String recipientName) { this.recipientName = recipientName; }

  public String getRecipientPhone() { return recipientPhone; }
  public void setRecipientPhone(String recipientPhone) { this.recipientPhone = recipientPhone; }

  public String getRecipientAddress() { return recipientAddress; }
  public void setRecipientAddress(String recipientAddress) { this.recipientAddress = recipientAddress; }

  public String getRecipientCity() { return recipientCity; }
  public void setRecipientCity(String recipientCity) { this.recipientCity = recipientCity; }

  public BigDecimal getWeightKg() { return weightKg; }
  public void setWeightKg(BigDecimal weightKg) { this.weightKg = weightKg; }

  public String getSize() { return size; }
  public void setSize(String size) { this.size = size; }

  public String getCategory() { return category; }
  public void setCategory(String category) { this.category = category; }

  public String getDescription() { return description; }
  public void setDescription(String description) { this.description = description; }

  public String getDestinationType() { return destinationType; }
  public void setDestinationType(String destinationType) { this.destinationType = destinationType; }

  public boolean isExpress() { return isExpress; }
  public void setExpress(boolean express) { isExpress = express; }

  public BigDecimal getFee() { return fee; }
  public void setFee(BigDecimal fee) { this.fee = fee; }

  public String getCurrency() { return currency; }
  public void setCurrency(String currency) { this.currency = currency; }

  public int getEstimatedDays() { return estimatedDays; }
  public void setEstimatedDays(int estimatedDays) { this.estimatedDays = estimatedDays; }

  public TrackingStatus getStatus() { return status; }
  public void setStatus(TrackingStatus status) {
    this.status = status;
    if (status == TrackingStatus.DELIVERED && this.deliveredAt == null) {
      this.deliveredAt = Instant.now();
    }
  }

  public String getFailureReason() { return failureReason; }
  public void setFailureReason(String failureReason) { this.failureReason = failureReason; }

  public Instant getCreatedAt() { return createdAt; }
  public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

  public Instant getDeliveredAt() { return deliveredAt; }
  public void setDeliveredAt(Instant deliveredAt) { this.deliveredAt = deliveredAt; }

  public Instant getUpdatedAt() { return updatedAt; }
  public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}

