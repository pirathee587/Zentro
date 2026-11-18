package com.zentro.dispatchcourier.dispatch.coordination;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.web.servlet.MockMvc;
@WebMvcTest(AssignmentOperationController.class)
@ImportAutoConfiguration(com.zentro.security.config.ZentroSecurityAutoConfiguration.class)
@Import({com.zentro.dispatchcourier.security.DispatchCourierSecurityConfig.class,com.zentro.dispatchcourier.security.DispatchPermissions.class})
class AssignmentOperationControllerTest {
 @Autowired MockMvc mvc;@MockBean JwtDecoder decoder;@MockBean AssignmentOperationStore operations;@MockBean CoordinatedParcelSource source;
 final UUID id=UUID.randomUUID(),parcel=UUID.randomUUID();
 AssignmentOperationStore.Operation op(String mode,String owner){return new AssignmentOperationStore.Operation(id,parcel,owner,mode,"PREPARED",false);}
 @Test void anonymousDenied() throws Exception {mvc.perform(get("/internal/dispatch/assignment-operations")).andExpect(status().isUnauthorized());verifyNoInteractions(operations,source);}
 @ParameterizedTest @ValueSource(strings={"COURIER","CUSTOMER","SERVICE"})
 void nonDispatcherDenied(String role) throws Exception {mvc.perform(get("/internal/dispatch/assignment-operations").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_"+role)))).andExpect(status().isForbidden());verifyNoInteractions(operations,source);}
 @Test void foreignDispatcherCannotReadOutcome() throws Exception {when(operations.get(id)).thenReturn(op("ASSIGNED","owner-A"));mvc.perform(get("/internal/dispatch/assignment-operations/{id}",id).with(jwt().jwt(j->j.subject("owner-B")).authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER")))).andExpect(status().isForbidden());}
 @Test void initialOperationRequiresAssignmentPermission() throws Exception {when(operations.get(id)).thenReturn(op("ASSIGN","owner"));mvc.perform(post("/internal/dispatch/assignment-operations/{id}/reconcile",id).with(jwt().jwt(j->j.subject("owner")).authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER")))).andExpect(status().isForbidden());verifyNoInteractions(source);}
 @Test void ownerCanReconcileWithFreshSessionAndExistingPermission() throws Exception {when(operations.get(id)).thenReturn(op("ASSIGN","owner"));when(source.reconcile(id,"fresh-synthetic-token")).thenReturn(op("ASSIGN","owner"));mvc.perform(post("/internal/dispatch/assignment-operations/{id}/reconcile",id).with(jwt().jwt(j->j.subject("owner").tokenValue("fresh-synthetic-token").claim("permissions",List.of("DISPATCH_ASSIGN"))).authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER")))).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));verify(source).reconcile(id,"fresh-synthetic-token");}
 @Test void assignedOutcomeRetainsDispatcherRoleBoundary() throws Exception {when(operations.get(id)).thenReturn(op("ASSIGNED","owner"));mvc.perform(get("/internal/dispatch/assignment-operations/{id}",id).with(jwt().jwt(j->j.subject("owner")).authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER")))).andExpect(status().isOk());}
}
