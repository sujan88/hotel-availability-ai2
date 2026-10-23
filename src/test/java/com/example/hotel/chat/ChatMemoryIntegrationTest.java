package com.example.hotel.chat;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.example.hotel.client.HotelApiClient;
import com.example.hotel.dto.AvailabilityResponse;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.*;

@SpringBootTest(
    properties = {
      "spring.ai.model.chat=none", "spring.ai.openai.api-key=test-key",
      "app.security.mode=basic", "app.security.reader-password=reader-memory-password",
      "app.security.booker-password=booker-memory-password", "app.chat.memory.max-messages=6"
    })
@AutoConfigureMockMvc
class ChatMemoryIntegrationTest {
  @TestConfiguration(proxyBeanMethods = false)
  static class Models {
    @Bean
    HistoryModel historyModel() {
      return new HistoryModel();
    }
  }

  static class HistoryModel implements ChatModel {
    final List<Prompt> prompts = new CopyOnWriteArrayList<>();

    public ChatOptions getOptions() {
      return OpenAiChatOptions.builder().build();
    }

    public ChatResponse call(Prompt prompt) {
      prompts.add(prompt);
      var users =
          prompt.getInstructions().stream()
              .filter(UserMessage.class::isInstance)
              .map(UserMessage.class::cast)
              .map(UserMessage::getText)
              .toList();
      var output = new AssistantMessage(String.join(" | ", users));
      String finish = "stop";
      if (users.get(users.size() - 1).equals("do search")
          && prompt.getInstructions().stream().noneMatch(ToolResponseMessage.class::isInstance)) {
        output =
            AssistantMessage.builder()
                .content("")
                .toolCalls(
                    List.of(
                        new AssistantMessage.ToolCall(
                            "search",
                            "function",
                            "searchAvailability",
                            "{\"hotelId\":\"h1\",\"checkIn\":\"2027-04-10\",\"checkOut\":\"2027-04-11\",\"guests\":2}")))
                .build();
        finish = "tool_calls";
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
  @Autowired HistoryModel model;
  @MockitoBean HotelApiClient backend;

  @BeforeEach
  void reset() {
    model.prompts.clear();
    when(backend.search(any())).thenReturn(AvailabilityResponse.success(List.of()));
  }

  @Test
  void sameUserAndConversationRecallPreviousMessage() throws Exception {
    var first = chat("reader", null, "My chosen hotel is h1");
    var second = chat("reader", first.path("conversationId").asText(), "Which hotel?");
    assertThat(second.path("message").asText()).isEqualTo("My chosen hotel is h1 | Which hotel?");
    assertThat(second.path("turnId").asText()).isNotEqualTo(first.path("turnId").asText());
    assertThat(second.path("traceId").asText()).isNotEqualTo(first.path("traceId").asText());
  }

  @Test
  void differentConversationsAndUsersDoNotShareHistory() throws Exception {
    var first = chat("reader", null, "private reader conversation");
    var second = chat("reader", null, "new conversation");
    var third = chat("booker", null, "booker conversation");
    assertThat(second.path("message").asText()).isEqualTo("new conversation");
    assertThat(third.path("message").asText()).isEqualTo("booker conversation");
    assertThat(
            chat("reader", first.path("conversationId").asText(), "continue")
                .path("message")
                .asText())
        .isEqualTo("private reader conversation | continue");
  }

  @Test
  void anotherUsersConversationIsDeniedBeforeModelCall() throws Exception {
    var first = chat("reader", null, "private message");
    int before = model.prompts.size();
    mvc.perform(
            post("/api/chat")
                .with(httpBasic("booker", "booker-memory-password"))
                .header("Conversation-Id", first.path("conversationId").asText())
                .contentType("application/json")
                .content("{\"message\":\"show previous messages\"}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.errorCode").value("CONVERSATION_ACCESS_DENIED"));
    assertThat(model.prompts).hasSize(before);
    verifyNoInteractions(backend);
  }

  @Test
  void unknownConversationIsNotSilentlyCreated() throws Exception {
    mvc.perform(
            post("/api/chat")
                .with(httpBasic("reader", "reader-memory-password"))
                .header("Conversation-Id", UUID.randomUUID().toString())
                .contentType("application/json")
                .content("{\"message\":\"hello\"}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.errorCode").value("CONVERSATION_NOT_FOUND"));
    assertThat(model.prompts).isEmpty();
  }

  @Test
  void olderTurnsLeaveTheSixMessageWindow() throws Exception {
    String id = chat("reader", null, "turn1").path("conversationId").asText();
    for (int turn = 2; turn <= 5; turn++) chat("reader", id, "turn" + turn);
    var last = model.prompts.get(model.prompts.size() - 1).getInstructions();
    assertThat(last.stream().filter(UserMessage.class::isInstance).map(Message::getText).toList())
        .containsExactly("turn2", "turn3", "turn4", "turn5");
    // At most six stored history messages plus the current user message and system instructions.
    assertThat(last.stream().filter(m -> !(m instanceof SystemMessage)).count())
        .isLessThanOrEqualTo(7);
  }

  @Test
  void toolHistoryAndBookingKeyAreNotReusedAcrossTurns() throws Exception {
    var first = chat("booker", null, "do search", true);
    String id = first.path("conversationId").asText();
    chat("booker", id, "continue without booking key");
    var last = model.prompts.get(model.prompts.size() - 1);
    assertThat(last.getInstructions()).noneMatch(ToolResponseMessage.class::isInstance);
    assertThat(
            last.getInstructions().stream()
                .filter(AssistantMessage.class::isInstance)
                .map(AssistantMessage.class::cast))
        .allMatch(message -> message.getToolCalls().isEmpty());
    var tools = ((ToolCallingChatOptions) last.getOptions()).getToolCallbacks();
    assertThat(tools.stream().map(c -> c.getToolDefinition().name()))
        .containsExactlyInAnyOrder("searchAvailability", "getReservationStatus");
    verify(backend, times(1)).search(any());
  }

  private JsonNode chat(String user, String id, String message) throws Exception {
    return chat(user, id, message, false);
  }

  private JsonNode chat(String user, String id, String message, boolean bookingKey)
      throws Exception {
    var request =
        post("/api/chat")
            .with(httpBasic(user, user + "-memory-password"))
            .contentType("application/json")
            .content(json.writeValueAsString(Map.of("message", message)));
    if (id != null) request.header("Conversation-Id", id);
    if (bookingKey) request.header("Idempotency-Key", "memory-booking-key-001");
    var response =
        mvc.perform(request)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andReturn()
            .getResponse();
    return json.readTree(response.getContentAsString());
  }
}
