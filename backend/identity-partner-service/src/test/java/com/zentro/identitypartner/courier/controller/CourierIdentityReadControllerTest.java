package com.zentro.identitypartner.courier.controller;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.server.ResponseStatusException;
import com.zentro.identitypartner.courier.service.CourierIdentityReadService;

@WebMvcTest(CourierIdentityReadController.class)
@Import({com.zentro.security.config.ZentroSecurityAutoConfiguration.class})
class CourierIdentityReadControllerTest {
  private static final String PATH = "/internal/courier-dashboard/identity";
  @Autowired MockMvc mvc;
  @MockitoBean JwtDecoder decoder;
  @MockitoBean CourierIdentityReadService identity;

  private final UUID user = UUID.randomUUID(), profile = UUID.randomUUID();
  @BeforeEach void setup() { when(identity.resolve("subject-A")).thenReturn(new CourierIdentityReadService.Identity(user, profile)); }
  private RequestPostProcessor courier() {
    return jwt().jwt(j -> j.tokenValue("synthetic-courier").subject("subject-A"))
        .authorities(new SimpleGrantedAuthority("ROLE_COURIER"));
  }
  @Test void selfLookupIgnoresArbitraryIdentityParameters() throws Exception {
    mvc.perform(get(PATH).with(courier()).param("courierId", UUID.randomUUID().toString()))
        .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(jsonPath("$.userId").value(user.toString()));
    verify(identity).resolve("subject-A");
  }
  @Test void anonymousRejected() throws Exception {
    mvc.perform(get(PATH)).andExpect(status().isUnauthorized()); verifyNoInteractions(identity);
  }
  @ParameterizedTest @ValueSource(strings={"invalid", "expired"})
  void rejectedBearerNeverResolvesIdentity(String token) throws Exception {
    when(decoder.decode(token)).thenThrow(new BadJwtException("Rejected synthetic JWT"));
    mvc.perform(get(PATH).header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
    verifyNoInteractions(identity);
  }
  @ParameterizedTest @ValueSource(strings={"CUSTOMER", "DISPATCHER", "SERVICE"})
  void wrongRoleRejected(String role) throws Exception {
    mvc.perform(get(PATH).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_"+role))))
        .andExpect(status().isForbidden()); verifyNoInteractions(identity);
  }
  @Test void serviceCombinedWithCourierIsRejected() throws Exception {
    mvc.perform(get(PATH).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_COURIER"),new SimpleGrantedAuthority("ROLE_SERVICE"))))
        .andExpect(status().isForbidden()); verifyNoInteractions(identity);
  }
  @ParameterizedTest @ValueSource(ints={403,404})
  void missingOrIneligibleIdentityNeverSucceeds(int status) throws Exception {
    when(identity.resolve(anyString())).thenThrow(new ResponseStatusException(HttpStatus.valueOf(status)));
    mvc.perform(get(PATH).with(courier())).andExpect(status().is(status));
  }

  @Test void identityStorageFailureReturnsSanitizedUnavailableProblem() throws Exception {
    when(identity.resolve(anyString())).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("PRIVATE-DB"));
    mvc.perform(get(PATH).with(courier())).andExpect(status().isServiceUnavailable())
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(jsonPath("$.code").value("COURIER_IDENTITY_UNAVAILABLE"))
        .andExpect(result -> org.assertj.core.api.Assertions.assertThat(result.getResponse().getContentAsString()).doesNotContain("PRIVATE-DB"));
  }

}
