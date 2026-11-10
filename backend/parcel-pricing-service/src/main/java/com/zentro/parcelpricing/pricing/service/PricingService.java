package com.zentro.parcelpricing.pricing.service;

import com.zentro.parcelpricing.pricing.rules.PricingCalculationEngine;
import com.zentro.parcelpricing.pricing.rules.PricingRule;
import com.zentro.parcelpricing.pricing.dto.FeeEstimateRequest;
import com.zentro.parcelpricing.pricing.dto.FeeEstimateResponse;
import com.zentro.parcelpricing.pricing.dto.PricingRuleUpdateRequest;
import com.zentro.parcelpricing.exception.PricingUnavailableException;
import com.zentro.parcelpricing.exception.PricingValidationException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PricingService {
  private static final RowMapper<PricingRule> RULE_MAPPER = PricingService::mapRule;
  private static final BigDecimal MAX_AMOUNT = new BigDecimal("1000000.00");
  private static final BigDecimal MAX_PER_KM_RATE = new BigDecimal("100000.00");
  private static final BigDecimal MAX_INCLUDED_WEIGHT_KG = new BigDecimal("100.00");

  private final JdbcTemplate jdbc;
  private final PricingCalculationEngine calculationEngine;

  public PricingService(JdbcTemplate jdbc, PricingCalculationEngine calculationEngine) {
    this.jdbc = jdbc;
    this.calculationEngine = calculationEngine;
  }

  public FeeEstimateResponse estimateFee(FeeEstimateRequest request) {
    PricingRule activeRule = getActiveRule();
    if (!isRuleUsable(activeRule)) {
      throw new PricingUnavailableException("Active pricing rule configuration is unavailable or incomplete.");
    }
    return calculationEngine.calculate(request, activeRule);
  }

  private static boolean isRuleUsable(PricingRule rule) {
    return rule != null
        && rule.active()
        && rule.baseFee() != null && rule.baseFee().compareTo(BigDecimal.ZERO) > 0
        && rule.includedWeightKg() != null && rule.includedWeightKg().compareTo(BigDecimal.ZERO) > 0
        && rule.additionalWeightRatePerKg() != null && rule.additionalWeightRatePerKg().compareTo(BigDecimal.ZERO) >= 0
        && rule.distanceSurchargeOtherDistrict() != null && rule.distanceSurchargeOtherDistrict().compareTo(BigDecimal.ZERO) >= 0
        && rule.expressSurcharge() != null && rule.expressSurcharge().compareTo(BigDecimal.ZERO) >= 0
        && rule.perKmRate() != null && rule.perKmRate().compareTo(BigDecimal.ZERO) >= 0
        && rule.currency() != null && !rule.currency().isBlank();
  }

  public PricingRule getActiveRule() {
    try {
      List<PricingRule> rules = jdbc.query("""
          SELECT id, rule_name, base_fee, included_weight_kg, additional_weight_rate_per_kg,
                 distance_surcharge_other_district, express_surcharge, per_km_rate,
                 currency, active, updated_at
            FROM pricing.pricing_rule
           WHERE active = true
           ORDER BY updated_at DESC
           LIMIT 1
          """, RULE_MAPPER);
      if (!rules.isEmpty()) {
        return rules.get(0);
      }
    } catch (DataAccessException e) {
      // Fallback to approved defaults if table is not yet initialized
    }
    return PricingRule.defaultStandard();
  }

  @Transactional
  public PricingRule updateStandardRule(PricingRuleUpdateRequest update) {
    if (update == null) {
      throw new IllegalArgumentException("Pricing rule update request cannot be null.");
    }

    validateRuleAmounts(update);

    String currency = "LKR";
    if (update.currency() != null && !update.currency().isBlank()) {
      String trimmed = update.currency().trim().toUpperCase(Locale.ROOT);
      if (!"LKR".equals(trimmed)) {
        throw new PricingValidationException(
            "Unsupported currency '" + update.currency().trim() + "'. Only 'LKR' is supported.",
            Map.of("currency", "Unsupported currency '" + update.currency().trim() + "'. Only 'LKR' is supported."));
      }
      currency = trimmed;
    }

    try {
      jdbc.update("""
          INSERT INTO pricing.pricing_rule (
              rule_name, base_fee, included_weight_kg, additional_weight_rate_per_kg,
              distance_surcharge_other_district, express_surcharge, per_km_rate, currency, active, updated_at
          ) VALUES ('STANDARD_DEFAULT', ?, ?, ?, ?, ?, ?, ?, true, now())
          ON CONFLICT (rule_name) DO UPDATE SET
              base_fee = EXCLUDED.base_fee,
              included_weight_kg = EXCLUDED.included_weight_kg,
              additional_weight_rate_per_kg = EXCLUDED.additional_weight_rate_per_kg,
              distance_surcharge_other_district = EXCLUDED.distance_surcharge_other_district,
              express_surcharge = EXCLUDED.express_surcharge,
              per_km_rate = EXCLUDED.per_km_rate,
              currency = EXCLUDED.currency,
              updated_at = now()
          """,
          update.baseFee().setScale(2, RoundingMode.HALF_UP),
          update.includedWeightKg().setScale(2, RoundingMode.HALF_UP),
          update.additionalWeightRatePerKg().setScale(2, RoundingMode.HALF_UP),
          update.distanceSurchargeOtherDistrict().setScale(2, RoundingMode.HALF_UP),
          update.expressSurcharge().setScale(2, RoundingMode.HALF_UP),
          update.perKmRate().setScale(2, RoundingMode.HALF_UP),
          currency);

      return getActiveRule();
    } catch (DataAccessException ex) {
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,
          "Failed to persist pricing rule update to database.",
          ex);
    }
  }

  private static void validateRuleAmounts(PricingRuleUpdateRequest update) {
    Map<String, String> errors = new LinkedHashMap<>();

    if (update.baseFee() == null) {
      errors.put("baseFee", "Base fee is required.");
    } else if (update.baseFee().compareTo(BigDecimal.ZERO) <= 0) {
      errors.put("baseFee", "Base fee must be greater than zero.");
    } else if (update.baseFee().compareTo(MAX_AMOUNT) > 0) {
      errors.put("baseFee", "Base fee cannot exceed 1,000,000.00.");
    } else if (hasMoreThanTwoDecimals(update.baseFee())) {
      errors.put("baseFee", "Base fee must have at most 2 decimal places.");
    }

    if (update.includedWeightKg() == null) {
      errors.put("includedWeightKg", "Included weight is required.");
    } else if (update.includedWeightKg().compareTo(BigDecimal.ZERO) <= 0) {
      errors.put("includedWeightKg", "Included weight must be greater than zero.");
    } else if (update.includedWeightKg().compareTo(MAX_INCLUDED_WEIGHT_KG) > 0) {
      errors.put("includedWeightKg", "Included weight cannot exceed 100.00 kg.");
    } else if (hasMoreThanTwoDecimals(update.includedWeightKg())) {
      errors.put("includedWeightKg", "Included weight must have at most 2 decimal places.");
    }

    if (update.additionalWeightRatePerKg() == null) {
      errors.put("additionalWeightRatePerKg", "Rate per additional kg is required.");
    } else if (update.additionalWeightRatePerKg().compareTo(BigDecimal.ZERO) < 0) {
      errors.put("additionalWeightRatePerKg", "Rate per additional kg cannot be negative.");
    } else if (update.additionalWeightRatePerKg().compareTo(MAX_AMOUNT) > 0) {
      errors.put("additionalWeightRatePerKg", "Rate per additional kg cannot exceed 1,000,000.00.");
    } else if (hasMoreThanTwoDecimals(update.additionalWeightRatePerKg())) {
      errors.put("additionalWeightRatePerKg", "Rate per additional kg must have at most 2 decimal places.");
    }

    if (update.distanceSurchargeOtherDistrict() == null) {
      errors.put("distanceSurchargeOtherDistrict", "Other-district surcharge is required.");
    } else if (update.distanceSurchargeOtherDistrict().compareTo(BigDecimal.ZERO) < 0) {
      errors.put("distanceSurchargeOtherDistrict", "Other-district surcharge cannot be negative.");
    } else if (update.distanceSurchargeOtherDistrict().compareTo(MAX_AMOUNT) > 0) {
      errors.put("distanceSurchargeOtherDistrict", "Other-district surcharge cannot exceed 1,000,000.00.");
    } else if (hasMoreThanTwoDecimals(update.distanceSurchargeOtherDistrict())) {
      errors.put("distanceSurchargeOtherDistrict", "Other-district surcharge must have at most 2 decimal places.");
    }

    if (update.expressSurcharge() == null) {
      errors.put("expressSurcharge", "Express surcharge is required.");
    } else if (update.expressSurcharge().compareTo(BigDecimal.ZERO) < 0) {
      errors.put("expressSurcharge", "Express surcharge cannot be negative.");
    } else if (update.expressSurcharge().compareTo(MAX_AMOUNT) > 0) {
      errors.put("expressSurcharge", "Express surcharge cannot exceed 1,000,000.00.");
    } else if (hasMoreThanTwoDecimals(update.expressSurcharge())) {
      errors.put("expressSurcharge", "Express surcharge must have at most 2 decimal places.");
    }

    if (update.perKmRate() == null) {
      errors.put("perKmRate", "Rate per km is required.");
    } else if (update.perKmRate().compareTo(BigDecimal.ZERO) < 0) {
      errors.put("perKmRate", "Rate per km cannot be negative.");
    } else if (update.perKmRate().compareTo(MAX_PER_KM_RATE) > 0) {
      errors.put("perKmRate", "Rate per km cannot exceed 100,000.00.");
    } else if (hasMoreThanTwoDecimals(update.perKmRate())) {
      errors.put("perKmRate", "Rate per km must have at most 2 decimal places.");
    }

    if (!errors.isEmpty()) {
      throw new PricingValidationException(errors.values().iterator().next(), errors);
    }
  }

  private static boolean hasMoreThanTwoDecimals(BigDecimal value) {
    return value.scale() > 2 && value.stripTrailingZeros().scale() > 2;
  }

  private static PricingRule mapRule(ResultSet rs, int rowNum) throws SQLException {
    return new PricingRule(
        rs.getObject("id", UUID.class),
        rs.getString("rule_name"),
        rs.getBigDecimal("base_fee"),
        rs.getBigDecimal("included_weight_kg"),
        rs.getBigDecimal("additional_weight_rate_per_kg"),
        rs.getBigDecimal("distance_surcharge_other_district"),
        rs.getBigDecimal("express_surcharge"),
        rs.getBigDecimal("per_km_rate"),
        rs.getString("currency"),
        rs.getBoolean("active"),
        rs.getObject("updated_at", OffsetDateTime.class)
    );
  }
}
