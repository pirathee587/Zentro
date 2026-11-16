package com.zentro.notificationintegration.assistant.service;

import com.zentro.notificationintegration.assistant.dto.request.AssistantChatRequest;
import com.zentro.notificationintegration.assistant.dto.response.AssistantChatResponse;
import com.zentro.notificationintegration.assistant.dto.response.AssistantConversationResponse;
import com.zentro.notificationintegration.assistant.dto.response.AssistantHistoryResponse;
import com.zentro.notificationintegration.assistant.repository.AssistantConversationStore;
import java.util.List;
import java.util.UUID;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

/** Coordinates conversation leases, model context and persistence without a long database transaction. */
@Service
public class AssistantConversationService {
  private final AssistantConversationStore store;
  private final DeepSeekAssistant assistant;

  public AssistantConversationService(AssistantConversationStore store, DeepSeekAssistant assistant) {
    this.store = store;
    this.assistant = assistant;
  }

  public AssistantChatResponse chat(Jwt jwt, AssistantChatRequest request) {
    String owner = jwt.getSubject();
    UUID id = request.conversationId() == null ? store.create(owner).id() : request.conversationId();
    UUID claim = store.claim(id, owner);
    try {
      var entries = store.history(id, owner).messages();
      List<Message> context = entries.stream().skip(Math.max(0, entries.size() - 20))
          .<Message>map(entry -> "user".equals(entry.role())
              ? new UserMessage(entry.text()) : new AssistantMessage(entry.text())).toList();
      String answer = assistant.reply(jwt, context, request.message().trim());
      store.complete(id, owner, claim, request.message().trim(), answer);
      return new AssistantChatResponse(answer, id);
    } finally {
      store.release(id, owner, claim);
    }
  }

  public AssistantConversationResponse create(String owner) { return store.create(owner); }
  public List<AssistantConversationResponse> list(String owner) { return store.list(owner); }
  public AssistantHistoryResponse history(UUID id, String owner) { return store.history(id, owner); }
}
