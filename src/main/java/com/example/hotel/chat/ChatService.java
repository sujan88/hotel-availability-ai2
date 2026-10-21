package com.example.hotel.chat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.model.tool.ToolCallLimitExceededException;
import org.springframework.stereotype.Service;

@Service
public class ChatService {
  private static final Logger log = LoggerFactory.getLogger(ChatService.class);
  private final ChatClient client;

  public ChatService(ChatClient client) {
    this.client = client;
  }

  public ChatResult chat(String message) {
    try {
      // Spring AI owns the tool loop, conversation history, and tool-call counters.
      var response = client.prompt().user(message).call().chatResponse();
      if (response == null || response.getResults().size() != 1) return failed();
      var generation = response.getResult();
      if (ToolCallLimitExceededException.FINISH_REASON.equals(
          generation.getMetadata().getFinishReason())) {
        return ChatResult.failure("TOOL_CALL_LIMIT_EXCEEDED", generation.getOutput().getText());
      }
      String text = generation.getOutput().getText();
      if (!"stop".equalsIgnoreCase(generation.getMetadata().getFinishReason())
          || text == null
          || text.isBlank()) return failed();
      return ChatResult.success(text);
    } catch (RuntimeException e) {
      log.warn("Chat execution failed: cause={}", e.getClass().getSimpleName());
      return failed();
    }
  }

  private ChatResult failed() {
    return ChatResult.failure(
        "CHAT_EXECUTION_FAILED", "The assistant could not complete this request.");
  }
}
