package com.example.hotel.security;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.example.hotel.client.HotelApiClient;
import com.example.hotel.dto.*;
import com.example.hotel.integration.ReservationService;
import com.example.hotel.tool.ReservationTools;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.*;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(
    properties = {
      "spring.ai.model.chat=none",
      "spring.ai.openai.api-key=test-key",
      "app.security.reader-password=reader-test-password",
      "app.security.booker-password=booker-test-password",
      "app.security.chat-password=chat-test-password"
    })
@AutoConfigureMockMvc
class ToolPermissionsIntegrationTest {
  @TestConfiguration(proxyBeanMethods = false)
  static class Models {
    @Bean
    RecordingModel permissionModel() {
      return new RecordingModel();
    }
  }

  static class RecordingModel implements ChatModel {
    @Override
    public ChatOptions getOptions() {
      return OpenAiChatOptions.builder().build();
    }

    @Override
    public ChatResponse call(Prompt prompt) {
      var options = (ToolCallingChatOptions) prompt.getOptions();
      var names =
          options.getToolCallbacks() == null
              ? List.<String>of()
              : options.getToolCallbacks().stream()
                  .map(callback -> callback.getToolDefinition().name())
                  .sorted()
                  .toList();
      var userText =
          prompt.getInstructions().stream()
              .filter(UserMessage.class::isInstance)
              .map(UserMessage.class::cast)
              .map(UserMessage::getText)
              .findFirst()
              .orElse("");
      boolean hasResult =
          prompt.getInstructions().stream().anyMatch(ToolResponseMessage.class::isInstance);
      AssistantMessage output;
      String finish;
      if (userText.equals("force booking") && !hasResult) {
        output =
            AssistantMessage.builder()
                .content("")
                .toolCalls(
                    List.of(
                        new AssistantMessage.ToolCall(
                            "permission-call",
                            "function",
                            "createReservation",
                            "{\"hotelId\":\"h1\",\"roomId\":\"r1\",\"checkIn\":\"2027-04-10\",\"checkOut\":\"2027-04-11\",\"guests\":2,\"guestName\":\"Alex"
                                + " Guest\",\"guestEmail\":\"alex@example.com\"}")))
                .build();
        finish = "tool_calls";
      } else {
        output = new AssistantMessage(names.toString());
        finish = "stop";
      }
      return ChatResponse.builder()
          .generations(
              List.of(
                  new Generation(
                      output, ChatGenerationMetadata.builder().finishReason(finish).build())))
          .build();
    }
  }

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired ReservationService reservations;
  @Autowired ReservationTools reservationTools;
  @MockitoBean HotelApiClient backend;

  @BeforeEach
  void setup() {
    when(backend.createReservation(any(), any()))
        .thenReturn(new Reservation("res-permitted", "CONFIRMED"));
  }

  String tools(String user, boolean key) throws Exception {
    var request =
        post("/api/chat")
            .with(httpBasic(user, user + "-test-password"))
            .contentType("application/json")
            .content("{\"message\":\"list tools\"}");
    if (key) request.header("Idempotency-Key", "permission-booking-001");
    var result =
        mvc.perform(request)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andReturn();
    return json.readTree(result.getResponse().getContentAsString()).path("message").asText();
  }

  @Test
  void noAuthenticationCannotReachChatEvenWithForgedRoleHeaders() throws Exception {
    mvc.perform(
            post("/api/chat")
                .header("X-Role", "BOOKER")
                .header("Idempotency-Key", "forged-booking-001")
                .contentType("application/json")
                .content("{\"message\":\"force booking\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.errorCode").value("AUTHENTICATION_REQUIRED"));
    verifyNoInteractions(backend);
  }

  @Test
  void badPasswordIsRejected() throws Exception {
    mvc.perform(
            post("/api/chat")
                .with(httpBasic("booker", "wrong"))
                .contentType("application/json")
                .content("{\"message\":\"list tools\"}"))
        .andExpect(status().isUnauthorized());
    verifyNoInteractions(backend);
  }

