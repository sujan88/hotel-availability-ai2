package com.example.hotel.audit;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sun.net.httpserver.*;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.*;

/** Real OpenAI HTTP adapter, real native tool loop, real JDBC audit and real HTTP retries. */
@SpringBootTest(
    properties = {
      "spring.ai.openai.api-key=test-key",
      "hotel.api.read-timeout=150ms",
      "resilience4j.retry.instances.availability.waitDuration=5ms",
      "resilience4j.retry.instances.reservation.waitDuration=5ms"
    })
@AutoConfigureMockMvc
class AuditFlowIntegrationTest {
  static final ObjectMapper JSON = new ObjectMapper();
  static volatile String mode = "success";
  static final AtomicInteger attempts = new AtomicInteger();
  static final List<String> traceHeaders = new CopyOnWriteArrayList<>();
  static final List<String> writeKeys = new CopyOnWriteArrayList<>();
  static final ExecutorService threads = Executors.newCachedThreadPool();
  static final HttpServer server;
  static final String TOKEN = "pm_private_audit_token",
      KEY = "private-audit-booking-key",
      EMAIL = "private.audit@example.com";

  static {
    try {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.setExecutor(threads);
      server.createContext(
          "/v1/chat/completions",
          exchange -> {
            var request = JSON.readTree(exchange.getRequestBody());
            boolean hadTool = false;
            for (var message : request.path("messages"))
              if (message.path("role").asText().equals("tool")) hadTool = true;
            if (mode.equals("model-failure") && hadTool) {
              respond(
                  exchange,
                  500,
                  "{\"error\":{\"message\":\"private provider"
                      + " failure\",\"type\":\"server_error\"}}");
              return;
            }
            boolean create =
                mode.startsWith("create")
                    || mode.equals("duplicate")
                    || mode.equals("model-failure");
            boolean call =
                !mode.equals("plain")
                    && (!hadTool || mode.equals("duplicate") || mode.equals("limit"));
            Map<String, Object> message;
            if (call) {
              String name =
                  mode.equals("status")
                      ? "getReservationStatus"
                      : create ? "createReservation" : "searchAvailability";
              String args =
                  mode.equals("status")
                      ? "{\"reservationId\":\"res-audit-123\"}"
                      : create
                          ? "{\"hotelId\":\"h1\",\"roomId\":\"r1\",\"checkIn\":\"2027-01-01\",\"checkOut\":\"2027-01-02\",\"guests\":2,\"guestName\":\"Private"
                                + " Audit Guest\",\"guestEmail\":\""
                              + EMAIL
                              + "\"}"
                          : "{\"hotelId\":\"h1\",\"checkIn\":\"2027-01-01\",\"checkOut\":\"2027-01-02\",\"guests\":2}";
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
                              AuditContext.id(),
                              "type",
                              "function",
                              "function",
                              Map.of("name", name, "arguments", args))));
            } else
              message =
                  Map.of(
                      "role",
                      "assistant",
                      "content",
                      "Private model wording deliberately excludes any reservation identifier.");
            respond(
                exchange,
                200,
                JSON.writeValueAsString(
                    Map.of(
                        "id",
                        "local-model-id",
                        "object",
                        "chat.completion",
                        "created",
                        1,
                        "model",
                        "gpt-4o-mini",
                        "choices",
                        List.of(
                            Map.of(
                                "index",
                                0,
                                "message",
                                message,
                                "finish_reason",
                                call ? "tool_calls" : "stop")),
                        "usage",
                        Map.of(
                            "prompt_tokens", 100, "completion_tokens", 20, "total_tokens", 120))));
          });
      server.createContext(
          "/hotels/",
          exchange -> {
            exchange.getRequestBody().readAllBytes();
            int attempt = attempts.incrementAndGet();
            traceHeaders.add(exchange.getRequestHeaders().getFirst("X-Trace-Id"));
            if (mode.equals("timeout"))
              try {
                Thread.sleep(500);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
            int code = mode.equals("bad") ? 400 : mode.equals("retry") && attempt < 3 ? 503 : 200;
            respond(
                exchange,
                code,
                code == 200
                    ? "[{\"roomId\":\"r1\",\"roomType\":\"King\",\"price\":120,\"currency\":\"USD\"}]"
                    : "{}");
          });
      server.createContext(
          "/reservations",
          exchange -> {
            if (exchange.getRequestMethod().equals("GET")) {
              attempts.incrementAndGet();
              traceHeaders.add(exchange.getRequestHeaders().getFirst("X-Trace-Id"));
              respond(
                  exchange, 200, "{\"reservationId\":\"res-audit-123\",\"status\":\"CONFIRMED\"}");
              return;
            }

            String body =
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            int attempt = attempts.incrementAndGet();
            traceHeaders.add(exchange.getRequestHeaders().getFirst("X-Trace-Id"));
            writeKeys.add(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
            if (!body.contains(TOKEN)) {
              respond(exchange, 402, "{}");
              return;
            }
            String response =
                "{\"reservationId\":\"res-audit-123\",\"status\":\"PENDING_PAYMENT\"}";
            if (mode.equals("create-body-timeout") && attempt == 1) {
              byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
              exchange.getResponseHeaders().set("Content-Type", "application/json");
              exchange.sendResponseHeaders(200, bytes.length);
              exchange.getResponseBody().write(bytes, 0, 1);
              exchange.getResponseBody().flush();
              try {
                Thread.sleep(500);
                exchange.getResponseBody().write(bytes, 1, bytes.length - 1);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              } catch (java.io.IOException ignored) {
              } finally {
                exchange.close();
              }
            } else respond(exchange, 202, response);
          });
      server.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  static void respond(HttpExchange exchange, int code, String body) throws java.io.IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    try {
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(code, bytes.length);
      exchange.getResponseBody().write(bytes);
    } catch (java.io.IOException ignored) {
    } finally {
      exchange.close();
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add(
        "spring.ai.openai.base-url",
        () -> "http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
    registry.add("hotel.api.base-url", () -> "http://127.0.0.1:" + server.getAddress().getPort());
    registry.add("spring.datasource.url", () -> "jdbc:h2:mem:audit-flow;DB_CLOSE_DELAY=-1");
  }

  ListAppender<ILoggingEvent> logged;

  @AfterEach
  void stopCapture() {
    ((Logger) LoggerFactory.getLogger(AuditStore.class)).detachAppender(logged);
    logged.stop();
  }

  @Autowired MockMvc mvc;
  @Autowired AuditStore store;
  @Autowired JdbcTemplate db;
  @Autowired CircuitBreakerRegistry circuits;

  @BeforeEach
  void reset() {
    logged = new ListAppender<>();
    logged.list = new CopyOnWriteArrayList<>();
    logged.start();
    ((Logger) LoggerFactory.getLogger(AuditStore.class)).addAppender(logged);
    mode = "success";
    attempts.set(0);
    traceHeaders.clear();
    writeKeys.clear();
    circuits.circuitBreaker("availability").reset();
    circuits.circuitBreaker("reservation").reset();
  }

  @AfterAll
  static void stop() {
    server.stop(0);
    threads.shutdownNow();
  }

  JsonNode chat(String conversation, boolean write) throws Exception {
    var request =
        post("/api/chat")
            .contentType("application/json")
            .content("{\"message\":\"DO_NOT_PERSIST_PRIVATE_MESSAGE\"}");
    if (conversation != null) request.header("Conversation-Id", conversation);
    if (write) request.header("Idempotency-Key", KEY).header("Payment-Method-Token", TOKEN);
    return JSON.readTree(
        mvc.perform(request)
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString());
  }

  java.util.List<java.util.Map<String, Object>> logRecords() {
    return logged.list.stream()
        .map(
            event -> {
              java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
              if (event.getKeyValuePairs() != null)
                event.getKeyValuePairs().forEach(pair -> row.put(pair.key, pair.value));
              return row;
            })
        .toList();
  }

  JsonNode trace(JsonNode response) {
    String id = response.path("traceId").asText();
    var records = logRecords().stream().filter(row -> id.equals(row.get("traceId"))).toList();
    java.util.function.Function<String, java.util.List<java.util.Map<String, Object>>> kind =
        name -> records.stream().filter(row -> name.equals(row.get("event"))).toList();
    var turn = kind.apply("turn.completed");
    return JSON.valueToTree(
        java.util.Map.of(
            "turn",
            turn.isEmpty() ? java.util.Map.of() : turn.get(turn.size() - 1),
            "modelCalls",
            kind.apply("model.call"),
            "toolExecutions",
            kind.apply("tool.call"),
            "downstreamCalls",
            kind.apply("downstream.call"),
            "events",
            records.stream()
                .filter(
                    row ->
                        java.util.Set.of("audit.event", "business.event")
                            .contains(row.get("event")))
                .toList()));
  }

  @Test
  void linksEveryModelToolAndHttpAttemptWithReportedUsage() throws Exception {
    var response = chat(null, false);
    var data = trace(response);
    assertThat(response.path("auditComplete").asBoolean()).isTrue();
    assertThat(data.path("modelCalls").size()).isEqualTo(2);
    assertThat(data.path("modelCalls").get(0).path("requestedTools").get(0).asText())
        .isEqualTo("searchAvailability");
    assertThat(data.path("modelCalls").get(0).path("availableTools").toString())
        .contains("searchAvailability")
        .doesNotContain("createReservation");
    assertThat(data.path("modelCalls").get(1).path("requestedTools").size()).isZero();
    assertThat(data.path("turn").path("toolsCalled").get(0).asText())
        .isEqualTo("searchAvailability");
    assertThat(data.path("turn").path("toolCallCount").asInt()).isOne();
    assertThat(data.path("downstreamCalls").get(0).path("toolName").asText())
        .isEqualTo("searchAvailability");
    assertThat(data.path("toolExecutions").size()).isOne();
    assertThat(data.path("downstreamCalls").size()).isOne();
    var tool = data.path("toolExecutions").get(0);
    assertThat(tool.path("modelCallId").asText())
        .isEqualTo(data.path("modelCalls").get(0).path("modelCallId").asText());
    assertThat(data.path("downstreamCalls").get(0).path("toolCallId").asText())
        .isEqualTo(tool.path("toolCallId").asText());
    assertThat(data.path("turn").path("totalTokens").asInt()).isEqualTo(240);
    assertThat(data.path("turn").path("status").asText()).isEqualTo("COMPLETED");
    assertThat(traceHeaders).containsOnly(response.path("traceId").asText());
    assertThat(AuditContext.current()).isNull();
    assertThat(MDC.get("traceId")).isNull();
    assertThat(MDC.get("toolName")).isNull();
  }

  @Test
  void recordsAllRetryAttemptsAndPropagatesTraceAcrossRetryThreads() throws Exception {
    mode = "retry";
    var response = chat(null, false);
    var data = trace(response);
    assertThat(response.path("success").asBoolean()).isTrue();
    assertThat(data.path("toolExecutions").size()).isOne();
    assertThat(data.path("downstreamCalls").size()).isEqualTo(3);
    assertThat(data.path("downstreamCalls").get(0).path("httpStatus").asInt()).isEqualTo(503);
    assertThat(data.path("downstreamCalls").get(2).path("httpStatus").asInt()).isEqualTo(200);
    assertThat(data.path("downstreamCalls").get(2).path("attemptNo").asInt()).isEqualTo(3);
    assertThat(traceHeaders).hasSize(3).containsOnly(response.path("traceId").asText());
    for (var call : data.path("downstreamCalls"))
      assertThat(call.path("toolName").asText()).isEqualTo("searchAvailability");
  }

  @Test
  void timeoutAttemptsAndFinalToolFailureRemainVisible() throws Exception {
    mode = "timeout";
    var response = chat(null, false);
    var data = trace(response);
    assertThat(data.path("downstreamCalls").size()).isEqualTo(3);
    for (var call : data.path("downstreamCalls"))
      assertThat(call.path("status").asText()).isEqualTo("FAILED");
    assertThat(data.path("toolExecutions").get(0).path("status").asText()).isEqualTo("FAILED");
  }

  @Test
  void nonRetryable400IsOneFailedAttempt() throws Exception {
    mode = "bad";
    var data = trace(chat(null, false));
    assertThat(data.path("downstreamCalls").size()).isOne();
    assertThat(data.path("downstreamCalls").get(0).path("httpStatus").asInt()).isEqualTo(400);
  }

  @Test
  void openCircuitProducesToolFailureWithoutInventingAnHttpAttempt() throws Exception {
    circuits.circuitBreaker("availability").transitionToOpenState();
    var data = trace(chat(null, false));
    assertThat(data.path("downstreamCalls").size()).isZero();
    assertThat(data.path("toolExecutions").get(0).path("errorCode").asText())
        .isEqualTo("CIRCUIT_OPEN");
  }

  @Test
  void duplicateIsRejectedBeforeSecondHttpCallAndRecordedAsSuch() throws Exception {
    mode = "duplicate";
    var response = chat(null, true);
    var data = trace(response);
    assertThat(response.path("errorCode").asText()).isEqualTo("DUPLICATE_TOOL_CALL");
    assertThat(response.path("reservations").get(0).path("reservationId").asText())
        .isEqualTo("res-audit-123");
    assertThat(data.path("toolExecutions").size()).isEqualTo(2);
    assertThat(data.path("downstreamCalls").size()).isOne();
    assertThat(data.path("toolExecutions").get(1).path("duplicateDetected").asBoolean()).isTrue();
    assertThat(data.path("turn").path("toolCallCount").asInt()).isOne();
    assertThat(data.path("turn").path("toolsCalled").get(0).asText())
        .isEqualTo("createReservation");
    assertThat(data.path("events").toString())
        .contains("TOOL_REJECTED", "RESERVATION_ACCEPTED")
        .doesNotContain("PAYMENT_SUCCESS", "USER_CONFIRMED");
  }

  @Test
  void nativeLimitHasAnAuditEventAndNoExtraHttpCall() throws Exception {
    mode = "limit";
    var response = chat(null, false);
    var data = trace(response);
    assertThat(response.path("errorCode").asText()).isEqualTo("TOOL_CALL_LIMIT_EXCEEDED");
    assertThat(data.path("toolExecutions").size()).isEqualTo(2);
    assertThat(data.path("downstreamCalls").size()).isEqualTo(2);
    assertThat(data.path("events").toString()).contains("TOOL_LIMIT_EXCEEDED");
  }

  @Test
  void paymentTokenAndPiiNeverEnterAnyAuditTable() throws Exception {
    mode = "create";
    var response = chat(null, true);
    assertThat(response.path("reservations").size()).isOne();
    String traceId = response.path("traceId").asText();
    String rows =
        JSON.writeValueAsString(
            db.queryForList("SELECT * FROM reservation_audit WHERE trace_id=?", traceId));
    String logs = JSON.writeValueAsString(logRecords());
    assertThat(rows + logs)
        .doesNotContain(
            TOKEN,
            KEY,
            EMAIL,
            "Private Audit Guest",
            "DO_NOT_PERSIST_PRIVATE_MESSAGE",
            "Private model wording");
  }

  @Test
  void responseBodyTimeoutAfter200IsAFailedAttemptAndKeepsTheWriteKey() throws Exception {
    mode = "create-body-timeout";
    var response = chat(null, true);
    var data = trace(response);
    assertThat(response.path("reservations").get(0).path("reservationId").asText())
        .isEqualTo("res-audit-123");
    assertThat(data.path("downstreamCalls").size()).isEqualTo(2);
    assertThat(data.path("downstreamCalls").get(0).path("httpStatus").asInt()).isEqualTo(200);
    assertThat(data.path("downstreamCalls").get(0).path("status").asText()).isEqualTo("FAILED");
    assertThat(writeKeys).hasSize(2).containsOnly(KEY);
  }

  @Test
  void modelFailureIsRecordedWhileKnownReservationIdSurvives() throws Exception {
    mode = "model-failure";
    var response = chat(null, true);
    var data = trace(response);
    assertThat(response.path("success").asBoolean()).isFalse();
    assertThat(response.path("reservations").size()).isOne();
    assertThat(data.path("modelCalls").get(1).path("status").asText()).isEqualTo("FAILED");
    assertThat(data.path("turn").path("status").asText()).isEqualTo("FAILED");
  }

  @Test
  void conversationGroupsTurnsButDoesNotReuseTraceIds() throws Exception {
    mode = "plain";
    var first = chat(null, false);
    assertThat(trace(first).path("turn").path("toolCallCount").asInt()).isZero();
    assertThat(trace(first).path("turn").path("toolsCalled").size()).isZero();
    var second = chat(first.path("conversationId").asText(), false);
    assertThat(second.path("traceId").asText()).isNotEqualTo(first.path("traceId").asText());
    assertThat(
            logRecords().stream()
                .filter(
                    row ->
                        "user.message.received".equals(row.get("event"))
                            && first
                                .path("conversationId")
                                .asText()
                                .equals(row.get("conversationId"))))
        .hasSize(2);
    assertThat(second.path("conversationId").asText())
        .isEqualTo(first.path("conversationId").asText());
  }

  @Test
  void concurrentTurnsAndRequestsKeepTheirContextsSeparate() throws Exception {
    mode = "plain";
    var first = chat(null, false);
    String conversation = first.path("conversationId").asText();
    var pool = Executors.newFixedThreadPool(2);
    try {
      var a = pool.submit(() -> chat(conversation, false));
      var b = pool.submit(() -> chat(conversation, false));
      var one = a.get(10, TimeUnit.SECONDS);
      var two = b.get(10, TimeUnit.SECONDS);
      assertThat(one.path("traceId").asText()).isNotEqualTo(two.path("traceId").asText());
      assertThat(trace(one).path("modelCalls").size()).isOne();
      assertThat(trace(two).path("modelCalls").size()).isOne();
      assertThat(
              logRecords().stream()
                  .filter(
                      row ->
                          "user.message.received".equals(row.get("event"))
                              && conversation.equals(row.get("conversationId"))))
          .hasSize(3);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void businessEventApiIsLocalAndRemovedTraceEndpointsReturnNotFound() throws Exception {
    mvc.perform(get("/api/audit/events").param("traceId", "invalid"))
        .andExpect(status().isBadRequest());
    mvc.perform(get("/api/audit/events").param("traceId", "a".repeat(32)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.events").isEmpty());
    mvc.perform(
            get("/api/audit/events")
                .param("traceId", "a".repeat(32))
                .with(
                    request -> {
                      request.setRemoteAddr("10.0.0.5");
                      return request;
                    }))
        .andExpect(status().isForbidden());
    mvc.perform(get("/api/audit/traces/" + "a".repeat(32))).andExpect(status().isNotFound());
    mvc.perform(get("/api/audit/conversations/" + AuditContext.id()))
        .andExpect(status().isNotFound());
    mvc.perform(
            post("/api/chat")
                .header("Conversation-Id", "invalid")
                .contentType("application/json")
                .content("{\"message\":\"hello\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void statusReadsCaptureObservedBusinessStateWithoutClaimingAPaymentEvent() throws Exception {
    mode = "status";
    var response = chat(null, false);
    var data = trace(response);
    assertThat(response.path("reservations").get(0).path("status").asText()).isEqualTo("CONFIRMED");
    assertThat(data.path("toolExecutions").get(0).path("resultStatus").asText())
        .isEqualTo("CONFIRMED");
    assertThat(data.path("events").toString())
        .contains("RESERVATION_STATUS_OBSERVED")
        .doesNotContain("PAYMENT_SUCCESS");
  }

  @Test
  void ordinarySearchWritesNoDatabaseRowsAndSchemaHasOnlyOneAuditTable() throws Exception {
    int before = db.queryForObject("SELECT COUNT(*) FROM reservation_audit", Integer.class);
    chat(null, false);
    assertThat(db.queryForObject("SELECT COUNT(*) FROM reservation_audit", Integer.class))
        .isEqualTo(before);
    assertThat(
            db.queryForList(
                "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA='PUBLIC'",
                String.class))
        .containsExactly("RESERVATION_AUDIT");
  }

  @Test
  void acceptedBookingPersistsOneBusinessEventWhileModelAndRetryDetailsRemainInLogs()
      throws Exception {
    mode = "create";
    var response = chat(null, true);
    String id = response.path("traceId").asText();
    mvc.perform(get("/api/audit/events").param("traceId", id))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.events.length()").value(1))
        .andExpect(jsonPath("$.events[0].eventType").value("RESERVATION_ACCEPTED"));
    assertThat(trace(response).path("modelCalls").size()).isEqualTo(2);
  }

  @Test
  void actualJsonFileContainsScopedToolEventsAndTurnSummary() throws Exception {
    var response = chat(null, false);
    String id = response.path("traceId").asText();
    var records =
        java.nio.file.Files.readAllLines(java.nio.file.Path.of("target/test-audit.jsonl")).stream()
            .map(JSON::readTree)
            .filter(row -> id.equals(row.path("traceId").asText()))
            .toList();
    assertThat(records.stream().filter(row -> row.path("event").asText().equals("tool.call")))
        .singleElement()
        .satisfies(
            row -> assertThat(row.path("toolName").asText()).isEqualTo("searchAvailability"));
    assertThat(records.stream().filter(row -> row.path("event").asText().equals("turn.completed")))
        .singleElement()
        .satisfies(
            row ->
                assertThat(row.path("toolsCalled").get(0).asText())
                    .isEqualTo("searchAvailability"));
    assertThat(records.stream().filter(row -> row.path("event").asText().equals("downstream.call")))
        .singleElement()
        .satisfies(
            row -> assertThat(row.path("toolName").asText()).isEqualTo("searchAvailability"));
  }

  @Test
  void consoleShowsCompactOrderedChatToolServiceAndExternalApiFlow() throws Exception {
    var response = chat(null, false);
    String id = response.path("traceId").asText();
    var logger = (Logger) LoggerFactory.getLogger(AuditStore.class);
    var console =
        (ch.qos.logback.core.ConsoleAppender<ILoggingEvent>) logger.getAppender("AUDIT_CONSOLE");
    var encoder = (ch.qos.logback.classic.encoder.PatternLayoutEncoder) console.getEncoder();
    var lines =
        logged.list.stream()
            .filter(event -> event.getFormattedMessage().endsWith("traceId=" + id))
            .map(event -> encoder.getLayout().doLayout(event).stripTrailing())
            .toList();
    assertThat(lines).hasSize(11);
    assertThat(lines)
        .allSatisfy(
            line -> {
              assertThat(line).startsWith("[").hasSizeLessThan(320);
              assertThat(line)
                  .doesNotContain(
                      "logger_name",
                      "thread_name",
                      "conversationId",
                      "modelCallId",
                      TOKEN,
                      EMAIL,
                      "DO_NOT_PERSIST_PRIVATE_MESSAGE");
            });
    assertThat(lines.stream().map(line -> line.substring(0, line.indexOf(']') + 1)))
        .containsExactly(
            "[CHAT]",
            "[MODEL]",
            "[MODEL]",
            "[TOOL]",
            "[SERVICE]",
            "[EXTERNAL API]",
            "[EXTERNAL API]",
            "[TOOL]",
            "[MODEL]",
            "[MODEL]",
            "[CHAT]");
    assertThat(lines.get(4))
        .contains("service.call", "AvailabilityService.search", "searchAvailability");
    assertThat(lines.get(6)).contains("downstream.call", "httpStatus=200");
    assertThat(lines.get(7))
        .contains("tool.call", "toolName=searchAvailability", "status=COMPLETED");
  }
}
