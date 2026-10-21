package com.example.hotel.config;

import com.example.hotel.tool.HotelTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ChatConfig {
  @Bean
  public ChatClient chatClient(
      ChatClient.Builder builder, ToolCallingManager toolCallingManager, HotelTools tools) {
    // Use Spring AI's auto-configured manager so application.yml native limits apply.
    var toolAdvisor = ToolCallingAdvisor.builder().toolCallingManager(toolCallingManager).build();
    return builder
        .defaultSystem(
            "You help guests search hotels. Ask for missing hotel ID, dates and guest count. Use"
                + " the availability tool for live results. Never invent inventory or prices."
                + " Explain tool failures without treating them as no rooms.")
        .defaultTools(tools)
        .defaultAdvisors(toolAdvisor)
        .build();
  }
}
