package com.example.hotel.reservation;

import com.example.hotel.audit.AuditStore;
import com.example.hotel.exception.DuplicateToolCallException;
import com.example.hotel.exception.InvalidReservationToolCallException;
import java.util.List;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.*;
import org.springframework.ai.tool.definition.ToolDefinition;

/** Orchestration boundary: reject identical WRITE calls before the tool or backend runs. */
public final class WriteFingerprintingToolCallingManager implements ToolCallingManager {
  private final ToolCallingManager delegate;
  private final ReservationFingerprint fingerprints;
  private final AuditStore audit;

  public WriteFingerprintingToolCallingManager(
      ToolCallingManager delegate, ReservationFingerprint fingerprints, AuditStore audit) {
    this.delegate = delegate;
    this.fingerprints = fingerprints;
    this.audit = audit;
  }

  @Override
  public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions options) {
    return delegate.resolveToolDefinitions(options);
  }

  @Override
  public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse response) {
    var writes =
        response.getResult().getOutput().getToolCalls().stream()
            .filter(c -> c.name().equals("createReservation"))
            .toList();
    try {
      if (!writes.isEmpty()) {
        if (!(prompt.getOptions() instanceof ToolCallingChatOptions options)
            || options.getToolContext() == null
            || !(options.getToolContext().get(ReservationExecution.CONTEXT_KEY)
                instanceof ReservationExecution execution)
            || !execution.canCreateReservation()) throw new InvalidReservationToolCallException();
        execution.claimBatch(writes.stream().map(fingerprints::of).toList());
      }
    } catch (DuplicateToolCallException e) {
      writes.forEach(call -> audit.rejected(call.name(), "DUPLICATE_TOOL_CALL"));
      throw e;
    } catch (InvalidReservationToolCallException e) {
      writes.forEach(call -> audit.rejected(call.name(), "INVALID_RESERVATION_REQUEST"));
      throw e;
    }
    // Native manager still owns total/per-tool counts, tool dispatch and history.
    try {
      return delegate.executeToolCalls(prompt, response);
    } catch (ToolCallLimitExceededException e) {
      audit.event(
          com.example.hotel.audit.AuditContext.current(),
          "TOOL_LIMIT_EXCEEDED",
          null,
          "TOOL_CALL_LIMIT_EXCEEDED");
      throw e;
    }
  }
}