  @Test
  void readerGetsOnlyReadToolsEvenWithBookingKey() throws Exception {
    assertThat(tools("reader", true)).isEqualTo("[getReservationStatus, searchAvailability]");
    verifyNoInteractions(backend);
  }

  @Test
  void bookerNeedsAKeyForWriteExposure() throws Exception {
    assertThat(tools("booker", false)).isEqualTo("[getReservationStatus, searchAvailability]");
    assertThat(tools("booker", true))
        .isEqualTo("[createReservation, getReservationStatus, searchAvailability]");
  }

  @Test
  void chatOnlyHasNoDefaultToolLeak() throws Exception {
    assertThat(tools("chat", true)).isEqualTo("[]");
    verifyNoInteractions(backend);
  }

  @Test
  void perRequestPermissionsDoNotLeakBetweenUsers() throws Exception {
    assertThat(tools("booker", true)).contains("createReservation");
    assertThat(tools("reader", true)).doesNotContain("createReservation");
    assertThat(tools("chat", true)).isEqualTo("[]");
  }

  @Test
  void concurrentRequestsKeepTheirOwnToolSets() throws Exception {
    var pool = Executors.newFixedThreadPool(3);
    try {
      var tasks =
          List.<Callable<String>>of(
              () -> tools("booker", true), () -> tools("reader", true), () -> tools("chat", true));
      var results = pool.invokeAll(tasks);
      assertThat(results.get(0).get()).contains("createReservation");
      assertThat(results.get(1).get()).doesNotContain("createReservation");
      assertThat(results.get(2).get()).isEqualTo("[]");
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void forgedRoleInBodyAndHeaderDoesNotElevateReader() throws Exception {
    mvc.perform(
            post("/api/chat")
                .with(httpBasic("reader", "reader-test-password"))
                .header("X-Role", "BOOKER")
                .header("Idempotency-Key", "forged-role-key-001")
                .contentType("application/json")
                .content("{\"message\":\"list tools\",\"role\":\"BOOKER\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.message").value("[getReservationStatus, searchAvailability]"));
    verifyNoInteractions(backend);
  }

  @Test
  void modelCannotExecuteAnUnexposedWrite() throws Exception {
    mvc.perform(
            post("/api/chat")
                .with(httpBasic("reader", "reader-test-password"))
                .header("Idempotency-Key", "reader-attempt-001")
                .contentType("application/json")
                .content("{\"message\":\"force booking\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(false));
    verifyNoInteractions(backend);
  }

  @Test
  void authorizedBookingStillUsesToolAndReturnsStructuredReservation() throws Exception {
    mvc.perform(
            post("/api/chat")
                .with(httpBasic("booker", "booker-test-password"))
                .header("Idempotency-Key", "booker-authorized-001")
                .contentType("application/json")
                .content("{\"message\":\"force booking\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.reservations[0].reservationId").value("res-permitted"));
    verify(backend).createReservation(any(), eq("booker-authorized-001"));
  }

  @Test
  void directReservationEndpointsCannotBypassPermissions() throws Exception {
    mvc.perform(
            post("/api/reservations")
                .with(httpBasic("reader", "reader-test-password"))
                .header("Idempotency-Key", "reader-direct-001")
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.errorCode").value("ACCESS_DENIED"));
    mvc.perform(
            get("/api/reservations/res-permitted").with(httpBasic("chat", "chat-test-password")))
        .andExpect(status().isForbidden());
    verifyNoInteractions(backend);
  }

  @Test
  @WithMockUser(authorities = "HOTEL_READ")
  void serviceAndToolChecksRejectBypassBeforeBackendCall() {
    var request =
        new ReservationRequest(
            "h1",
            "r1",
            LocalDate.of(2027, 4, 10),
            LocalDate.of(2027, 4, 11),
            2,
            "Alex Guest",
            "alex@example.com");
    assertThatThrownBy(() -> reservations.create(request, "reader-service-001"))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(
            () ->
                reservationTools.createReservation(
                    "h1",
                    "r1",
                    "2027-04-10",
                    "2027-04-11",
                    2,
                    "Alex Guest",
                    "alex@example.com",
                    null))
        .isInstanceOf(AccessDeniedException.class);
    verifyNoInteractions(backend);
  }
}
