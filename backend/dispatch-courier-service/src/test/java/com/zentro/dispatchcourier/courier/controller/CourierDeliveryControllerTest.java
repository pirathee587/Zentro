package com.zentro.dispatchcourier.courier.controller;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.zentro.dispatchcourier.courier.dto.SubmitProofRequest;
import com.zentro.dispatchcourier.courier.entity.ProofOfDelivery;
import com.zentro.dispatchcourier.courier.repository.ProofRepository;
import com.zentro.dispatchcourier.courier.service.IdentityCourierClient;
import com.zentro.dispatchcourier.courier.service.ProofOfDeliveryService;
import com.zentro.dispatchcourier.dispatch.repository.AssignmentRepository;
import com.zentro.dispatchcourier.exception.ProofException;
import com.zentro.dispatchcourier.exception.ProofExceptionHandler;
import com.zentro.dispatchcourier.security.DispatchCourierSecurityConfig;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@WebMvcTest(CourierDeliveryController.class)
@Import({com.zentro.security.config.ZentroSecurityAutoConfiguration.class,
    DispatchCourierSecurityConfig.class, ProofOfDeliveryService.class, ProofExceptionHandler.class})
@TestPropertySource(properties = {"zentro.security.issuer-uri=http://localhost/realm", "zentro.security.audience=zentro-api"})
class CourierDeliveryControllerTest {
  @Autowired MockMvc mvc;
  @MockBean JwtDecoder decoder;
  @MockBean IdentityCourierClient identity;
  @MockBean AssignmentRepository assignments;
  @MockBean ProofRepository proofs;
  static final UUID PARCEL = UUID.randomUUID(), PROFILE = UUID.randomUUID(), USER = UUID.randomUUID();
  static final String ROOT = "/api/v1/courier/deliveries";
  static final String PROOF = ROOT + "/" + PARCEL + "/proof";
  static final String BODY = "{\"recipientName\":\" Nimal \",\"notes\":\"   \",\"proofReference\":\" REF-1 \"}";
  static RequestPostProcessor courier() {
    return jwt().jwt(j -> j.subject("keycloak-subject-distinct-from-both-UUIDs"))
        .authorities(new SimpleGrantedAuthority("ROLE_COURIER"));
  }
  @BeforeEach void setup() {
    when(identity.currentCourier(anyString())).thenReturn(new IdentityCourierClient.Courier(PROFILE, USER, "Courier", true, "ACTIVE"));
    when(assignments.find(PARCEL)).thenReturn(Optional.of(new AssignmentRepository.Assignment(PARCEL, PROFILE, USER, "dispatcher", Instant.now())));
    when(proofs.find(PARCEL)).thenReturn(Optional.empty());
  }
  @Test void validSubmissionUsesCanonicalOwnershipAndNormalizedFields() throws Exception {
    var proof = new ProofOfDelivery(UUID.randomUUID(), PARCEL, "Nimal", null, "REF-1", "actor", Instant.now(), "PENDING", null);
    when(proofs.save(eq(PARCEL), anyString(), any())).thenReturn(new ProofRepository.Submission(proof, true));
    mvc.perform(post(PROOF).with(courier()).contentType("application/json").content(BODY))
        .andExpect(status().isCreated()).andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(jsonPath("recipientName").value("Nimal")).andExpect(jsonPath("completionState").value("PENDING"));
    verify(proofs).save(PARCEL, "keycloak-subject-distinct-from-both-UUIDs", new SubmitProofRequest("Nimal", null, "REF-1"));
  }
  @ParameterizedTest @ValueSource(strings = {"{}", "null", "not-json", "{\"recipientName\":\"  \",\"proofReference\":\"x\"}", "{\"recipientName\":\"x\"}"})
  void rejectsInvalidInput(String body) throws Exception {
    mvc.perform(post(PROOF).with(courier()).contentType("application/json").content(body)).andExpect(status().isBadRequest());
    verify(proofs, never()).save(any(), any(), any());
  }
  @Test void missingAuthenticationCannotReadOrSubmit() throws Exception {
    mvc.perform(get(PROOF)).andExpect(status().isUnauthorized());
    mvc.perform(post(PROOF).contentType("application/json").content(BODY)).andExpect(status().isUnauthorized());
    verifyNoInteractions(identity, proofs);
  }
  @ParameterizedTest @ValueSource(strings = {"CUSTOMER", "MERCHANT", "DISPATCHER", "SERVICE"})
  void wrongRoleCannotReadOrSubmit(String role) throws Exception {
    var auth = jwt().authorities(new SimpleGrantedAuthority("ROLE_" + role));
    mvc.perform(get(PROOF).with(auth)).andExpect(status().isForbidden());
    mvc.perform(post(PROOF).with(auth).contentType("application/json").content(BODY)).andExpect(status().isForbidden());
    verifyNoInteractions(identity, proofs);
  }
  @Test void wrongCourierCannotAccessEvidence() throws Exception {
    when(assignments.find(PARCEL)).thenReturn(Optional.of(new AssignmentRepository.Assignment(PARCEL, UUID.randomUUID(), USER, "dispatcher", Instant.now())));
    mvc.perform(get(PROOF).with(courier())).andExpect(status().isForbidden());
    mvc.perform(post(PROOF).with(courier()).contentType("application/json").content(BODY)).andExpect(status().isForbidden());
    verifyNoInteractions(proofs);
  }
  @Test void unknownAssignmentAndInvalidId() throws Exception {
    when(assignments.find(PARCEL)).thenReturn(Optional.empty());
    mvc.perform(get(ROOT + "/" + PARCEL).with(courier())).andExpect(status().isNotFound());
    mvc.perform(get(ROOT + "/bad").with(courier())).andExpect(status().isBadRequest());
  }
  @ParameterizedTest @ValueSource(strings = {"CUSTOMER", "MERCHANT", "DISPATCHER", "SERVICE"})
  void wrongRoleCannotListOrReadDeliveryDetail(String role) throws Exception {
    var auth = jwt().authorities(new SimpleGrantedAuthority("ROLE_" + role));
    mvc.perform(get(ROOT).with(auth)).andExpect(status().isForbidden());
    mvc.perform(get(ROOT + "/" + PARCEL).with(auth)).andExpect(status().isForbidden());
    verifyNoInteractions(identity, assignments, proofs);
  }
  @Test void anonymousCannotListOrReadDeliveryDetail() throws Exception {
    mvc.perform(get(ROOT)).andExpect(status().isUnauthorized());
    mvc.perform(get(ROOT + "/" + PARCEL)).andExpect(status().isUnauthorized());
    verifyNoInteractions(identity, assignments, proofs);
  }
  @ParameterizedTest @ValueSource(booleans = {true, false})
  void eachCanonicalOwnershipIdIsRequiredForDetail(boolean wrongProfile) throws Exception {
    when(assignments.find(PARCEL)).thenReturn(Optional.of(new AssignmentRepository.Assignment(
        PARCEL, wrongProfile ? UUID.randomUUID() : PROFILE,
        wrongProfile ? USER : UUID.randomUUID(), "dispatcher", Instant.now())));
    mvc.perform(get(ROOT + "/" + PARCEL).with(courier())).andExpect(status().isForbidden());
    verifyNoInteractions(proofs);
  }
  @Test void matchingProfileWithWrongAppUserCannotReadOrSubmitProof() throws Exception {
    when(assignments.find(PARCEL)).thenReturn(Optional.of(new AssignmentRepository.Assignment(
        PARCEL, PROFILE, UUID.randomUUID(), "dispatcher", Instant.now())));
    mvc.perform(get(PROOF).with(courier())).andExpect(status().isForbidden());
    mvc.perform(post(PROOF).with(courier()).contentType("application/json").content(BODY))
        .andExpect(status().isForbidden());
    verifyNoInteractions(proofs);
  }
  @Test void identityFailureFailsClosed() throws Exception {
    when(identity.currentCourier(anyString())).thenThrow(new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE));
    mvc.perform(get(PROOF).with(courier())).andExpect(status().isServiceUnavailable());
    verifyNoInteractions(assignments, proofs);
  }
  @Test void missingProofHasSpecificCode() throws Exception {
    mvc.perform(get(PROOF).with(courier())).andExpect(status().isNotFound()).andExpect(jsonPath("code").value("PROOF_NOT_FOUND"));
  }
  @Test void listsOnlyResolvedCourierAndOmitsEvidence() throws Exception {
    when(proofs.deliveries(PROFILE, USER)).thenReturn(List.of(new ProofRepository.DeliverySummary(PARCEL, PROFILE, Instant.now(), UUID.randomUUID(), "PENDING")));
    mvc.perform(get(ROOT).with(courier())).andExpect(status().isOk()).andExpect(jsonPath("$[0].parcelId").value(PARCEL.toString()))
        .andExpect(jsonPath("$[0].recipientName").doesNotExist()).andExpect(jsonPath("$[0].proofReference").doesNotExist());
  }
  @Test void replayIs200AndConflictingProof409() throws Exception {
    var proof = new ProofOfDelivery(UUID.randomUUID(), PARCEL, "Nimal", null, "REF-1", "actor", Instant.now(), "PENDING", null);
    when(proofs.save(any(), any(), any())).thenReturn(new ProofRepository.Submission(proof, false));
    mvc.perform(post(PROOF).with(courier()).contentType("application/json").content(BODY)).andExpect(status().isOk());
    when(proofs.save(any(), any(), any())).thenThrow(new ProofException(409, "PROOF_CONFLICT", "Proof differs."));
    mvc.perform(post(PROOF).with(courier()).contentType("application/json").content(BODY)).andExpect(status().isConflict());
  }
  @Test void databaseFailureIsSafe503() throws Exception {
    when(proofs.save(any(), any(), any())).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("private database details"));
    mvc.perform(post(PROOF).with(courier()).contentType("application/json").content(BODY))
        .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("code").value("PROOF_STORAGE_UNAVAILABLE"));
  }
}

