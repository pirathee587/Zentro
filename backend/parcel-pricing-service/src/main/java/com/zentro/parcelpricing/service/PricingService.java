package com.zentro.parcelpricing.service;

import com.zentro.parcelpricing.domain.PricingRule;
import com.zentro.parcelpricing.dto.FeeEstimateRequest;
import com.zentro.parcelpricing.dto.FeeEstimateResponse;
import com.zentro.parcelpricing.dto.PricingRuleUpdateRequest;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PricingService {
  private static final RowMapper<PricingRule> RULE_MAPPER = PricingService::mapRule;

  private final JdbcTemplate jdbc;
  private final PricingCalculationEngine calculationEngine;

  public PricingService(JdbcTemplate jdbc, PricingCalculationEngine calculationEngine) {
    this.jdbc = jdbc;
    this.calculationEngine = calculationEngine;
  }

  public FeeEstimateResponse estimateFee(FeeEstimateRequest request) {
    PricingRule activeRule = getActiveRule();
    return calculationEngine.calculate(request, activeRule);
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
    String currency = (update.currency() != null && !update.currency().isBlank())
        ? update.currency()
        : "LKR";

    PricingRule current = getActiveRule();
    OffsetDateTime now = OffsetDateTime.now();

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
