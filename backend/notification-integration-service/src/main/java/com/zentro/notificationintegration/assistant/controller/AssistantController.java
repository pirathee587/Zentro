package com.zentro.notificationintegration.assistant.controller;

import com.zentro.notificationintegration.assistant.service.AssistantConversationService;
import com.zentro.notificationintegration.assistant.dto.request.AssistantChatRequest;
import com.zentro.notificationintegration.assistant.dto.response.AssistantChatResponse;
import com.zentro.notificationintegration.assistant.dto.response.AssistantConversationResponse;
import com.zentro.notificationintegration.assistant.dto.response.AssistantHistoryResponse;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/assistant")
@PreAuthorize("hasRole('CUSTOMER') and !hasAnyRole('DISPATCHER', 'SERVICE')")
public class AssistantController {
  private final AssistantConversationService conversations;

  public AssistantController(AssistantConversationService conversations) {
    this.conversations = conversations;
  }

  @PostMapping("/chat")
  public AssistantChatResponse chat(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody AssistantChatRequest request) {
    subject(jwt);
    return conversations.chat(jwt, request);
  }

  @PostMapping("/conversations")
  @ResponseStatus(HttpStatus.CREATED)
  public AssistantConversationResponse create(@AuthenticationPrincipal Jwt jwt) { return conversations.create(subject(jwt)); }
  @GetMapping("/conversations")
  public List<AssistantConversationResponse> list(@AuthenticationPrincipal Jwt jwt) { return conversations.list(subject(jwt)); }
  @GetMapping("/conversations/{id}")
  public AssistantHistoryResponse history(@AuthenticationPrincipal Jwt jwt, @PathVariable("id") UUID id) {
    return conversations.history(id, subject(jwt));
  }
  private String subject(Jwt jwt) {
    if (jwt == null || jwt.getSubject() == null || jwt.getSubject().isBlank()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    return jwt.getSubject();
  }
}
