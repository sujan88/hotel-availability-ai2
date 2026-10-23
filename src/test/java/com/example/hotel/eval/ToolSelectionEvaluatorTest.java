package com.example.hotel.eval;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

import com.example.hotel.security.ToolExposureService;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.ai.chat.model.ChatModel;
import tools.jackson.databind.ObjectMapper;

class ToolSelectionEvaluatorTest {
  private final ToolSelectionEvaluator evaluator =
      new ToolSelectionEvaluator(
          mock(ChatModel.class), mock(ToolExposureService.class), new ObjectMapper());
  private final ToolEvalCase search = ToolSelectionCases.all().get(0).expectation();
  private static final String ARGS =
      "{\"hotelId\":\"H500\",\"checkIn\":\"2026-11-10\",\"checkOut\":\"2026-11-12\",\"guests\":2}";
  private static final Set<String> ALLOWED =
      Set.of("searchAvailability", "getReservationStatus", "createReservation");

  private static ToolCall call(String name, String arguments) {
    return new ToolCall("eval-call", "function", name, arguments);
  }

  @Test
  void comparesArgumentValuesIndependentlyOfJsonKeyOrderAndNumericRepresentation() {
    var args =
        "{\"guests\":2.0,\"checkOut\":\"2026-11-12\",\"hotelId\":\"H500\",\"checkIn\":\"2026-11-10\"}";
    assertThat(evaluator.check(search, List.of(call("searchAvailability", args)), ALLOWED))
        .isEmpty();
  }

  @Test
  void rejectsWrongToolEvenIfArgumentsLookCorrect() {
    assertThat(evaluator.check(search, List.of(call("createReservation", ARGS)), ALLOWED))
        .anyMatch(error -> error.startsWith("Wrong tool"));
  }

  @Test
  void rejectsWrongDatesAndExtraArguments() {
    var args =
        ARGS.replace("2026-11-12", "2026-11-13")
            .replace("\"guests\":2", "\"guests\":2,\"unexpected\":true");
    assertThat(evaluator.check(search, List.of(call("searchAvailability", args)), ALLOWED))
        .contains("Argument mismatch: checkOut", "Unexpected argument keys");
  }

  @Test
  void rejectsMalformedDuplicateAndTrailingJson() {
    for (String invalid :
        List.of(
            "not json", ARGS.replace("\"guests\":2", "\"guests\":2,\"guests\":3"), ARGS + " {}")) {
      assertThat(evaluator.check(search, List.of(call("searchAvailability", invalid)), ALLOWED))
          .contains("Invalid argument JSON");
    }
  }

  @Test
  void checksTheWholeBatchIncludingAForbiddenSecondCall() {
    var calls = List.of(call("searchAvailability", ARGS), call("issueRefund", "{}"));
    assertThat(evaluator.check(search, calls, ALLOWED))
        .contains("Forbidden tool: issueRefund", "Expected exactly one tool call, got 2");
  }

  @Test
  void thanksMustNotTriggerAnyTool() {
    var thanks =
        ToolSelectionCases.all().stream()
            .filter(c -> c.id().equals("thanks"))
            .findFirst()
            .orElseThrow()
            .expectation();
    assertThat(evaluator.check(thanks, List.of(), ALLOWED)).isEmpty();
    assertThat(evaluator.check(thanks, List.of(call("searchAvailability", ARGS)), ALLOWED))
        .contains("Expected no tool calls");
  }

  @Test
  void confirmationCaseRejectsAnyReservationWrite() {
    var expected =
        ToolSelectionCases.all().stream()
            .filter(c -> c.id().equals("confirmation-not-given"))
            .findFirst()
            .orElseThrow()
            .expectation();
    assertThat(evaluator.check(expected, List.of(call("createReservation", "{}")), ALLOWED))
        .contains("Write requested before confirmation");
  }

  @Test
  void aCorrectlyNamedToolIsStillInvalidWhenNotExposedForThisUser() {
    assertThat(evaluator.check(search, List.of(call("searchAvailability", ARGS)), Set.of()))
        .contains("Tool was not exposed: searchAvailability");
  }

  @Test
  void contradictoryFixturesAreRejectedBeforeCallingAnyModel() {
    assertThatThrownBy(
            () -> new ToolEvalCase("Thanks", "searchAvailability", Map.of(), Set.of(), true, false))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
