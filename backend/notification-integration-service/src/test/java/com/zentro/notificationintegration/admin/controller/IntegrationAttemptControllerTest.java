package com.zentro.notificationintegration.admin.controller;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zentro.notificationintegration.admin.dto.AttemptHistoryResponse;
import com.zentro.notificationintegration.notification.repository.NotificationRepository;
import com.zentro.notificationintegration.webhook.entity.WebhookDelivery;
import com.zentro.notificationintegration.webhook.entity.WebhookDeliveryStatus;
import com.zentro.notificationintegration.webhook.repository.WebhookDeliveryRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Cross-role authorization negative test matrix for the DISPATCHER-only
 * IntegrationAttemptController (GET /api/v1/admin/notification-attempts and
 * GET /api/v1/admin/webhook-deliveries).
 *
 * <p>Verifies that:
 * <ul>
 *   <li>Unauthenticated requests return 401 and never reach the service layer.</li>
 *   <li>CUSTOMER, MERCHANT, and COURIER tokens are denied with 403 on both endpoints.</li>
 *   <li>SERVICE tokens are denied with 403 (admin endpoints are human-role only).</li>
 *   <li>DISPATCHER tokens are accepted and return paginated results.</li>
 * </ul>
 *
 * <p>The test uses the {@code jwt()} MockMvc post-processor, which bypasses real JWT signature
 * validation while exercising the full Spring Security {@code @PreAuthorize} method-security
 * layer enforced by {@link com.zentro.security.config.ZentroSecurityAutoConfiguration}.
 */
@WebMvcTest(IntegrationAttemptController.class)
@Import(com.zentro.security.config.ZentroSecurityAutoConfiguration.class)
@TestPropertySource(properties = {
    "zentro.security.issuer-uri=http://localhost:8180/realms/zentro",
    "zentro.security.audience=zentro-api"
})
class IntegrationAttemptControllerTest {

  private static final String NOTIFICATION_PATH = "/api/v1/admin/notification-attempts";
  private static final String WEBHOOK_PATH      = "/api/v1/admin/webhook-deliveries";

  @Autowired MockMvc mvc;

  @MockBean JwtDecoder jwtDecoder;
  @MockBean NotificationRepository notificationRepository;
  @MockBean WebhookDeliveryRepository webhookDeliveryRepository;

  // ---- unauthenticated ----

  @Test
  @DisplayName("Unauthenticated request to notification-attempts returns 401 and never reaches the repository")
  void unauthenticated_notificationAttempts_returns401() throws Exception {
    mvc.perform(get(NOTIFICATION_PATH)).andExpect(status().isUnauthorized());
    verifyNoInteractions(notificationRepository, webhookDeliveryRepository);
  }

  @Test
  @DisplayName("Unauthenticated request to webhook-deliveries returns 401 and never reaches the repository")
  void unauthenticated_webhookDeliveries_returns401() throws Exception {
    mvc.perform(get(WEBHOOK_PATH)).andExpect(status().isUnauthorized());
    verifyNoInteractions(notificationRepository, webhookDeliveryRepository);
  }

  // ---- wrong-role cross-service negative matrix ----

