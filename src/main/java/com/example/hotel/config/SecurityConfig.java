package com.example.hotel.config;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;

/** Select one authentication mechanism; both use the same tool and service permissions. */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {
  @Bean
  SecurityFilterChain apiSecurity(
      HttpSecurity http,
      @Value("${app.security.mode:basic}") String mode,
      @Value("${app.security.oauth2.authorities-claim:permissions}") String authoritiesClaim,
      ObjectProvider<JwtDecoder> decoders)
      throws Exception {
    if (!List.of("basic", "oauth2").contains(mode))
      throw new IllegalArgumentException("app.security.mode must be basic or oauth2");
    AuthenticationEntryPoint unauthorized =
        (request, response, error) -> {
          response.setHeader(
              "WWW-Authenticate", mode.equals("basic") ? "Basic realm=\"hotel-ai2\"" : "Bearer");
          failure(response, 401, "AUTHENTICATION_REQUIRED");
        };
    http.securityMatcher("/api/chat", "/api/reservations", "/api/reservations/**")
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
        .exceptionHandling(
            errors ->
                errors
                    .authenticationEntryPoint(unauthorized)
                    .accessDeniedHandler(
                        (request, response, error) -> failure(response, 403, "ACCESS_DENIED")));
    if (mode.equals("basic")) {
      http.httpBasic(basic -> basic.authenticationEntryPoint(unauthorized));
    } else {
      if (authoritiesClaim.isBlank())
        throw new IllegalArgumentException(
            "app.security.oauth2.authorities-claim must not be blank");
      var authorities = new JwtGrantedAuthoritiesConverter();
      authorities.setAuthoritiesClaimName(authoritiesClaim);
      authorities.setAuthorityPrefix("");
      var converter = new JwtAuthenticationConverter();
      converter.setJwtGrantedAuthoritiesConverter(authorities);
      http.oauth2ResourceServer(
          oauth ->
              oauth
                  .authenticationEntryPoint(unauthorized)
                  .accessDeniedHandler(
                      (request, response, error) -> failure(response, 403, "ACCESS_DENIED"))
                  .jwt(
                      jwt ->
                          jwt.decoder(decoders.getObject()).jwtAuthenticationConverter(converter)));
    }
    return http.build();
  }

  @Bean
  @ConditionalOnProperty(name = "app.security.mode", havingValue = "basic", matchIfMissing = true)
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

  @Bean
  @ConditionalOnProperty(name = "app.security.mode", havingValue = "oauth2")
  JwtDecoder jwtDecoder(
      @Value("${app.security.oauth2.issuer-uri:}") String issuer,
      @Value("${app.security.oauth2.audience:}") String audience,
      @Value("${app.security.oauth2.jwk-set-uri:}") String jwkSetUri) {
    if (issuer.isBlank() || audience.isBlank())
      throw new IllegalArgumentException(
          "OAuth2 mode requires app.security.oauth2.issuer-uri and audience");
    var decoder =
        jwkSetUri.isBlank()
            ? NimbusJwtDecoder.withIssuerLocation(issuer).build()
            : NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
    var audienceValidator =
        new JwtClaimValidator<List<String>>(
            "aud", values -> values != null && values.contains(audience));
    decoder.setJwtValidator(
        new DelegatingOAuth2TokenValidator<>(
            JwtValidators.createDefaultWithIssuer(issuer), audienceValidator));
    return decoder;
  }

  private static void failure(HttpServletResponse response, int status, String code)
      throws IOException {
    response.setStatus(status);
    response.setContentType("application/json");
    response.getWriter().write("{\"success\":false,\"errorCode\":\"" + code + "\"}");
  }
}
