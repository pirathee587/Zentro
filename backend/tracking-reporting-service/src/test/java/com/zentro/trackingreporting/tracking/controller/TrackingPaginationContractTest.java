package com.zentro.trackingreporting.tracking.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.zentro.trackingreporting.exception.GlobalTrackingExceptionHandler;
import com.zentro.trackingreporting.security.TrackingSecurityConfig;
import com.zentro.trackingreporting.tracking.service.TrackingService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(value = CustomerTrackingController.class,
    properties = {"logging.level.org.springframework=INFO", "spring.test.mockmvc.print=NONE"})
@Import({TrackingSecurityConfig.class, GlobalTrackingExceptionHandler.class,
    com.zentro.security.observability.CorrelationIdFilter.class})
@ExtendWith(OutputCaptureExtension.class)
class TrackingPaginationContractTest {
  @Autowired MockMvc mvc;
  @MockBean TrackingService service;
  @MockBean JwtDecoder decoder;

  @ParameterizedTest
  @CsvSource({"page,abc", "size,abc", "limit,abc", "page,2147483648",
      "size,-2147483649", "limit,999999999999999999999", "size,private-secret"})
  void invalidIntegersAreSafe400(String parameter, String value, CapturedOutput output) throws Exception {
    String body = mvc.perform(get("/api/v1/tracking/mine").param(parameter, value)
        .header("X-Correlation-Id", "pagination-contract")
        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
        .andExpect(status().isBadRequest())
        .andExpect(header().string("X-Correlation-Id", "pagination-contract"))
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        .andExpect(jsonPath("$.type").value("about:blank"))
        .andExpect(jsonPath("$.title").value("Bad Request"))
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"))
        .andExpect(jsonPath("$.instance").value("/api/v1/tracking/mine"))
        .andReturn().getResponse().getContentAsString();
    assertThat(body).doesNotContain(value, "Exception", "java.lang", "stackTrace");
    assertThat(output.getAll()).doesNotContain("Tracking request failed: category=unexpected_error");
    verifyNoInteractions(service);
  }

  @ParameterizedTest
  @CsvSource({"1,1", "1,100", "-2147483648,-2147483648", "2147483647,2147483647", "0,0"})
  void validIntegerBoundsStillReachExistingServiceClamping(int page, int size) throws Exception {
    mvc.perform(get("/api/v1/tracking/mine").param("page", "" + page).param("size", "" + size)
        .with(jwt().jwt(j -> j.subject("owner")).authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
        .andExpect(status().isOk());
    verify(service).getCustomerDeliveries("owner", page, size, null, null);
  }

  @Test void emptyValuesKeepExistingDefaults() throws Exception {
    mvc.perform(get("/api/v1/tracking/mine").param("page", "").param("size", "").param("limit", "")
        .with(jwt().jwt(j -> j.subject("owner")).authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
        .andExpect(status().isOk());
    verify(service).getCustomerDeliveries("owner", 1, 10, null, null);
  }

  @Test void securityPrecedesConversion() throws Exception {
    mvc.perform(get("/api/v1/tracking/mine?size=abc")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/v1/tracking/mine?size=abc")
        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_COURIER"))))
        .andExpect(status().isForbidden());
    verifyNoInteractions(service);
  }
}
