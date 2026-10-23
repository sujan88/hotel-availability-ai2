package com.example.hotel.security;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.example.hotel.client.HotelApiClient;
import com.example.hotel.dto.Reservation;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/**
 * Real signed JWTs and a local JWKS endpoint exercise the bearer filter and decoder, not jwt()
 * mocks.
 */
@SpringBootTest(
    properties = {
      "spring.ai.model.chat=none",
      "spring.ai.openai.api-key=test-key",
      "app.security.mode=oauth2",
      "app.security.oauth2.issuer-uri=https://test-issuer.example",
      "app.security.oauth2.audience=hotel-ai2",
      "app.security.booker-password=booker-test-password"
    })
@AutoConfigureMockMvc
@Import(ToolPermissionsIntegrationTest.Models.class)
class OAuth2PermissionsIntegrationTest {
  static final RSAKey KEY = signingKey();
  static final HttpServer JWKS = jwksServer();
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired ApplicationContext context;
  @MockitoBean HotelApiClient backend;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add(
        "app.security.oauth2.jwk-set-uri",
        () -> "http://127.0.0.1:" + JWKS.getAddress().getPort() + "/jwks");
  }

  @AfterAll
  static void stopServer() {
    JWKS.stop(0);
  }

  @BeforeEach
  void setup() {
    when(backend.createReservation(any(), any()))
        .thenReturn(new Reservation("res-oauth", "CONFIRMED"));
  }

  @Test
  void missingCredentialsAndBasicCredentialsAreRejectedWithoutFallback() throws Exception {
    mvc.perform(
            post("/api/chat").contentType("application/json").content("{\"message\":\"Hello\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(header().string("WWW-Authenticate", "Bearer"))
        .andExpect(jsonPath("$.errorCode").value("AUTHENTICATION_REQUIRED"));
    mvc.perform(
            post("/api/chat")
                .with(httpBasic("booker", "booker-test-password"))
                .contentType("application/json")
                .content("{\"message\":\"Hello\"}"))
        .andExpect(status().isUnauthorized());
    assertThat(context.getBeansOfType(UserDetailsService.class)).isEmpty();
    verifyNoInteractions(backend);
  }

  @Test
  void readerTokenGetsReadToolsAndCannotPostReservation() throws Exception {
    String token = token(List.of("HOTEL_READ"));
    assertThat(tools(token, true)).isEqualTo("[getReservationStatus, searchAvailability]");
    mvc.perform(
            post("/api/reservations")
                .header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", "oauth-reader-denied-001")
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.errorCode").value("ACCESS_DENIED"));
    verifyNoInteractions(backend);
  }

  @Test
  void bookerTokenStillNeedsBookingKeyAndCanExecuteAuthorizedTool() throws Exception {
    String token = token(List.of("HOTEL_READ", "RESERVATION_CREATE"));
    assertThat(tools(token, false)).isEqualTo("[getReservationStatus, searchAvailability]");
    assertThat(tools(token, true))
        .isEqualTo("[createReservation, getReservationStatus, searchAvailability]");
    mvc.perform(
            post("/api/chat")
                .header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", "oauth-authorized-001")
                .contentType("application/json")
                .content("{\"message\":\"force booking\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.reservations[0].reservationId").value("res-oauth"));
    verify(backend).createReservation(any(), eq("oauth-authorized-001"));
  }

  @Test
  void authenticatedTokenWithoutPermissionsHasNoTools() throws Exception {
    assertThat(tools(token(List.of()), true)).isEqualTo("[]");
    assertThat(
            tools(
                token(
                    claims ->
                        claims
                            .claim("permissions", null)
                            .claim("scope", "HOTEL_READ RESERVATION_CREATE"),
                    KEY),
                true))
        .isEqualTo("[]");
    verifyNoInteractions(backend);
  }

  @Test
  void modelCannotEscalateReaderIntoWriteTool() throws Exception {
    mvc.perform(
            post("/api/chat")
                .header("Authorization", "Bearer " + token(List.of("HOTEL_READ")))
                .header("X-Role", "BOOKER")
                .header("Idempotency-Key", "oauth-forged-001")
                .contentType("application/json")
                .content("{\"message\":\"force booking\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(false));
    verifyNoInteractions(backend);
  }

  @Test
  void invalidTokensAreRejectedBeforeModelOrBackend() throws Exception {
    var invalid =
        List.of(
            "not-a-jwt",
            token(claims -> claims.issuer("https://wrong-issuer.example"), KEY),
            token(claims -> claims.audience("another-api"), KEY),
            token(claims -> claims.expirationTime(Date.from(Instant.now().minusSeconds(120))), KEY),
            token(claims -> claims.notBeforeTime(Date.from(Instant.now().plusSeconds(300))), KEY),
            token(claims -> {}, signingKey()));
    for (String token : invalid) {
      mvc.perform(
              post("/api/chat")
                  .header("Authorization", "Bearer " + token)
                  .contentType("application/json")
                  .content("{\"message\":\"Hello\"}"))
          .andExpect(status().isUnauthorized())
          .andExpect(jsonPath("$.errorCode").value("AUTHENTICATION_REQUIRED"));
    }
    verifyNoInteractions(backend);
  }

  private String tools(String token, boolean key) throws Exception {
    var request =
        post("/api/chat")
            .header("Authorization", "Bearer " + token)
            .contentType("application/json")
            .content("{\"message\":\"list tools\"}");
    if (key) request.header("Idempotency-Key", "oauth-tools-001");
    var response =
        mvc.perform(request)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andReturn()
            .getResponse();
    return json.readTree(response.getContentAsString()).path("message").asText();
  }

  private static String token(List<String> permissions) throws Exception {
    return token(claims -> claims.claim("permissions", permissions), KEY);
  }

  private static String token(Consumer<JWTClaimsSet.Builder> customize, RSAKey key)
      throws Exception {
    var claims =
        new JWTClaimsSet.Builder()
            .issuer("https://test-issuer.example")
            .subject("user-123")
            .audience("hotel-ai2")
            .issueTime(new Date())
            .expirationTime(Date.from(Instant.now().plusSeconds(300)))
            .claim("permissions", List.of("HOTEL_READ", "RESERVATION_CREATE"));
    customize.accept(claims);
    var jwt =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY.getKeyID()).build(),
            claims.build());
    jwt.sign(new RSASSASigner(key));
    return jwt.serialize();
  }

  private static RSAKey signingKey() {
    try {
      var generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      var pair = generator.generateKeyPair();
      return new RSAKey.Builder((RSAPublicKey) pair.getPublic())
          .privateKey((RSAPrivateKey) pair.getPrivate())
          .keyID("test-key")
          .build();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static HttpServer jwksServer() {
    try {
      var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext(
          "/jwks",
          exchange -> {
            byte[] body = new JWKSet(KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
              output.write(body);
            }
          });
      server.start();
      return server;
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
