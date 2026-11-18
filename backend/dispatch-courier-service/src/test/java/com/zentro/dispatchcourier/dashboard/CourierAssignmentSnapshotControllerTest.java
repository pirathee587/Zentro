package com.zentro.dispatchcourier.dashboard;
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
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.server.ResponseStatusException;
import com.zentro.dispatchcourier.dashboard.CourierIdentityReadClient;

@WebMvcTest(CourierAssignmentSnapshotController.class)
@org.springframework.boot.autoconfigure.ImportAutoConfiguration(com.zentro.security.config.ZentroSecurityAutoConfiguration.class)
@Import(com.zentro.dispatchcourier.security.DispatchCourierSecurityConfig.class)
class CourierAssignmentSnapshotControllerTest {
  private static final String PATH = "/internal/courier-dashboard/assignments";
  @Autowired MockMvc mvc;
  @MockBean JwtDecoder decoder;
  @MockBean CourierIdentityReadClient identity;
  @MockBean CourierAssignmentSnapshotRepository assignments;

  private final UUID user = UUID.randomUUID(), profile = UUID.randomUUID();
  @BeforeEach void setup() { when(identity.resolve("synthetic-courier")).thenReturn(new CourierIdentityReadClient.Identity(user, profile));
    when(assignments.snapshot(user, profile)).thenReturn(List.of()); }
  private RequestPostProcessor courier() {
    return jwt().jwt(j -> j.tokenValue("synthetic-courier").subject("subject-A"))
        .authorities(new SimpleGrantedAuthority("ROLE_COURIER"));
  }
  @Test void selfLookupIgnoresArbitraryIdentityParameters() throws Exception {
    mvc.perform(get(PATH).with(courier()).param("courierId", UUID.randomUUID().toString()))
        .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(content().json("[]"));
    verify(identity).resolve("synthetic-courier"); verify(assignments).snapshot(user, profile);
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

  @Test void differentCourierUsesOnlyTheirResolvedIds() throws Exception {
    UUID otherUser = UUID.randomUUID(), otherProfile = UUID.randomUUID();
    when(identity.resolve("courier-B")).thenReturn(new CourierIdentityReadClient.Identity(otherUser, otherProfile));
    when(assignments.snapshot(otherUser, otherProfile)).thenReturn(List.of());
    mvc.perform(get(PATH).with(jwt().jwt(j -> j.tokenValue("courier-B").subject("subject-B"))
        .authorities(new SimpleGrantedAuthority("ROLE_COURIER"))).param("courierId", user.toString()))
        .andExpect(status().isOk()).andExpect(content().json("[]"));
    verify(assignments).snapshot(otherUser, otherProfile); verify(assignments, never()).snapshot(user, profile);
  }
  @Test void storageFailureIsSanitized() throws Exception {
    when(assignments.snapshot(user, profile)).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("PRIVATE-DB"));
    mvc.perform(get(PATH).with(courier())).andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("ASSIGNMENT_SNAPSHOT_UNAVAILABLE"))
        .andExpect(result -> org.assertj.core.api.Assertions.assertThat(result.getResponse().getContentAsString()).doesNotContain("PRIVATE-DB"));
  }

}
