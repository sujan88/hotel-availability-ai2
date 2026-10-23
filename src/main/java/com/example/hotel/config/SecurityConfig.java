package com.example.hotel.config;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/** Local learning setup. Replace in-memory users with your identity provider for deployment. */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {
  @Bean
  SecurityFilterChain apiSecurity(HttpSecurity http) throws Exception {
    return http.securityMatcher("/api/chat", "/api/reservations", "/api/reservations/**")
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .csrf(csrf -> csrf.disable()) // JSON API for Postman, no cookie/session login.
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers(HttpMethod.POST, "/api/reservations")
                    .hasAuthority("RESERVATION_CREATE")
                    .requestMatchers(HttpMethod.GET, "/api/reservations/**")
                    .hasAuthority("HOTEL_READ")
                    .anyRequest()
                    .authenticated())
        .httpBasic(
            basic ->
                basic.authenticationEntryPoint(
                    (request, response, error) -> {
                      response.setHeader("WWW-Authenticate", "Basic realm=\"hotel-ai2\"");
                      failure(response, 401, "AUTHENTICATION_REQUIRED");
                    }))
        .exceptionHandling(
            errors ->
                errors
                    .authenticationEntryPoint(
                        (request, response, error) -> {
                          response.setHeader("WWW-Authenticate", "Basic realm=\"hotel-ai2\"");
                          failure(response, 401, "AUTHENTICATION_REQUIRED");
                        })
                    .accessDeniedHandler(
                        (request, response, error) -> failure(response, 403, "ACCESS_DENIED")))
        .build();
  }

  @Bean
  UserDetailsService localUsers(
      @Value("${app.security.reader-password:}") String readerPassword,
      @Value("${app.security.booker-password:}") String bookerPassword,
      @Value("${app.security.chat-password:}") String chatPassword) {
    var encoder = new BCryptPasswordEncoder();
    var users = new ArrayList<UserDetails>();
    if (!readerPassword.isBlank())
      users.add(
          User.withUsername("reader")
              .password("{bcrypt}" + encoder.encode(readerPassword))
              .authorities("HOTEL_READ")
              .build());
    if (!bookerPassword.isBlank())
      users.add(
          User.withUsername("booker")
              .password("{bcrypt}" + encoder.encode(bookerPassword))
              .authorities("HOTEL_READ", "RESERVATION_CREATE")
              .build());
    if (!chatPassword.isBlank())
      users.add(
          User.withUsername("chat")
              .password("{bcrypt}" + encoder.encode(chatPassword))
              .authorities("CHAT_ONLY")
              .build());
    return new InMemoryUserDetailsManager(users);
  }

  private static void failure(HttpServletResponse response, int status, String code)
      throws IOException {
    response.setStatus(status);
    response.setContentType("application/json");
    response.getWriter().write("{\"success\":false,\"errorCode\":\"" + code + "\"}");
  }
}
