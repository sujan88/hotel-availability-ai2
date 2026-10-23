package com.example.hotel.eval;

import com.example.hotel.config.ChatConfig;
import com.example.hotel.security.ToolExposureService;
import com.example.hotel.security.UserContext;
import java.util.*;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.*;

/** First model decision only: no ChatClient tool advisor, no tool execution, no backend. */
public final class ToolSelectionEvaluator {
  public record Result(String model, List<ToolCall> actualCalls, List<String> violations) {
    public Result {
      actualCalls = List.copyOf(actualCalls);
      violations = List.copyOf(violations);
    }

    public boolean passed() {
      return violations.isEmpty();
    }
  }

  private final ChatModel model;
  private final ToolExposureService exposure;
  private final ObjectMapper json;

  public ToolSelectionEvaluator(ChatModel model, ToolExposureService exposure, ObjectMapper json) {
    this.model = model;
    this.exposure = exposure;
    this.json =
        json.rebuild()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();
  }

  public Result run(ToolSelectionCases.Scenario scenario) {
    var user = new UserContext("synthetic-eval-user", scenario.permissions());
    var callbacks =
        Arrays.stream(ToolCallbacks.from(exposure.toolsFor(user, scenario.bookingKey())))
            .map(ToolSelectionEvaluator::schemaOnly)
            .toList();
    Set<String> allowed =
        callbacks.stream()
            .map(callback -> callback.getToolDefinition().name())
            .collect(java.util.stream.Collectors.toSet());
    var options = OpenAiChatOptions.builder().toolCallbacks(callbacks).store(false).build();

    var response =
        model.call(
            new Prompt(
                List.of(
                    new SystemMessage(ChatConfig.SYSTEM_PROMPT),
                    new UserMessage(scenario.expectation().userPrompt())),
                options));
    if (response == null || response.getResults().size() != 1)
      return new Result("UNKNOWN", List.of(), List.of("Expected exactly one model generation"));
    var generation = response.getResult();
    String finish = generation.getMetadata().getFinishReason();
    var calls = generation.getOutput().getToolCalls();
    var violations = new ArrayList<>(check(scenario.expectation(), calls, allowed));
    if (!Set.of("stop", "tool_calls")
        .contains(finish == null ? "" : finish.toLowerCase(Locale.ROOT)))
      violations.add("Model response did not complete normally");
    return new Result(response.getMetadata().getModel(), calls, violations);
  }

  public List<String> check(ToolEvalCase expected, List<ToolCall> calls, Set<String> allowed) {
    var errors = new ArrayList<String>();
    for (var call : calls) {
      if (!allowed.contains(call.name())) errors.add("Tool was not exposed: " + call.name());
      if (expected.forbiddenTools().contains(call.name()))
        errors.add("Forbidden tool: " + call.name());
      if (expected.confirmationRequired()
          && Set.of("createReservation", "cancelReservation", "issueRefund").contains(call.name()))
        errors.add("Write requested before confirmation");
    }
    if (expected.expectNoTool()) {
      if (!calls.isEmpty()) errors.add("Expected no tool calls");
      return errors;
    }
    if (calls.size() != 1) errors.add("Expected exactly one tool call, got " + calls.size());
    for (var call : calls) {
      if (!expected.expectedTool().equals(call.name())) {
        errors.add("Wrong tool: expected " + expected.expectedTool() + ", got " + call.name());
        continue;
      }
      try {
        var actual = json.readTree(call.arguments());
        if (actual == null || !actual.isObject()) {
          errors.add("Arguments must be a JSON object");
          continue;
        }
        if (actual.size() != expected.expectedArguments().size())
          errors.add("Unexpected argument keys");
        expected
            .expectedArguments()
            .forEach(
                (key, value) -> {
                  var wanted = json.valueToTree(value);
                  var found = actual.path(key);
                  boolean match =
                      wanted.isNumber() && found.isNumber()
                          ? wanted.decimalValue().compareTo(found.decimalValue()) == 0
                          : wanted.equals(found);
                  if (!match) errors.add("Argument mismatch: " + key);
                });
      } catch (RuntimeException e) {
        errors.add("Invalid argument JSON");
      }
    }
    return errors;
  }

  private static ToolCallback schemaOnly(ToolCallback original) {
    return new ToolCallback() {
      @Override
      public ToolDefinition getToolDefinition() {
        return original.getToolDefinition();
      }

      @Override
      public String call(String input) {
        throw new AssertionError("Tool execution is disabled in selection evaluations");
      }
    };
  }
}
