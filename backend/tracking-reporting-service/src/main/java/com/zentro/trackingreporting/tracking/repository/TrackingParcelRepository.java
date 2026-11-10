package com.zentro.trackingreporting.tracking.repository;

import com.zentro.trackingreporting.tracking.entity.TrackingParcel;
import com.zentro.trackingreporting.tracking.entity.TrackingStatus;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface TrackingParcelRepository extends JpaRepository<TrackingParcel, UUID> {

  Optional<TrackingParcel> findByTrackingRef(String trackingRef);

  Optional<TrackingParcel> findByParcelId(UUID parcelId);

  Page<TrackingParcel> findByCustomerSubject(String customerSubject, Pageable pageable);

  Page<TrackingParcel> findByCustomerSubjectAndStatus(String customerSubject, TrackingStatus status, Pageable pageable);

  Page<TrackingParcel> findByMerchantSubject(String merchantSubject, Pageable pageable);

  Page<TrackingParcel> findByMerchantSubjectAndStatus(String merchantSubject, TrackingStatus status, Pageable pageable);

  Page<TrackingParcel> findByCourierSubject(String courierSubject, Pageable pageable);

  Page<TrackingParcel> findByCourierSubjectAndStatus(String courierSubject, TrackingStatus status, Pageable pageable);

  @Query("SELECT p FROM TrackingParcel p WHERE (p.customerSubject = :subject OR p.merchantSubject = :subject) ")
  Page<TrackingParcel> findByCustomerOrMerchantSubject(@Param("subject") String subject, Pageable pageable);

  @Query("SELECT p FROM TrackingParcel p WHERE (p.customerSubject = :subject OR p.merchantSubject = :subject) AND p.status = :status")
  Page<TrackingParcel> findByCustomerOrMerchantSubjectAndStatus(@Param("subject") String subject, @Param("status") TrackingStatus status, Pageable pageable);

  @Query("SELECT p FROM TrackingParcel p WHERE (p.customerSubject = :subject OR p.merchantSubject = :subject) " +
         "AND (LOWER(p.trackingRef) LIKE LOWER(CONCAT('%', :query, '%')) " +
         "OR LOWER(p.recipientName) LIKE LOWER(CONCAT('%', :query, '%')) " +
         "OR LOWER(p.recipientCity) LIKE LOWER(CONCAT('%', :query, '%')) " +
         "OR LOWER(p.senderCity) LIKE LOWER(CONCAT('%', :query, '%')))")
  Page<TrackingParcel> searchByCustomerOrMerchant(
      @Param("subject") String subject,
      @Param("query") String query,
      Pageable pageable);

  @Query("SELECT p FROM TrackingParcel p WHERE (p.customerSubject = :subject OR p.merchantSubject = :subject) " +
         "AND p.status = :status " +
         "AND (LOWER(p.trackingRef) LIKE LOWER(CONCAT('%', :query, '%')) " +
         "OR LOWER(p.recipientName) LIKE LOWER(CONCAT('%', :query, '%')) " +
         "OR LOWER(p.recipientCity) LIKE LOWER(CONCAT('%', :query, '%')) " +
         "OR LOWER(p.senderCity) LIKE LOWER(CONCAT('%', :query, '%')))")
  Page<TrackingParcel> searchByCustomerOrMerchantAndStatus(
      @Param("subject") String subject,
      @Param("status") TrackingStatus status,
      @Param("query") String query,
      Pageable pageable);

  @Query("SELECT p FROM TrackingParcel p WHERE p.customerSubject = :customerSubject " +
         "AND (LOWER(p.trackingRef) LIKE LOWER(CONCAT('%', :query, '%')) " +
         "OR LOWER(p.recipientName) LIKE LOWER(CONCAT('%', :query, '%')) " +
         "OR LOWER(p.recipientCity) LIKE LOWER(CONCAT('%', :query, '%')) " +
         "OR LOWER(p.senderCity) LIKE LOWER(CONCAT('%', :query, '%')))")
  Page<TrackingParcel> searchByCustomer(
      @Param("customerSubject") String customerSubject,
      @Param("query") String query,
      Pageable pageable);

  @Query("SELECT p FROM TrackingParcel p WHERE p.customerSubject = :customerSubject " +
         "AND p.status = :status " +
         "AND (LOWER(p.trackingRef) LIKE LOWER(CONCAT('%', :query, '%')) " +
         "OR LOWER(p.recipientName) LIKE LOWER(CONCAT('%', :query, '%')) " +
         "OR LOWER(p.recipientCity) LIKE LOWER(CONCAT('%', :query, '%')) " +
         "OR LOWER(p.senderCity) LIKE LOWER(CONCAT('%', :query, '%')))")
  Page<TrackingParcel> searchByCustomerAndStatus(
      @Param("customerSubject") String customerSubject,
      @Param("status") TrackingStatus status,
      @Param("query") String query,
      Pageable pageable);
}

