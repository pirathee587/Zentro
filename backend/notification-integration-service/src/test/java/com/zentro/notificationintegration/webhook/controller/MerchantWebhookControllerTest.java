package com.zentro.notificationintegration.webhook.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zentro.notificationintegration.webhook.dto.response.WebhookDeliveryPage;
import com.zentro.notificationintegration.webhook.exception.WebhookExceptionHandler;
import com.zentro.notificationintegration.webhook.service.WebhookSubscriptionService;
import com.zentro.security.config.ZentroSecurityAutoConfiguration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@WebMvcTest(controllers = MerchantWebhookController.class)
@Import({ZentroSecurityAutoConfiguration.class, WebhookExceptionHandler.class})
@TestPropertySource(properties = {
    "zentro.security.issuer-uri=http://localhost:8180/realms/zentro",
    "zentro.security.audience=zentro-api"
})
class MerchantWebhookControllerTest {
  private static final String PATH = "/api/v1/merchant/webhooks";

  @Autowired MockMvc mvc;
  @MockBean WebhookSubscriptionService service;
  @MockBean JwtDecoder jwtDecoder;

  @Test
  @DisplayName("Webhook configuration requires authentication")
  void anonymousRequestIsUnauthorized() throws Exception {
    mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
    verifyNoInteractions(service);
  }

  @Test
  @DisplayName("Webhook configuration requires MERCHANT role")
  void nonMerchantRoleIsForbidden() throws Exception {
    mvc.perform(get(PATH).with(jwt().jwt(jwt -> jwt.subject("customer-1"))
            .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
        .andExpect(status().isForbidden());
    verifyNoInteractions(service);
  }

  @Test
  @DisplayName("A merchant's token subject scopes configuration and missing configuration remains 404")
  void readsConfigurationForTokenSubjectOnly() throws Exception {
    when(service.find("merchant-1")).thenReturn(Optional.empty());

    mvc.perform(get(PATH).with(merchant("merchant-1")))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("WEBHOOK_NOT_FOUND"));

    verify(service).find("merchant-1");
  }

  @Test
  @DisplayName("Delivery history is requested only for the authenticated merchant subject")
  void deliveryHistoryUsesTokenSubject() throws Exception {
    when(service.deliveries("merchant-1", 0, 20)).thenReturn(new WebhookDeliveryPage(List.of(), 0, 20, 0));

    mvc.perform(get(PATH + "/deliveries").with(merchant("merchant-1")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items").isEmpty())
        .andExpect(jsonPath("$.total").value(0));

    verify(service).deliveries("merchant-1", 0, 20);
  }

  private static RequestPostProcessor merchant(String subject) {
    return jwt().jwt(jwt -> jwt.subject(subject)).authorities(new SimpleGrantedAuthority("ROLE_MERCHANT"));
  }
}
