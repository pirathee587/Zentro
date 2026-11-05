package com.zentro.identitypartner.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zentro.security.config.ZentroSecurityAutoConfiguration;
import com.zentro.identitypartner.merchant.controller.MerchantApplicationController;
import com.zentro.identitypartner.merchant.service.MerchantApplicationService;
import com.zentro.identitypartner.user.controller.AdminUserController;
import com.zentro.identitypartner.user.service.UserProfileService;
import com.zentro.identitypartner.user.controller.UserProfileController;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import java.security.KeyPair;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;

/**
 * ZeroTrustSecurityTest — verifies that every endpoint in identity-partner-service
 * enforces JWT authentication and role-based authorisation correctly.
 *
 * <p>Uses @WebMvcTest (no DB, no Keycloak, no gRPC) so tests run in milliseconds.
 * JWTs are signed with a local RSA key pair generated per test run.
 */
@WebMvcTest(controllers = {
    UserProfileController.class,
    AdminUserController.class,
    MerchantApplicationController.class,
})
@Import(ZentroSecurityAutoConfiguration.class)
@TestPropertySource(properties = {
    "zentro.security.issuer-uri=http://localhost:9000/realms/zentro",
    "zentro.security.audience=zentro-api",
    "spring.main.allow-bean-definition-overriding=true"
})
class ZeroTrustSecurityTest {

    private static KeyPair keyPair;
    private static final String ISSUER = "http://localhost:9000/realms/zentro";
    private static final String AUDIENCE = "zentro-api";

    @Autowired
    MockMvc mockMvc;

    @MockBean UserProfileService userProfileService;
    @MockBean MerchantApplicationService merchantApplicationService;

    @BeforeAll
    static void generateKeyPair() {
        keyPair = Keys.keyPairFor(SignatureAlgorithm.RS256);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helper: build a signed JWT for testing
    // ─────────────────────────────────────────────────────────────────────────

    private String jwt(String role) {
        return jwt(role, ISSUER, AUDIENCE, Instant.now().plusSeconds(300));
    }

    private String jwt(String role, String issuer, String audience, Instant expiry) {
        return Jwts.builder()
            .setSubject(UUID.randomUUID().toString())
            .setIssuer(issuer)
            .setAudience(audience)
            .setIssuedAt(Date.from(Instant.now()))
            .setExpiration(Date.from(expiry))
            .claim("realm_access", Map.of("roles", List.of(role)))
            .signWith(keyPair.getPrivate(), SignatureAlgorithm.RS256)
            .compact();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 1. Missing Authorization header → 401
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void noToken_usersMeEndpoint_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/users/me"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void noToken_adminUsersEndpoint_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/admin/users"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void noToken_merchantApply_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/merchants/apply")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"businessName\":\"Acme\",\"businessRegNo\":\"BR-1\",\"contactPhone\":\"+94771234567\"}"))
            .andExpect(status().isUnauthorized());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 2. CUSTOMER can access own profile
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void customerToken_usersMeEndpoint_returns2xx() throws Exception {
        mockMvc.perform(get("/api/v1/users/me")
                .header("Authorization", "Bearer " + jwt("CUSTOMER")))
            .andExpect(status().is2xxSuccessful());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 3. CUSTOMER is blocked from admin endpoints → 403
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void customerToken_adminUsersEndpoint_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/admin/users")
                .header("Authorization", "Bearer " + jwt("CUSTOMER")))
            .andExpect(status().isForbidden());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 4. DISPATCHER can access admin user list
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void dispatcherToken_adminUsersEndpoint_returns2xx() throws Exception {
        mockMvc.perform(get("/api/v1/admin/users")
                .header("Authorization", "Bearer " + jwt("DISPATCHER")))
            .andExpect(status().is2xxSuccessful());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 5. CUSTOMER can submit a merchant application
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void customerToken_merchantApply_returns2xx() throws Exception {
        mockMvc.perform(post("/api/v1/merchants/apply")
                .header("Authorization", "Bearer " + jwt("CUSTOMER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"businessName\":\"Acme\",\"businessRegNo\":\"BR-1\",\"contactPhone\":\"+94771234567\"}"))
            .andExpect(status().is2xxSuccessful());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 6. DISPATCHER cannot submit a merchant application → 403
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void dispatcherToken_merchantApply_returns403() throws Exception {
        mockMvc.perform(post("/api/v1/merchants/apply")
                .header("Authorization", "Bearer " + jwt("DISPATCHER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"businessName\":\"Acme\",\"businessRegNo\":\"BR-1\",\"contactPhone\":\"+94771234567\"}"))
            .andExpect(status().isForbidden());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 7. Wrong issuer → 401
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void wrongIssuer_returns401() throws Exception {
        String token = jwt("CUSTOMER", "https://evil.invalid/realms/zentro", AUDIENCE,
            Instant.now().plusSeconds(300));
        mockMvc.perform(get("/api/v1/users/me")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isUnauthorized());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 8. Expired JWT → 401
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void expiredToken_returns401() throws Exception {
        String token = jwt("CUSTOMER", ISSUER, AUDIENCE, Instant.now().minusSeconds(60));
        mockMvc.perform(get("/api/v1/users/me")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isUnauthorized());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 9. Wrong audience → 401
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void wrongAudience_returns401() throws Exception {
        String token = jwt("CUSTOMER", ISSUER, "wrong-audience", Instant.now().plusSeconds(300));
        mockMvc.perform(get("/api/v1/users/me")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isUnauthorized());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 10. Invalid signature → 401
    // ─────────────────────────────────────────────────────────────────roughly

    @Test
    void invalidSignature_returns401() throws Exception {
        // Build valid JWT, then corrupt the signature portion
        String validToken = jwt("CUSTOMER");
        String[] parts = validToken.split("\\.");
        String corrupted = parts[0] + "." + parts[1] + ".invalidsignatureXYZ";
        mockMvc.perform(get("/api/v1/users/me")
                .header("Authorization", "Bearer " + corrupted))
            .andExpect(status().isUnauthorized());
    }
}
