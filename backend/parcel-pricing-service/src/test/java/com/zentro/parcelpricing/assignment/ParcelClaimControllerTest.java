package com.zentro.parcelpricing.assignment;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.web.servlet.MockMvc;
@WebMvcTest(ParcelClaimController.class)
@Import({com.zentro.security.config.ZentroSecurityAutoConfiguration.class,AssignmentPermissions.class})
class ParcelClaimControllerTest {
 @Autowired MockMvc mvc;@MockBean JwtDecoder decoder;@MockBean ParcelClaimService claims;
 final UUID operation=UUID.randomUUID(),parcel=UUID.randomUUID();
 String body(String mode){return "{\"parcelId\":\""+parcel+"\",\"mode\":\""+mode+"\"}";}
 @Test void anonymousCannotAcquire() throws Exception {mvc.perform(post("/internal/assignment-claims/{id}/acquire",operation).contentType(MediaType.APPLICATION_JSON).content(body("ASSIGN"))).andExpect(status().isUnauthorized());verifyNoInteractions(claims);}
 @ParameterizedTest @ValueSource(strings={"COURIER","CUSTOMER","SERVICE"})
 void otherRolesRejected(String role) throws Exception {mvc.perform(get("/internal/assignment-claims/candidates").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_"+role)))).andExpect(status().isForbidden());verifyNoInteractions(claims);}
 @Test void dispatcherNeedsViewPermissionForCandidates() throws Exception {mvc.perform(get("/internal/assignment-claims/candidates").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER")))).andExpect(status().isForbidden());verifyNoInteractions(claims);}
 @Test void viewOnlyCannotAssign() throws Exception {mvc.perform(post("/internal/assignment-claims/{id}/acquire",operation).with(jwt().jwt(j->j.claim("permissions",List.of("DISPATCH_VIEW"))).authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER"))).contentType(MediaType.APPLICATION_JSON).content(body("ASSIGN"))).andExpect(status().isForbidden());verifyNoInteractions(claims);}
 @Test void assignedClaimPreservesExistingDispatcherRoleBoundary() throws Exception {mvc.perform(post("/internal/assignment-claims/{id}/acquire",operation).with(jwt().jwt(j->j.subject("dispatcher").tokenValue("synthetic-token")).authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER"))).contentType(MediaType.APPLICATION_JSON).content(body("ASSIGNED"))).andExpect(status().isOk());verify(claims).acquire(operation,new ParcelClaimService.Request(parcel,"ASSIGNED"),"dispatcher","synthetic-token");}
 @Test void permissionAndSubjectComeFromValidatedJwt() throws Exception {mvc.perform(post("/internal/assignment-claims/{id}/acquire",operation).with(jwt().jwt(j->j.subject("dispatcher").tokenValue("synthetic-token").claim("permissions",List.of("DISPATCH_ASSIGN"))).authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER"))).contentType(MediaType.APPLICATION_JSON).content(body("ASSIGN"))).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));verify(claims).acquire(operation,new ParcelClaimService.Request(parcel,"ASSIGN"),"dispatcher","synthetic-token");}
 @ParameterizedTest @ValueSource(strings={"invalid","expired"})
 void invalidBearerRejected(String token) throws Exception {when(decoder.decode(token)).thenThrow(new BadJwtException("synthetic invalid JWT"));mvc.perform(get("/internal/assignment-claims/candidates").header("Authorization","Bearer "+token)).andExpect(status().isUnauthorized());verifyNoInteractions(claims);}
}
