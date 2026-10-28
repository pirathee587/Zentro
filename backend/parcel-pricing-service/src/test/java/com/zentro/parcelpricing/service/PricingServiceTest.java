package com.zentro.parcelpricing.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.zentro.parcelpricing.domain.DestinationType;
import com.zentro.parcelpricing.domain.PricingRule;
import com.zentro.parcelpricing.dto.FeeEstimateRequest;
import com.zentro.parcelpricing.dto.FeeEstimateResponse;
import com.zentro.parcelpricing.dto.PricingRuleUpdateRequest;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class PricingServiceTest {
  private JdbcTemplate jdbc;
  private PricingCalculationEngine calculationEngine;
  private PricingService pricingService;

  @BeforeEach
  void setUp() {
    jdbc = mock(JdbcTemplate.class);
    calculationEngine = new PricingCalculationEngine();
    pricingService = new PricingService(jdbc, calculationEngine);
  }

  @Test
  @DisplayName("getActiveRule returns database rule when available")
  void returnsDatabaseRuleWhenAvailable() {
    PricingRule customRule = new PricingRule(
        null, "CUSTOM", new BigDecimal("600.00"), new BigDecimal("1.00"),
        new BigDecimal("150.00"), new BigDecimal("450.00"), new BigDecimal("350.00"),
        BigDecimal.ZERO, "LKR", true, null
    );
    when(jdbc.query(anyString(), any(RowMapper.class))).thenReturn(List.of(customRule));

    PricingRule rule = pricingService.getActiveRule();

    assertEquals("CUSTOM", rule.ruleName());
    assertEquals(new BigDecimal("600.00"), rule.baseFee());
  }

  @Test
  @DisplayName("getActiveRule falls back to standard default when database query fails")
  void fallsBackToStandardDefaultOnDatabaseError() {
    when(jdbc.query(anyString(), any(RowMapper.class)))
        .thenThrow(new DataRetrievalFailureException("Table not initialized"));

    PricingRule rule = pricingService.getActiveRule();

    assertEquals("STANDARD_DEFAULT", rule.ruleName());
    assertEquals(new BigDecimal("550.00"), rule.baseFee());
    assertEquals(new BigDecimal("120.00"), rule.additionalWeightRatePerKg());
  }

  @Test
  @DisplayName("estimateFee successfully calculates using active rule")
  void estimateFeeCalculatesSuccessfully() {
    when(jdbc.query(anyString(), any(RowMapper.class))).thenReturn(List.of(PricingRule.defaultStandard()));

    FeeEstimateRequest req = new FeeEstimateRequest(
        "Colombo", "Colombo", DestinationType.SAME_CITY, null,
        new BigDecimal("2.00"), null, false, null, null, null, null, null, null, "LKR"
    );

    FeeEstimateResponse res = pricingService.estimateFee(req);

    assertNotNull(res);
    assertEquals(new BigDecimal("670.00"), res.fee());
  }

  @Test
  @DisplayName("updateStandardRule updates rule successfully")
  void updateStandardRuleUpdatesSuccessfully() {
    when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

    PricingRuleUpdateRequest update = new PricingRuleUpdateRequest(
        new BigDecimal("580.00"),
        new BigDecimal("1.00"),
        new BigDecimal("130.00"),
        new BigDecimal("420.00"),
        new BigDecimal("320.00"),
        new BigDecimal("15.00"),
        "LKR"
    );

    PricingRule updated = pricingService.updateStandardRule(update);
    assertNotNull(updated);
  }

  @Test
  @DisplayName("updateStandardRule throws ResponseStatusException on database error")
  void updateStandardRuleThrowsOnDatabaseFailure() {
    when(jdbc.update(anyString(), any(Object[].class)))
        .thenThrow(new DataRetrievalFailureException("Database write failed"));

    PricingRuleUpdateRequest update = new PricingRuleUpdateRequest(
        new BigDecimal("580.00"),
        new BigDecimal("1.00"),
        new BigDecimal("130.00"),
        new BigDecimal("420.00"),
        new BigDecimal("320.00"),
        new BigDecimal("15.00"),
        "LKR"
    );

    org.springframework.web.server.ResponseStatusException ex = assertThrows(
        org.springframework.web.server.ResponseStatusException.class,
        () -> pricingService.updateStandardRule(update)
    );
    assertEquals(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR, ex.getStatusCode());
    assertTrue(ex.getReason().contains("Failed to persist pricing rule update"));
  }
}
