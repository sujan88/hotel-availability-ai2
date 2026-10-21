package com.example.hotel.config;

import java.time.Duration;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.client.RestClient;

@Configuration
public class AvailabilityConfig {
  @Bean
  public ThreadPoolTaskExecutor availabilityExecutor() {
    var executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(8);
    executor.setMaxPoolSize(16);
    executor.setQueueCapacity(16);
    executor.setThreadNamePrefix("availability-");
    executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
    executor.setWaitForTasksToCompleteOnShutdown(true);
    executor.setAwaitTerminationSeconds(5);
    return executor;
  }

  @Bean
  public RestClient hotelRestClient(
      RestClient.Builder builder, @Value("${hotel.api.base-url}") String url) {
    var factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(Duration.ofMillis(500));
    factory.setReadTimeout(Duration.ofMillis(1500));
    return builder.baseUrl(url).requestFactory(factory).build();
  }
}
