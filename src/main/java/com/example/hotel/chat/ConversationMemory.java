package com.example.hotel.chat;

import com.example.hotel.audit.AuditProblem;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Small, process-local histories. No database, summaries, or shared history between users. */
@Component
public class ConversationMemory {
  static final class Conversation {
    final String id;
    final String owner;
    final MessageChatMemoryAdvisor advisor;
    long lastAccess;

    Conversation(String owner, int maxMessages, long now) {
      this.id = UUID.randomUUID().toString();
      this.owner = owner;
      this.lastAccess = now;
      var memory = MessageWindowChatMemory.builder().maxMessages(maxMessages).build();
      this.advisor =
          MessageChatMemoryAdvisor.builder(memory)
              // Outside the tool loop: keep user/final assistant text, not intermediate tool
              // payloads.
              .order(ToolCallingAdvisor.DEFAULT_ORDER - 100)
              .build();
    }
  }

  private final Map<String, Conversation> conversations = new LinkedHashMap<>(16, 0.75f, true);
  private final int maxMessages;
  private final int maxConversations;
  private final long idleMillis;
  private final Clock clock;

  @Autowired
  public ConversationMemory(
      @Value("${app.chat.memory.max-messages:6}") int maxMessages,
      @Value("${app.chat.memory.idle-ttl:PT30M}") Duration idleTtl,
      @Value("${app.chat.memory.max-conversations:1000}") int maxConversations) {
    this(maxMessages, idleTtl, maxConversations, Clock.systemUTC());
  }

  ConversationMemory(int maxMessages, Duration idleTtl, int maxConversations, Clock clock) {
    if (maxMessages < 2 || maxConversations < 1 || idleTtl.toMillis() < 1)
      throw new IllegalArgumentException(
          "Memory needs at least 2 messages, 1 conversation and a positive TTL");
    this.maxMessages = maxMessages;
    this.maxConversations = maxConversations;
    this.idleMillis = idleTtl.toMillis();
    this.clock = clock;
  }

  /** A supplied ID must already exist and belong to this authenticated user. */
  synchronized Conversation open(String owner, String requestedId) {
    long now = clock.millis();
    conversations.values().removeIf(c -> now - c.lastAccess >= idleMillis);
    if (requestedId != null) {
      if (!requestedId.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))
        throw new AuditProblem(400, "INVALID_CONVERSATION_ID");
      var conversation = conversations.get(requestedId);
      if (conversation == null) throw new AuditProblem(404, "CONVERSATION_NOT_FOUND");
      if (!conversation.owner.equals(owner))
        throw new AuditProblem(403, "CONVERSATION_ACCESS_DENIED");
      conversation.lastAccess = now;
      return conversation;
    }
    if (conversations.size() >= maxConversations)
      conversations.remove(conversations.keySet().iterator().next());
    var conversation = new Conversation(owner, maxMessages, now);
    conversations.put(conversation.id, conversation);
    return conversation;
  }
}
