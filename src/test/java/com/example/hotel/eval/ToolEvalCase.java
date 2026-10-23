package com.example.hotel.eval;

import java.util.Map;
import java.util.Set;

/** Expected first model decision. No backend call is executed by these evaluations. */
public record ToolEvalCase(
    String userPrompt,
    String expectedTool,
    Map<String, Object> expectedArguments,
    Set<String> forbiddenTools,
    boolean expectNoTool,
    boolean confirmationRequired) {
  public ToolEvalCase {
    expectedArguments = Map.copyOf(expectedArguments);
    forbiddenTools = Set.copyOf(forbiddenTools);
    if (userPrompt == null || userPrompt.isBlank())
      throw new IllegalArgumentException("Prompt required");
    if (expectNoTool && (expectedTool != null || !expectedArguments.isEmpty()))
      throw new IllegalArgumentException("No-tool cases cannot expect a tool or arguments");
    if (!expectNoTool && (expectedTool == null || expectedTool.isBlank()))
      throw new IllegalArgumentException("Expected tool required");
    if (expectedTool != null && forbiddenTools.contains(expectedTool))
      throw new IllegalArgumentException("Expected tool cannot be forbidden");
  }
}
