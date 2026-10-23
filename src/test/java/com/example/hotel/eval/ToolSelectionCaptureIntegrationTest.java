package com.example.hotel.eval;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.verifyNoInteractions;

import com.example.hotel.client.HotelApiClient;
import com.example.hotel.config.ChatConfig;
import com.example.hotel.security.ToolExposureService;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.*;

/** Real OpenAI HTTP adapter, scripted local responses: tests capture, not LLM intelligence. */
@SpringBootTest(properties = "spring.ai.openai.api-key=test-key")
class ToolSelectionCaptureIntegrationTest {
  static final ObjectMapper JSON = new ObjectMapper();
  static final AtomicInteger requests = new AtomicInteger();
  static volatile String mode = "search";
  static volatile JsonNode sent;
  static final HttpServer provider;

  static {
    try {
      provider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      provider.createContext(
          "/v1/chat/completions",
          exchange -> {
            sent = JSON.readTree(exchange.getRequestBody());
            requests.incrementAndGet();
            boolean tool = Set.of("search", "wrong-date", "booking").contains(mode);
            Map<String, Object> message;
            if (tool) {
              String name = mode.equals("booking") ? "createReservation" : "searchAvailability";
              String args =
                  JSON.writeValueAsString(
                      mode.equals("booking")
                          ? ToolSelectionCases.BOOKING_ARGS
                          : ToolSelectionCases.SEARCH_ARGS);
              if (mode.equals("wrong-date")) args = args.replace("2026-11-12", "2026-11-13");
              message =
                  Map.of(
                      "role",
                      "assistant",
                      "content",
                      "",
                      "tool_calls",
                      List.of(
                          Map.of(
                              "id",
                              "capture-test-call",
                              "type",
                              "function",
                              "function",
                              Map.of("name", name, "arguments", args))));
            } else message = Map.of("role", "assistant", "content", "You're welcome.");
            var response =
                Map.of(
                    "id",
                    "eval-test",
                    "object",
                    "chat.completion",
                    "created",
                    1,
                    "model",
                    "eval-test-model",
                    "choices",
                    List.of(
                        Map.of(
                            "index",
                            0,
                            "message",
                            message,
                            "finish_reason",
                            mode.equals("truncated") ? "length" : tool ? "tool_calls" : "stop")));
            var bytes = JSON.writeValueAsBytes(response);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
          });
      provider.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry properties) {
    properties.add(
        "spring.ai.openai.base-url",
        () -> "http://127.0.0.1:" + provider.getAddress().getPort() + "/v1");
  }

  @Autowired ChatModel model;
  @Autowired ToolExposureService exposure;
  @MockitoBean HotelApiClient backend;

  @BeforeEach
  void reset() {
    requests.set(0);
    mode = "search";
    sent = null;
  }

  @AfterEach
  void noBusinessCalls() {
    verifyNoInteractions(backend);
  }

  @AfterAll
  static void stop() {
    provider.stop(0);
  }

  ToolSelectionEvaluator.Result run(String id) {
    var scenario =
        ToolSelectionCases.all().stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow();
    return new ToolSelectionEvaluator(model, exposure, JSON).run(scenario);
  }

  @Test
  void capturesSearchFromActualModelAdapterWithoutExecutingIt() {
    var result = run("search-iso-dates");
    assertThat(result.violations()).isEmpty();
    assertThat(result.actualCalls())
        .singleElement()
        .satisfies(call -> assertThat(call.name()).isEqualTo("searchAvailability"));
    assertThat(requests.get()).isOne();
    assertThat(sent.path("messages").get(0).path("content").asText())
        .isEqualTo(ChatConfig.SYSTEM_PROMPT);
    assertThat(sent.path("store").asBoolean(true)).isFalse();
  }

  @Test
  void capturesBookingButCannotCreateAReservation() {
    mode = "booking";
    var result = run("confirmed-booking");
    assertThat(result.violations()).isEmpty();
    assertThat(result.actualCalls())
        .singleElement()
        .satisfies(call -> assertThat(call.name()).isEqualTo("createReservation"));
    assertThat(requests.get()).isOne();
  }

  @Test
  void thanksIsGradedAsNoTool() {
    mode = "thanks";
    var result = run("thanks");
    assertThat(result.violations()).isEmpty();
    assertThat(result.actualCalls()).isEmpty();
    assertThat(requests.get()).isOne();
  }

  @Test
  void wrongModelArgumentsFailTheEvaluation() {
    mode = "wrong-date";
    assertThat(run("search-iso-dates").violations()).contains("Argument mismatch: checkOut");
  }

  @Test
  void permissionFilteredSchemasAreUsedAndUnavailableToolIsRejected() {
    var result = run("chat-only-cannot-search");
    assertThat(sent.path("tools").size()).isZero();
    assertThat(result.passed()).isFalse();
    assertThat(result.violations()).contains("Tool was not exposed: searchAvailability");
  }

  @Test
  void aTruncatedResponseIsNotMistakenForASuccessfulNoToolDecision() {
    mode = "truncated";
    assertThat(run("thanks").violations()).contains("Model response did not complete normally");
  }
}
