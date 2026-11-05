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

