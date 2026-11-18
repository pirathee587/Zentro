package com.zentro.trackingreporting.graphql.dashboard;

import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.security.core.Authentication;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Controller;

@Controller
public class CourierDashboardGraphQlEndpoint {
  private final CourierDashboardService service;
  public CourierDashboardGraphQlEndpoint(CourierDashboardService service) { this.service = service; }
  @QueryMapping public CourierTodayDashboardDto courierTodayDashboard(@Argument("page") Integer page,
      @Argument("size") Integer size, @Nullable Authentication authentication) {
    return service.dashboard(page, size, authentication);
  }
}
