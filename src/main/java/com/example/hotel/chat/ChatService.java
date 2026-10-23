package com.example.hotel.chat;

import com.example.hotel.audit.*;
import com.example.hotel.exception.DuplicateToolCallException;
import com.example.hotel.exception.InvalidReservationToolCallException;
import com.example.hotel.reservation.ReservationExecution;
import com.example.hotel.security.ToolExposureService;
import com.example.hotel.security.UserContext;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.model.tool.ToolCallLimitExceededException;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

@Service
public class ChatService {
  private static final Logger log = LoggerFactory.getLogger(ChatService.class);
  private final ChatClient client;
  private final ToolExposureService toolExposure;
  private final AuditStore audit;
  private final ConversationMemory memory;

  public ChatService(
      ChatClient client,
      ToolExposureService toolExposure,
      AuditStore audit,
      ConversationMemory memory) {
    this.client = client;
    this.toolExposure = toolExposure;
    this.audit = audit;
    this.memory = memory;
  }

  public ChatResult chat(
      String message, String idempotencyKey, String paymentMethodToken, String conversationId) {
    var conversation = memory.open(UserContext.current().username(), conversationId);
    // Serialize turns of this conversation; other users/conversations can run independently.
    synchronized (conversation) {
      var frame = audit.begin(conversation.id);
      try (var scope = AuditContext.open(frame)) {
        ChatResult result = runChat(message, idempotencyKey, paymentMethodToken, conversation);
        audit.finish(frame, result);
        return result.withAudit(frame);
      }
    }
  }

  private ChatResult runChat(
      String message,
      String idempotencyKey,
      String paymentMethodToken,
      ConversationMemory.Conversation conversation) {
    if (paymentMethodToken != null && idempotencyKey == null)
      return ChatResult.failure(
          "INVALID_IDEMPOTENCY_KEY", "Payment context requires an Idempotency-Key.");
    ReservationExecution execution;
    try {
      execution =
          idempotencyKey == null
              ? new ReservationExecution()
              : new ReservationExecution(idempotencyKey, paymentMethodToken);
    } catch (IllegalArgumentException e) {
      return ChatResult.failure("INVALID_IDEMPOTENCY_KEY", e.getMessage());
    }
    return execute(message, execution, conversation).withReservations(execution.results());
  }

  private ChatResult execute(
      String message,
      ReservationExecution execution,
      ConversationMemory.Conversation conversation) {
    try {
      // Spring AI owns memory and the tool loop. Permissions and write tracking are fresh each
      // turn.
      Object[] allowedTools =
          toolExposure.toolsFor(UserContext.current(), execution.canCreateReservation());

      ChatClient.ChatClientRequestSpec prompt =
          client
              .prompt()
              .user(message)
              .advisors(conversation.advisor)
              .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversation.id))
              .options(
                  OpenAiChatOptions.builder()
                      .toolContext(Map.of(ReservationExecution.CONTEXT_KEY, execution)));
      if (allowedTools.length > 0) prompt.tools(allowedTools);
      ChatResponse response = prompt.call().chatResponse();

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
    } catch (DuplicateToolCallException e) {
      return ChatResult.failure(
          "DUPLICATE_TOOL_CALL",
          "An identical reservation was already attempted in this execution. No additional booking"
              + " request was sent. Reconcile or replay using the same Idempotency-Key.");
    } catch (InvalidReservationToolCallException e) {
      return ChatResult.failure("INVALID_RESERVATION_REQUEST", e.getMessage());
    } catch (AccessDeniedException e) {
      return ChatResult.failure("TOOL_ACCESS_DENIED", "Your account cannot use this tool.");
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