  @ParameterizedTest(name = "{0} token on notification-attempts → 403")
  @ValueSource(strings = {"CUSTOMER", "MERCHANT", "COURIER", "SERVICE"})
  @DisplayName("Non-DISPATCHER roles are forbidden on GET /api/v1/admin/notification-attempts")
  void wrongRole_notificationAttempts_returns403(String role) throws Exception {
    mvc.perform(get(NOTIFICATION_PATH)
            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_" + role))))
        .andExpect(status().isForbidden());
    verifyNoInteractions(notificationRepository, webhookDeliveryRepository);
  }

  @ParameterizedTest(name = "{0} token on webhook-deliveries → 403")
  @ValueSource(strings = {"CUSTOMER", "MERCHANT", "COURIER", "SERVICE"})
  @DisplayName("Non-DISPATCHER roles are forbidden on GET /api/v1/admin/webhook-deliveries")
  void wrongRole_webhookDeliveries_returns403(String role) throws Exception {
    mvc.perform(get(WEBHOOK_PATH)
            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_" + role))))
        .andExpect(status().isForbidden());
    verifyNoInteractions(notificationRepository, webhookDeliveryRepository);
  }

  // ---- DISPATCHER positive baseline ----

  @Test
  @DisplayName("DISPATCHER token on notification-attempts returns 200 with paginated result")
  void dispatcher_notificationAttempts_returns200() throws Exception {
    when(notificationRepository.listHistory(25, 0)).thenReturn(List.of());
    when(notificationRepository.countHistory()).thenReturn(0L);
    when(webhookDeliveryRepository.listHistory(25, 0)).thenReturn(List.of());
    when(webhookDeliveryRepository.countHistory()).thenReturn(0L);

    mvc.perform(get(NOTIFICATION_PATH)
            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.page").value(0))
        .andExpect(jsonPath("$.size").value(25));
  }

  @Test
  @DisplayName("DISPATCHER token on webhook-deliveries returns 200 with paginated result")
  void dispatcher_webhookDeliveries_returns200() throws Exception {
    when(notificationRepository.listHistory(25, 0)).thenReturn(List.of());
    when(notificationRepository.countHistory()).thenReturn(0L);
    when(webhookDeliveryRepository.listHistory(25, 0)).thenReturn(List.of());
    when(webhookDeliveryRepository.countHistory()).thenReturn(0L);

    mvc.perform(get(WEBHOOK_PATH)
            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.page").value(0))
        .andExpect(jsonPath("$.size").value(25));
  }

  @Test
  @DisplayName("DISPATCHER webhook-deliveries includes responseClass for observability")
  void dispatcher_webhookDeliveries_includesResponseClass() throws Exception {
    when(notificationRepository.listHistory(25, 0)).thenReturn(List.of());
    when(notificationRepository.countHistory()).thenReturn(0L);

    UUID deliveryId = UUID.randomUUID();
    UUID eventId = UUID.randomUUID();
    UUID parcelId = UUID.randomUUID();
    UUID subId = UUID.randomUUID();
    WebhookDelivery delivery = new WebhookDelivery(
        deliveryId, subId, eventId, "parcel.status.updated", parcelId,
        WebhookDeliveryStatus.SUCCEEDED, 1, 200, null, null,
        OffsetDateTime.now(), OffsetDateTime.now());
    when(webhookDeliveryRepository.listHistory(25, 0)).thenReturn(List.of(delivery));
    when(webhookDeliveryRepository.countHistory()).thenReturn(1L);

    mvc.perform(get(WEBHOOK_PATH)
            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.webhooks[0].id").value(deliveryId.toString()))
        .andExpect(jsonPath("$.webhooks[0].responseStatus").value(200))
        .andExpect(jsonPath("$.webhooks[0].responseClass").value("SUCCESSFUL"));
  }

  // ---- pagination guard ----

  @Test
  @DisplayName("Negative page index returns 400")
  void dispatcher_negativePageIndex_returns400() throws Exception {
    mvc.perform(get(NOTIFICATION_PATH).param("page", "-1")
            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER"))))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(notificationRepository, webhookDeliveryRepository);
  }

  @Test
  @DisplayName("Size out of range (0 or over 100) returns 400")
  void dispatcher_invalidSize_returns400() throws Exception {
    var auth = jwt().authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER"));
    mvc.perform(get(NOTIFICATION_PATH).param("size", "0").with(auth))
        .andExpect(status().isBadRequest());
    mvc.perform(get(NOTIFICATION_PATH).param("size", "101").with(auth))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(notificationRepository, webhookDeliveryRepository);
  }

  @ParameterizedTest
  @ValueSource(strings = {"/api/v1/admin/notification-attempts", "/api/v1/admin/webhook-deliveries"})
  void bothHistoriesRejectUnsafePagination(String path) throws Exception {
    var auth = jwt().authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER"));
    for (String size : List.of("0", "-1", "100000", "abc", "2147483648")) {
      mvc.perform(get(path).param("size", size).with(auth)).andExpect(status().isBadRequest());
    }
    mvc.perform(get(path).param("page", "-1").with(auth)).andExpect(status().isBadRequest());
    mvc.perform(get(path).param("page", "abc").with(auth)).andExpect(status().isBadRequest());
    verifyNoInteractions(notificationRepository, webhookDeliveryRepository);
  }
}

