package com.example.hotel.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.hotel.security.ToolExposureService;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.ObjectMapper;

/** Explicit opt-in only. Calls the real model, but never invokes tools or backend APIs. */
@EnabledIfEnvironmentVariable(named = "RUN_LIVE_TOOL_EVALS", matches = "true")
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:live-tool-eval;DB_CLOSE_DELAY=-1")
class LiveToolSelectionEvalTest {
  static final Path REPORT = Path.of("target/tool-evals/results.jsonl");
  @Autowired ChatModel model;
  @Autowired ToolExposureService exposure;
  @Autowired ObjectMapper json;

  @BeforeAll
  static void resetReport() throws Exception {
    Files.createDirectories(REPORT.getParent());
    Files.writeString(REPORT, "");
  }

  @TestFactory
  Stream<DynamicTest> evaluateRealModelToolSelections() {
      ToolSelectionEvaluator evaluator = new ToolSelectionEvaluator(model, exposure, json);
    return ToolSelectionCases.all().stream()
        .map(
            scenario ->
                DynamicTest.dynamicTest(
                    scenario.id(),
                    () -> {
                      var row = new LinkedHashMap<String, Object>();
                      row.put("case", scenario.id());
                      row.put("expectedTool", scenario.expectation().expectedTool());
                      row.put("expectNoTool", scenario.expectation().expectNoTool());
                      row.put(
                          "confirmationRequired", scenario.expectation().confirmationRequired());
                      ToolSelectionEvaluator.Result result;
                      try {
                        result = evaluator.run(scenario);
                      } catch (RuntimeException e) {
                        row.put("passed", false);
                        row.put("error", e.getClass().getSimpleName());
                        write(row);
                        throw new AssertionError(
                            "Model request failed for "
                                + scenario.id()
                                + ": "
                                + e.getClass().getSimpleName());
                      }
                      row.put("model", result.model());
                      row.put(
                          "actualTools",
                          result.actualCalls().stream().map(call -> call.name()).toList());
                      row.put("passed", result.passed());
                      row.put("violations", result.violations());
                      write(row);
                      assertThat(result.violations()).as(scenario.id()).isEmpty();
                    }));
  }

  private void write(Map<String, Object> row) throws Exception {
    Files.writeString(
        REPORT, json.writeValueAsString(row) + System.lineSeparator(), StandardOpenOption.APPEND);
  }
}
