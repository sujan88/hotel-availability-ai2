package com.example.hotel.audit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.example.hotel.HotelApplication;
import com.example.hotel.chat.ChatResult;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

class AuditPersistenceTest {
  @TempDir Path directory;

  @Test
  void onlyBusinessEventsSurviveInTheDatabaseAndConversationIdsCanBeReusedAfterRestart() {
    String[] args = {
      "--server.port=0",
      "--spring.ai.openai.api-key=test-key",
      "--spring.datasource.url=jdbc:h2:file:"
          + directory.resolve("audit")
          + ";DB_CLOSE_ON_EXIT=FALSE",
      "--audit.log-file=" + directory.resolve("events.jsonl")
    };
    String trace, conversation;
    try (var first = new SpringApplicationBuilder(HotelApplication.class).run(args)) {
      var store = first.getBean(AuditStore.class);
      var frame = store.begin(null);
      trace = frame.turn().traceId;
      conversation = frame.turn().conversationId;
      store.event(frame, "RESERVATION_ACCEPTED", "res-restart", null);
      store.finish(frame, ChatResult.success("not stored"));
    }
    try (var second = new SpringApplicationBuilder(HotelApplication.class).run(args)) {
      var store = second.getBean(AuditStore.class);
      assertThat((java.util.List<?>) store.events(trace, null, null).get("events")).hasSize(1);
      var frame = store.begin(conversation);
      assertThat(frame.turn().conversationId).isEqualTo(conversation);
      assertThat(frame.turn().traceId).isNotEqualTo(trace);
      assertThat((java.util.List<?>) store.events(null, null, conversation).get("events"))
          .hasSize(1);
    }
  }

  @Test
  void failedBusinessAuditDoesNotThrowAfterAReservationMayAlreadyExist() {
    var broken =
        new JdbcTemplate() {
          @Override
          public int update(String sql, Object... args) {
            throw new DataAccessResourceFailureException("private exception content");
          }
        };
    var store = new AuditStore(broken);
    var frame = store.begin(null);
    assertThatCode(() -> store.event(frame, "RESERVATION_ACCEPTED", "res-accepted", null))
        .doesNotThrowAnyException();
    assertThat(frame.turn().complete.get()).isFalse();
  }

  @Test
  void operationalTracingNeverTouchesTheDatabase() {
    var database = mock(JdbcTemplate.class);
    var store = new AuditStore(database);
    var frame = store.begin(null);
    var model = store.startModel(frame, "local-model");
    store.finishModel(frame, model, null, "MODEL_CALL_FAILED");
    store.finish(frame, ChatResult.failure("CHAT_EXECUTION_FAILED", "not logged"));
    verifyNoInteractions(database);
  }

  @Test
  void investigationBearerTokenIsEnforcedWhenConfigured() {
    var store = TestAuditStore.create();
    var frame = store.begin(null);
    var controller = new AuditController(store, "local-investigation-secret");
    var request = mock(HttpServletRequest.class);
    assertThatThrownBy(() -> controller.events(frame.turn().traceId, null, null, null, request))
        .hasMessage("AUDIT_UNAUTHORIZED");
    assertThatThrownBy(
            () -> controller.events(frame.turn().traceId, null, null, "Bearer wrong", request))
        .hasMessage("AUDIT_UNAUTHORIZED");
    assertThat(
            controller.events(
                frame.turn().traceId, null, null, "Bearer local-investigation-secret", request))
        .containsKey("events");
  }

  @Test
  void emittedJsonLinesContainSearchableMetadataAndNoRawChatContent() throws Exception {
    String secret = "PRIVATE_MESSAGE_NOT_FOR_LOGS";
    var source = directory.resolve("json-test");
    String[] args = {
      "--server.port=0",
      "--spring.ai.openai.api-key=test-key",
      "--spring.datasource.url=jdbc:h2:mem:json-test",
      "--audit.log-file=" + source
    };
    String trace;
    try (var app = new SpringApplicationBuilder(HotelApplication.class).run(args)) {
      var store = app.getBean(AuditStore.class);
      var frame = store.begin(null);
      trace = frame.turn().traceId;
      store.finish(frame, ChatResult.success(secret));
    }
    var mapper = new ObjectMapper();
    var lines = Files.readAllLines(source);
    assertThat(lines).hasSize(2);
    var first = mapper.readTree(lines.get(0));
    var last = mapper.readTree(lines.get(1));
    assertThat(first.path("traceId").asText()).isEqualTo(trace);
    assertThat(last.path("event").asText()).isEqualTo("turn.completed");
    assertThat(last.path("totalLatencyMs").asLong()).isGreaterThanOrEqualTo(0);
    assertThat(String.join("\n", lines)).doesNotContain(secret);
  }
}
