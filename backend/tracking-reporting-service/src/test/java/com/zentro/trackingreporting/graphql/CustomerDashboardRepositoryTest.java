package com.zentro.trackingreporting.graphql;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import com.zentro.trackingreporting.graphql.service.*;
import com.zentro.trackingreporting.graphql.dto.CustomerDashboardSummary;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
class CustomerDashboardRepositoryTest {
 @Test void aggregatesAuthoritativeBookingsWithOriginalJwt() {
   var client=mock(CustomerBookingClient.class); var service=new CustomerDashboardService(client);
   var jwt=Jwt.withTokenValue("synthetic").header("alg","RS256").subject("alice").build();
   var summary=new CustomerDashboardSummary(2,1,1,1,1,4,"2026-11","Asia/Colombo",List.of(),List.of());
   when(client.summary(jwt)).thenReturn(summary);
   assertThat(service.summary(jwt)).isSameAs(summary);
   verify(client).summary(jwt); assertThat(service.liveParcel(jwt)).isNull(); verify(client).live(jwt);
 }
}
