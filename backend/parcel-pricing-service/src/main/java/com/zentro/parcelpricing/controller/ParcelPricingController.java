package com.zentro.parcelpricing.controller;

import com.zentro.parcelpricing.domain.DestinationType;
import com.zentro.parcelpricing.dto.FeeEstimateRequest;
import com.zentro.parcelpricing.dto.FeeEstimateResponse;
import com.zentro.parcelpricing.error.PricingUnavailableException;
import com.zentro.parcelpricing.error.PricingValidationException;
import com.zentro.parcelpricing.service.PricingService;
import com.zentro.parcelpricing.service.ZoneService;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only price quotation endpoint exposing the single-source-of-truth pricing calculation.
 * Never persists bookings, events, or logs personal data.
 */
@RestController
@RequestMapping("/api/v1/parcels")
@PreAuthorize("hasAnyRole('CUSTOMER', 'DISPATCHER')")
public class ParcelPricingController {
  private static final BigDecimal VOLUMETRIC_DIVISOR = new BigDecimal("5000");
  private static final BigDecimal MAX_WEIGHT_KG = new BigDecimal("100.00");

  private final PricingService pricingService;
  private final ZoneService zoneService;

  public ParcelPricingController(PricingService pricingService, ZoneService zoneService) {
    this.pricingService = pricingService;
    this.zoneService = zoneService;
  }

