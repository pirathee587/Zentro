package com.zentro.gateway.constants;

import java.util.Set;

public final class GatewayConstants {
  public static final String CORRELATION_ID_HEADER = "X-Correlation-Id";
  public static final String PROBLEM_JSON_MEDIA_TYPE = "application/problem+json";

  public static final String ROLE_CUSTOMER = "ROLE_CUSTOMER";
  public static final String ROLE_MERCHANT = "ROLE_MERCHANT";
  public static final String ROLE_COURIER = "ROLE_COURIER";
  public static final String ROLE_DISPATCHER = "ROLE_DISPATCHER";
  public static final String ROLE_SERVICE = "ROLE_SERVICE";

  public static final Set<String> ALLOWED_REALM_ROLES = Set.of(
      "CUSTOMER", "MERCHANT", "COURIER", "DISPATCHER", "SERVICE");

  public static final Set<String> HUMAN_AUTHORITIES = Set.of(
      ROLE_CUSTOMER, ROLE_MERCHANT, ROLE_COURIER, ROLE_DISPATCHER);

  private GatewayConstants() {}
}

