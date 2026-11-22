package com.zentro.notificationintegration.inbox;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class InboxAvailabilityTest {
  @Test void missingPreparedSchemaReturns503BeforeQueriesOrStreamAndStillEnforcesRoles() throws Exception {
    var inbox=mock(InboxRepository.class); var stream=mock(InboxStream.class);
    var mvc=MockMvcBuilders.standaloneSetup(new InboxController(inbox,stream)).build();
    var auth=new JwtAuthenticationToken(Jwt.withTokenValue("synthetic").header("alg","none").subject("customer")
        .expiresAt(Instant.now().plusSeconds(60)).build(),List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")));
    mvc.perform(get("/api/v1/notifications").param("portal","CUSTOMER").principal(auth)).andExpect(status().isServiceUnavailable());
    mvc.perform(get("/api/v1/notifications/events").param("portal","CUSTOMER").principal(auth)).andExpect(status().isServiceUnavailable());
    mvc.perform(post("/api/v1/notifications/read-all").param("portal","CUSTOMER").principal(auth)).andExpect(status().isServiceUnavailable());
    mvc.perform(get("/api/v1/notifications").param("portal","MERCHANT").principal(auth)).andExpect(status().isForbidden());
    verify(inbox,times(3)).available(); verifyNoMoreInteractions(inbox); verifyNoInteractions(stream);
  }
}