  @PostMapping(
      path = {"/estimate", "/estimate-fee"},
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public FeeEstimateResponse estimate(@Valid @RequestBody FeeEstimateRequest request) {
    validateRequest(request);

    FeeEstimateResponse response;
    try {
      response = pricingService.estimateFee(request);
    } catch (IllegalArgumentException ex) {
      String message = ex.getMessage() != null ? ex.getMessage() : "Invalid pricing request.";
      String field = resolveFieldForIllegalArgument(message);
      throw new PricingValidationException(message, Map.of(field, message));
    } catch (PricingUnavailableException ex) {
      throw ex;
    } catch (RuntimeException ex) {
      throw new PricingUnavailableException("Pricing service is temporarily unavailable. Please try again later.", ex);
    }

    if (!isValidQuote(response)) {
      throw new PricingUnavailableException("Pricing service is temporarily unavailable. Please try again later.");
    }
    return response;
  }

  private void validateRequest(FeeEstimateRequest request) {
    Map<String, String> fieldErrors = new LinkedHashMap<>();

    boolean hasAnyDimension = request.lengthCm() != null || request.widthCm() != null || request.heightCm() != null;
    if (request.weightKg() == null && request.parcelSize() == null && !hasAnyDimension) {
      fieldErrors.put("weightKg", "Either weightKg, dimensions, or parcelSize must be provided.");
    }

    if (hasAnyDimension) {
      if (request.lengthCm() == null) {
        fieldErrors.put("lengthCm", "When specifying dimensions, length, width, and height must all be provided.");
      }
      if (request.widthCm() == null) {
        fieldErrors.put("widthCm", "When specifying dimensions, length, width, and height must all be provided.");
      }
      if (request.heightCm() == null) {
        fieldErrors.put("heightCm", "When specifying dimensions, length, width, and height must all be provided.");
      }
      if (request.hasDimensions()
          && request.lengthCm().compareTo(BigDecimal.ZERO) > 0
          && request.widthCm().compareTo(BigDecimal.ZERO) > 0
          && request.heightCm().compareTo(BigDecimal.ZERO) > 0) {
        BigDecimal volumetricWeight = request.lengthCm()
            .multiply(request.widthCm())
            .multiply(request.heightCm())
            .divide(VOLUMETRIC_DIVISOR, 4, RoundingMode.HALF_UP);
        if (volumetricWeight.compareTo(MAX_WEIGHT_KG) > 0) {
          fieldErrors.put("weightKg", "Volumetric weight cannot exceed 100 kg.");
        }
      }
    }

    boolean originProvided = request.originZone() != null;
    boolean destinationProvided = request.destinationZone() != null;
    boolean originValid = false;
    boolean destinationValid = false;

    try {
      if (originProvided) {
        if (request.originZone().isBlank()) {
          fieldErrors.put("originZone", "Origin zone cannot be blank.");
        } else if (!zoneService.isValidZone(request.originZone())) {
          fieldErrors.put("originZone", "Unsupported origin zone: " + request.originZone().trim());
        } else {
          originValid = true;
        }
      }
      if (destinationProvided) {
        if (request.destinationZone().isBlank()) {
          fieldErrors.put("destinationZone", "Destination zone cannot be blank.");
        } else if (!zoneService.isValidZone(request.destinationZone())) {
          fieldErrors.put("destinationZone", "Unsupported destination zone: " + request.destinationZone().trim());
        } else {
          destinationValid = true;
        }
      }
    } catch (PricingUnavailableException ex) {
      throw ex;
    } catch (DataAccessException ex) {
      throw new PricingUnavailableException(
          "Delivery zone configuration is temporarily unavailable. Please try again later.", ex);
    }

    if (originValid && !destinationProvided) {
      fieldErrors.put("destinationZone", "Destination zone is required when origin zone is provided.");
    }
    if (destinationValid && !originProvided) {
      fieldErrors.put("originZone", "Origin zone is required when destination zone is provided.");
    }

    if (originValid && destinationValid
        && request.destinationType() == DestinationType.SAME_CITY
        && !request.originZone().trim().equalsIgnoreCase(request.destinationZone().trim())) {
      fieldErrors.put("destinationType",
          "Unsupported route and zone combination: SAME_CITY cannot span different delivery zones.");
    }

    if (request.currency() != null && request.currency().isBlank()) {
      fieldErrors.put("currency", "Currency cannot be blank.");
    }

    if (!fieldErrors.isEmpty()) {
      String firstMessage = fieldErrors.values().iterator().next();
      throw new PricingValidationException(firstMessage, fieldErrors);
    }
  }

  private static String resolveFieldForIllegalArgument(String message) {
    String lower = message.toLowerCase();
    if (lower.contains("currency")) {
      return "currency";
    }
    if (lower.contains("dimension") || lower.contains("length") || lower.contains("width") || lower.contains("height")) {
      return "lengthCm";
    }
    return "weightKg";
  }

  private static boolean isValidQuote(FeeEstimateResponse response) {
    if (response == null
        || response.fee() == null
        || response.fee().compareTo(BigDecimal.ZERO) <= 0
        || response.currency() == null
        || response.currency().isBlank()
        || response.formattedFee() == null
        || response.formattedFee().isBlank()
        || response.estimatedDays() <= 0
        || response.breakdown() == null) {
      return false;
    }
    FeeEstimateResponse.Breakdown breakdown = response.breakdown();
    if (breakdown.baseFee() == null || breakdown.baseFee().compareTo(BigDecimal.ZERO) <= 0
        || breakdown.weightFee() == null || breakdown.weightFee().compareTo(BigDecimal.ZERO) < 0) {
      return false;
    }
    BigDecimal distance = breakdown.distanceSurcharge() != null ? breakdown.distanceSurcharge() : BigDecimal.ZERO;
    BigDecimal express = breakdown.expressFee() != null ? breakdown.expressFee() : BigDecimal.ZERO;
    if (distance.compareTo(BigDecimal.ZERO) < 0 || express.compareTo(BigDecimal.ZERO) < 0) {
      return false;
    }
    BigDecimal expectedTotal = breakdown.baseFee()
        .add(breakdown.weightFee())
        .add(distance)
        .add(express)
        .setScale(2, RoundingMode.HALF_UP);
    return response.fee().setScale(2, RoundingMode.HALF_UP).compareTo(expectedTotal) == 0;
  }
}
