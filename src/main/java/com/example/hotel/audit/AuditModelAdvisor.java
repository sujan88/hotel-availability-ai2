package com.example.hotel.audit;

import org.springframework.ai.chat.client.*;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.*;
import org.springframework.stereotype.Component;

/** Inside the native tool loop: structured events for each actual synchronous model invocation. */
@Component
public class AuditModelAdvisor implements CallAdvisor {
  private final AuditStore store;

  public AuditModelAdvisor(AuditStore store) {
    this.store = store;
  }

  @Override
  public String getName() {
    return "Model audit";
  }

  @Override
  public int getOrder() {
    return ToolCallingAdvisor.DEFAULT_ORDER + 100;
  }

  @Override
  public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
    var frame = AuditContext.current();
    if (frame == null) return chain.nextCall(request);
    var span =
        store.startModel(
            frame,
            request.prompt().getOptions() == null
                ? null
                : request.prompt().getOptions().getModel());
    if (request.prompt().getOptions()
        instanceof org.springframework.ai.model.tool.ToolCallingChatOptions options)
      span.fields()
          .put(
              "availableTools",
              options.getToolCallbacks().stream()
                  .map(callback -> callback.getToolDefinition().name())
                  .distinct()
                  .sorted()
                  .toList());
    frame.turn().lastModelCallId = span.id();
    try (var scope = AuditContext.open(frame.withModel(span.id()))) {
      try {
        var result = chain.nextCall(request);
        var response = result == null ? null : result.chatResponse();
        store.finishModel(frame, span, response, response == null ? "EMPTY_MODEL_RESPONSE" : null);
        return result;
      } catch (RuntimeException e) {
        store.finishModel(frame, span, null, "MODEL_CALL_FAILED");
        throw e;
      }
    }
  }
}
